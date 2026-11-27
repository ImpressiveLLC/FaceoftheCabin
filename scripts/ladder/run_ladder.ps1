<#
.SYNOPSIS
  Detached launcher for the synthetic CPU length ladder (docs/ai-assistant/training/ladder.py).

.DESCRIPTION
  Run from a plain terminal. Needs no Claude session: every record goes to disk under -OutRoot\<UTC timestamp>\.
  Invented rows only. No real data, no weights export, no network inside the container.

  -Preflight   Read-only. Prints and saves host facts and the start gate. Starts nothing.
  -Run         Checks the gate, quits Docker Desktop if running, shuts WSL down, waits for the Docker daemon inside the
               Ubuntu WSL distro (where the Stage 2 image and model cache live), re-checks the gate, checks the model cache is
               already present (no download), then runs the ladder in a no-swap container while a sampler logs Windows
               available memory and a watchdog kills the container if it stays under the 4 GiB floor.
  -Stop        Kills a running ladder container and tells the sampler to stop.
  -Cleanup     Return the machine to normal: kills any ladder container, runs wsl --shutdown, quits Docker Desktop. Touches
               nothing else and deletes nothing (the records stay). Safe to run any time, also after a failed run.
  -Verify      With -RunId: read-only check of that run's manifest.sha256 against the files in its folder (exit 0 or 5).
  -EvidenceMode  Off unless given; paging rule v1 only. A rung whose only failure is the v1 paging rule stays FAILURE
               but the ladder continues. Every other failure stops it as usual. The result is never DONE for such a run and
               is not a qualification.
  -GradientCheckpointing  Off unless given. Configuration C-gc: C (-CeilingMetric anon_shmem -ContainerCap budget,
               paging rule v1) plus gradient checkpointing in the rung. A rung that does not show it active is FAILURE.
               train_sft.py is unchanged, so nothing here says anything about a trainer without checkpointing.
  -DryRun      With -Run or -Preflight: prints the plan as JSON (gate, budget, the exact docker argv) and executes nothing.
               -FakeAvailableBytes supplies the Windows-available figure for tests; it is refused without -DryRun.

  Policy (Codex 7096272556, Cowork 7091727691, 7123997237): at least 6 GiB Windows available to start; the pinned 3B run
  needs 14.21 GiB (Stage 2 peak) / 0.9 plus the 4 GiB floor, so about 19.8 GiB at the start; stop below 4 GiB; container 22 GiB with
  no swap; budget fixed once before the run as min(22 GiB, available at start minus the floor); never recomputed.
  The 90 percent ceiling is on the effective budget: the lower of that budget and the container memory limit. With
  -ContainerCap budget the limit is the budget less 2 GiB, so the start needs 2 GiB more (about 21.8 GiB).
  A refused or failed start writes a NOT_RUN or STOPPED marker with the reason and never counts as a pass.
  The container is not launched until the host sampler has two readings, the latest fresh and none under the floor
  (30 s to get there, else NOT_RUN). During the run a floor breach, a stopped sampler, a reading older than 10 s or an
  operator stop kills the container. DONE needs every rung PASS and none of: breach, abnormal or unknown container exit,
  gap in the host readings, operator stop, launcher or cleanup error. Otherwise the run is STOPPED with every reason.
  Every abort and cleanup WSL/Docker command is limited to 20 s and its native exit code is checked; a failed or timed-out
  one leaves the container state unknown, which is recorded, and the other cleanup steps still run.
  The 10 s, 30 s and 20 s limits were chosen by Code with finding 3's repair; they are not measured values.
  The manifest is written after the last write into the run folder and verified at once; a bundle that does not verify
  exits 5. Everything that touches the machine is in host_ops.ps1 beside this script.
#>
[CmdletBinding()]
param(
    [switch]$Preflight,
    [switch]$Run,
    [switch]$Stop,
    [switch]$Cleanup,
    [switch]$Verify,
    [switch]$DryRun,
    [switch]$EvidenceMode,
    [switch]$GradientCheckpointing,
    [string]$OutRoot = 'C:\ladder-run',
    [string]$Distro = 'Ubuntu-24.04',
    [ValidateSet('original', 'anon_shmem')][string]$CeilingMetric = 'original',
    [ValidateSet('fixed', 'budget')][string]$ContainerCap = 'fixed',
    [ValidateSet('v1', 'v2')][string]$PagingRule = 'v1',
    [double]$PagingMaxTrainRefaultsPerSecond = -1,
    [string]$Image = 'fotc-dev-sft-r1:stage2',
    [string]$ModelVolume = 'fotc-hf-cache',
    [string]$RepoDir = '',
    [string]$RunId = '',
    [string]$DesktopDir = '',
    [int64]$FakeAvailableBytes = 0
)

$ErrorActionPreference = 'Stop'
$GiB = [int64]1073741824
$StartMinBytes = 6 * $GiB
$FloorBytes = 4 * $GiB
$Stage2PeakBytes = [int64][math]::Round(14.21 * $GiB)
# The 90 percent ceiling is on the budget (available minus the floor), so the budget itself must hold the Stage 2 peak / 0.9.
$NeedAtStartBytes = [int64][math]::Ceiling($Stage2PeakBytes / 0.9) + $FloorBytes
$ContainerLimitBytes = 22 * $GiB
$PollSeconds = 1   # the host fell 14 GiB in 5 s in run 2; a 5 s poll with two samples was too slow
$MaxSampleGapSeconds = 10   # an older or wider gap than this is no coverage (chosen by Code, not measured)
$SamplerReadySeconds = 30   # time the sampler has to produce two good readings before launch
$AbortWaitSeconds = 60      # after an abort, how long to keep killing before giving up on the container client
$CommandTimeoutSeconds = 20 # longest any abort or cleanup WSL/Docker command may take
$JobStopSeconds = 10        # longest to wait for an abandoned background job to stop
$OverheadMarginBytes = 2 * $GiB
# In budget cap mode the container limit (budget less the margin) is the ceiling denominator, so the start needs the margin too.
if ($ContainerCap -eq 'budget') { $NeedAtStartBytes += $OverheadMarginBytes }
$ModelRevision = '006f5dcd1393c3add266de40994ba96225e9689d'
$ModelCacheDir = 'models--unsloth--Llama-3.2-3B-Instruct'

$modes = @($Preflight, $Run, $Stop, $Cleanup, $Verify | Where-Object { $_ }).Count
if ($modes -ne 1) { Write-Error 'Choose exactly one of -Preflight, -Run, -Stop, -Cleanup, -Verify.'; exit 2 }
if ($PagingRule -eq 'v2' -and ([double]::IsNaN($PagingMaxTrainRefaultsPerSecond) -or [double]::IsInfinity($PagingMaxTrainRefaultsPerSecond) -or $PagingMaxTrainRefaultsPerSecond -lt 0)) { [Console]::Error.WriteLine('-PagingRule v2 needs -PagingMaxTrainRefaultsPerSecond (no limit has been ruled, so there is no default).'); exit 2 }
if ($EvidenceMode -and $PagingRule -ne 'v1') { [Console]::Error.WriteLine('-EvidenceMode continues past a v1 paging failure only; use it with -PagingRule v1.'); exit 2 }
$EvidenceNotice = 'EVIDENCE MODE: a paging-only (v1) failure does not stop the ladder; not a qualification'
if ($GradientCheckpointing -and -not ($CeilingMetric -eq 'anon_shmem' -and $ContainerCap -eq 'budget' -and $PagingRule -eq 'v1')) { [Console]::Error.WriteLine('-GradientCheckpointing is configuration C plus one change: use it with -CeilingMetric anon_shmem -ContainerCap budget and -PagingRule v1.'); exit 2 }
$CheckpointingNotice = 'C-gc: gradient checkpointing requested; a new labelled configuration, not a qualification of the current trainer'
# Printed with the result of a run; empty when neither switch is given.
$Notices = @($(if ($GradientCheckpointing) { $CheckpointingNotice }); $(if ($EvidenceMode) { $EvidenceNotice }))
if ($FakeAvailableBytes -gt 0 -and -not $DryRun) { Write-Error '-FakeAvailableBytes is only allowed with -DryRun.'; exit 2 }
if (-not $RepoDir) { $RepoDir = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path }
$TrainingDir = Join-Path (Join-Path (Join-Path $RepoDir 'docs') 'ai-assistant') 'training'
$HostOps = Join-Path $PSScriptRoot 'host_ops.ps1'
. $HostOps

function Get-AvailableBytes {
    if ($DryRun -and $FakeAvailableBytes -gt 0) { return $FakeAvailableBytes }
    return (Get-HostAvailableBytes)
}

function Get-GateReasons([int64]$Available) {
    $reasons = @()
    if ($Available -lt $StartMinBytes) { $reasons += "Windows available $Available bytes is below the $StartMinBytes start minimum (6 GiB)" }
    if ($Available -lt $NeedAtStartBytes) { $reasons += "Windows available $Available bytes is below the $NeedAtStartBytes needed to start the pinned 3B (Stage 2 peak 14.21 GiB / 0.9 for the 90 percent ceiling, plus the 4 GiB floor, plus 2 GiB in budget cap mode)" }
    return $reasons
}

function Get-Budget([int64]$Available) {
    return [int64][math]::Min($ContainerLimitBytes, $Available - $FloorBytes)
}

function ConvertTo-WslPath([string]$Path) {
    $p = $Path.Replace([string][char]92, '/')
    if ($p -match '^([A-Za-z]):(.*)$') { return '/mnt/' + $Matches[1].ToLower() + $Matches[2] }
    return $p
}

# The one ceiling denominator: the lower of the fixed budget and the container memory limit.
function Get-EffectiveBudget([int64]$Budget) { return [int64][math]::Min($Budget, (Get-ContainerLimitBytes $Budget)) }
function Get-CeilingBytes([int64]$Budget) { $e = Get-EffectiveBudget $Budget; return [int64](($e * 9 - (($e * 9) % 10)) / 10) }

# Docker runs INSIDE the Ubuntu WSL distro (where the Stage 2 image and the model cache live), not Docker Desktop.
function Invoke-Docker { Invoke-Wsl -d $Distro --exec docker @args }

function Test-DistroRunning {
    $state = (Invoke-Wsl -l -v 2>&1 | Out-String) -replace [string][char]0, ''
    return [bool]($state -match "$Distro +Running")
}

function Get-ContainerLimitBytes([int64]$Budget) {
    # fixed = the first two runs (22 GiB). budget = the fixed budget less a VM and host overhead margin, so the cgroup reclaims
    # page cache inside the container instead of leaving it to count against Windows.
    if ($ContainerCap -eq 'budget') { return [int64]($Budget - $OverheadMarginBytes) }
    return $ContainerLimitBytes
}

function Get-DockerArgv([string]$Name, [string]$OutDir, [int64]$Budget, [int64]$AvailableAtStart) {
    $limit = Get-ContainerLimitBytes $Budget
    $mem = if ($ContainerCap -eq 'budget') { "$limit" } else { '22g' }
    return @(
        'run', '--rm', '--pull=never', '--name', $Name,
        "--memory=$mem", "--memory-swap=$mem", '--network', 'none',
        '-e', 'HF_HUB_OFFLINE=1', '-e', 'HF_HOME=/root/.cache/huggingface', '-e', 'PYTHONDONTWRITEBYTECODE=1',
        '-v', "${ModelVolume}:/root/.cache/huggingface",
        '-v', ((ConvertTo-WslPath $TrainingDir) + ':/work:ro'),
        '-v', ((ConvertTo-WslPath $OutDir) + ':/out'),
        '-w', '/work', $Image,
        'python', 'ladder.py', 'run', '--model', 'pinned',
        '--budget-bytes', "$Budget", '--out', '/out/ladder',
        '--ceiling-metric', $CeilingMetric,
        '--container-cap', $ContainerCap, '--container-memory-bytes', "$limit",
        '--paging-rule', $PagingRule,
        '--host-available-start', "$AvailableAtStart", '--host-csv', '/out/host.csv'
    ) + $(if ($EvidenceMode) { @('--evidence-mode') } else { @() }) + $(if ($GradientCheckpointing) { @('--gradient-checkpointing') } else { @() }) + $(if ($PagingRule -eq 'v2') { @('--paging-max-train-refaults-per-s', "$PagingMaxTrainRefaultsPerSecond") } else { @() })
}

function New-RunId { if ($RunId) { return $RunId } else { return (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ') } }

function Get-HostFacts {
    $facts = Get-HostMachineFacts $OutRoot
    $facts.wsl_state = (Invoke-Wsl -l -v 2>&1 | Out-String) -replace "`0", ''
    $facts.docker_daemon = $false; $facts.image_present = $false; $facts.model_volume_present = $false
    $facts.distro_running = Test-DistroRunning
    if ($facts.distro_running) {   # read-only: never start the distro just to look
        Invoke-Docker info --format '{{.ServerVersion}}' *> $null
        if ($LASTEXITCODE -eq 0) {
            $facts.docker_daemon = $true
            Invoke-Docker image inspect $Image *> $null; $facts.image_present = ($LASTEXITCODE -eq 0)
            Invoke-Docker volume inspect $ModelVolume *> $null; $facts.model_volume_present = ($LASTEXITCODE -eq 0)
        }
    }
    $facts.docker_desktop_running = Test-DockerDesktopRunning
    return $facts
}

# The bundle's manifest: one "sha256  relative path" line per file except the manifest itself.
function Write-Manifest([string]$Dir) {
    $root = (Resolve-Path -LiteralPath $Dir).Path
    $lines = Get-ChildItem -LiteralPath $root -Recurse -File -Force | Where-Object { $_.Name -ne 'manifest.sha256' } | ForEach-Object {
        # a file still held open cannot be hashed: it is left out, and verification then reports it as not listed
        try { "{0}  {1}" -f (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256 -ErrorAction Stop).Hash.ToLower(), $_.FullName.Substring($root.Length + 1) }
        catch { Write-Host "cannot hash a file in the run folder: $_" }
    }
    Set-Content -LiteralPath (Join-Path $root 'manifest.sha256') $lines
}

# Read-only. Returns every difference between the manifest and the folder; nothing returned means it verifies.
function Test-Manifest([string]$Dir) {
    $manifest = Join-Path $Dir 'manifest.sha256'
    if (-not (Test-Path -LiteralPath $manifest)) { return 'no manifest.sha256' }
    $root = (Resolve-Path -LiteralPath $Dir).Path
    $listed = @{}
    foreach ($line in @(Get-Content -LiteralPath $manifest)) {
        if ($line -notmatch '^([0-9a-f]{64})  (.+)$') { "unreadable manifest line: $line"; continue }
        $hash, $name = $Matches[1], $Matches[2]
        $listed[$name] = $true
        $file = Join-Path $root $name
        if (-not (Test-Path -LiteralPath $file)) { "missing: $name" }
        elseif ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLower() -ne $hash) { "changed: $name" }
    }
    if ($listed.Count -eq 0) { 'manifest lists no files' }
    Get-ChildItem -LiteralPath $root -Recurse -File -Force | Where-Object { $_.Name -ne 'manifest.sha256' } | ForEach-Object {
        $name = $_.FullName.Substring($root.Length + 1)
        if (-not $listed.ContainsKey($name)) { "not listed: $name" }
    }
}

function Get-EpochNow { return [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() }

# The sampler's `epoch,bytes` lines. Reads while the sampler writes. -Complete drops a last line still being written.
function Read-Samples([string]$Csv, [switch]$Complete) {
    $samples = @(); $invalid = 0
    if (Test-Path -LiteralPath $Csv) {
        # A concurrent writer may briefly deny sharing. Bound the wait to 400 ms;
        # never substitute old samples or retry other IO errors. The caller still
        # checks the timestamps, missing samples and floor after this read.
        for ($attempt = 1; ; $attempt++) {
            try {
                $stream = [IO.File]::Open($Csv, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
                break
            } catch [IO.IOException] {
                $code = $_.Exception.HResult -band 0xffff
                if ($code -notin 32, 33 -or $attempt -ge 5) { throw }
                Start-Sleep -Milliseconds 100
            }
        }
        try { $text = [IO.StreamReader]::new($stream).ReadToEnd() } finally { $stream.Dispose() }
        if ($Complete -and -not $text.EndsWith("`n")) { $text = $text.Substring(0, $text.LastIndexOf("`n") + 1) }
        foreach ($line in @($text -split "`r?`n" | Select-Object -Skip 1)) {
            if (-not $line) { continue }
            if ($line -match '^(\d+),(\d+)$') { $samples += [pscustomobject]@{ epoch = [int64]$Matches[1]; bytes = [int64]$Matches[2] } }
            else { $invalid++ }
        }
    }
    return [pscustomobject]@{ samples = $samples; invalid = $invalid }
}

# Why the sampler cannot be relied on right now, or nothing.
function Get-SamplerProblem($Job, [string]$Csv, [string]$BreachFile, [int64]$Floor, [int]$MaxGap) {
    if (Test-Path -LiteralPath $BreachFile) { return 'host floor breach recorded by the watchdog' }
    if ($Job.State -notin 'NotStarted', 'Running') { return "the host sampler stopped (job $($Job.State))" }
    $read = Read-Samples $Csv -Complete
    if ($read.invalid -gt 0) { return 'unreadable host reading line' }
    if ($read.samples.Count -eq 0) { return 'no host reading yet' }
    $low = @($read.samples | Where-Object { $_.bytes -lt $Floor })
    if ($low.Count -gt 0) { return "Windows available fell to $($low[0].bytes) bytes, below the $Floor floor" }
    $age = (Get-EpochNow) - $read.samples[-1].epoch
    if ($age -gt $MaxGap) { return "no host reading for $age s (limit $MaxGap)" }
}

# Waits until the sampler has two good readings. Returns nothing when ready, else the reason not to launch.
function Wait-SamplerReady($Job, [string]$Csv, [string]$BreachFile, [int64]$Floor, [int]$MaxGap, [int]$TimeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $problem = Get-SamplerProblem $Job $Csv $BreachFile $Floor $MaxGap
        if (-not $problem) {
            if ((Read-Samples $Csv -Complete).samples.Count -ge 2) { return }
            $problem = 'fewer than two host readings'
        }
        elseif ($problem -ne 'no host reading yet' -and $problem -notlike 'no host reading for*') { return $problem }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    return "the host sampler was not ready within $TimeoutSeconds s: $problem"
}

# After the run: did the readings cover launch to end without a gap, and did any fall under the floor?
function Get-CoverageProblems([string]$Csv, [int64]$From, [int64]$To, [int64]$Floor, [int]$MaxGap) {
    $read = Read-Samples $Csv
    if ($read.invalid -gt 0) { "host readings: $($read.invalid) unreadable line(s)" }
    $before = @($read.samples | Where-Object { $_.epoch -le $From })
    if ($before.Count -eq 0) { 'host readings: none at or before launch'; return }
    $window = @($before[-1]) + @($read.samples | Where-Object { $_.epoch -gt $From })
    for ($i = 1; $i -lt $window.Count; $i++) {
        if ($window[$i - 1].epoch -ge $To) { break }
        $gap = $window[$i].epoch - $window[$i - 1].epoch
        if ($gap -gt $MaxGap -or $gap -lt 0) { "host readings: gap of $gap s during the run (limit $MaxGap)"; break }
    }
    $tail = $To - $window[-1].epoch
    if ($tail -gt $MaxGap) { "host readings: none in the last $tail s of the run (limit $MaxGap)" }
    $low = @($window | Where-Object { $_.bytes -lt $Floor } | Sort-Object bytes)
    if ($low.Count -gt 0) { "Windows available fell to $($low[0].bytes) bytes, below the $Floor floor" }
}

# One WSL command with a time limit. Runs in its own job so a command that never returns cannot hold the launcher.
# ok only when it finished in time and the native exit code was 0.
function Invoke-WslBounded([string[]]$WslArgs, [int]$TimeoutSeconds) {
    $call = Start-Job -ArgumentList $HostOps, $WslArgs -ScriptBlock {
        param($hostOps, $wslArgs)
        . $hostOps
        $global:LASTEXITCODE = $null
        $text = @(Invoke-Wsl @wslArgs 2>&1 | ForEach-Object { "$_" -replace [string][char]0, '' })
        [pscustomobject]@{ exit = $global:LASTEXITCODE; output = $text }
    }
    if (-not (Wait-Job $call -Timeout $TimeoutSeconds)) {
        $null = Stop-JobBounded $call
        return [pscustomobject]@{ ok = $false; why = "timed out after $TimeoutSeconds s"; output = @() }
    }
    $result = @(Receive-Job $call -ErrorAction SilentlyContinue) | Select-Object -Last 1
    $state = $call.State
    Remove-Job $call -Force -ErrorAction SilentlyContinue
    $why = if ($state -ne 'Completed' -or $null -eq $result -or $null -eq $result.exit) { "no exit code (job $state)" }
    elseif ($result.exit -ne 0) { "exit $($result.exit)" }
    return [pscustomobject]@{ ok = -not $why; why = $why; output = @(if ($result) { $result.output }) }
}

# Ends a background job without ever blocking for longer than $JobStopSeconds. Returns $true when it has ended.
function Stop-JobBounded($Job) {
    if ($Job.State -in 'NotStarted', 'Running') {
        # Stop-Job blocks until the job ends, so it runs in its own runspace and is waited on with a limit
        $stopper = [powershell]::Create()
        $null = $stopper.AddScript('param($j) Stop-Job -Job $j').AddArgument($Job)
        $wait = $stopper.BeginInvoke()
        if ($wait.AsyncWaitHandle.WaitOne($JobStopSeconds * 1000)) { $stopper.Dispose() }
    }
    if ($Job.State -in 'NotStarted', 'Running', 'Stopping') { return $false }
    Remove-Job $Job -Force -ErrorAction SilentlyContinue
    return $true
}

function Invoke-DockerBounded([string[]]$DockerArgs) {
    return Invoke-WslBounded (@('-d', $Distro, '--exec', 'docker') + $DockerArgs) $CommandTimeoutSeconds
}

function Out-Json($Object, [string]$Path) {
    $text = $Object | ConvertTo-Json -Depth 6
    if ($Path) { Set-Content -Path $Path -Value $text -Encoding UTF8 }
    Write-Output $text
}

# ------------------------------------------------------------------ dry run: print the plan, execute nothing
if ($DryRun) {
    $avail = Get-AvailableBytes
    $reasons = @(Get-GateReasons $avail)
    $budget = Get-Budget $avail
    $id = New-RunId
    $outDir = ($OutRoot.TrimEnd([char]92, [char]47)) + [IO.Path]::DirectorySeparatorChar + $id   # plain string: Join-Path rejects C: paths on Linux
    $plan = [ordered]@{
        mode = if ($Run) { 'run' } elseif ($Preflight) { 'preflight' } elseif ($Cleanup) { 'cleanup' } elseif ($Verify) { 'verify' } else { 'stop' }
        executed = $false
        windows_available_bytes = $avail
        start_min_bytes = $StartMinBytes
        need_at_start_bytes = $NeedAtStartBytes
        floor_bytes = $FloorBytes
        gate_passes = ($reasons.Count -eq 0)
        gate_reasons = $reasons
        budget_bytes = $budget
        ceiling_metric = $CeilingMetric
        container_cap = $ContainerCap
        paging_rule = $PagingRule
        container_memory_bytes = (Get-ContainerLimitBytes $budget)
        effective_budget_bytes = (Get-EffectiveBudget $budget)
        ceiling_bytes = (Get-CeilingBytes $budget)
        out_dir = $outDir
        docker_argv = @(if ($reasons.Count -eq 0) { Get-DockerArgv "ladder-$id" '/out-dir' $budget $avail })
        steps = @('preflight gate', 'keep awake', 'quit Docker Desktop if running', 'wsl --shutdown', 'wait for the Docker daemon inside the WSL distro',
            'second gate read', 'check the image and the model cache are present offline', 'start sampler and watchdog', 'docker run', 'summary, marker, manifest')
    }
    if ($EvidenceMode) { $plan.evidence_mode = $EvidenceNotice }
    if ($GradientCheckpointing) { $plan.gradient_checkpointing = $CheckpointingNotice }
    Out-Json $plan $null
    exit $(if ($reasons.Count -eq 0) { 0 } else { 3 })
}

# ------------------------------------------------------------------ verify: read-only manifest check of one run folder
if ($Verify) {
    if (-not $RunId) { [Console]::Error.WriteLine('-Verify needs -RunId.'); exit 2 }
    $problems = @(Test-Manifest (Join-Path $OutRoot $RunId))
    if ($problems.Count -gt 0) { $problems | ForEach-Object { Write-Output $_ }; exit 5 }
    Write-Output 'manifest verified'
    exit 0
}

# ------------------------------------------------------------------ preflight
if ($Preflight) {
    New-Item -ItemType Directory -Force -Path $OutRoot | Out-Null
    $facts = Get-HostFacts
    $reasons = @(Get-GateReasons $facts.windows_available_bytes)
    $facts.gate_passes = ($reasons.Count -eq 0)
    $facts.gate_reasons = $reasons
    $facts.budget_if_started_now = Get-Budget $facts.windows_available_bytes
    Out-Json $facts (Join-Path $OutRoot ("preflight-" + (New-RunId) + '.json'))
    exit $(if ($reasons.Count -eq 0) { 0 } else { 3 })
}

# ------------------------------------------------------------------ cleanup: back to normal operation
if ($Cleanup) {
    Write-Output 'Cleanup: killing any ladder container, wsl --shutdown, quitting Docker Desktop. Records are kept.'
    if (Test-DistroRunning) {
        $ids = @(Invoke-Docker ps -q --filter 'name=ladder-' 2>$null)
        foreach ($c in $ids) { Invoke-Docker kill $c | Out-Null }
    }
    Invoke-Wsl --shutdown
    $null = @(Stop-DockerDesktop)
    Start-Sleep 5
    $avail = Get-HostAvailableBytes
    Write-Output ("Cleanup done. Windows available now {0:N2} GiB. Your machine is back to normal operation; nothing else was changed." -f ($avail / $GiB))
    exit 0
}

# ------------------------------------------------------------------ stop
if ($Stop) {
    $latest = Get-ChildItem $OutRoot -Directory -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -Last 1
    if ($latest -and (Test-Path (Join-Path $latest.FullName 'manifest.sha256'))) { $latest = $null }   # finished: never change a sealed bundle
    if ($latest) { Set-Content (Join-Path $latest.FullName 'STOP') 'operator stop' }
    $ids = @()
    if (Test-DistroRunning) { $ids = @(Invoke-Docker ps -q --filter 'name=ladder-') }
    foreach ($id in $ids) { Invoke-Docker kill $id | Out-Null }
    if ($latest) { Set-Content (Join-Path $latest.FullName 'STOPPED-by-operator') ((Get-Date).ToUniversalTime().ToString('o')) }
    Write-Output "stopped $($ids.Count) container(s)"
    exit 0
}

# ------------------------------------------------------------------ run
$id = New-RunId
$out = Join-Path $OutRoot $id
New-Item -ItemType Directory -Force -Path (Join-Path $out 'ladder') | Out-Null
$log = Join-Path $out 'launcher.log'
function Log([string]$Message) { Add-Content -Path $log -Value ("{0} {1}" -f (Get-Date).ToUniversalTime().ToString('o'), $Message); Write-Host $Message }
function Mark([string]$Name, [string]$Reason) { Set-Content (Join-Path $out $Name) ("{0}`n{1}" -f (Get-Date).ToUniversalTime().ToString('o'), $Reason) }
# A refusal records why and exits; the bundle is sealed after the last write (in the finally block below).
function Refuse([string[]]$Reasons) {
    Log ("NOT_RUN: " + ($Reasons -join '; '))
    Mark 'NOT_RUN' ($Reasons -join "`n")
    $script:Refused = $true
    exit 3
}
# Seals the bundle: nothing may be written into $out after this. Returns the differences found on verification.
function Complete-Bundle {
    Write-Manifest $out
    @(Test-Manifest $out)
}

Log "ladder launcher start, run id $id, out $out"
foreach ($n in $Notices) { Log $n }
$job = $null
$runJob = $null
$name = "ladder-$id"
$csv = Join-Path $out 'host.csv'
$stopFile = Join-Path $out 'STOP'
$breachFile = Join-Path $out 'host-floor-breach.json'
$failures = [Collections.Generic.List[string]]::new()   # anything here means the run cannot be DONE
function Fail([string]$Reason) { if (-not $failures.Contains($Reason)) { $failures.Add($Reason); Log "failure: $Reason" } }
$launcherError = $false
$exit = $null
$script:Refused = $false
$engaged = $false   # true once anything on the machine has been changed and so must be put back
try {
    $avail1 = Get-AvailableBytes
    Log "first read: Windows available $avail1 bytes"
    $reasons = @(Get-GateReasons $avail1)
    if ($reasons.Count -gt 0) { Refuse $reasons }

    $engaged = $true
    Set-KeepAwake $true
    foreach ($n in @(Stop-DockerDesktop)) { Log "quit $n (not used; it competes for memory)" }
    Log 'wsl --shutdown (all WSL distros stop; Nate approved this)'
    Invoke-Wsl --shutdown
    Start-Sleep 3
    Log "starting the Docker daemon inside $Distro (systemd starts it when the distro boots)"
    $deadline = (Get-Date).AddSeconds(180)
    do { Invoke-Docker info --format '{{.ServerVersion}}' *> $null; if ($LASTEXITCODE -ne 0) { Start-Sleep 5 } } while ($LASTEXITCODE -ne 0 -and (Get-Date) -lt $deadline)
    if ($LASTEXITCODE -ne 0) { Refuse @("Docker daemon in $Distro did not come up within 180 s") }
    $avail2 = Get-AvailableBytes
    Log "second read after the daemon is up: Windows available $avail2 bytes"
    $reasons = @(Get-GateReasons $avail2)
    if ($reasons.Count -gt 0) { Refuse $reasons }
    Invoke-Docker image inspect $Image *> $null
    if ($LASTEXITCODE -ne 0) { Refuse @("image $Image is not present in $Distro; building it is a separate step") }
    $snapshot = "/c/hub/$ModelCacheDir/snapshots/$ModelRevision"
    Invoke-Docker run --rm --pull=never --network none -v "${ModelVolume}:/c:ro" alpine test -d $snapshot
    if ($LASTEXITCODE -ne 0) { Refuse @("pinned model revision is not in volume $ModelVolume; fetching it is a download that needs Nate's yes") }

    $budget = Get-Budget $avail2
    Log ("container memory limit: {0} bytes (cap mode {1})" -f (Get-ContainerLimitBytes $budget), $ContainerCap)
    Log "ceiling metric: $CeilingMetric (original = first-run gate; anon_shmem = new labelled configuration)"
    Log "fixed budget $budget bytes (min of 22 GiB and available minus 4 GiB)"
    Log ("effective budget {0} bytes (lower of budget and container limit); ceiling {1} bytes (90 percent)" -f (Get-EffectiveBudget $budget), (Get-CeilingBytes $budget))
    Set-Content $csv 'epoch,bytes'
    $job = Start-Job -ArgumentList $csv, $name, $FloorBytes, $PollSeconds, $stopFile, $breachFile, $Distro, $HostOps -ScriptBlock {
        param($csv, $name, $floor, $poll, $stopFile, $breachFile, $distro, $hostOps)
        $ErrorActionPreference = 'Stop'   # a failed reading ends the job; the launcher sees that and aborts
        . $hostOps
        $low = 0
        while (-not (Test-Path $stopFile)) {
            $b = [int64](Get-HostAvailableBytes)
            if ($b -le 0) { throw "unusable host reading $b" }
            $e = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
            for ($try = 1; ; $try++) {
                try { Add-Content -Path $csv -Value "$e,$b"; break } catch { if ($try -ge 5) { throw }; Start-Sleep -Milliseconds 100 }
            }
            if ($b -lt $floor) { $low++ } else { $low = 0 }
            if ($low -ge 1) {
                Set-Content $breachFile "{`"epoch`":$e,`"available_bytes`":$b,`"floor_bytes`":$floor}"
                Invoke-Wsl -d $distro --exec docker kill $name | Out-Null
                break
            }
            Start-Sleep $poll
        }
    }
    $notReady = Wait-SamplerReady $job $csv $breachFile $FloorBytes $MaxSampleGapSeconds $SamplerReadySeconds
    $argv = Get-DockerArgv $name $out $budget $avail2
    Log ("docker " + ($argv -join ' '))
    if (-not $notReady) { $notReady = Get-SamplerProblem $job $csv $breachFile $FloorBytes $MaxSampleGapSeconds }   # last look
    if ($notReady) { Refuse @("not launched: $notReady") }
    if (Test-Path $stopFile) { Refuse @('not launched: operator stop') }
    $launched = Get-EpochNow
    $runJob = Start-Job -ArgumentList $HostOps, $Distro, $argv, (Join-Path $out 'container.log') -ScriptBlock {
        param($hostOps, $distro, $argv, $containerLog)
        . $hostOps
        Invoke-Wsl -d $distro --exec docker @argv *> $containerLog
        $LASTEXITCODE
    }
    $abort = $null
    while ($runJob.State -in 'NotStarted', 'Running') {
        if (-not $abort) {
            $abort = if (Test-Path $stopFile) { 'operator stop' } else { Get-SamplerProblem $job $csv $breachFile $FloorBytes $MaxSampleGapSeconds }
            if ($abort) { Fail "run aborted: $abort"; $giveUp = (Get-Date).AddSeconds($AbortWaitSeconds) }
        }
        if ($abort) {
            # repeated: the first kill can arrive before the container exists
            $kill = Invoke-DockerBounded @('kill', $name)   # a nonzero exit is expected before the container exists
            if ($kill.why -like 'timed out*') { Fail "the abort kill $($kill.why)" }
            if ((Get-Date) -gt $giveUp) { Fail "the container client did not end within $AbortWaitSeconds s of the abort"; break }
        }
        Start-Sleep 1
    }
    $ended = Get-EpochNow
    if ($runJob.State -eq 'Completed') { $exit = @(Receive-Job $runJob) | Where-Object { $_ -is [int] } | Select-Object -Last 1 }
    if ($null -eq $exit) { Fail 'container exit code unknown' }
    else { Log "container exited $exit"; if ($exit -ne 0) { Fail "container exited $exit" } }
    foreach ($problem in @(Get-CoverageProblems $csv $launched $ended $FloorBytes $MaxSampleGapSeconds)) { Fail $problem }
}
catch {
    $launcherError = $true
    Fail "launcher error: $_"
}
finally {
    if ($engaged) {
        # every step is tried even if an earlier one fails, and a failure to put the machine back is itself recorded
        $steps = [ordered]@{
            'stop the sampler' = {
                if (-not (Test-Path $stopFile)) { Set-Content $stopFile 'run finished' }
                if ($job) {
                    $null = Wait-Job $job -Timeout 15
                    # A sampler told to stop ends as Completed. Anything else, including a failure after the run loop's last
                    # look (the container finishing in between), is recorded here, before the job is removed.
                    if ($job.State -ne 'Completed') {
                        Fail "the host sampler did not end cleanly (job $($job.State)) $($job.ChildJobs[0].JobStateInfo.Reason.Message)".Trim()
                    }
                    if (-not (Stop-JobBounded $job)) { Fail 'the host sampler could not be stopped' }
                }
            }
            'kill leftover containers' = {
                $wsl = Invoke-WslBounded @('-l', '-v') $CommandTimeoutSeconds
                if (-not $wsl.ok) { Fail "cleanup: could not query WSL ($($wsl.why)); container state unknown" }
                elseif (-not (($wsl.output -join "`n") -match "$Distro +Running")) { return }   # no VM, so no container
                $list = Invoke-DockerBounded @('ps', '-q', '--filter', "name=$name")
                if (-not $list.ok) {
                    Fail "cleanup: could not list containers ($($list.why)); container state unknown"
                    $kill = Invoke-DockerBounded @('kill', $name)   # try anyway; its result cannot be confirmed
                    return
                }
                $left = @($list.output | Where-Object { $_ })
                foreach ($c in $left) {
                    $kill = Invoke-DockerBounded @('kill', $c)
                    if (-not $kill.ok) { Fail "cleanup: docker kill $($kill.why)" }
                }
                if ($left.Count -gt 0) {
                    $again = Invoke-DockerBounded @('ps', '-q', '--filter', "name=$name")
                    if (-not $again.ok -or @($again.output | Where-Object { $_ }).Count -gt 0) { Fail 'cleanup: could not confirm that the container stopped' }
                }
            }
            'end the container client' = {
                if ($runJob) {
                    if ($runJob.State -in 'NotStarted', 'Running') { Fail 'the container client was still running at cleanup' }
                    if (-not (Stop-JobBounded $runJob)) { Fail 'the container client could not be stopped' }
                }
            }
            'release keep-awake' = { Set-KeepAwake $false }
        }
        foreach ($step in $steps.Keys) {
            try { . $steps[$step] } catch { $launcherError = $true; Fail "cleanup failed ($step): $_" }
        }
    }
    if ($script:Refused) {
        $problems = @(Complete-Bundle)
        if ($problems.Count -gt 0) { Write-Host ('MANIFEST DOES NOT VERIFY: ' + ($problems -join '; ')) -ForegroundColor Red; exit 5 }
    }
}

# Terminal status. A child's own success never outranks anything the launcher saw go wrong.
if (Test-Path $breachFile) { Fail 'host floor breach recorded' }
if (Test-Path (Join-Path $out 'STOPPED-by-operator')) { Fail 'operator stop' }
$summary = $null
$summaryPath = Join-Path (Join-Path $out 'ladder') 'summary.json'
if (Test-Path $summaryPath) {
    try { $summary = Get-Content $summaryPath -Raw | ConvertFrom-Json } catch { Fail 'the ladder summary is not valid JSON' }
    if ($summary.complete -ne $true) { Fail "ladder not complete: stopped_at $($summary.stopped_at)" }
} else {
    Fail "no ladder summary was written (container exit $exit)"
}
if ($failures.Count -eq 0) { Mark 'DONE' ((@('all rungs PASS') + $Notices) -join "`n") }
else { Mark 'STOPPED' ((@($failures) + $Notices) -join "`n") }
Log 'done; reopen Claude Code and read the records'
$problems = @(Complete-Bundle)   # the last writes into $out are above this line
$sealed = if ($problems.Count -eq 0) { 'manifest verified' } else { 'MANIFEST DOES NOT VERIFY: ' + ($problems -join '; ') }
$result = (Get-ChildItem $out -File | Where-Object { $_.Name -in 'DONE', 'STOPPED', 'NOT_RUN' } | Select-Object -First 1).Name
$note = "LADDER FINISHED: $result`r`n$(($Notices | ForEach-Object { "$_`r`n" }) -join '')Records: $out ($sealed)`r`nNext: run  powershell -File scripts/ladder/run_ladder.ps1 -Cleanup  to return the machine to normal, then reopen Claude Code and tell it the ladder finished."
$desktop = if ($DesktopDir) { $DesktopDir } else { Get-DesktopPath }
if ($desktop -and (Test-Path $desktop)) { Set-Content (Join-Path $desktop 'LADDER-FINISHED.txt') $note }
Write-Host ''
Write-Host '==================================================' -ForegroundColor Green
Write-Host "  LADDER FINISHED: $result" -ForegroundColor Green
Write-Host "  Records: $out" -ForegroundColor Green
foreach ($n in $Notices) { Write-Host "  $n" -ForegroundColor Yellow }
Write-Host "  $sealed" -ForegroundColor $(if ($problems.Count -eq 0) { 'Green' } else { 'Red' })
Write-Host '  Next: run -Cleanup, then reopen Claude Code.' -ForegroundColor Green
Write-Host '==================================================' -ForegroundColor Green
Send-FinishSignal
if ($problems.Count -gt 0) { exit 5 }
if ($launcherError) { exit 4 }
