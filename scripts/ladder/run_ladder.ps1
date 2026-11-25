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
  -DryRun      With -Run or -Preflight: prints the plan as JSON (gate, budget, the exact docker argv) and executes nothing.
               -FakeAvailableBytes supplies the Windows-available figure for tests; it is refused without -DryRun.

  Policy (Codex 7096272556, Cowork 7091727691, 7123997237): at least 6 GiB Windows available to start; the pinned 3B run
  needs 14.21 GiB (Stage 2 peak) / 0.9 plus the 4 GiB floor, so about 19.8 GiB at the start; stop below 4 GiB; container 22 GiB with
  no swap; budget fixed once before the run as min(22 GiB, available at start minus the floor); never recomputed.
  The 90 percent ceiling is on the effective budget: the lower of that budget and the container memory limit. With
  -ContainerCap budget the limit is the budget less 2 GiB, so the start needs 2 GiB more (about 21.8 GiB).
  A refused or failed start writes a NOT_RUN or STOPPED marker with the reason and never counts as a pass.
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
$OverheadMarginBytes = 2 * $GiB
# In budget cap mode the container limit (budget less the margin) is the ceiling denominator, so the start needs the margin too.
if ($ContainerCap -eq 'budget') { $NeedAtStartBytes += $OverheadMarginBytes }
$ModelRevision = '006f5dcd1393c3add266de40994ba96225e9689d'
$ModelCacheDir = 'models--unsloth--Llama-3.2-3B-Instruct'

$modes = @($Preflight, $Run, $Stop, $Cleanup, $Verify | Where-Object { $_ }).Count
if ($modes -ne 1) { Write-Error 'Choose exactly one of -Preflight, -Run, -Stop, -Cleanup, -Verify.'; exit 2 }
if ($PagingRule -eq 'v2' -and ([double]::IsNaN($PagingMaxTrainRefaultsPerSecond) -or [double]::IsInfinity($PagingMaxTrainRefaultsPerSecond) -or $PagingMaxTrainRefaultsPerSecond -lt 0)) { [Console]::Error.WriteLine('-PagingRule v2 needs -PagingMaxTrainRefaultsPerSecond (no limit has been ruled, so there is no default).'); exit 2 }
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

function Get-DockerArgv([string]$Name, [string]$OutDir, [int64]$Budget, [int64]$AvailableAtStart, [int64]$HostEpoch = 0) {
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
        '--host-available-start', "$AvailableAtStart", '--host-csv', '/out/host.csv',
        '--host-epoch-at-start', "$HostEpoch"
    ) + $(if ($PagingRule -eq 'v2') { @('--paging-max-train-refaults-per-s', "$PagingMaxTrainRefaultsPerSecond") } else { @() })
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
        "{0}  {1}" -f (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLower(), $_.FullName.Substring($root.Length + 1) }
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
$job = $null
$name = "ladder-$id"
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
    $csv = Join-Path $out 'host.csv'
    Set-Content $csv 'epoch,bytes'
    $job = Start-Job -ArgumentList $csv, $name, $FloorBytes, $PollSeconds, (Join-Path $out 'STOP'), (Join-Path $out 'host-floor-breach.json'), $Distro, $HostOps -ScriptBlock {
        param($csv, $name, $floor, $poll, $stopFile, $breachFile, $distro, $hostOps)
        . $hostOps
        $low = 0
        while (-not (Test-Path $stopFile)) {
            $b = Get-HostAvailableBytes
            $e = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
            Add-Content -Path $csv -Value "$e,$b"
            if ($b -lt $floor) { $low++ } else { $low = 0 }
            if ($low -ge 1) {
                Invoke-Wsl -d $distro --exec docker kill $name | Out-Null
                Set-Content $breachFile "{`"epoch`":$e,`"available_bytes`":$b,`"floor_bytes`":$floor}"
                break
            }
            Start-Sleep $poll
        }
    }
    Start-Sleep ($PollSeconds + 1)   # at least one reading before the first rung
    $argv = Get-DockerArgv $name $out $budget $avail2 ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())
    Log ("docker " + ($argv -join ' '))
    Invoke-Docker @argv *> (Join-Path $out 'container.log')
    $exit = $LASTEXITCODE
    Log "container exited $exit"
}
finally {
    if ($engaged) {
        Set-Content (Join-Path $out 'STOP') 'run finished'
        if ($job) { Wait-Job $job -Timeout 15 | Out-Null; Stop-Job $job -ErrorAction SilentlyContinue; Remove-Job $job -Force -ErrorAction SilentlyContinue }
        if (Test-DistroRunning) {
            $left = @(Invoke-Docker ps -q --filter "name=$name" 2>$null)
            foreach ($c in $left) { Invoke-Docker kill $c | Out-Null }
        }
        Set-KeepAwake $false
    }
    if ($script:Refused) {
        $problems = @(Complete-Bundle)
        if ($problems.Count -gt 0) { Write-Host ('MANIFEST DOES NOT VERIFY: ' + ($problems -join '; ')) -ForegroundColor Red; exit 5 }
    }
}

$summaryPath = Join-Path (Join-Path $out 'ladder') 'summary.json'
if (Test-Path $summaryPath) {
    $summary = Get-Content $summaryPath -Raw | ConvertFrom-Json
    if ($summary.complete -eq $true) { Mark 'DONE' 'all rungs PASS' } else { Mark 'STOPPED' ("stopped_at " + $summary.stopped_at) }
} else {
    Mark 'STOPPED' "no ladder summary was written (container exit $exit)"
}
if (Test-Path (Join-Path $out 'host-floor-breach.json')) { Log 'host floor breach recorded' }
Log 'done; reopen Claude Code and read the records'
$problems = @(Complete-Bundle)   # the last writes into $out are above this line
$sealed = if ($problems.Count -eq 0) { 'manifest verified' } else { 'MANIFEST DOES NOT VERIFY: ' + ($problems -join '; ') }
$result = (Get-ChildItem $out -File | Where-Object { $_.Name -in 'DONE', 'STOPPED', 'NOT_RUN' } | Select-Object -First 1).Name
$note = "LADDER FINISHED: $result`r`nRecords: $out ($sealed)`r`nNext: run  powershell -File scripts/ladder/run_ladder.ps1 -Cleanup  to return the machine to normal, then reopen Claude Code and tell it the ladder finished."
$desktop = if ($DesktopDir) { $DesktopDir } else { Get-DesktopPath }
if ($desktop -and (Test-Path $desktop)) { Set-Content (Join-Path $desktop 'LADDER-FINISHED.txt') $note }
Write-Host ''
Write-Host '==================================================' -ForegroundColor Green
Write-Host "  LADDER FINISHED: $result" -ForegroundColor Green
Write-Host "  Records: $out" -ForegroundColor Green
Write-Host "  $sealed" -ForegroundColor $(if ($problems.Count -eq 0) { 'Green' } else { 'Red' })
Write-Host '  Next: run -Cleanup, then reopen Claude Code.' -ForegroundColor Green
Write-Host '==================================================' -ForegroundColor Green
Send-FinishSignal
if ($problems.Count -gt 0) { exit 5 }
