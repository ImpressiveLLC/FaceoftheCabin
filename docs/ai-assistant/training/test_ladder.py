"""Tests for the synthetic CPU length ladder (ladder.py, ladder_rung.py). Offline; the torch tests use a tiny random model
and are skipped when torch is not installed. Nothing here loads the pinned base model or touches real data."""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import ladder

HERE = Path(__file__).resolve().parent
GIB = 1024 ** 3
BUDGET = 10 * GIB


def good_record(**over):
    record = {"completed": True, "oom_events": 0, "losses": [4.1, 4.0, 3.9], "grad_norms": [0.1, 0.1, 0.1],
              "adapter_saved": True, "adapter_reload_equal": True, "memory_peak_bytes": 5 * GIB,
              "swap_peak_bytes": 0, "major_fault_delta": 0, "wall_seconds": 30.0}
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


try:
    import torch  # noqa: F401
    import peft  # noqa: F401
    import transformers  # noqa: F401
    HAVE_TORCH = True
except ImportError:
    HAVE_TORCH = False


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
                self.assertEqual(lines[0], "epoch,current,rss,anon,file,shmem,metric")
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
        self.assertEqual(trace[0], "epoch,current,rss,anon,file,shmem,metric")

    def test_wrong_length_rows_are_refused(self):
        import ladder_rung
        rows = ladder_rung.synthetic_dataset(40, 4)
        self.assertEqual({len(r["input_ids"]) for r in rows}, {40})


if __name__ == "__main__":
    unittest.main()
