"""Tests for run_ladder.ps1 in -DryRun mode. A dry run prints the plan as JSON and executes nothing (no docker, no wsl, no
writes), so these run anywhere pwsh is installed (the GitHub ubuntu runner has it) and are SKIPPED, not passed, otherwise.
The real -Run and -Preflight paths are NOT covered here: they need Docker Desktop and the host, and have not run."""
import json
import shutil
import subprocess
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

    def test_boundary_is_exactly_stage2_peak_plus_floor(self):
        need = round(14.21 * GIB) + 4 * GIB
        ok = run("-Run", "-DryRun", "-FakeAvailableBytes", str(need), "-RunId", "t1")
        low = run("-Run", "-DryRun", "-FakeAvailableBytes", str(need - 1), "-RunId", "t1")
        self.assertEqual(ok.returncode, 0)
        self.assertEqual(low.returncode, 3)

    def test_six_gib_start_minimum_is_named_when_below_it(self):
        _, p = plan(5)
        self.assertTrue(any("start minimum" in r for r in p["gate_reasons"]))

    def test_docker_argv_has_the_fixed_safety_settings(self):
        _, p = plan(22)
        argv = p["docker_argv"]
        for needed in ("--rm", "--memory=22g", "--memory-swap=22g", "HF_HUB_OFFLINE=1"):
            self.assertIn(needed, argv)
        self.assertEqual(argv[argv.index("--network") + 1], "none")
        self.assertEqual(argv[argv.index("--model") + 1], "pinned")
        self.assertEqual(argv[argv.index("--budget-bytes") + 1], str(18 * GIB))
        self.assertEqual(argv[argv.index("--host-available-start") + 1], str(22 * GIB))
        self.assertEqual(argv[argv.index("--host-csv") + 1], "/out/host.csv")
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


if __name__ == "__main__":
    unittest.main()
