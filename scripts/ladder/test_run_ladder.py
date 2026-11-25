"""Tests for run_ladder.ps1. They need pwsh (the GitHub ubuntu runner has it) and are SKIPPED, not passed, without it.
No test touches the machine: dry runs execute nothing, and flow tests run a COPY of the script beside a stub host_ops.ps1,
with LADDER_TEST_NO_HOST set so the real host_ops.ps1 would refuse even if it were loaded. The real host operations
themselves (WSL, Docker, process stop, power state, beep) are therefore NOT covered here."""
import hashlib
import json
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "run_ladder.ps1"
HOST_OPS = HERE / "host_ops.ps1"
PWSH = shutil.which("pwsh")
GIB = 1024 ** 3


def run(*args, expect=None):
    proc = subprocess.run([PWSH, "-NoProfile", "-NonInteractive", "-File", str(SCRIPT), *args],
                          capture_output=True, text=True, timeout=120, env=dict(os.environ, LADDER_TEST_NO_HOST="1"))
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
        self.assertIn("function Invoke-Docker { Invoke-Wsl -d $Distro --exec docker @args }", text)
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
        for avail in (22.0, 24.0, 30.0):
            with self.subTest(avail=avail):
                _, c = plan(avail, "-Run", "-ContainerCap", "budget")
                budget = min(22, avail - 4)
                limit = int(budget * GIB) - 2 * GIB
                self.assertEqual(c["container_memory_bytes"], limit)
                self.assertEqual(c["effective_budget_bytes"], limit)
                self.assertEqual(c["ceiling_bytes"], limit * 9 // 10)
                self.assertEqual(c["budget_bytes"], int(budget * GIB))

    def test_fixed_cap_keeps_the_budget_as_the_denominator(self):
        _, a = plan(22)
        self.assertEqual(a["effective_budget_bytes"], 18 * GIB)
        _, big = plan(40)
        self.assertEqual(big["effective_budget_bytes"], 22 * GIB)

    def test_budget_cap_needs_the_margin_at_the_start(self):
        need = math.ceil(round(14.21 * GIB) / 0.9) + 6 * GIB
        base = ("-Run", "-DryRun", "-RunId", "t1", "-ContainerCap", "budget", "-FakeAvailableBytes")
        ok = run(*base, str(need))
        self.assertEqual(ok.returncode, 0, ok.stdout + ok.stderr)
        self.assertGreaterEqual(json.loads(ok.stdout)["ceiling_bytes"], round(14.21 * GIB))
        self.assertEqual(run(*base, str(need - 1)).returncode, 3)

    def test_watchdog_polls_every_second_and_kills_on_one_low_sample(self):
        text = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("$PollSeconds = 1", text)
        self.assertIn("if ($low -ge 1) {", text)
        self.assertNotIn("$low -ge 2", text)

    def test_invalid_container_cap_is_rejected(self):
        self.assertNotEqual(run("-Run", "-DryRun", "-FakeAvailableBytes", str(22 * GIB), "-ContainerCap", "nonsense").returncode, 0)

    def test_paging_rule_defaults_to_v1_and_v2_needs_an_explicit_limit(self):
        _, p = plan(22)
        self.assertEqual(p["paging_rule"], "v1")
        self.assertEqual(p["docker_argv"][p["docker_argv"].index("--paging-rule") + 1], "v1")
        self.assertNotIn("--paging-max-train-refaults-per-s", p["docker_argv"])
        _, v2 = plan(22, "-Run", "-PagingRule", "v2", "-PagingMaxTrainRefaultsPerSecond", "25")
        self.assertEqual(v2["docker_argv"][v2["docker_argv"].index("--paging-rule") + 1], "v2")
        self.assertEqual(v2["docker_argv"][v2["docker_argv"].index("--paging-max-train-refaults-per-s") + 1], "25")
        refused = run("-Run", "-DryRun", "-FakeAvailableBytes", str(22 * GIB), "-PagingRule", "v2")
        self.assertNotEqual(refused.returncode, 0)
        self.assertIn("no limit has been ruled", refused.stdout + refused.stderr)
        for bad in ("NaN", "Infinity", "-5"):
            with self.subTest(limit=bad):
                proc = run("-Run", "-DryRun", "-FakeAvailableBytes", str(22 * GIB), "-PagingRule", "v2",
                           "-PagingMaxTrainRefaultsPerSecond", bad)
                self.assertNotEqual(proc.returncode, 0)

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


HOST_PATTERNS = (r"&\s*wsl\b", r"&\s*docker\b", r"\bStop-Process\b", r"\bStart-Process\b", r"\bGet-Process\b", r"\bGet-CimInstance\b",
                 r"\bGet-WmiObject\b", r"\bAdd-Type\b", r"SetThreadExecutionState", r"\[console\]::Beep", r"GetFolderPath",
                 r"(?m)^\s*wsl(\.exe)?\s", r"\btaskkill\b", r"\bStop-Service\b", r"\bRestart-Computer\b")


class HostIsolationTests(unittest.TestCase):
    """Codex finding 6: a test must not be able to reach real processes, sampling or power state."""

    def test_the_launcher_touches_the_machine_only_through_host_ops(self):
        text = SCRIPT.read_text(encoding="utf-8")
        for pattern in HOST_PATTERNS:
            self.assertIsNone(re.search(pattern, text, re.IGNORECASE), f"direct host call in run_ladder.ps1: {pattern}")
        self.assertIn("$HostOps = Join-Path $PSScriptRoot 'host_ops.ps1'", text)
        self.assertEqual(text.count(". $HostOps"), 1)
        self.assertIn(". $hostOps", text)   # the sampler job loads the same file

    def test_every_real_host_function_that_acts_is_guarded(self):
        functions = self.host_functions()
        self.assertGreaterEqual(len(functions), 9)
        for name, block in functions:
            if name not in ("Assert-HostOpsAllowed", "Get-DockerDesktopProcessNames"):
                self.assertTrue(block.split("{", 1)[1].lstrip().startswith("Assert-HostOpsAllowed"), f"{name}: guard first")

    def host_functions(self):
        blocks = re.split(r"(?m)^function ", HOST_OPS.read_text(encoding="utf-8"))[1:]
        return [(b.split()[0].split("(")[0], b) for b in blocks]

    @unittest.skipUnless(PWSH, "pwsh is not installed")
    def test_real_host_ops_refuse_under_the_test_guard(self):
        calls = ("Invoke-Wsl --version", "Get-HostAvailableBytes", "Test-DockerDesktopRunning", "Stop-DockerDesktop",
                 "Set-KeepAwake $true", "Get-HostMachineFacts 'x'", "Get-DesktopPath", "Send-FinishSignal")
        # Shadow every command the real functions would use, so a missing guard fails this test instead of acting on the machine.
        shadow = " ".join(f"function {c} {{ throw 'REACHED THE HOST: {c}' }};" for c in
                          ("wsl", "Get-Process", "Stop-Process", "Get-CimInstance", "Add-Type", "Get-PSDrive"))
        self.assertTrue(all(b.split("{", 1)[1].lstrip().startswith("Assert-HostOpsAllowed") for n, b in self.host_functions()
                            if n in ("Send-FinishSignal", "Get-DesktopPath")), "guard first: these cannot be shadowed")
        script = shadow + f" . '{HOST_OPS}'; " + " ".join(
            f"try {{ {c} | Out-Null; 'RAN {c.split()[0]}' }} catch {{ if ($_ -match 'refused') {{ 'ok' }} else {{ 'OTHER ' + $_ }} }};" for c in calls)
        proc = subprocess.run([PWSH, "-NoProfile", "-NonInteractive", "-Command", script], capture_output=True, text=True,
                              timeout=120, env=dict(os.environ, LADDER_TEST_NO_HOST="1"))
        self.assertEqual(proc.stdout.split(), ["ok"] * len(calls), proc.stdout + proc.stderr)


    @unittest.skipUnless(PWSH, "pwsh is not installed")
    def test_real_invoke_wsl_passes_every_argument_through_unchanged(self):
        """The real Invoke-Wsl, with a fake `wsl` first on PATH that only prints its arguments. The distro name does not
        exist, so even a missed fake could start nothing."""
        wanted = ["-d", "NoSuchDistro", "--exec", "docker", "run", "--rm", "--pull=never", "--memory=22g", "--network", "none",
                  "-e", "HF_HUB_OFFLINE=1", "-v", "vol:/root/.cache/huggingface", "-v", "/mnt/c/a:/work:ro", "-w", "/work",
                  "img:tag", "python", "ladder.py", "--budget-bytes", "123"]
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            (tmp / "echo_args.py").write_text("import sys\nfor a in sys.argv[1:]:\n    print(a)\n")
            (tmp / "wsl.cmd").write_text(f'@"{sys.executable}" "%~dp0echo_args.py" %*\n')
            (tmp / "wsl").write_text(f'#!/bin/sh\nexec "{sys.executable}" "$(dirname "$0")/echo_args.py" "$@"\n', newline="\n")
            (tmp / "wsl").chmod(0o755)
            line = next(x for x in SCRIPT.read_text(encoding="utf-8").splitlines() if x.startswith("function Invoke-Docker "))
            argv = " ".join(f"'{a}'" if not a.startswith("-") else a for a in wanted[4:])
            script = (f"$env:PATH = '{tmp}' + [IO.Path]::PathSeparator + $env:PATH; . '{HOST_OPS}'; $Distro = 'NoSuchDistro'; "
                      f"{line}; Invoke-Docker {argv}")
            env = {k: v for k, v in os.environ.items() if k != "LADDER_TEST_NO_HOST"}
            proc = subprocess.run([PWSH, "-NoProfile", "-NonInteractive", "-Command", script], capture_output=True, text=True,
                                  timeout=120, env=env)
        self.assertEqual(proc.stdout.split(), wanted, proc.stdout + proc.stderr)


STUB_OPS = r"""
function Note([string]$Line) { Add-Content -LiteralPath $env:STUB_LOG -Value $Line }
function Invoke-Wsl {
    $a = @($args | ForEach-Object { "$_" })
    $global:LASTEXITCODE = 0
    Note ('wsl ' + ($a -join ' '))
    if ($a -contains '-l') { 'Ubuntu-24.04 Running'; return }
    if ($a -contains '--shutdown') { return }
    if ($env:STUB_NO_IMAGE -and ($a -contains 'image')) { $global:LASTEXITCODE = 1; return }
    if (($a -contains 'run') -and ($a -contains '--name')) {
        foreach ($v in $a) { if ($v -match '^(.*):/out$') { $out = $Matches[1] } }
        if ($out -match '^/mnt/([a-z])/(.*)$') { $out = $Matches[1].ToUpper() + ':/' + $Matches[2] }
        Start-Sleep 3
        if ($env:STUB_COMPLETE) { Set-Content (Join-Path (Join-Path $out 'ladder') 'summary.json') ('{"complete": ' + $env:STUB_COMPLETE + ', "stopped_at": 128}') }
        'container output'
    }
}
function Get-HostAvailableBytes { if ($env:STUB_AVAILABLE) { return [int64]$env:STUB_AVAILABLE }; return [int64]32212254720 }
function Get-DockerDesktopProcessNames { return @('Docker Desktop') }
function Test-DockerDesktopRunning { return $true }
function Stop-DockerDesktop { Note 'stop-docker-desktop'; 'Docker Desktop' }
function Set-KeepAwake([bool]$On) { Note "keep-awake $On" }
function Get-HostMachineFacts([string]$OutRoot) { return [ordered]@{ windows_available_bytes = (Get-HostAvailableBytes) } }
function Get-DesktopPath { Note 'desktop-path'; return $env:STUB_DESKTOP }
function Send-FinishSignal { Note 'beep' }
"""


@unittest.skipUnless(PWSH, "pwsh is not installed")
class RunFlowWithStubsTests(unittest.TestCase):
    """Runs the real -Run flow from a copy of the launcher beside a stub host_ops.ps1: gate, sampler job, markers,
    manifest and banner execute for real; nothing on the machine is touched."""

    def setUp(self):
        d = tempfile.TemporaryDirectory()
        self.addCleanup(d.cleanup)
        self.tmp = Path(d.name).resolve()
        launcher = self.tmp / "launcher"
        launcher.mkdir()
        shutil.copy(SCRIPT, launcher / "run_ladder.ps1")
        (launcher / "host_ops.ps1").write_text(STUB_OPS, encoding="utf-8")
        self.script = launcher / "run_ladder.ps1"
        self.log = self.tmp / "calls.log"
        self.out = self.tmp / "out"

    def launch(self, *args, **stub):
        env = dict(os.environ, LADDER_TEST_NO_HOST="1", STUB_LOG=str(self.log), STUB_DESKTOP=str(self.tmp), STUB_COMPLETE="true")
        env.update(stub)
        return subprocess.run([PWSH, "-NoProfile", "-NonInteractive", "-File", str(self.script), *args, "-OutRoot", str(self.out),
                               "-RepoDir", str(HERE.parent.parent)], capture_output=True, text=True, timeout=180, env=env)

    def calls(self):
        return self.log.read_text(encoding="utf-8-sig").splitlines() if self.log.exists() else []

    def run_dir(self):
        return next(self.out.iterdir())

    def assertSealed(self, bundle):
        """Checked here independently of the script: every file is listed and every hash matches."""
        listed = {}
        for line in (bundle / "manifest.sha256").read_text(encoding="utf-8-sig").splitlines():
            digest, name = line.split("  ", 1)
            listed[name.replace("\\", "/")] = digest
        actual = {p.relative_to(bundle).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                  for p in bundle.rglob("*") if p.is_file() and p.name != "manifest.sha256"}
        self.assertEqual(listed, actual)
        self.assertIn("launcher.log", listed)

    def test_run_flow_reaches_done_and_the_bundle_verifies(self):
        proc = self.launch("-Run")
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        run_dir = self.run_dir()
        self.assertTrue((run_dir / "DONE").exists(), proc.stdout + proc.stderr)
        self.assertGreaterEqual(len((run_dir / "host.csv").read_text(encoding="utf-8-sig").splitlines()), 2)
        self.assertSealed(run_dir)
        note = (self.tmp / "LADDER-FINISHED.txt").read_text(encoding="utf-8-sig")
        self.assertIn("LADDER FINISHED: DONE", note)
        self.assertIn("manifest verified", note)
        calls = self.calls()
        for needed in ("keep-awake True", "stop-docker-desktop", "wsl --shutdown", "keep-awake False", "beep"):
            self.assertIn(needed, calls)

    def test_stopped_and_missing_summary_bundles_verify(self):
        for complete in ("false", ""):
            with self.subTest(complete=complete):
                self.setUp()
                proc = self.launch("-Run", STUB_COMPLETE=complete)
                self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
                self.assertTrue((self.run_dir() / "STOPPED").exists())
                self.assertSealed(self.run_dir())

    def test_first_gate_refusal_changes_nothing_and_verifies(self):
        proc = self.launch("-Run", STUB_AVAILABLE=str(5 * GIB))
        self.assertEqual(proc.returncode, 3, proc.stdout + proc.stderr)
        self.assertTrue((self.run_dir() / "NOT_RUN").exists())
        self.assertSealed(self.run_dir())
        self.assertEqual(self.calls(), [])   # no keep-awake, no process stop, no wsl

    def test_late_refusal_releases_keep_awake_and_verifies(self):
        proc = self.launch("-Run", STUB_NO_IMAGE="1")
        self.assertEqual(proc.returncode, 3, proc.stdout + proc.stderr)
        self.assertTrue((self.run_dir() / "NOT_RUN").exists())
        self.assertTrue((self.run_dir() / "STOP").exists())
        self.assertSealed(self.run_dir())
        self.assertIn("keep-awake False", self.calls())

    def test_verify_mode_passes_a_clean_bundle_and_names_each_difference(self):
        self.assertEqual(self.launch("-Run").returncode, 0)
        bundle = self.run_dir()
        self.assertEqual(self.launch("-Verify", "-RunId", bundle.name).returncode, 0)
        with open(bundle / "launcher.log", "a", encoding="utf-8") as fh:
            fh.write("appended after sealing\n")
        (bundle / "extra.txt").write_text("x")
        (bundle / "host.csv").unlink()
        proc = self.launch("-Verify", "-RunId", bundle.name)
        self.assertEqual(proc.returncode, 5, proc.stdout + proc.stderr)
        for needle in ("changed: launcher.log", "not listed: extra.txt", "missing: host.csv"):
            self.assertIn(needle, proc.stdout)

    def test_verify_without_a_manifest_fails(self):
        (self.out / "r1").mkdir(parents=True)
        self.assertEqual(self.launch("-Verify", "-RunId", "r1").returncode, 5)

    def test_stop_never_changes_a_sealed_bundle(self):
        self.assertEqual(self.launch("-Run").returncode, 0)
        bundle = self.run_dir()
        self.assertEqual(self.launch("-Stop").returncode, 0)
        self.assertSealed(bundle)
        self.assertFalse((bundle / "STOPPED-by-operator").exists())

    def test_stop_still_marks_an_unsealed_run(self):
        (self.out / "r1").mkdir(parents=True)
        self.assertEqual(self.launch("-Stop").returncode, 0)
        self.assertTrue((self.out / "r1" / "STOPPED-by-operator").exists())

    def test_cleanup_and_preflight_go_through_the_stubs(self):
        self.assertEqual(self.launch("-Cleanup").returncode, 0)
        self.assertIn("stop-docker-desktop", self.calls())
        self.assertIn("wsl --shutdown", self.calls())
        proc = self.launch("-Preflight")
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)

    def test_keep_awake_flags_are_unsigned_casts(self):
        text = HOST_OPS.read_text(encoding="utf-8")
        self.assertNotIn("SetThreadExecutionState(0x", text)  # a bare 0x80000001 is Int32 in PowerShell and throws
        for flag in ("[uint32]2147483649", "[uint32]2147483648"):
            self.assertIn(flag, text)


if __name__ == "__main__":
    unittest.main()
