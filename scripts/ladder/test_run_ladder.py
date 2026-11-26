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
import time
import unittest
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "run_ladder.ps1"
HOST_OPS = HERE / "host_ops.ps1"
# LADDER_TEST_PWSH runs the same tests under another host, e.g. Windows PowerShell 5.1 (powershell.exe)
PWSH = shutil.which(os.environ.get("LADDER_TEST_PWSH") or "pwsh")
GIB = 1024 ** 3


def base_env():
    """The environment the PowerShell under test starts with. Windows PowerShell 5.1 started from a process that carries a
    PowerShell 7 module path cannot load its own modules (Get-FileHash was not found), so that variable is dropped for it
    and 5.1 builds its default. Other hosts get the environment unchanged."""
    env = dict(os.environ)
    if PWSH and Path(PWSH).stem.lower() == "powershell":
        for key in [k for k in env if k.lower() == "psmodulepath"]:
            del env[key]
    return env


def said(proc):
    """Everything the script printed, with all whitespace removed: Windows PowerShell wraps error text to the window width,
    mid-word, at a place that depends on the checkout path."""
    return "".join((proc.stdout + proc.stderr).split())


def run(*args, expect=None):
    proc = subprocess.run([PWSH, "-NoProfile", "-NonInteractive", "-File", str(SCRIPT), *args],
                          capture_output=True, text=True, timeout=120, env=dict(base_env(), LADDER_TEST_NO_HOST="1"))
    if expect is not None:
        assert proc.returncode == expect, f"exit {proc.returncode}\n{proc.stdout}\n{proc.stderr}"
    return proc


def plan(avail_gib, mode="-Run", *extra):
    proc = run(mode, "-DryRun", "-FakeAvailableBytes", str(int(avail_gib * GIB)), "-RunId", "t1", *extra)
    try:
        return proc, json.loads(proc.stdout)
    except ValueError:
        raise AssertionError("no JSON plan; exit %s | STDOUT: %s | STDERR: %s" % (proc.returncode, proc.stdout, proc.stderr))


class TestHarnessTests(unittest.TestCase):
    def test_windows_powershell_does_not_inherit_a_module_path_and_pwsh_does(self):
        with mock.patch.dict(os.environ, {"PSModulePath": "C:/pwsh7/Modules"}):
            for host, kept in (("C:/x/powershell.exe", False), ("/usr/bin/pwsh", True), ("C:/x/pwsh.exe", True)):
                with mock.patch.object(sys.modules[__name__], "PWSH", host):
                    keys = {k.lower() for k in base_env()}
                    self.assertEqual("psmodulepath" in keys, kept, host)

    def test_every_powershell_launch_uses_the_prepared_environment(self):
        source = Path(__file__).read_text(encoding="utf-8")
        needle = "os." + "environ"
        users = [line.strip() for line in source.splitlines() if needle in line]
        allowed = [u for u in users if u == "env = dict(" + needle + ")" or u.startswith(("PWSH = ", "with mock.patch.dict("))]
        self.assertEqual(users, allowed)

    def test_said_ignores_wrapping(self):
        proc = subprocess.CompletedProcess([], 1, "no limit has be\nen ru", "led here")
        self.assertIn("nolimithasbeenruled", said(proc))


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
        self.assertIn("nolimithasbeenruled", said(refused))
        for bad in ("NaN", "Infinity", "-5"):
            with self.subTest(limit=bad):
                proc = run("-Run", "-DryRun", "-FakeAvailableBytes", str(22 * GIB), "-PagingRule", "v2",
                           "-PagingMaxTrainRefaultsPerSecond", bad)
                self.assertNotEqual(proc.returncode, 0)

    def test_evidence_mode_is_off_unless_asked_and_changes_one_argument(self):
        _, off = plan(22)
        _, on = plan(22, "-Run", "-EvidenceMode")
        self.assertNotIn("evidence_mode", off)
        self.assertNotIn("--evidence-mode", off["docker_argv"])
        self.assertIn("not a qualification", on["evidence_mode"])
        extra = list(on["docker_argv"])
        extra.remove("--evidence-mode")
        strip = lambda argv: [a for a in argv if not a.startswith("--host-available-start")]  # noqa: E731
        self.assertEqual(strip(extra), strip(off["docker_argv"]))
        self.assertEqual({k for k in on} - {k for k in off}, {"evidence_mode"})
        refused = run("-Run", "-DryRun", "-FakeAvailableBytes", str(22 * GIB), "-EvidenceMode", "-PagingRule", "v2",
                      "-PagingMaxTrainRefaultsPerSecond", "1")
        self.assertNotEqual(refused.returncode, 0)
        self.assertIn("v1pagingfailureonly", said(refused))

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
        self.assertNotIn("--host-epoch-at-start", argv)   # rung readings are chosen by file position, not by clock
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
        self.assertIn("onlyallowedwith-DryRun", said(proc))

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
                              timeout=120, env=dict(base_env(), LADDER_TEST_NO_HOST="1"))
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
            env = {k: v for k, v in base_env().items() if k != "LADDER_TEST_NO_HOST"}
            proc = subprocess.run([PWSH, "-NoProfile", "-NonInteractive", "-Command", script], capture_output=True, text=True,
                                  timeout=120, env=env)
        self.assertEqual(proc.stdout.split(), wanted, proc.stdout + proc.stderr)


    @unittest.skipUnless(PWSH, "pwsh is not installed")
    def test_real_container_job_reports_the_native_exit_code_and_output(self):
        """The launcher's own container job block with the real host_ops.ps1 and a fake `wsl` on PATH that prints and
        exits 7. The distro name does not exist, so even a missed fake could start nothing."""
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            (tmp / "echo_args.py").write_text("import sys\nfor a in sys.argv[1:]:\n    print(a)\nsys.exit(7)\n")
            (tmp / "wsl.cmd").write_text(f'@"{sys.executable}" "%~dp0echo_args.py" %*\n')
            (tmp / "wsl").write_text(f'#!/bin/sh\nexec "{sys.executable}" "$(dirname "$0")/echo_args.py" "$@"\n', newline="\n")
            (tmp / "wsl").chmod(0o755)
            script = (
                "$env:PATH = $env:FAKE_DIR + [IO.Path]::PathSeparator + $env:PATH; "
                "$ast = [Management.Automation.Language.Parser]::ParseFile($env:LADDER_SCRIPT, [ref]$null, [ref]$null); "
                "$block = $ast.Find({ param($n) $n -is [Management.Automation.Language.ScriptBlockExpressionAst] -and "
                "$n.ScriptBlock.ParamBlock -and $n.ScriptBlock.ParamBlock.Extent.Text -match 'containerLog' }, $true); "
                "$code = [scriptblock]::Create($block.ScriptBlock.Extent.Text.Trim().TrimStart('{').TrimEnd('}')); "
                "$argv = @('run', '--rm', '--pull=never', '--memory=22g'); $log = Join-Path $env:FAKE_DIR 'container.log'; "
                "$j = Start-Job -ArgumentList $env:HOST_OPS_PATH, 'NoSuchDistro', $argv, $log -ScriptBlock $code; "
                "Wait-Job $j | Out-Null; 'state ' + $j.State; 'exit ' + (@(Receive-Job $j) | Where-Object { $_ -is [int] } | Select-Object -Last 1); "
                "Get-Content $log")
            env = {k: v for k, v in base_env().items() if k != "LADDER_TEST_NO_HOST"}
            env.update(FAKE_DIR=str(tmp), LADDER_SCRIPT=str(SCRIPT), HOST_OPS_PATH=str(HOST_OPS))
            proc = subprocess.run([PWSH, "-NoProfile", "-NonInteractive", "-Command", script], capture_output=True, text=True,
                                  timeout=120, env=env)
        out = proc.stdout.split()
        self.assertEqual(out[:4], ["state", "Completed", "exit", "7"], proc.stdout + proc.stderr)
        self.assertEqual(out[4:], ["-d", "NoSuchDistro", "--exec", "docker", "run", "--rm", "--pull=never", "--memory=22g"])


STUB_OPS = r"""
function Note([string]$Line) {
    for ($i = 0; $i -lt 50; $i++) { try { Add-Content -LiteralPath $env:STUB_LOG -Value $Line -ErrorAction Stop; return } catch { Start-Sleep -Milliseconds 40 } }
}
function Invoke-Wsl {
    $a = @($args | ForEach-Object { "$_" })
    $global:LASTEXITCODE = 0
    Note ('wsl ' + ($a -join ' '))
    if ($a -contains '--shutdown') { return }
    if ($env:STUB_THROW_ON -and ($a -contains $env:STUB_THROW_ON)) { throw 'stub failure' }
    $op = if ($a -contains '-l') { '-l' } elseif ($a -contains 'ps') { 'ps' } elseif ($a -contains 'kill') { 'kill' }
    if ($op -and $env:STUB_HANG -eq $op) {
        if ($env:STUB_NATIVE) { & $env:STUB_NATIVE -c 'import time; time.sleep(150)' } else { Start-Sleep 150 }
    }
    if ($op -and $env:STUB_FAIL -eq $op) { $global:LASTEXITCODE = 1; return }
    if ($a -contains 'ps') {
        $seen = $env:STUB_LOG + '.ps'
        Add-Content -LiteralPath $seen 'p'
        if ($env:STUB_LEFTOVER -eq 'stays' -or ($env:STUB_LEFTOVER -eq 'once' -and @(Get-Content -LiteralPath $seen).Count -eq 1)) { 'abc123' }
        return
    }
    if ($a -contains 'kill') { Set-Content -LiteralPath ($env:STUB_LOG + '.killed') 'x'; return }
    if ($a -contains '-l') { 'Ubuntu-24.04 Running'; return }
    if ($env:STUB_NO_IMAGE -and ($a -contains 'image')) { $global:LASTEXITCODE = 1; return }
    if (($a -contains 'run') -and ($a -contains '--name')) {
        foreach ($v in $a) { if ($v -match '^(.*):/out$') { $out = $Matches[1] } }
        if ($out -match '^/mnt/([a-z])/(.*)$') { $out = $Matches[1].ToUpper() + ':/' + $Matches[2] }
        $summary = Join-Path (Join-Path $out 'ladder') 'summary.json'
        $text = '{"complete": ' + $env:STUB_COMPLETE + ', "stopped_at": 128}'
        if ($env:STUB_SUMMARY_FIRST) { Set-Content $summary $text }
        if ($env:STUB_OPERATOR_STOP) { Start-Sleep 2; Set-Content (Join-Path $out 'STOP') 'operator stop' }
        $seconds = if ($env:STUB_RUN_SECONDS) { [int]$env:STUB_RUN_SECONDS } else { 3 }
        $until = (Get-Date).AddSeconds($seconds)
        while ((Get-Date) -lt $until) {
            Start-Sleep -Milliseconds 200
            if (-not $env:STUB_IGNORE_KILL -and (Test-Path -LiteralPath ($env:STUB_LOG + '.killed'))) { $global:LASTEXITCODE = 137; return }
        }
        if ($env:STUB_COMPLETE) { Set-Content $summary $text }
        if ($env:STUB_SUMMARY_RAW) { Set-Content $summary $env:STUB_SUMMARY_RAW }
        if ($env:STUB_APPEND_CSV) { Add-Content (Join-Path $out 'host.csv') $env:STUB_APPEND_CSV }
        if ($env:STUB_HOLD_FILE) {
            $held = Join-Path $out 'held.txt'
            Start-Process -FilePath (Get-Process -Id $PID).Path -WindowStyle Hidden -ArgumentList '-NoProfile', '-Command',
                "`$f = [IO.File]::Open('$held', 'Create', 'Write', 'None'); Start-Sleep 25; `$f.Dispose()"
            while (-not (Test-Path -LiteralPath $held)) { Start-Sleep -Milliseconds 100 }
        }
        if ($env:STUB_END_SAMPLER) {
            Set-Content -LiteralPath ($env:STUB_LOG + '.endsampler') 'x'
            while (-not (Test-Path -LiteralPath ($env:STUB_LOG + '.sampler-hit'))) { Start-Sleep -Milliseconds 50 }
        }
        if ($env:STUB_EXIT) { $global:LASTEXITCODE = [int]$env:STUB_EXIT }
        'container output'
    }
}
function Get-HostAvailableBytes {
    if ($env:STUB_END_SAMPLER -and (Test-Path -LiteralPath ($env:STUB_LOG + '.endsampler'))) {
        Set-Content -LiteralPath ($env:STUB_LOG + '.sampler-hit') 'x'
        if ($env:STUB_END_SAMPLER -eq 'throw') { throw 'stub: failed at completion' }
        Start-Sleep 60
    }
    if ($env:STUB_AVAILABLE) { return [int64]$env:STUB_AVAILABLE }
    if ($env:STUB_BAD) {
        # reads 1 and 2 are the launcher's gate; the sampler's start at 3
        $counter = $env:STUB_LOG + '.reads'
        Add-Content -LiteralPath $counter 'r'
        if (@(Get-Content -LiteralPath $counter).Count -ge [int]$env:STUB_BAD_FROM) {
            if ($env:STUB_BAD -eq 'low') { return [int64]1073741824 }
            if ($env:STUB_BAD -eq 'zero') { return [int64]0 }
            if ($env:STUB_BAD -eq 'throw') { throw 'stub: reading failed' }
            if ($env:STUB_BAD -eq 'hang') { Start-Sleep 45 }
            if ($env:STUB_BAD -eq 'error') { Write-Error 'stub: non-terminating error' }
        }
    }
    return [int64]32212254720
}
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
        env = dict(base_env(), LADDER_TEST_NO_HOST="1", STUB_LOG=str(self.log), STUB_DESKTOP=str(self.tmp), STUB_COMPLETE="true")
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

    # ---- finding 3: nothing launches without a healthy sampler, and a launcher-seen failure outranks the child's success

    def launched(self):
        return [c for c in self.calls() if c.startswith("wsl ") and " run " in c and "--name" in c]

    def assertNotRun(self, proc, needle):
        self.assertEqual(proc.returncode, 3, proc.stdout + proc.stderr)
        text = (self.run_dir() / "NOT_RUN").read_text(encoding="utf-8-sig")
        self.assertIn(needle, text)
        self.assertEqual(self.launched(), [], "the container must not start")
        self.assertIn("keep-awake False", self.calls())
        self.assertSealed(self.run_dir())

    def assertStopped(self, proc, needle, code=0):
        self.assertEqual(proc.returncode, code, proc.stdout + proc.stderr)
        self.assertFalse((self.run_dir() / "DONE").exists(), proc.stdout)
        text = (self.run_dir() / "STOPPED").read_text(encoding="utf-8-sig")
        self.assertIn(needle, text, proc.stdout)
        self.assertIn("keep-awake False", self.calls())
        self.assertSealed(self.run_dir())
        return text

    def test_a_floor_breach_before_launch_prevents_launch(self):
        self.assertNotRun(self.launch("-Run", STUB_BAD="low", STUB_BAD_FROM="3"), "floor")

    def test_a_breach_between_the_first_and_second_reading_prevents_launch(self):
        self.assertNotRun(self.launch("-Run", STUB_BAD="low", STUB_BAD_FROM="4"), "floor")

    def test_a_failing_sampler_prevents_launch(self):
        for bad in ("throw", "zero", "error"):
            with self.subTest(bad=bad):
                self.setUp()
                self.assertNotRun(self.launch("-Run", STUB_BAD=bad, STUB_BAD_FROM="3"), "sampler stopped")

    def test_a_sampler_with_no_second_reading_prevents_launch(self):
        self.assertNotRun(self.launch("-Run", STUB_BAD="hang", STUB_BAD_FROM="4"), "not")

    def test_breach_during_the_run_kills_the_container_and_beats_a_successful_summary(self):
        proc = self.launch("-Run", STUB_BAD="low", STUB_BAD_FROM="9", STUB_RUN_SECONDS="40", STUB_SUMMARY_FIRST="1")
        text = self.assertStopped(proc, "host floor breach")
        self.assertIn("container exited 137", text)
        self.assertTrue(any(" kill " in c for c in self.calls()))

    def test_breach_beats_a_child_that_finishes_cleanly_anyway(self):
        proc = self.launch("-Run", STUB_BAD="low", STUB_BAD_FROM="9", STUB_RUN_SECONDS="14", STUB_IGNORE_KILL="1")
        text = self.assertStopped(proc, "host floor breach")
        self.assertNotIn("container exited", text)

    def test_sampler_failure_during_the_run_aborts_it(self):
        proc = self.launch("-Run", STUB_BAD="throw", STUB_BAD_FROM="9", STUB_RUN_SECONDS="40")
        text = self.assertStopped(proc, "run aborted: the host sampler stopped")
        self.assertIn("container exited 137", text)   # the launcher itself killed it; no watchdog kill happens here
        self.assertTrue(any(" kill " in c for c in self.calls()))

    def test_stale_readings_during_the_run_abort_it(self):
        proc = self.launch("-Run", STUB_BAD="hang", STUB_BAD_FROM="9", STUB_RUN_SECONDS="40")
        self.assertStopped(proc, "run aborted: no host reading for")

    def test_nonzero_container_exit_beats_a_successful_summary(self):
        self.assertStopped(self.launch("-Run", STUB_EXIT="1"), "container exited 1")

    def test_operator_stop_during_the_run_is_stopped_not_done(self):
        proc = self.launch("-Run", STUB_OPERATOR_STOP="1", STUB_RUN_SECONDS="40", STUB_SUMMARY_FIRST="1")
        self.assertStopped(proc, "operator stop")

    def test_launcher_error_still_seals_a_stopped_bundle(self):
        proc = self.launch("-Run", STUB_THROW_ON="image")
        self.assertStopped(proc, "launcher error: stub failure", code=4)

    # ---- Codex HOLD on 30c88d9: completion-boundary sampler failure, native cleanup failures, calls that never return

    def test_sampler_failure_at_container_completion_is_not_done(self):
        self.assertStopped(self.launch("-Run", STUB_END_SAMPLER="throw"), "the host sampler did not end cleanly (job Failed)")

    def test_a_sampler_that_hangs_as_the_run_ends_is_not_done(self):
        # every reading so far is fresh; only the check after telling it to stop catches this
        self.assertStopped(self.launch("-Run", STUB_END_SAMPLER="hang"), "the host sampler did not end cleanly")

    def test_failed_native_cleanup_commands_are_recorded_and_not_done(self):
        cases = {"ps": "could not list containers (exit 1)", "-l": "could not query WSL (exit 1)"}
        for op, needle in cases.items():
            with self.subTest(op=op):
                self.setUp()
                self.assertStopped(self.launch("-Run", STUB_FAIL=op), needle)

    def test_a_leftover_container_that_cannot_be_killed_is_not_done(self):
        text = self.assertStopped(self.launch("-Run", STUB_LEFTOVER="stays", STUB_FAIL="kill"), "docker kill exit 1")
        self.assertIn("could not confirm that the container stopped", text)

    def test_a_leftover_container_that_survives_a_successful_kill_is_not_done(self):
        self.assertStopped(self.launch("-Run", STUB_LEFTOVER="stays"), "could not confirm that the container stopped")

    def test_a_leftover_container_that_is_killed_and_confirmed_gone_is_done(self):
        proc = self.launch("-Run", STUB_LEFTOVER="once")
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertTrue((self.run_dir() / "DONE").exists(), proc.stdout)
        self.assertTrue(any(c.endswith("kill abc123") for c in self.calls()))

    @unittest.skipUnless(sys.platform == "win32", "only Windows refuses to read a file another process holds exclusively")
    def test_a_file_still_held_open_gives_an_unverified_bundle_not_a_crash(self):
        proc = self.launch("-Run", STUB_HOLD_FILE="1")
        self.assertEqual(proc.returncode, 5, proc.stdout + proc.stderr)
        self.assertIn("MANIFEST DOES NOT VERIFY: not listed: held.txt", proc.stdout)
        self.assertTrue((self.run_dir() / "manifest.sha256").exists())
        self.assertIn("keep-awake False", self.calls())
        time.sleep(30)   # let the holder exit before the temporary folder is removed

    def bounded(self, needle, **stub):
        began = time.monotonic()
        proc = self.launch("-Run", **stub)
        self.assertLess(time.monotonic() - began, 120, "a call that never returns must not hold the launcher")
        return self.assertStopped(proc, needle)

    def test_a_cleanup_command_that_never_returns_is_bounded(self):
        for op in ("ps", "-l"):
            with self.subTest(op=op):
                self.setUp()
                self.bounded("timed out after 20 s", STUB_HANG=op)

    def test_a_native_command_that_never_returns_is_bounded(self):
        self.bounded("timed out after 20 s", STUB_HANG="ps", STUB_NATIVE=sys.executable)

    def test_an_abort_kill_that_never_returns_is_bounded(self):
        began = time.monotonic()
        proc = self.launch("-Run", STUB_BAD="throw", STUB_BAD_FROM="9", STUB_RUN_SECONDS="150", STUB_HANG="kill")
        self.assertLess(time.monotonic() - began, 145)
        text = self.assertStopped(proc, "the abort kill timed out")
        self.assertIn("did not end within 60 s", text)
        self.assertIn("container exit code unknown", text)

    def test_evidence_mode_says_so_everywhere_and_never_turns_a_failure_into_done(self):
        notice = "EVIDENCE MODE: a paging-only (v1) failure does not stop the ladder; not a qualification"
        proc = self.launch("-Run", "-EvidenceMode", STUB_COMPLETE="false", STUB_EXIT="1")
        text = self.assertStopped(proc, "ladder not complete")
        self.assertIn(notice, text)
        self.assertIn(notice, proc.stdout)
        self.assertIn(notice, (self.run_dir() / "launcher.log").read_text(encoding="utf-8-sig"))
        self.assertIn(notice, (self.tmp / "LADDER-FINISHED.txt").read_text(encoding="utf-8-sig"))
        [line] = self.launched()
        self.assertIn("--evidence-mode", line)

    def test_evidence_mode_keeps_the_memory_host_guard(self):
        proc = self.launch("-Run", "-EvidenceMode", STUB_BAD="low", STUB_BAD_FROM="9", STUB_RUN_SECONDS="40")
        self.assertStopped(proc, "host floor breach")

    def test_without_evidence_mode_nothing_mentions_it(self):
        proc = self.launch("-Run")
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        everything = proc.stdout + "".join(p.read_text(encoding="utf-8-sig", errors="replace")
                                          for p in self.run_dir().rglob("*") if p.is_file())
        self.assertNotIn("EVIDENCE MODE", everything)
        self.assertNotIn("--evidence-mode", everything)
        self.assertEqual((self.run_dir() / "DONE").read_text(encoding="utf-8-sig").splitlines()[1:], ["all rungs PASS"])

    def test_coverage_is_checked_after_the_run_too(self):
        # a bad line written as the container ends; this wording comes only from the after-run coverage check
        self.assertStopped(self.launch("-Run", STUB_APPEND_CSV="garbage"), "host readings: 1 unreadable line(s)")

    def test_a_summary_that_does_not_say_complete_is_stopped(self):
        for raw in (" ", "{}", "not json", '{"complete": "yes"}', "null"):
            with self.subTest(raw=raw):
                self.setUp()
                self.assertStopped(self.launch("-Run", STUB_SUMMARY_RAW=raw), "ladder")

    def test_the_container_receives_the_planned_arguments(self):
        self.assertEqual(self.launch("-Run").returncode, 0)
        [line] = self.launched()
        for needed in ("--rm --pull=never --name ladder-", "--memory=22g --memory-swap=22g --network none",
                       ":/work:ro", ":/out -w /work fotc-dev-sft-r1:stage2 python ladder.py run --model pinned",
                       "--container-cap fixed", "--paging-rule v1"):
            self.assertIn(needed, line)

    def coverage(self, rows, start=100, end=130):
        csv = self.tmp / "c.csv"
        csv.write_text("epoch,bytes\n" + "".join(r + "\n" for r in rows), encoding="utf-8")
        script = (
            "$ast = [Management.Automation.Language.Parser]::ParseFile($env:LADDER_SCRIPT, [ref]$null, [ref]$null); "
            "foreach ($f in $ast.FindAll({ param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] "
            "-and $n.Name -in 'Read-Samples', 'Get-CoverageProblems' }, $true)) { Invoke-Expression $f.Extent.Text }; "
            f"Get-CoverageProblems $env:LADDER_CSV {start} {end} 1000 10")
        proc = subprocess.run([PWSH, "-NoProfile", "-NonInteractive", "-Command", script], capture_output=True, text=True, timeout=120,
                              env=dict(base_env(), LADDER_SCRIPT=str(SCRIPT), LADDER_CSV=str(csv), LADDER_TEST_NO_HOST="1"))
        self.assertEqual(proc.returncode, 0, proc.stderr)
        return proc.stdout

    def test_coverage_rules(self):
        good = ["99,5000", "105,5000", "114,5000", "123,5000", "131,5000"]
        self.assertEqual(self.coverage(good).strip(), "")
        self.assertEqual(self.coverage(good + ["200,5000"]).strip(), "")   # a gap after the end is not the run's
        cases = {
            "none at or before launch": ["101,5000", "110,5000", "120,5000", "130,5000"],
            "gap of 11 s": ["99,5000", "110,5000", "120,5000", "130,5000"],
            "none in the last 11 s": ["99,5000", "109,5000", "119,5000"],
            "below the 1000 floor": ["99,5000", "105,999", "114,5000", "123,5000", "131,5000"],
            "unreadable line": ["99,5000", "105,", "109,5000", "119,5000", "129,5000"],
        }
        for needle, rows in cases.items():
            with self.subTest(needle=needle):
                self.assertIn(needle, self.coverage(rows))
        self.assertIn("below the 1000 floor", self.coverage(["99,999", "108,5000", "117,5000", "126,5000"]))

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
