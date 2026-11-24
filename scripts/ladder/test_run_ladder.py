"""Tests for run_ladder.ps1 in -DryRun mode. A dry run prints the plan as JSON and executes nothing (no docker, no wsl, no
writes), so these run anywhere pwsh is installed (the GitHub ubuntu runner has it) and are SKIPPED, not passed, otherwise.
The real -Run and -Preflight paths are NOT covered here: they need Docker Desktop and the host, and have not run."""
import json
import math
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "run_ladder.ps1"
PWSH = shutil.which("pwsh")
GIB = 1024 ** 3


def run(*args, expect=None):
    proc = subprocess.run([PWSH, "-NoProfile", "-NonInteractive", "-File", str(SCRIPT), *args],
                          capture_output=True, text=True, timeout=120)
    if expect is not None:
        assert proc.returncode == expect, f"exit {proc.returncode}\n{proc.stdout}\n{proc.stderr}"
    return proc


def plan(avail_gib, mode="-Run", *extra):
    proc = run(mode, "-DryRun", "-FakeAvailableBytes", str(int(avail_gib * GIB)), "-RunId", "t1", *extra)
    try:
        return proc, json.loads(proc.stdout)
    except ValueError:
        raise AssertionError("no JSON plan; exit %s | STDOUT: %s | STDERR: %s" % (proc.returncode, proc.stdout, proc.stderr))


@unittest.skipUnless(PWSH, "pwsh is not installed")
class DryRunTests(unittest.TestCase):
    def test_gate_passes_with_headroom(self):
        proc, p = plan(22)
        self.assertEqual(proc.returncode, 0)
        self.assertTrue(p["gate_passes"])
        self.assertFalse(p["executed"])
        self.assertEqual(p["budget_bytes"], 18 * GIB)  # min(22 GiB, 22 - 4)
        self.assertEqual(p["ceiling_bytes"], int(18 * GIB * 0.9))

    def test_budget_is_capped_at_the_container_limit(self):
        _, p = plan(40)
        self.assertEqual(p["budget_bytes"], 22 * GIB)

    def test_below_the_pinned_3b_need_is_refused_with_no_argv(self):
        for avail in (17.5, 17.01, 13.16, 5.0):
            with self.subTest(avail=avail):
                proc, p = plan(avail)
                self.assertEqual(proc.returncode, 3)
                self.assertFalse(p["gate_passes"])
                self.assertTrue(p["gate_reasons"])
                self.assertEqual(p["docker_argv"], [])

    def test_boundary_holds_the_stage2_peak_inside_the_90_percent_ceiling(self):
        # budget = available - 4 GiB; ceiling = 0.9 * budget must be at least the 14.21 GiB Stage 2 peak
        need = math.ceil(round(14.21 * GIB) / 0.9) + 4 * GIB
        _, at_need = plan(need / GIB)
        self.assertGreaterEqual(at_need["ceiling_bytes"], round(14.21 * GIB) - 2)
        ok = run("-Run", "-DryRun", "-FakeAvailableBytes", str(need), "-RunId", "t1")
        low = run("-Run", "-DryRun", "-FakeAvailableBytes", str(need - 1), "-RunId", "t1")
        self.assertEqual(ok.returncode, 0)
        self.assertEqual(low.returncode, 3)

    def test_gate_arithmetic_at_the_boundary(self):
        need = math.ceil(round(14.21 * GIB) / 0.9) + 4 * GIB
        ok = run("-Run", "-DryRun", "-FakeAvailableBytes", str(need), "-RunId", "t1")
        low = run("-Run", "-DryRun", "-FakeAvailableBytes", str(need - 1), "-RunId", "t1")
        self.assertEqual(ok.returncode, 0)
        self.assertEqual(low.returncode, 3)
        # the earlier gate (14.21 + 4 = 18.25 GiB) was too low: it let a run start whose ceiling is under the peak
        old_need = round(14.21 * GIB) + 4 * GIB
        self.assertEqual(run("-Run", "-DryRun", "-FakeAvailableBytes", str(old_need), "-RunId", "t1").returncode, 3)

    def test_docker_runs_inside_the_wsl_distro_image_and_volume(self):
        _, p = plan(22)
        argv = p["docker_argv"]
        self.assertIn("fotc-dev-sft-r1:stage2", argv)
        mounts = [argv[i + 1] for i, a in enumerate(argv) if a == "-v"]
        self.assertIn("fotc-hf-cache:/root/.cache/huggingface", mounts)
        text = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("function Invoke-Docker { & wsl -d $Distro --exec docker @args }", text)
        self.assertNotIn("& docker ", text)  # never the Windows docker (Docker Desktop)

    def test_no_docker_run_can_pull_an_image(self):
        text = SCRIPT.read_text(encoding="utf-8")
        runs = [line for line in text.splitlines() if "Invoke-Docker run" in line]
        self.assertTrue(runs)
        for line in runs:
            self.assertIn("--pull=never", line)

    def test_ceiling_metric_defaults_to_original_and_b_is_explicit(self):
        _, p = plan(22)
        self.assertEqual(p["ceiling_metric"], "original")
        self.assertEqual(p["docker_argv"][p["docker_argv"].index("--ceiling-metric") + 1], "original")
        _, b = plan(22, "-Run", "-CeilingMetric", "anon_shmem")
        self.assertEqual(b["docker_argv"][b["docker_argv"].index("--ceiling-metric") + 1], "anon_shmem")
        bad = run("-Run", "-DryRun", "-FakeAvailableBytes", str(22 * GIB), "-CeilingMetric", "nonsense")
        self.assertNotEqual(bad.returncode, 0)

    def test_config_c_caps_the_container_from_the_budget_and_a_is_unchanged(self):
        _, a = plan(22)
        argv = a["docker_argv"]
        self.assertIn("--memory=22g", argv)       # first two runs: unchanged
        self.assertEqual(argv[argv.index("--container-cap") + 1], "fixed")
        _, c = plan(22, "-Run", "-CeilingMetric", "anon_shmem", "-ContainerCap", "budget")
        argv = c["docker_argv"]
        limit = 18 * GIB - 2 * GIB                # budget (22 - 4) less the 2 GiB overhead margin
        self.assertIn(f"--memory={limit}", argv)
        self.assertIn(f"--memory-swap={limit}", argv)   # no swap: swap limit equals the memory limit
        self.assertEqual(argv[argv.index("--container-cap") + 1], "budget")
        self.assertEqual(argv[argv.index("--container-memory-bytes") + 1], str(limit))
        self.assertEqual(c["container_memory_bytes"], limit)
        self.assertEqual(c["ceiling_metric"], "anon_shmem")
        self.assertNotIn("--memory=22g", argv)

    def test_budget_cap_follows_available_memory(self):
        for avail in (21.5, 24.0, 30.0):
            with self.subTest(avail=avail):
                _, c = plan(avail, "-Run", "-ContainerCap", "budget")
                budget = min(22, avail - 4)
                self.assertEqual(c["container_memory_bytes"], int(budget * GIB) - 2 * GIB)

    def test_watchdog_polls_every_second_and_kills_on_one_low_sample(self):
        text = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("$PollSeconds = 1", text)
        self.assertIn("if ($low -ge 1) {", text)
        self.assertNotIn("$low -ge 2", text)

    def test_invalid_container_cap_is_rejected(self):
        self.assertNotEqual(run("-Run", "-DryRun", "-FakeAvailableBytes", str(22 * GIB), "-ContainerCap", "nonsense").returncode, 0)

    def test_six_gib_start_minimum_is_named_when_below_it(self):
        _, p = plan(5)
        self.assertTrue(any("start minimum" in r for r in p["gate_reasons"]))

    def test_docker_argv_has_the_fixed_safety_settings(self):
        _, p = plan(22)
        argv = p["docker_argv"]
        for needed in ("--rm", "--pull=never", "--memory=22g", "--memory-swap=22g", "HF_HUB_OFFLINE=1"):
            self.assertIn(needed, argv)
        self.assertEqual(argv[argv.index("--network") + 1], "none")
        self.assertEqual(argv[argv.index("--model") + 1], "pinned")
        self.assertEqual(argv[argv.index("--budget-bytes") + 1], str(18 * GIB))
        self.assertEqual(argv[argv.index("--host-available-start") + 1], str(22 * GIB))
        self.assertEqual(argv[argv.index("--host-csv") + 1], "/out/host.csv")
        self.assertIn("--host-epoch-at-start", argv)
        mounts = [argv[i + 1] for i, a in enumerate(argv) if a == "-v"]
        self.assertTrue(any(m.endswith(":/work:ro") for m in mounts), "training dir must be read-only")
        self.assertFalse(any("/data" in m or "eval-results" in m for m in mounts), "no real-data mount")
        self.assertEqual(argv[argv.index("--name") + 1], "ladder-t1")

    def test_dry_run_writes_nothing(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "ladder-run"
            run("-Run", "-DryRun", "-FakeAvailableBytes", str(22 * GIB), "-RunId", "t1", "-OutRoot", str(root))
            run("-Preflight", "-DryRun", "-FakeAvailableBytes", str(22 * GIB), "-RunId", "t1", "-OutRoot", str(root))
            self.assertFalse(root.exists())

    def test_fake_bytes_are_refused_outside_dry_run(self):
        proc = run("-Preflight", "-FakeAvailableBytes", str(22 * GIB))
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("only allowed with -DryRun", proc.stdout + proc.stderr)

    def test_cleanup_mode_is_a_distinct_mode(self):
        proc = run("-Cleanup", "-DryRun", "-FakeAvailableBytes", str(22 * GIB), "-RunId", "t1")
        self.assertEqual(json.loads(proc.stdout)["mode"], "cleanup")
        self.assertNotEqual(run("-Cleanup", "-Run", "-DryRun").returncode, 0)

    def test_script_has_a_finished_banner_and_a_cleanup_that_deletes_nothing(self):
        text = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("LADDER FINISHED", text)
        self.assertIn("LADDER-FINISHED.txt", text)
        cleanup = text[text.index("if ($Cleanup) {"):text.index("# ---", text.index("if ($Cleanup) {"))]
        for forbidden in ("Remove-Item", "docker rm", "docker volume rm", "docker system prune", "wsl --unregister"):
            self.assertNotIn(forbidden, cleanup)

    def test_exactly_one_mode_is_required(self):
        self.assertNotEqual(run("-DryRun").returncode, 0)
        self.assertNotEqual(run("-Run", "-Stop", "-DryRun").returncode, 0)


@unittest.skipUnless(PWSH and sys.platform == "win32", "needs pwsh on Windows (Win32 keep-awake call, cmd stubs)")
class RunFlowWithStubsTests(unittest.TestCase):
    """Runs the real -Run flow with stub docker and wsl on PATH and the gate thresholds patched down, so the keep-awake
    call, sampler job, markers, manifest and banner execute for real. Docker and WSL are NOT touched."""

    # One stub stands in for wsl.exe: answers `wsl -l -v`, ignores `--shutdown`, and plays the part of `wsl -d X --exec docker ...`.
    WSL_STUB = (
        "if ($args -contains '-l') { 'Ubuntu-24.04 Running'; exit 0 }\n"
        "if ($args -contains '--shutdown') { exit 0 }\n"
        "$i = [array]::IndexOf($args, 'docker')\n"
        "$d = if ($i -ge 0 -and $i + 1 -lt $args.Count) { @($args[($i + 1)..($args.Count - 1)]) } else { @() }\n"
        "if ($d.Count -gt 0 -and $d[0] -eq 'run' -and ($d -contains '--name')) {\n"
        "  for ($k = 0; $k -lt $d.Count; $k++) { if ($d[$k] -eq '-v' -and $d[$k + 1] -match '^/mnt/([a-z])/(.*):/out$') { $out = $Matches[1].ToUpper() + ':/' + $Matches[2] } }\n"
        "  New-Item -ItemType Directory -Force -Path (Join-Path $out 'ladder') | Out-Null\n"
        "  Start-Sleep 7\n"
        "  Set-Content (Join-Path $out 'ladder/summary.json') '{\"complete\": true, \"stopped_at\": null}'\n"
        "}\nexit 0\n")

    def test_run_flow_reaches_done_with_stubs(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            stubs = tmp / "stubs"
            stubs.mkdir()
            (stubs / "wsl.cmd").write_text('@echo off\r\npwsh -NoProfile -File "%~dp0wsl_stub.ps1" %*\r\nexit /b %ERRORLEVEL%\r\n')
            (stubs / "wsl_stub.ps1").write_text(self.WSL_STUB)
            text = SCRIPT.read_text(encoding="utf-8")
            patched = text.replace("$StartMinBytes = 6 * $GiB", "$StartMinBytes = 1 * $GiB").replace(
                "$NeedAtStartBytes = [int64][math]::Ceiling($Stage2PeakBytes / 0.9) + $FloorBytes", "$NeedAtStartBytes = 1 * $GiB")
            self.assertIn("$NeedAtStartBytes = 1 * $GiB", patched)
            self.assertNotEqual(patched, text)
            sim = tmp / "run_ladder_sim.ps1"
            sim.write_text(patched, encoding="utf-8")
            env = dict(os.environ, PATH=str(stubs) + os.pathsep + os.environ["PATH"])
            proc = subprocess.run([PWSH, "-NoProfile", "-File", str(sim), "-Run", "-OutRoot", str(tmp / "out"),
                                   "-RepoDir", str(HERE.parent.parent), "-DesktopDir", str(tmp)],
                                  capture_output=True, text=True, timeout=180, env=env)
            self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
            run_dir = next((tmp / "out").iterdir())
            self.assertTrue((run_dir / "DONE").exists(), proc.stdout + proc.stderr)
            self.assertTrue((run_dir / "manifest.sha256").exists())
            self.assertGreaterEqual(len((run_dir / "host.csv").read_text().splitlines()), 2)
            self.assertIn("LADDER FINISHED: DONE", (tmp / "LADDER-FINISHED.txt").read_text())

    def test_keep_awake_flags_are_unsigned_casts(self):
        text = SCRIPT.read_text(encoding="utf-8")
        self.assertNotIn("SetThreadExecutionState(0x", text)  # a bare 0x80000001 is Int32 in PowerShell and throws
        self.assertIn("SetThreadExecutionState([uint32]", text)


if __name__ == "__main__":
    unittest.main()
