"""Tests for the synthetic CPU length ladder (ladder.py, ladder_rung.py). Offline; the torch tests use a tiny random model
and are skipped when torch is not installed. Nothing here loads the pinned base model or touches real data."""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import ladder

HERE = Path(__file__).resolve().parent
GIB = 1024 ** 3
BUDGET = 10 * GIB


def good_record(**over):
    record = {"completed": True, "oom_events": 0, "losses": [4.1, 4.0, 3.9], "grad_norms": [0.1, 0.1, 0.1],
              "adapter_saved": True, "adapter_reload_equal": True, "memory_peak_bytes": 5 * GIB,
              "swap_peak_bytes": 0, "swap_missing_samples": 0, "major_fault_delta": 0, "wall_seconds": 30.0,
              "kernel_memory_peak_bytes": 5 * GIB}
    record.update(over)
    return record


def fake_build_example(messages, encode, bos_id, max_length):
    ids = [bos_id] + encode(" ".join(m["content"] for m in messages))
    return {"input_ids": ids[:max_length]}


def fake_encode(text):
    return [7] * len(text.split())


class BuildRowTests(unittest.TestCase):
    def test_hits_target_for_every_rung(self):
        for target in ladder.DEFAULT_RUNGS:
            messages, actual = ladder.build_row(3, target, fake_encode, 1, fake_build_example)
            self.assertLessEqual(actual, target)
            self.assertGreaterEqual(actual, target - ladder.LENGTH_TOLERANCE)
            self.assertEqual(len(messages), 3)

    def test_target_below_empty_row_is_refused(self):
        with self.assertRaises(ValueError):
            ladder.build_row(0, 5, fake_encode, 1, fake_build_example)

    def test_target_the_encoder_cannot_hit_is_refused(self):
        coarse = lambda text: [7] * (len(text.split()) // 10 * 10)  # noqa: E731  tokens come in steps of ten
        with self.assertRaises(ValueError):
            ladder.build_row(0, 206, coarse, 1, fake_build_example)

    def test_rows_are_invented(self):
        messages, _ = ladder.build_row(1, 128, fake_encode, 1, fake_build_example)
        self.assertIn("invented qualification row", messages[0]["content"])


class JudgeTests(unittest.TestCase):
    def judge(self, **over):
        return ladder.judge_rung(good_record(**over), budget_bytes=BUDGET)

    def test_good_record_passes(self):
        self.assertEqual(self.judge()["status"], "PASS")

    def test_each_stop_rule_fails(self):
        cases = {
            "incomplete": {"completed": False, "failure": "boom"},
            "oom counter": {"oom_events": 1},
            "oom unreadable": {"oom_events": None},
            "nan loss": {"losses": [4.0, float("nan"), 3.0]},
            "inf grad": {"grad_norms": [float("inf")]},
            "no losses": {"losses": []},
            "no grads": {"grad_norms": None},
            "no save": {"adapter_saved": False},
            "reload differs": {"adapter_reload_equal": False},
            "reload missing": {"adapter_reload_equal": None},
            "over 90 percent": {"memory_peak_bytes": int(0.91 * BUDGET)},
            "no peak": {"memory_peak_bytes": None},
            "swap": {"swap_peak_bytes": 4096},
            "paging": {"major_fault_delta": ladder.MAJOR_FAULT_DELTA_LIMIT + 1},
            "slow": {"wall_seconds": ladder.RUNG_TIMEOUT_SECONDS + 1},
        }
        for name, over in cases.items():
            with self.subTest(name):
                self.assertEqual(self.judge(**over)["status"], "FAILURE")

    def test_exactly_ninety_percent_is_allowed(self):
        self.assertEqual(self.judge(memory_peak_bytes=int(0.9 * BUDGET))["status"], "PASS")

    def test_empty_or_missing_record_is_failure_not_pass(self):
        self.assertEqual(ladder.judge_rung({}, budget_bytes=BUDGET)["status"], "FAILURE")
        self.assertEqual(ladder.judge_rung(None, budget_bytes=BUDGET)["status"], "FAILURE")

    def test_host_floor(self):
        low = ladder.judge_rung(good_record(), budget_bytes=BUDGET, host_after=ladder.HOST_STOP_BELOW_BYTES - 1)
        self.assertEqual(low["status"], "FAILURE")
        ok = ladder.judge_rung(good_record(), budget_bytes=BUDGET, host_after=ladder.HOST_STOP_BELOW_BYTES)
        self.assertEqual(ok["status"], "PASS")

    def test_start_gate(self):
        self.assertTrue(ladder.host_start_gate(None))
        self.assertTrue(ladder.host_start_gate(ladder.HOST_START_MIN_BYTES - 1))
        self.assertEqual(ladder.host_start_gate(ladder.HOST_START_MIN_BYTES), [])


def stub(record=None, code=0, sleep=0):
    """A child command that writes `record` as rung.json (or nothing) and exits with `code`."""
    def command(rung, rung_dir):
        script = ("import json,sys,time,pathlib\n"
                  f"time.sleep({sleep})\n"
                  f"rec={record!r}\n"
                  "p=pathlib.Path(sys.argv[1])\n"
                  "if rec is not None: (p/'rung.json').write_text(json.dumps(rec))\n"
                  f"sys.exit({code})\n")
        return [sys.executable, "-c", script, str(rung_dir)]
    return command


class RunLadderTests(unittest.TestCase):
    def run_ladder(self, command, rungs=(128, 256, 512), **kw):
        with tempfile.TemporaryDirectory() as tmp:
            kw.setdefault("host_available_start", 8 * GIB)
            summary = ladder.run_ladder(rungs=list(rungs), budget_bytes=BUDGET, out_dir=Path(tmp), model="pinned",
                                        child_command=command, **kw)
            on_disk = json.loads((Path(tmp) / "summary.json").read_text())
        self.assertEqual(summary["rungs"], on_disk["rungs"])
        return summary

    def test_all_pass(self):
        summary = self.run_ladder(stub(good_record()))
        self.assertTrue(summary["complete"])
        self.assertIsNone(summary["stopped_at"])
        self.assertTrue(summary["qualification"])

    def test_stops_at_first_failure_and_marks_rest_not_run(self):
        def command(rung, rung_dir):
            bad = rung == 256
            return stub(good_record(oom_events=2) if bad else good_record())(rung, rung_dir)
        summary = self.run_ladder(command, rungs=(128, 256, 512, 1024))
        self.assertEqual([r["status"] for r in summary["rungs"]], ["PASS", "FAILURE", "NOT_RUN", "NOT_RUN"])
        self.assertEqual(summary["stopped_at"], 256)
        self.assertFalse(summary["complete"])

    def test_killed_child_is_oom_failure(self):
        summary = self.run_ladder(stub(None, code=137))
        self.assertEqual(summary["rungs"][0]["status"], "FAILURE")
        self.assertTrue(any("SIGKILL" in r for r in summary["rungs"][0]["reasons"]))
        self.assertEqual(summary["rungs"][1]["status"], "NOT_RUN")

    def test_child_that_wrote_nothing_fails(self):
        summary = self.run_ladder(stub(None, code=0))
        self.assertEqual(summary["rungs"][0]["status"], "FAILURE")

    def test_nonzero_exit_fails_even_with_good_record(self):
        summary = self.run_ladder(stub(good_record(), code=2))
        self.assertEqual(summary["rungs"][0]["status"], "FAILURE")

    def test_timeout_kills_and_fails(self):
        summary = self.run_ladder(stub(good_record(), sleep=30), rung_timeout=1)
        self.assertEqual(summary["rungs"][0]["status"], "FAILURE")
        self.assertTrue(any("exceeded" in r for r in summary["rungs"][0]["reasons"]))

    def test_start_gate_failure_runs_nothing(self):
        summary = self.run_ladder(stub(good_record()), host_available_start=ladder.HOST_START_MIN_BYTES - 1)
        self.assertEqual({r["status"] for r in summary["rungs"]}, {"NOT_RUN"})
        self.assertEqual(summary["stopped_at"], "start gate")

    def test_missing_host_measurement_runs_nothing(self):
        summary = self.run_ladder(stub(good_record()), host_available_start=None)
        self.assertEqual({r["status"] for r in summary["rungs"]}, {"NOT_RUN"})

    def test_host_floor_after_rung_stops_ladder(self):
        summary = self.run_ladder(stub(good_record()), host_available_end_of_rung=lambda r: 1 * GIB)
        self.assertEqual(summary["rungs"][0]["status"], "FAILURE")
        self.assertEqual(summary["rungs"][1]["status"], "NOT_RUN")

    def test_synthetic_model_is_never_qualification(self):
        with tempfile.TemporaryDirectory() as tmp:
            summary = ladder.run_ladder(rungs=[128], budget_bytes=BUDGET, out_dir=Path(tmp), model="synthetic",
                                        host_available_start=8 * GIB, child_command=stub(good_record()))
        self.assertFalse(summary["qualification"])


class HostMinTests(unittest.TestCase):
    def csv(self, text):
        d = tempfile.TemporaryDirectory()
        self.addCleanup(d.cleanup)
        path = Path(d.name) / "host.csv"
        path.write_text(text, encoding="utf-8")
        return path

    def test_min_inside_window_only(self):
        path = self.csv("\n".join(["epoch,bytes", "100,9000", "200,5000", "210,3000", "400,1000"]) + "\n")
        self.assertEqual(ladder.read_host_min(path, 190, 215, slack=5), 3000)
        self.assertEqual(ladder.read_host_min(path, 90, 110, slack=5), 9000)

    def test_no_samples_or_no_file_is_none(self):
        path = self.csv("epoch,bytes\n100,9000\n")
        self.assertIsNone(ladder.read_host_min(path, 500, 600))
        self.assertIsNone(ladder.read_host_min(path.parent / "missing.csv", 0, 10 ** 12))

    def test_partial_last_line_is_ignored(self):
        path = self.csv("100,9000\n101,")
        self.assertEqual(ladder.read_host_min(path, 90, 110), 9000)

    def run_with(self, reader):
        with tempfile.TemporaryDirectory() as tmp:
            return ladder.run_ladder(rungs=[128, 256], budget_bytes=BUDGET, out_dir=Path(tmp), model="pinned",
                                     host_available_start=8 * GIB, child_command=stub(good_record()),
                                     host_min_reader=reader)

    def test_low_reading_during_rung_fails_and_stops(self):
        summary = self.run_with(lambda a, b: ladder.HOST_STOP_BELOW_BYTES - 1)
        self.assertEqual([r["status"] for r in summary["rungs"]], ["FAILURE", "NOT_RUN"])

    def test_no_reading_during_rung_is_missing_evidence(self):
        summary = self.run_with(lambda a, b: None)
        self.assertEqual(summary["rungs"][0]["status"], "FAILURE")
        self.assertTrue(any("no Windows-available reading" in r for r in summary["rungs"][0]["reasons"]))

    def test_healthy_readings_pass_and_are_recorded(self):
        summary = self.run_with(lambda a, b: 9 * GIB)
        self.assertTrue(summary["complete"])
        self.assertEqual(summary["rungs"][0]["host_available_min_during"], 9 * GIB)


class ClockOffsetTests(unittest.TestCase):
    """The WSL clock was seen 65 s ahead of Windows, so a rung's time window missed every sampler line."""

    def test_offset_is_windows_epoch_minus_local_clock(self):
        self.assertEqual(ladder.clock_offset(1000, now=1065), -65.0)
        self.assertEqual(ladder.clock_offset(1065, now=1000), 65.0)

    def test_no_epoch_means_no_correction(self):
        self.assertEqual(ladder.clock_offset(0), 0.0)
        self.assertEqual(ladder.clock_offset(None), 0.0)
        self.assertEqual(ladder.clock_offset(-5), 0.0)

    def test_rung_window_is_shifted_onto_the_windows_clock(self):
        seen = []

        def reader(a, b):
            seen.append((a, b))
            return 9 * GIB
        with tempfile.TemporaryDirectory() as tmp:
            summary = ladder.run_ladder(rungs=[128], budget_bytes=BUDGET, out_dir=Path(tmp), model="pinned",
                                        host_available_start=8 * GIB, child_command=stub(good_record()),
                                        host_min_reader=reader, host_clock_offset=-65.0)
        self.assertEqual(summary["host_clock_offset_seconds"], -65.0)
        start, end = seen[0]
        self.assertGreater(end, start)
        import time
        self.assertLess(abs(start - (time.time() - 65.0)), 120)  # near local-now minus 65 s, not near local-now

    def test_skewed_samples_are_found_only_with_the_offset(self):
        import time
        local_now = time.time()
        windows_now = local_now - 65.0   # Windows behind the container clock, as seen
        with tempfile.TemporaryDirectory() as tmp:
            csv = Path(tmp) / "host.csv"
            csv.write_text("epoch,bytes\n" + "".join(f"{int(windows_now) + k},{9 * GIB}\n" for k in range(-3, 4)), encoding="utf-8")
            reader = lambda a, b: ladder.read_host_min(csv, a, b)  # noqa: E731
            without = ladder.run_ladder(rungs=[128], budget_bytes=BUDGET, out_dir=Path(tmp) / "a", model="pinned",
                                        host_available_start=8 * GIB, child_command=stub(good_record()), host_min_reader=reader)
            with_offset = ladder.run_ladder(rungs=[128], budget_bytes=BUDGET, out_dir=Path(tmp) / "b", model="pinned",
                                            host_available_start=8 * GIB, child_command=stub(good_record()), host_min_reader=reader,
                                            host_clock_offset=-65.0)
        self.assertEqual(without["rungs"][0]["status"], "FAILURE")   # the bug that hid the readings
        self.assertTrue(any("no Windows-available reading" in r for r in without["rungs"][0]["reasons"]))
        self.assertEqual(with_offset["rungs"][0]["status"], "PASS")
        self.assertEqual(with_offset["rungs"][0]["host_available_min_during"], 9 * GIB)


class CeilingMetricJudgeTests(unittest.TestCase):
    """Configuration B (ceiling on anon+shmem) is a NEW labelled configuration; configuration A (original) is unchanged."""

    def judge(self, **over):
        return ladder.judge_rung(good_record(**over), budget_bytes=BUDGET)

    def test_cache_inflated_peak_fails_under_original_but_not_under_anon_shmem(self):
        inflated = int(0.95 * BUDGET)   # memory.current with page cache
        anon = int(0.50 * BUDGET)
        original = self.judge(memory_peak_bytes=inflated, ceiling_metric="original", ceiling_metric_peak_bytes=inflated)
        b = self.judge(memory_peak_bytes=inflated, ceiling_metric="anon_shmem", ceiling_metric_peak_bytes=anon)
        self.assertEqual(original["status"], "FAILURE")
        self.assertEqual(b["status"], "PASS")

    def test_anon_shmem_over_the_ceiling_still_fails_and_names_the_metric(self):
        verdict = self.judge(ceiling_metric="anon_shmem", ceiling_metric_peak_bytes=int(0.91 * BUDGET))
        self.assertEqual(verdict["status"], "FAILURE")
        self.assertTrue(any("anon_shmem" in r for r in verdict["reasons"]))

    def test_old_records_without_a_metric_are_judged_as_original(self):
        self.assertEqual(self.judge(memory_peak_bytes=int(0.95 * BUDGET))["status"], "FAILURE")
        self.assertEqual(self.judge(memory_peak_bytes=int(0.5 * BUDGET))["status"], "PASS")

    def test_summary_labels_the_configuration(self):
        for metric in ("original", "anon_shmem"):
            with tempfile.TemporaryDirectory() as tmp:
                summary = ladder.run_ladder(rungs=[128], budget_bytes=BUDGET, out_dir=Path(tmp), model="pinned",
                                            host_available_start=8 * GIB, child_command=stub(good_record()),
                                            ceiling_metric=metric)
            self.assertEqual(summary["configuration"]["ceiling_metric"], metric)
            self.assertEqual(summary["configuration"]["metric_label"], ladder.CEILING_METRICS[metric])
            self.assertEqual(summary["configuration"]["label"], ladder.CONFIG_LABELS[(metric, "fixed")])
        self.assertIn("NEW labelled configuration", ladder.CEILING_METRICS["anon_shmem"])
        self.assertNotIn("NEW labelled", ladder.CEILING_METRICS["original"])

    def test_child_command_carries_the_metric_and_defaults_to_original(self):
        argv = ladder.default_child_command(128, Path("x"))
        self.assertEqual(argv[argv.index("--ceiling-metric") + 1], "original")
        argv = ladder.default_child_command(128, Path("x"), ceiling_metric="anon_shmem")
        self.assertEqual(argv[argv.index("--ceiling-metric") + 1], "anon_shmem")


class ConfigCTests(unittest.TestCase):
    """Configuration C = B + a container cap from the fixed budget + 1 s host watchdog + per-second memory trace."""

    def test_labels_name_every_combination_and_mark_new_ones(self):
        self.assertEqual(set(ladder.CONFIG_LABELS), {(m, c) for m in ladder.CEILING_METRICS for c in ladder.CONTAINER_CAPS})
        self.assertTrue(ladder.CONFIG_LABELS[("anon_shmem", "budget")].startswith("C:"))
        self.assertIn("NEW labelled configuration", ladder.CONFIG_LABELS[("anon_shmem", "budget")])
        self.assertNotIn("NEW labelled", ladder.CONFIG_LABELS[("original", "fixed")])

    def test_summary_records_the_cap_and_label_for_c(self):
        with tempfile.TemporaryDirectory() as tmp:
            summary = ladder.run_ladder(rungs=[128], budget_bytes=BUDGET, out_dir=Path(tmp), model="pinned",
                                        host_available_start=8 * GIB, child_command=stub(good_record()),
                                        ceiling_metric="anon_shmem", container_cap="budget", container_memory_bytes=8 * GIB)
        cfg = summary["configuration"]
        self.assertEqual((cfg["ceiling_metric"], cfg["container_cap"], cfg["container_memory_bytes"]), ("anon_shmem", "budget", 8 * GIB))
        self.assertTrue(cfg["label"].startswith("C:"))

    def test_trace_summary_survives_a_killed_rung(self):
        rows = ["epoch,current,rss,anon,file,shmem,metric"] + [f"{1000 + k}.0,{(k + 1) * 10},{k},{k * 5},{k * 4},0,{k * 5}" for k in range(6)]
        with tempfile.TemporaryDirectory() as tmp:
            trace = Path(tmp) / "memory-trace.csv"
            trace.write_text("\n".join(rows) + "\n1006.0,70,6,3", encoding="utf-8")   # last line cut off by the kill
            summary = ladder.summarize_trace(trace)
        self.assertEqual(summary["rows"], 6)
        self.assertEqual((summary["current_peak_bytes"], summary["anon_peak_bytes"], summary["file_peak_bytes"]), (60, 25, 20))
        self.assertIsNone(ladder.summarize_trace(Path("no-such-trace.csv")))

    def test_killed_rung_entry_carries_its_trace(self):
        def command(rung, rung_dir):
            script = ("import sys,pathlib\n"
                      "p=pathlib.Path(sys.argv[1]);(p/'memory-trace.csv').write_text('epoch,current,rss,anon,file,shmem,metric\\n1.0,100,50,60,40,0,60\\n2.0,300,90,200,100,0,200\\n')\n"
                      "sys.exit(137)\n")
            return [sys.executable, "-c", script, str(rung_dir)]
        with tempfile.TemporaryDirectory() as tmp:
            summary = ladder.run_ladder(rungs=[128, 256], budget_bytes=BUDGET, out_dir=Path(tmp), model="pinned",
                                        host_available_start=8 * GIB, child_command=command)
        entry = summary["rungs"][0]
        self.assertEqual(entry["status"], "FAILURE")
        self.assertEqual((entry["memory_trace"]["current_peak_bytes"], entry["memory_trace"]["anon_peak_bytes"]), (300, 200))
        self.assertEqual(summary["rungs"][1]["status"], "NOT_RUN")


HEADER12 = "epoch,current,rss,anon,file,shmem,metric,phase,majflt,pgmajfault,refault_file,refault_anon"


def trace_text(rows):
    """rows: (t, phase, majflt, pgmajfault, refault_file, refault_anon)"""
    lines = [HEADER12]
    for t, phase, mj, pg, rf, ra in rows:
        lines.append(f"{t},100,50,60,40,0,60,{phase},{mj},{pg},{rf},{ra}")
    return "\n".join(lines) + "\n"


class PagingTimelineTests(unittest.TestCase):
    """Run 3 failed only on whole-rung process major faults (28750 against a limit Code chose). The timeline separates the cold
    read of the weights while loading from file pages that were evicted and read again while training."""

    def write(self, rows, tmp):
        path = Path(tmp) / "memory-trace.csv"
        path.write_text(trace_text(rows), encoding="utf-8")
        return path

    def test_cold_load_faults_are_not_training_refaults(self):
        rows = [(0, "load", 0, 0, 0, 0), (5, "load", 20000, 20000, 0, 0), (10, "load", 28000, 28000, 0, 0),
                (11, "train", 28100, 28100, 0, 0), (60, "train", 28300, 28300, 0, 0), (110, "train", 28750, 28750, 2, 0),
                (111, "save", 28760, 28760, 2, 0)]
        with tempfile.TemporaryDirectory() as tmp:
            p = ladder.paging_from_trace(self.write(rows, tmp))
        self.assertEqual(p["phases"]["load"]["majflt_delta"], 28000)
        self.assertEqual(p["phases"]["train"]["majflt_delta"], 760)   # last load sample to first save sample: both boundaries
        self.assertEqual(p["phases"]["train"]["refault_file_delta"], 2)
        self.assertLess(p["phases"]["train"]["max_refault_file_per_s"], 0.1)
        self.assertEqual(p["phases"]["train"]["refault_anon_delta"], 0)

    def test_worst_window_is_found_not_the_average(self):
        rows = [(0, "train", 0, 0, 0, 0), (10, "train", 0, 0, 0, 0), (15, "train", 0, 0, 5000, 0), (60, "train", 0, 0, 5000, 0)]
        with tempfile.TemporaryDirectory() as tmp:
            train = ladder.paging_from_trace(self.write(rows, tmp))["phases"]["train"]
        self.assertAlmostEqual(train["max_refault_file_per_s"], 1000.0, delta=1)   # 5000 refaults inside 5 s
        self.assertLess(train["refault_file_delta"] / train["seconds"], 100)        # the average would hide it

    def test_old_seven_column_traces_have_no_paging_analysis_and_still_summarise(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "memory-trace.csv"
            path.write_text("epoch,current,rss,anon,file,shmem,metric\n1.0,10,5,6,4,0,6\n2.0,30,9,20,10,0,20\n", encoding="utf-8")
            self.assertIsNone(ladder.paging_from_trace(path))
            self.assertEqual(ladder.summarize_trace(path)["current_peak_bytes"], 30)

    def test_twelve_column_trace_still_gives_memory_peaks(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = self.write([(0, "load", 0, 0, 0, 0), (1, "train", 5, 5, 0, 0)], tmp)
            s = ladder.summarize_trace(path)
        self.assertEqual((s["rows"], s["current_peak_bytes"]), (2, 100))

    def test_missing_trace_is_none(self):
        self.assertIsNone(ladder.paging_from_trace(Path("nope.csv")))


class PagingRuleTests(unittest.TestCase):
    def judge(self, paging, rule="v1", limit=None, **over):
        rec = good_record(paging=paging, **over)
        return ladder.judge_rung(rec, budget_bytes=BUDGET, paging_rule=rule, paging_limit=limit)

    def train(self, rate=0.0, anon=0):
        return {"phases": {"train": {"max_refault_file_per_s": rate, "refault_anon_delta": anon, "refault_file_delta": 0,
                                     "reset": [], "invalid_samples": 0, "coverage_ok": True}}}

    def test_v1_is_unchanged_and_still_fails_run_3s_record(self):
        self.assertEqual(self.judge(None, major_fault_delta=28750)["status"], "FAILURE")   # run 3 under the rule as run
        self.assertEqual(self.judge(None, major_fault_delta=100)["status"], "PASS")

    def test_v2_does_not_count_cold_load_faults(self):
        verdict = self.judge(self.train(0.0), rule="v2", limit=50.0, major_fault_delta=28750)
        self.assertEqual(verdict["status"], "PASS")

    def test_v2_fails_on_training_refaults_over_the_limit_only(self):
        self.assertEqual(self.judge(self.train(51.0), rule="v2", limit=50.0)["status"], "FAILURE")
        self.assertEqual(self.judge(self.train(50.0), rule="v2", limit=50.0)["status"], "PASS")

    def test_v2_fails_on_anonymous_refaults_in_training(self):
        verdict = self.judge(self.train(0.0, anon=3), rule="v2", limit=50.0)
        self.assertEqual(verdict["status"], "FAILURE")
        self.assertTrue(any("anonymous-page refaults" in r for r in verdict["reasons"]))

    def test_v2_missing_evidence_is_failure_not_pass(self):
        for paging in (None, {"phases": {}}, {"phases": {"train": {"max_refault_file_per_s": None}}}):
            with self.subTest(paging=paging):
                verdict = self.judge(paging, rule="v2", limit=50.0)
                self.assertEqual(verdict["status"], "FAILURE")
                self.assertTrue(any("paging evidence missing" in r for r in verdict["reasons"]))

    def test_v2_without_a_limit_is_refused_no_default(self):
        verdict = self.judge(self.train(0.0), rule="v2", limit=None)
        self.assertEqual(verdict["status"], "FAILURE")
        self.assertTrue(any("explicit" in r for r in verdict["reasons"]))

    def test_swap_use_still_fails_under_v2(self):
        self.assertEqual(self.judge(self.train(0.0), rule="v2", limit=50.0, swap_peak_bytes=4096)["status"], "FAILURE")

    def test_summary_records_the_rule_and_its_limit(self):
        with tempfile.TemporaryDirectory() as tmp:
            summary = ladder.run_ladder(rungs=[128], budget_bytes=BUDGET, out_dir=Path(tmp), model="pinned",
                                        host_available_start=8 * GIB, child_command=stub(good_record(paging=self.train(0.0))),
                                        paging_rule="v2", paging_limit=50.0)
        self.assertEqual(summary["configuration"]["paging_rule"], "v2")
        self.assertEqual(summary["configuration"]["paging_max_train_refaults_per_s"], 50.0)
        self.assertEqual(summary["rungs"][0]["status"], "PASS")

    def test_cli_refuses_v2_without_a_limit(self):
        proc = subprocess.run([sys.executable, str(HERE / "ladder.py"), "run", "--budget-bytes", "1000", "--out", "x",
                               "--paging-rule", "v2"], cwd=str(HERE), capture_output=True, text=True, timeout=60)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("no limit has been ruled", proc.stderr)


try:
    import torch  # noqa: F401
    import peft  # noqa: F401
    import transformers  # noqa: F401
    HAVE_TORCH = True
except ImportError:
    HAVE_TORCH = False


class EffectiveBudgetTests(unittest.TestCase):
    """The ceiling is 90 percent of the LOWER of the usable budget and the container limit (Codex finding 1)."""

    # figures from runs 3 and 4 as Codex computed them
    RUN4 = dict(budget=18572292096, cap=16424808448, ceiling=14782327603, current=16424808448, anon=15021985792)
    RUN3_CEILING = 13887988531

    def test_cap_below_budget_lowers_the_ceiling(self):
        r = self.RUN4
        self.assertEqual(ladder.effective_budget(r["budget"], r["cap"]), r["cap"])
        self.assertEqual(ladder.ceiling_bytes(r["cap"]), r["ceiling"])
        self.assertEqual(ladder.ceiling_bytes(15431098368), self.RUN3_CEILING)
        self.assertEqual(ladder.effective_budget(r["budget"], None, 0), r["budget"])
        self.assertEqual(ladder.effective_budget(r["cap"], r["budget"]), r["cap"])

    def test_judge_fails_run_4_on_both_metrics_and_budget_alone_did_not(self):
        r = self.RUN4
        for metric, peak in (("original", r["current"]), ("anon_shmem", r["anon"])):
            with self.subTest(metric=metric):
                rec = good_record(ceiling_metric=metric, ceiling_metric_peak_bytes=peak, memory_peak_bytes=r["current"])
                self.assertEqual(ladder.judge_rung(rec, budget_bytes=r["budget"])["status"], "PASS")
                verdict = ladder.judge_rung(rec, budget_bytes=r["budget"], container_memory_bytes=r["cap"])
                self.assertEqual(verdict["status"], "FAILURE")
                self.assertTrue(any("effective budget %d" % r["cap"] in x for x in verdict["reasons"]))

    def test_judge_boundary_is_the_effective_ceiling(self):
        cap = 8 * GIB
        at = ladder.ceiling_bytes(cap)
        for peak, status in ((at, "PASS"), (at + 1, "FAILURE")):
            verdict = ladder.judge_rung(good_record(memory_peak_bytes=peak), budget_bytes=BUDGET, container_memory_bytes=cap)
            self.assertEqual(verdict["status"], status, peak)

    def test_the_recorded_cgroup_limit_also_lowers_the_ceiling(self):
        rec = good_record(memory_peak_bytes=5 * GIB, cgroup_memory_max=5 * GIB)
        self.assertEqual(ladder.judge_rung(rec, budget_bytes=BUDGET)["status"], "FAILURE")

    def test_child_that_used_another_denominator_fails(self):
        rec = good_record(effective_budget_bytes=BUDGET)
        verdict = ladder.judge_rung(rec, budget_bytes=BUDGET, container_memory_bytes=8 * GIB)
        self.assertEqual(verdict["status"], "FAILURE")
        self.assertTrue(any("the verdict uses" in x for x in verdict["reasons"]))

    def run_real(self, rec, **kw):
        kw.setdefault("container_memory_bytes", 18 * GIB)
        kw.setdefault("cgroup_memory_max", 18 * GIB)
        seen = []

        def command(rung, rung_dir):
            seen.append(rung)
            return stub(rec)(rung, rung_dir)
        with tempfile.TemporaryDirectory() as tmp:
            summary = ladder.run_ladder(rungs=[128], budget_bytes=20 * GIB, out_dir=Path(tmp), model="pinned",
                                        host_available_start=26 * GIB, require_cgroup_evidence=True,
                                        child_command=command, **kw)
        return summary, seen

    def test_run_records_budget_cap_and_effective_separately(self):
        summary, _ = self.run_real(good_record(cgroup_memory_max=18 * GIB))
        self.assertEqual(summary["budget_bytes"], 20 * GIB)
        self.assertEqual(summary["configuration"]["container_memory_bytes"], 18 * GIB)
        self.assertEqual(summary["cgroup_memory_max_bytes"], 18 * GIB)
        self.assertEqual(summary["effective_budget_bytes"], 18 * GIB)
        self.assertEqual(summary["ceiling_bytes"], ladder.ceiling_bytes(18 * GIB))
        self.assertEqual(summary["rungs"][0]["status"], "PASS", summary["rungs"][0]["reasons"])

    def test_admission_refuses_before_any_rung(self):
        cases = {"not declared": dict(container_memory_bytes=None),
                 "not readable": dict(cgroup_memory_max=None),
                 "does not match": dict(cgroup_memory_max=22 * GIB),
                 "below the Stage 2 peak": dict(container_memory_bytes=15 * GIB, cgroup_memory_max=15 * GIB)}
        for needle, kw in cases.items():
            with self.subTest(needle=needle):
                summary, seen = self.run_real(good_record(), **kw)
                self.assertEqual(seen, [])
                self.assertEqual(summary["stopped_at"], "start gate")
                self.assertTrue(any(needle in x for x in summary["host_start_gate"]), summary["host_start_gate"])

    def test_page_rounding_of_the_kernel_limit_is_accepted(self):
        declared = 18 * GIB + 1024
        self.assertEqual(ladder.cap_gate(20 * GIB, declared, 18 * GIB), [])
        self.assertTrue(ladder.cap_gate(20 * GIB, 18 * GIB, 18 * GIB + 4096))

    def test_a_rung_record_without_the_cgroup_limit_fails_a_real_run(self):
        summary, _ = self.run_real(good_record())
        self.assertEqual(summary["rungs"][0]["status"], "FAILURE")
        self.assertTrue(any("no cgroup memory.max reading" in x for x in summary["rungs"][0]["reasons"]))

    def test_cgroup_max_reader(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "memory.max"
            path.write_text("16424808448\n")
            self.assertEqual(ladder.read_cgroup_max(path), 16424808448)
            path.write_text("max\n")
            self.assertIsNone(ladder.read_cgroup_max(path))
            self.assertIsNone(ladder.read_cgroup_max(Path(tmp) / "absent"))

    def test_the_runner_hands_the_effective_budget_to_the_default_child(self):
        seen = []

        def fake(rung, rung_dir, *args):
            seen.append(args)
            return stub(good_record(cgroup_memory_max=18 * GIB))(rung, rung_dir)
        with mock.patch.object(ladder, "default_child_command", fake), tempfile.TemporaryDirectory() as tmp:
            ladder.run_ladder(rungs=[128], budget_bytes=20 * GIB, out_dir=Path(tmp), model="pinned",
                              host_available_start=26 * GIB, container_memory_bytes=18 * GIB, cgroup_memory_max=18 * GIB)
        self.assertEqual(seen[0], ("pinned", 3, 20 * GIB, "original", 18 * GIB))

    def test_cli_requires_cgroup_evidence_for_the_pinned_model_only(self):
        for model, required in (("pinned", True), ("synthetic", False)):
            result = {"model": model, "qualification": False, "configuration": {}, "stopped_at": None, "complete": True}
            with mock.patch.object(ladder, "run_ladder", return_value=result) as runner,                     mock.patch.object(ladder, "read_cgroup_max", return_value=123), mock.patch("builtins.print"):
                ladder.main(["run", "--budget-bytes", "1000", "--out", "x", "--model", model])
            self.assertEqual(runner.call_args.kwargs["require_cgroup_evidence"], required, model)
            self.assertEqual(runner.call_args.kwargs["cgroup_memory_max"], 123)

    def test_child_gets_the_effective_budget(self):
        argv = ladder.default_child_command(128, "d", budget_bytes=20, effective_budget_bytes=18)
        self.assertEqual(argv[argv.index("--budget-bytes") + 1], "20")
        self.assertEqual(argv[argv.index("--effective-budget-bytes") + 1], "18")


class EvidenceTests(unittest.TestCase):
    """Codex findings 2 and 4: the kernel high-water counts, and missing or invalid evidence is never zero or PASS."""

    NAN, INF = float("nan"), float("inf")
    TRAIN = {"max_refault_file_per_s": 0.0, "refault_anon_delta": 0, "reset": [], "invalid_samples": 0, "coverage_ok": True}

    def verdict(self, drop=(), rule="v1", limit=None, train=None, **over):
        if rule == "v2" and "paging" not in over:
            over["paging"] = {"phases": {"train": dict(self.TRAIN, **(train or {}))}}
        rec = good_record(**over)
        for key in drop:
            rec.pop(key, None)
        return ladder.judge_rung(rec, budget_bytes=BUDGET, paging_rule=rule, paging_limit=limit)

    def assertFails(self, needle, **kw):
        v = self.verdict(**kw)
        self.assertEqual(v["status"], "FAILURE", kw)
        self.assertTrue(any(needle in r for r in v["reasons"]), (needle, v["reasons"]))

    def test_baselines_pass(self):
        self.assertEqual(self.verdict()["status"], "PASS")
        self.assertEqual(self.verdict(rule="v2", limit=50.0)["status"], "PASS")

    def test_kernel_peak_between_samples_fails_the_original_metric(self):
        # Codex's reproduction: sampled 80, kernel 99, budget 100
        rec = good_record(memory_peak_bytes=99, ceiling_metric="original", ceiling_metric_peak_bytes=80, kernel_memory_peak_bytes=99)
        verdict = ladder.judge_rung(rec, budget_bytes=100)
        self.assertEqual(verdict["status"], "FAILURE")
        self.assertTrue(any("high-water 99" in r for r in verdict["reasons"]), verdict["reasons"])

    def test_original_metric_needs_the_kernel_peak(self):
        self.assertFails("kernel memory.peak not recorded", drop=("kernel_memory_peak_bytes",))
        self.assertFails("kernel memory.peak not recorded", kernel_memory_peak_bytes=self.NAN)

    def test_anon_shmem_is_not_judged_on_the_kernel_peak_unless_it_fell_back(self):
        over = dict(ceiling_metric="anon_shmem", ceiling_metric_peak_bytes=5 * GIB, kernel_memory_peak_bytes=BUDGET)
        self.assertEqual(self.verdict(**over)["status"], "PASS")
        self.assertFails("above 90 percent", ceiling_metric_fallback_to_original=True, **over)

    def test_anon_shmem_does_not_borrow_another_figure_when_its_peak_is_missing(self):
        self.assertFails("memory high-water not recorded", ceiling_metric="anon_shmem")

    def test_invalid_memory_figures_never_pass(self):
        for bad in (None, self.NAN, self.INF, -1, True):
            with self.subTest(bad=bad):
                self.assertFails("memory high-water not recorded", memory_peak_bytes=bad)

    def test_invalid_swap_oom_and_fault_counts_never_pass(self):
        cases = (("swap_peak_bytes", "swap evidence"), ("swap_missing_samples", "swap evidence"),
                 ("oom_events", "OOM counter"), ("major_fault_delta", "major_fault_delta"))
        for key, needle in cases:
            with self.subTest(key=key, case="absent"):
                self.assertFails(needle, drop=(key,))
            for bad in (None, self.NAN, -1):
                with self.subTest(key=key, bad=bad):
                    self.assertFails(needle, **{key: bad})
        self.assertFails("swap evidence", swap_missing_samples=1)

    def test_v2_missing_or_invalid_anonymous_refaults_never_pass(self):
        # Codex's reproduction: rate 0 with no anonymous counter returned PASS
        for bad in (None, self.NAN, -1):
            with self.subTest(bad=bad):
                self.assertFails("anonymous-page refault", rule="v2", limit=50.0, train={"refault_anon_delta": bad})

    def test_v2_rate_and_limit_must_be_finite_and_nonnegative(self):
        for bad in (None, self.NAN, self.INF, -1):
            with self.subTest(rate=bad):
                self.assertFails("refault_file rate", rule="v2", limit=50.0, train={"max_refault_file_per_s": bad})
        for bad in (self.NAN, self.INF, -1):   # Codex's reproduction: rate 100 with a NaN limit returned PASS
            with self.subTest(limit=bad):
                self.assertFails("not a finite number", rule="v2", limit=bad, train={"max_refault_file_per_s": 100.0})

    def test_v2_incomplete_or_reset_samples_never_pass(self):
        for train in ({"invalid_samples": 1}, {"invalid_samples": None}, {"reset": ["refault_file"]}, {"reset": None}):
            with self.subTest(train=train):
                self.assertFails("incomplete", rule="v2", limit=50.0, train=train)

    def test_a_reset_counter_fails_under_v1_too(self):
        self.assertFails("went backwards", paging={"phases": {"load": {"reset": ["majflt"]}}})

    def test_cli_rejects_a_nan_or_negative_limit(self):
        for bad in ("nan", "inf", "-1"):
            proc = subprocess.run([sys.executable, str(HERE / "ladder.py"), "run", "--budget-bytes", "1000", "--out", "x",
                                   "--paging-rule", "v2", f"--paging-max-train-refaults-per-s={bad}"],
                                  cwd=str(HERE), capture_output=True, text=True, timeout=60)
            self.assertNotEqual(proc.returncode, 0, bad)
            self.assertIn("finite number", proc.stderr, bad)

    def paging(self, rows, extra=""):
        header = "epoch,current,rss,anon,file,shmem,metric,phase,majflt,pgmajfault,refault_file,refault_anon\n"
        body = "".join(f"{t},1,1,1,1,0,1,{ph},{a},{b},{c},{d}\n" for t, ph, a, b, c, d in rows)
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "memory-trace.csv"
            path.write_text(header + body + extra, encoding="utf-8")
            return ladder.paging_from_trace(path)["phases"]

    def test_trace_reports_resets_inside_and_across_phases(self):
        inside = self.paging([(0, "train", 5, 5, 5, 0), (1, "train", 4, 5, 5, 0), (9, "train", 9, 5, 5, 0)])
        self.assertEqual(inside["train"]["reset"], ["majflt"])
        across = self.paging([(0, "load", 5, 5, 50, 0), (1, "train", 5, 5, 3, 0), (9, "train", 5, 5, 4, 0)])
        self.assertEqual(across["train"]["reset"], ["refault_file"])
        self.assertEqual(across["load"]["reset"], [])

    def test_trace_counts_blank_counters_and_cut_lines(self):
        clean = self.paging([(0, "train", 1, 1, 1, 0), (9, "train", 1, 1, 1, 0)])
        self.assertEqual((clean["train"]["invalid_samples"], clean["train"]["reset"]), (0, []))
        blank = self.paging([(0, "train", 1, 1, 1, 0), (5, "train", 1, 1, "", 0), (9, "train", 1, 1, 1, 0)])
        self.assertEqual(blank["train"]["invalid_samples"], 1)
        cut = self.paging([(0, "train", 1, 1, 1, 0), (9, "train", 1, 1, 1, 0)], extra="10,1,1,1")
        self.assertEqual(cut["train"]["invalid_samples"], 1)


class WatcherEvidenceTests(unittest.TestCase):
    """ladder_rung imports no torch at module level, so these run everywhere."""

    def cgroup(self, **files):
        import ladder_rung
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        cg = Path(tmp.name)
        for name, value in files.items():
            (cg / name.replace("_", ".", 1).replace("_", ".")).write_text(f"{value}\n")
        old = ladder_rung.CGROUP
        ladder_rung.CGROUP = cg
        self.addCleanup(setattr, ladder_rung, "CGROUP", old)
        return ladder_rung, cg

    def watch(self, module, metric="original", budget=1000):
        breaches = []
        with mock.patch.object(module, "_rss_bytes", return_value=10):
            watcher = module.Watcher(budget, 0.9, breaches.append, metric)
            watcher.sample()
            record = watcher.finish()
        return breaches, record

    def test_kernel_peak_breaches_live_and_is_the_metric_peak(self):
        module, _ = self.cgroup(memory_current=800, memory_peak=990, memory_swap_current=0)
        breaches, record = self.watch(module)
        self.assertEqual(breaches[0], 990)
        self.assertEqual(record["ceiling_metric_peak_bytes"], 990)
        self.assertEqual(record["kernel_memory_peak_bytes"], 990)
        self.assertEqual(record["kernel_memory_peak_at_start_bytes"], 990)

    def test_kernel_peak_does_not_enter_the_anon_shmem_metric(self):
        module, cg = self.cgroup(memory_current=800, memory_peak=990, memory_swap_current=0)
        (cg / "memory.stat").write_text("anon 100\nshmem 1\n")
        breaches, record = self.watch(module, "anon_shmem")
        self.assertEqual(breaches, [])
        self.assertEqual(record["ceiling_metric_peak_bytes"], 101)
        self.assertEqual(record["kernel_memory_peak_bytes"], 990)

    def test_unread_swap_is_none_not_zero(self):
        module, _ = self.cgroup(memory_current=1)
        _, record = self.watch(module)
        self.assertIsNone(record["swap_peak_bytes"])
        self.assertEqual(record["swap_missing_samples"], 2)
        self.assertIsNone(record["kernel_memory_peak_bytes"])

    def test_swap_high_water_between_samples_is_kept(self):
        module, _ = self.cgroup(memory_current=1, memory_swap_current=0, memory_swap_peak=4096)
        _, record = self.watch(module)
        self.assertEqual((record["swap_peak_bytes"], record["swap_missing_samples"]), (4096, 0))

    def test_oom_delta_never_invents_a_start_value(self):
        import ladder_rung
        both = {"oom": 0, "oom_kill": 0}
        self.assertEqual(ladder_rung.oom_delta(both, {"oom": 1, "oom_kill": 1}), 2)
        self.assertEqual(ladder_rung.oom_delta(both, both), 0)
        for start, end in (({}, both), (both, {}), ({"oom": 0}, both), ({"oom": 2, "oom_kill": 0}, both)):
            self.assertIsNone(ladder_rung.oom_delta(start, end), (start, end))


class PhaseCoverageTests(unittest.TestCase):
    """Codex finding 4, second half: boundary intervals kept, enough of training sampled, unrounded comparison."""

    HEADER = "epoch,current,rss,anon,file,shmem,metric,phase,majflt,pgmajfault,refault_file,refault_anon\n"

    def analyse(self, rows):
        """rows: (t, phase, refault_file, refault_anon)"""
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "memory-trace.csv"
            path.write_text(self.HEADER + "".join(f"{t},1,1,1,1,0,1,{ph},0,0,{rf},{ra}\n" for t, ph, rf, ra in rows), encoding="utf-8")
            return ladder.paging_from_trace(path)

    def good(self, step=1.0, end=10):
        train = [(1 + i * step, "train", 0, 0) for i in range(int(end / step) + 1)]
        return [(0, "load", 0, 0)] + train + [(train[-1][0] + 1, "save", 0, 0)]

    def verdict(self, paging, limit=50.0):
        return ladder.judge_rung(good_record(paging=paging), budget_bytes=BUDGET, paging_rule="v2", paging_limit=limit)

    def assertIncomplete(self, rows):
        paging = self.analyse(rows)
        self.assertFalse(paging["phases"]["train"]["coverage_ok"], paging)
        verdict = self.verdict(paging)
        self.assertEqual(verdict["status"], "FAILURE")
        self.assertTrue(any("do not cover the training phase" in r for r in verdict["reasons"]), verdict["reasons"])

    def test_a_well_sampled_training_phase_passes(self):
        paging = self.analyse(self.good())
        self.assertTrue(paging["phases"]["train"]["coverage_ok"])
        self.assertEqual(self.verdict(paging)["status"], "PASS")

    def test_anonymous_refaults_across_either_boundary_count_against_training(self):
        for which in ("entry", "exit"):
            with self.subTest(which=which):
                rows = self.good()
                if which == "entry":   # rose between the last load sample and the first training sample
                    rows = [rows[0]] + [(t, ph, rf, 4) for t, ph, rf, _ in rows[1:]]
                else:                  # rose between the last training sample and the first save sample
                    rows = rows[:-1] + [(rows[-1][0], "save", 0, 4)]
                paging = self.analyse(rows)
                self.assertEqual(paging["phases"]["train"]["refault_anon_delta"], 4)
                self.assertEqual(self.verdict(paging)["status"], "FAILURE")
                self.assertEqual(sum(b["refault_anon_delta"] for b in paging["boundaries"]), 4)

    def test_load_and_save_keep_their_own_samples(self):
        rows = [(0, "load", 0, 0), (1, "load", 7, 0), (2, "train", 9, 0), (8, "train", 9, 0), (9, "save", 9, 0)]
        phases = self.analyse(rows)["phases"]
        self.assertEqual((phases["load"]["refault_file_delta"], phases["train"]["refault_file_delta"]), (7, 2))

    def test_a_file_refault_spike_in_the_last_short_interval_is_seen(self):
        rows = [(0, "load", 0, 0), (1, "train", 0, 0), (6, "train", 0, 0), (6.5, "save", 5000, 0)]
        rate = self.analyse(rows)["phases"]["train"]["max_refault_file_per_s"]
        self.assertGreater(rate, 800)   # 5000 over the shortest window ending at the last sample

    def test_a_spike_at_the_start_is_seen(self):
        rows = [(0, "load", 0, 0), (0.5, "train", 5000, 0), (5.5, "train", 5000, 0), (11, "save", 5000, 0)]
        self.assertGreater(self.analyse(rows)["phases"]["train"]["max_refault_file_per_s"], 800)

    def test_rate_is_not_rounded_before_comparison(self):
        rows = [(0, "load", 0, 0), (0, "train", 0, 0), (5, "train", 251, 0), (10, "save", 251, 0)]
        paging = self.analyse(rows)
        rate = paging["phases"]["train"]["max_refault_file_per_s"]
        self.assertEqual(rate, 251 / 5)
        self.assertEqual(self.verdict(paging, limit=50.2)["status"], "PASS")
        self.assertEqual(self.verdict(paging, limit=50.1999)["status"], "FAILURE")
        paging["phases"]["train"]["max_refault_file_per_s"] = 50.004
        self.assertEqual(self.verdict(paging, limit=50.0)["status"], "FAILURE")
        third = self.analyse([(0, "load", 0, 0), (0, "train", 0, 0), (6, "train", 100, 0), (7, "save", 100, 0)])
        self.assertEqual(third["phases"]["train"]["max_refault_file_per_s"], 100 / 6)

    def test_training_shorter_than_one_window_is_incomplete(self):
        self.assertIncomplete([(0, "load", 0, 0), (1, "train", 0, 0), (3, "train", 0, 0), (4, "save", 0, 0)])

    def test_one_training_sample_cannot_borrow_a_window_from_its_neighbours(self):
        # Codex's first reproduction: boundary samples stretched one training sample to 6 s
        self.assertIncomplete([(0, "load", 0, 0), (4, "train", 0, 0), (6, "save", 0, 0)])

    def test_a_tenth_of_a_second_of_training_cannot_borrow_a_window(self):
        # Codex's second reproduction: 0.1 s between the training samples
        rows = [(0, "load", 0, 0), (4, "train", 0, 0), (4.1, "train", 400, 0), (8, "save", 400, 0)]
        self.assertIncomplete(rows)
        train = self.analyse(rows)["phases"]["train"]
        self.assertEqual((train["rows"], train["seconds"]), (2, 8))
        self.assertAlmostEqual(train["own_seconds"], 0.1)
        self.assertEqual(train["refault_file_delta"], 400)   # boundary counters are still attributed

    def test_the_training_samples_themselves_must_span_one_window(self):
        def rows(end):
            return [(0, "load", 0, 0), (1, "train", 0, 0), (end, "train", 0, 0), (end + 1, "save", 0, 0)]
        self.assertTrue(self.analyse(rows(6))["phases"]["train"]["coverage_ok"])
        self.assertIncomplete(rows(5.999))

    def test_a_gap_longer_than_the_window_is_incomplete(self):
        self.assertIncomplete([(0, "load", 0, 0), (1, "train", 0, 0), (7, "train", 0, 0), (8, "save", 0, 0)])
        self.assertIncomplete(self.good()[:-1] + [(30, "save", 0, 0)])             # across the exit boundary
        self.assertIncomplete([(-20, "load", 0, 0)] + self.good()[1:])             # across the entry boundary

    def test_missing_a_boundary_sample_is_incomplete(self):
        self.assertIncomplete(self.good()[1:])    # nothing before training
        self.assertIncomplete(self.good()[:-1])   # nothing after it

    def test_time_going_backwards_or_phases_out_of_order_is_incomplete(self):
        rows = self.good()
        rows[3] = (0.5, "train", 0, 0)
        self.assertIncomplete(rows)
        rows = self.good()
        rows.insert(4, (3.5, "load", 0, 0))
        self.assertIncomplete(rows)

    def test_a_summary_without_a_coverage_flag_never_passes(self):
        train = {"max_refault_file_per_s": 0.0, "refault_anon_delta": 0, "reset": [], "invalid_samples": 0}
        self.assertEqual(self.verdict({"phases": {"train": train}})["status"], "FAILURE")

    def test_non_finite_time_is_a_malformed_line(self):
        rows = self.good()
        rows.insert(3, ("nan", "train", 0, 0))
        self.assertEqual(self.analyse(rows)["phases"]["train"]["invalid_samples"], 1)


class PhaseSwitchTests(unittest.TestCase):
    def watcher(self):
        import ladder_rung
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.addCleanup(setattr, ladder_rung, "PHASE", "load")
        self.addCleanup(setattr, ladder_rung, "CGROUP", ladder_rung.CGROUP)
        ladder_rung.CGROUP = Path(tmp.name)
        trace = Path(tmp.name) / "memory-trace.csv"
        return ladder_rung, ladder_rung.Watcher(10 ** 12, 0.9, lambda peak: None, "original", trace), trace

    def test_a_switch_writes_a_closing_and_an_opening_sample(self):
        module, watcher, trace = self.watcher()
        watcher.set_phase("train")
        watcher.set_phase("save")
        watcher.finish()
        phases = [line.split(",")[7] for line in trace.read_text().splitlines()[1:]]
        self.assertEqual(phases, ["load", "train", "train", "save", "save"])

    def test_a_wall_clock_step_does_not_move_trace_times(self):
        import time
        module, watcher, trace = self.watcher()
        real = time.time()
        with mock.patch.object(module.time, "time", side_effect=[real - 61, real + 62, real - 3600, real] * 5):
            for _ in range(4):
                watcher.sample()
        watcher.finish()
        times = [float(line.split(",")[0]) for line in trace.read_text().splitlines()[1:]]
        self.assertEqual(times, sorted(times))
        self.assertLess(times[-1] - times[0], 5)
        self.assertLess(abs(times[0] - real), 5)

    def test_samples_from_two_threads_never_interleave(self):
        import threading
        import time
        module, watcher, trace = self.watcher()
        counter = iter(range(10 ** 6))

        def slow_faults():
            value = next(counter)
            time.sleep(0.02 if value % 2 == 0 else 0)   # an unlocked later sample would overtake this one
            return value
        with mock.patch.object(module, "_major_faults", slow_faults):
            threads = [threading.Thread(target=lambda: [watcher.sample() for _ in range(15)]) for _ in range(3)]
            for t in threads:
                t.start()
            for t in threads:
                t.join()
            watcher.finish()
        faults = [int(line.split(",")[8]) for line in trace.read_text().splitlines()[1:]]
        self.assertEqual(faults, sorted(faults))


@unittest.skipUnless(HAVE_TORCH, "torch, peft and transformers are not installed")
class ChildTests(unittest.TestCase):
    def run_child(self, rung, extra=()):
        with tempfile.TemporaryDirectory() as tmp:
            proc = subprocess.run([sys.executable, str(HERE / "ladder_rung.py"), "--rung", str(rung), "--out", tmp,
                                   "--model", "synthetic", "--steps", "3", "--budget-bytes", str(8 * GIB), *extra],
                                  cwd=str(HERE), capture_output=True, text=True, timeout=300)
            path = Path(tmp) / "rung.json"
            record = json.loads(path.read_text()) if path.exists() else None
        return proc, record

    def test_synthetic_rung_completes_with_evidence(self):
        proc, record = self.run_child(48)
        self.assertEqual(proc.returncode, 0, proc.stderr[-2000:])
        self.assertTrue(record["completed"])
        self.assertEqual(record["optimizer_steps"], 3)
        self.assertEqual(record["actual_token_lengths"], {"min": 48, "max": 48, "rows": 12})
        self.assertTrue(record["adapter_saved"])
        self.assertTrue(record["adapter_reload_equal"])
        self.assertGreater(record["adapter_tensors_compared"], 0)
        self.assertEqual(len(record["losses"]), 3)
        self.assertEqual(len(record["grad_norms"]), 3)
        self.assertTrue(all(map(lambda v: v == v and abs(v) != float("inf"), record["losses"] + record["grad_norms"])))
        self.assertTrue(record["synthetic_only"])

    def test_budget_breach_writes_failure_and_exits(self):
        with tempfile.TemporaryDirectory() as tmp:
            proc = subprocess.run([sys.executable, str(HERE / "ladder_rung.py"), "--rung", "48", "--out", tmp,
                                   "--model", "synthetic", "--steps", "3", "--budget-bytes", "1000000"],
                                  cwd=str(HERE), capture_output=True, text=True, timeout=300)
            record = json.loads((Path(tmp) / "rung.json").read_text())
        self.assertNotEqual(proc.returncode, 0)
        self.assertFalse(record["completed"])
        self.assertIn("percent of budget", record["failure"])

    def test_child_watcher_stops_on_the_lower_effective_budget(self):
        proc, record = self.run_child(48, extra=("--effective-budget-bytes", "1000000"))   # --budget-bytes is 8 GiB
        self.assertNotEqual(proc.returncode, 0)
        self.assertEqual(record["effective_budget_bytes"], 1000000)
        self.assertEqual(record["budget_bytes"], 8 * GIB)
        self.assertIn("percent of budget 1000000", record["failure"])

    def test_reload_comparison_detects_a_difference(self):
        import torch
        import ladder_rung

        class Fake:
            def __init__(self, a, b):
                self._s = {"m.lora_A.default.weight": a, "m.lora_A.reloaded.weight": b}

            def state_dict(self):
                return self._s
        same = torch.ones(2, 2)
        self.assertTrue(ladder_rung.adapter_tensors_equal(Fake(same, same.clone()), "default", "reloaded")[0])
        self.assertFalse(ladder_rung.adapter_tensors_equal(Fake(same, same + 1e-6), "default", "reloaded")[0])
        self.assertFalse(ladder_rung.adapter_tensors_equal(Fake(same, same), "nothere", "reloaded")[0])

    def test_memory_stat_parse(self):
        import ladder_rung
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "memory.stat"
            path.write_text("anon 100\nfile 250\nshmem 7\nbad line\nkernel 3\n", encoding="utf-8")
            parsed = ladder_rung._read_stat(path)
            self.assertEqual((parsed["anon"], parsed["file"], parsed["shmem"], parsed["kernel"]), (100, 250, 7, 3))
            self.assertEqual(ladder_rung._read_stat(Path(tmp) / "missing"), {})

    def test_watcher_records_anon_and_file_split_without_changing_the_stop_rule(self):
        import ladder_rung
        with tempfile.TemporaryDirectory() as tmp:
            cg = Path(tmp)
            old = ladder_rung.CGROUP
            ladder_rung.CGROUP = cg
            try:
                breaches = []
                watcher = ladder_rung.Watcher(10 ** 12, 0.9, breaches.append)   # ceiling 9e11
                (cg / "memory.current").write_text("600000000000\n")
                (cg / "memory.stat").write_text("anon 200\nfile 400\nshmem 0\n")
                watcher.sample()
                (cg / "memory.current").write_text("850000000000\n")
                (cg / "memory.stat").write_text("anon 300\nfile 550\nshmem 0\n")
                watcher.sample()
                self.assertEqual(breaches, [])   # 8.5e11 is under 9e11; the split does not enter the rule
                (cg / "memory.current").write_text("950000000000\n")
                (cg / "memory.stat").write_text("anon 310\nfile 640\nshmem 0\n")
                watcher.sample()
                self.assertEqual(len(breaches), 1)   # ceiling is still on memory.current, as before
                b = watcher.breakdown()["memory_breakdown"]
                self.assertEqual((b["cgroup_current_peak_bytes"], b["anon_peak_bytes"], b["file_cache_peak_bytes"]), (950000000000, 310, 640))
                self.assertEqual(b["memory_stat_at_current_peak"]["file"], 640)
                self.assertIn("Not used by any stop rule", b["note"])
            finally:
                ladder_rung.CGROUP = old

    def test_breakdown_without_a_cgroup_is_explicit_none(self):
        import ladder_rung
        with tempfile.TemporaryDirectory() as tmp:
            old = ladder_rung.CGROUP
            ladder_rung.CGROUP = Path(tmp) / "no-cgroup"
            try:
                watcher = ladder_rung.Watcher(0, 0.9, lambda peak: None)
                watcher.sample()
                b = watcher.breakdown()["memory_breakdown"]
                self.assertIsNone(b["anon_peak_bytes"])
                self.assertIsNone(b["file_cache_peak_bytes"])
                self.assertIsNone(b["cgroup_current_peak_bytes"])
            finally:
                ladder_rung.CGROUP = old

    def test_rung_record_carries_the_breakdown(self):
        proc, record = self.run_child(48)
        self.assertEqual(proc.returncode, 0, proc.stderr[-2000:])
        self.assertIn("memory_breakdown", record)
        self.assertIn("peak_source", record["memory_breakdown"])

    def _watcher_with(self, metric, current, stat_text, budget=10 ** 12):
        import ladder_rung
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        cg = Path(tmp.name)
        (cg / "memory.current").write_text(str(current) + "\n")
        if stat_text is not None:
            (cg / "memory.stat").write_text(stat_text)
        old = ladder_rung.CGROUP
        ladder_rung.CGROUP = cg
        self.addCleanup(setattr, ladder_rung, "CGROUP", old)
        breaches = []
        watcher = ladder_rung.Watcher(budget, 0.9, breaches.append, metric)
        watcher.sample()
        return watcher, breaches

    def test_anon_shmem_ignores_page_cache_but_original_does_not(self):
        stat = "anon 300000000000\nshmem 1000000000\nfile 650000000000\n"
        _, original_breaches = self._watcher_with("original", 950000000000, stat)
        watcher, b_breaches = self._watcher_with("anon_shmem", 950000000000, stat)
        self.assertEqual(len(original_breaches), 1)      # configuration A: memory.current 9.5e11 over 9e11
        self.assertEqual(b_breaches, [])                 # configuration B: anon+shmem 3.01e11 is under
        self.assertEqual(watcher.metric_peak, 301000000000)
        self.assertFalse(watcher.metric_fallback)

    def test_anon_shmem_still_stops_when_non_reclaimable_memory_is_over(self):
        _, breaches = self._watcher_with("anon_shmem", 950000000000, "anon 905000000000\nshmem 0\nfile 1000\n")
        self.assertEqual(breaches, [905000000000])

    def test_anon_shmem_falls_back_to_the_stricter_original_figure_when_memory_stat_is_unreadable(self):
        watcher, breaches = self._watcher_with("anon_shmem", 950000000000, None)
        self.assertTrue(watcher.metric_fallback)
        self.assertEqual(len(breaches), 1)
        self.assertTrue(watcher.breakdown()["ceiling_metric_fallback_to_original"])

    def test_record_names_the_metric_and_its_peak(self):
        watcher, _ = self._watcher_with("anon_shmem", 5, "anon 3\nshmem 1\nfile 1\n", budget=10 ** 12)
        b = watcher.breakdown()
        self.assertEqual(b["ceiling_metric"], "anon_shmem")
        self.assertEqual(b["ceiling_metric_peak_bytes"], 4)

    def test_child_synthetic_rung_under_anon_shmem_is_labelled(self):
        proc, record = self.run_child(48, extra=("--ceiling-metric", "anon_shmem"))
        self.assertEqual(proc.returncode, 0, proc.stderr[-2000:])
        self.assertEqual(record["ceiling_metric"], "anon_shmem")
        self.assertIn("NEW labelled configuration", record["configuration_label"])

    def test_watcher_writes_a_per_sample_trace_that_survives_without_finish(self):
        import ladder_rung
        with tempfile.TemporaryDirectory() as tmp:
            cg = Path(tmp) / "cg"
            cg.mkdir()
            (cg / "memory.current").write_text("500\n")
            (cg / "memory.stat").write_text("anon 300\nfile 150\nshmem 5\n")
            old = ladder_rung.CGROUP
            ladder_rung.CGROUP = cg
            try:
                trace = Path(tmp) / "run" / "memory-trace.csv"
                watcher = ladder_rung.Watcher(10 ** 12, 0.9, lambda peak: None, "anon_shmem", trace)
                watcher.sample()
                watcher.sample()
                # read BEFORE finish(): a SIGKILL never reaches finish(), so the lines must already be on disk
                lines = trace.read_text(encoding="utf-8").splitlines()
                self.assertEqual(lines[0], "epoch,current,rss,anon,file,shmem,metric,phase,majflt,pgmajfault,refault_file,refault_anon")
                self.assertEqual(len(lines), 3)
                cells = lines[1].split(",")
                self.assertEqual(cells[1], "500")
                self.assertEqual((cells[3], cells[4], cells[5], cells[6]), ("300", "150", "5", "305"))
                watcher.finish()
            finally:
                ladder_rung.CGROUP = old

    def test_child_writes_a_trace_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            proc = subprocess.run([sys.executable, str(HERE / "ladder_rung.py"), "--rung", "48", "--out", tmp, "--model", "synthetic",
                                   "--steps", "3", "--budget-bytes", str(8 * GIB)],
                                  cwd=str(HERE), capture_output=True, text=True, timeout=300)
            self.assertEqual(proc.returncode, 0, proc.stderr[-2000:])
            trace = (Path(tmp) / "memory-trace.csv").read_text(encoding="utf-8").splitlines()
        self.assertGreaterEqual(len(trace), 3)
        self.assertEqual(trace[0], "epoch,current,rss,anon,file,shmem,metric,phase,majflt,pgmajfault,refault_file,refault_anon")

    def test_child_trace_has_phases_and_the_record_has_paging(self):
        proc, record = self.run_child(48)
        self.assertEqual(proc.returncode, 0, proc.stderr[-2000:])
        phases = (record["paging"] or {}).get("phases", {})
        # the synthetic child runs on Windows in CI-free runs: counters may be blank, but the phase boundaries must be there
        self.assertIn("load", phases)
        self.assertIn("train", phases)

    def test_phase_callback_sets_train_then_save(self):
        import ladder_rung
        cb = ladder_rung.phase_callback()
        self.assertEqual(ladder_rung.PHASE, "load")
        cb.on_train_begin(None, None, None)
        self.assertEqual(ladder_rung.PHASE, "train")
        cb.on_train_end(None, None, None)
        self.assertEqual(ladder_rung.PHASE, "save")
        ladder_rung.PHASE = "load"

    def test_wrong_length_rows_are_refused(self):
        import ladder_rung
        rows = ladder_rung.synthetic_dataset(40, 4)
        self.assertEqual({len(r["input_ids"]) for r in rows}, {40})


if __name__ == "__main__":
    unittest.main()
