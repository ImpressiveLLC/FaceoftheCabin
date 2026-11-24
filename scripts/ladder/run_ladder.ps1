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
  -DryRun      With -Run or -Preflight: prints the plan as JSON (gate, budget, the exact docker argv) and executes nothing.
               -FakeAvailableBytes supplies the Windows-available figure for tests; it is refused without -DryRun.

  Policy (Codex 7096272556, Cowork 7091727691, 7123997237): at least 6 GiB Windows available to start; the pinned 3B run
  needs 14.21 GiB (Stage 2 peak) / 0.9 plus the 4 GiB floor, so about 19.8 GiB at the start; stop below 4 GiB; container 22 GiB with
  no swap; budget fixed once before the run as min(container limit, available at start minus the floor); never recomputed.
  A refused or failed start writes a NOT_RUN or STOPPED marker with the reason and never counts as a pass.
#>
[CmdletBinding()]
param(
    [switch]$Preflight,
    [switch]$Run,
    [switch]$Stop,
    [switch]$Cleanup,
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
$ModelRevision = '006f5dcd1393c3add266de40994ba96225e9689d'
$ModelCacheDir = 'models--unsloth--Llama-3.2-3B-Instruct'

$modes = @($Preflight, $Run, $Stop, $Cleanup | Where-Object { $_ }).Count
if ($modes -ne 1) { Write-Error 'Choose exactly one of -Preflight, -Run, -Stop, -Cleanup.'; exit 2 }
if ($PagingRule -eq 'v2' -and $PagingMaxTrainRefaultsPerSecond -lt 0) { [Console]::Error.WriteLine('-PagingRule v2 needs -PagingMaxTrainRefaultsPerSecond (no limit has been ruled, so there is no default).'); exit 2 }
if ($FakeAvailableBytes -gt 0 -and -not $DryRun) { Write-Error '-FakeAvailableBytes is only allowed with -DryRun.'; exit 2 }
if (-not $RepoDir) { $RepoDir = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path }
$TrainingDir = Join-Path (Join-Path (Join-Path $RepoDir 'docs') 'ai-assistant') 'training'

function Get-AvailableBytes {
    if ($DryRun -and $FakeAvailableBytes -gt 0) { return $FakeAvailableBytes }
    return [int64](Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory * 1024
}

function Get-GateReasons([int64]$Available) {
    $reasons = @()
    if ($Available -lt $StartMinBytes) { $reasons += "Windows available $Available bytes is below the $StartMinBytes start minimum (6 GiB)" }
    if ($Available -lt $NeedAtStartBytes) { $reasons += "Windows available $Available bytes is below the $NeedAtStartBytes needed to start the pinned 3B (Stage 2 peak 14.21 GiB / 0.9 for the 90 percent ceiling, plus the 4 GiB floor)" }
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

# Docker runs INSIDE the Ubuntu WSL distro (where the Stage 2 image and the model cache live), not Docker Desktop.
function Invoke-Docker { & wsl -d $Distro --exec docker @args }

function Test-DistroRunning {
    $state = (& wsl -l -v 2>&1 | Out-String) -replace [string][char]0, ''
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
    $facts = [ordered]@{}
    $cs = Get-CimInstance Win32_ComputerSystem
    $os = Get-CimInstance Win32_OperatingSystem
    $facts.physical_bytes = [int64]$cs.TotalPhysicalMemory
    $facts.windows_available_bytes = [int64]$os.FreePhysicalMemory * 1024
    $facts.pagefile_used_mb = @(Get-CimInstance Win32_PageFileUsage | ForEach-Object { $_.CurrentUsage })
    $wslconfig = Join-Path $env:USERPROFILE '.wslconfig'
    $facts.wslconfig = if (Test-Path $wslconfig) { @(Get-Content $wslconfig | Where-Object { $_ -match '^\s*[a-z]' }) } else { @() }
    $facts.wsl_state = (& wsl -l -v 2>&1 | Out-String) -replace "`0", ''
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
    $facts.docker_desktop_running = [bool](Get-Process -Name 'Docker Desktop' -ErrorAction SilentlyContinue)
    $drive = (Split-Path -Qualifier $OutRoot).TrimEnd(':')
    $facts.out_drive_free_bytes = [int64](Get-PSDrive $drive).Free
    $facts.top_processes_by_working_set = @(Get-Process | Group-Object Name | ForEach-Object {
            [pscustomobject]@{ name = $_.Name; n = $_.Count; ws_mb = [int](($_.Group | Measure-Object WorkingSet64 -Sum).Sum / 1MB) }
        } | Sort-Object ws_mb -Descending | Select-Object -First 15)
    return $facts
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
        mode = if ($Run) { 'run' } elseif ($Preflight) { 'preflight' } elseif ($Cleanup) { 'cleanup' } else { 'stop' }
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
        ceiling_bytes = [int64][math]::Floor($budget * 0.9)
        out_dir = $outDir
        docker_argv = @(if ($reasons.Count -eq 0) { Get-DockerArgv "ladder-$id" '/out-dir' $budget $avail })
        steps = @('preflight gate', 'keep awake', 'quit Docker Desktop if running', 'wsl --shutdown', 'wait for the Docker daemon inside the WSL distro',
            'second gate read', 'check the image and the model cache are present offline', 'start sampler and watchdog', 'docker run', 'summary, marker, manifest')
    }
    Out-Json $plan $null
    exit $(if ($reasons.Count -eq 0) { 0 } else { 3 })
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
    & wsl --shutdown
    foreach ($n in 'Docker Desktop', 'com.docker.backend', 'com.docker.build') { Get-Process -Name $n -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue }
    Start-Sleep 5
    $avail = [int64](Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory * 1024
    Write-Output ("Cleanup done. Windows available now {0:N2} GiB. Your machine is back to normal operation; nothing else was changed." -f ($avail / $GiB))
    exit 0
}

# ------------------------------------------------------------------ stop
if ($Stop) {
    $latest = Get-ChildItem $OutRoot -Directory -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -Last 1
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
function Write-Manifest {
    $lines = Get-ChildItem $out -Recurse -File | Where-Object { $_.Name -ne 'manifest.sha256' } | ForEach-Object {
        "{0}  {1}" -f (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower(), $_.FullName.Substring($out.Length + 1) }
    Set-Content (Join-Path $out 'manifest.sha256') $lines
}
function Refuse([string[]]$Reasons) {
    Log ("NOT_RUN: " + ($Reasons -join '; '))
    Mark 'NOT_RUN' ($Reasons -join "`n")
    Write-Manifest
    exit 3
}

Log "ladder launcher start, run id $id, out $out"
$avail1 = Get-AvailableBytes
Log "first read: Windows available $avail1 bytes"
$reasons = @(Get-GateReasons $avail1)
if ($reasons.Count -gt 0) { Refuse $reasons }

Add-Type -Namespace Native -Name Power -MemberDefinition '[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint f);'
[void][Native.Power]::SetThreadExecutionState([uint32]2147483649)   # ES_CONTINUOUS | ES_SYSTEM_REQUIRED
$job = $null
$name = "ladder-$id"
try {
    foreach ($n in 'Docker Desktop', 'com.docker.backend', 'com.docker.build') {
        if (Get-Process -Name $n -ErrorAction SilentlyContinue) { Log "quitting $n (not used; it competes for memory)"; Get-Process -Name $n -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue }
    }
    Log 'wsl --shutdown (all WSL distros stop; Nate approved this)'
    & wsl --shutdown
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
    Log "fixed budget $budget bytes (min of 22 GiB and available minus 4 GiB); ceiling 90 percent"
    $csv = Join-Path $out 'host.csv'
    Set-Content $csv 'epoch,bytes'
    $job = Start-Job -ArgumentList $csv, $name, $FloorBytes, $PollSeconds, (Join-Path $out 'STOP'), (Join-Path $out 'host-floor-breach.json'), $Distro -ScriptBlock {
        param($csv, $name, $floor, $poll, $stopFile, $breachFile, $distro)
        $low = 0
        while (-not (Test-Path $stopFile)) {
            $b = [int64](Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory * 1024
            $e = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
            Add-Content -Path $csv -Value "$e,$b"
            if ($b -lt $floor) { $low++ } else { $low = 0 }
            if ($low -ge 1) {
                & wsl -d $distro --exec docker kill $name | Out-Null
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
    Set-Content (Join-Path $out 'STOP') 'run finished'
    if ($job) { Wait-Job $job -Timeout 15 | Out-Null; Stop-Job $job -ErrorAction SilentlyContinue; Remove-Job $job -Force -ErrorAction SilentlyContinue }
    if (Test-DistroRunning) {
        $left = @(Invoke-Docker ps -q --filter "name=$name" 2>$null)
        foreach ($c in $left) { Invoke-Docker kill $c | Out-Null }
    }
    [void][Native.Power]::SetThreadExecutionState([uint32]2147483648)
}

$summaryPath = Join-Path $out 'ladder\summary.json'
if (Test-Path $summaryPath) {
    $summary = Get-Content $summaryPath -Raw | ConvertFrom-Json
    if ($summary.complete -eq $true) { Mark 'DONE' 'all rungs PASS' } else { Mark 'STOPPED' ("stopped_at " + $summary.stopped_at) }
} else {
    Mark 'STOPPED' "no ladder summary was written (container exit $exit)"
}
if (Test-Path (Join-Path $out 'host-floor-breach.json')) { Log 'host floor breach recorded' }
Write-Manifest
Log 'done; reopen Claude Code and read the records'
$result = (Get-ChildItem $out -File | Where-Object { $_.Name -in 'DONE', 'STOPPED', 'NOT_RUN' } | Select-Object -First 1).Name
$note = "LADDER FINISHED: $result`r`nRecords: $out`r`nNext: run  powershell -File scripts/ladder/run_ladder.ps1 -Cleanup  to return the machine to normal, then reopen Claude Code and tell it the ladder finished."
$desktop = if ($DesktopDir) { $DesktopDir } else { [Environment]::GetFolderPath('Desktop') }
if ($desktop -and (Test-Path $desktop)) { Set-Content (Join-Path $desktop 'LADDER-FINISHED.txt') $note }
Write-Host ''
Write-Host '==================================================' -ForegroundColor Green
Write-Host "  LADDER FINISHED: $result" -ForegroundColor Green
Write-Host "  Records: $out" -ForegroundColor Green
Write-Host '  Next: run -Cleanup, then reopen Claude Code.' -ForegroundColor Green
Write-Host '==================================================' -ForegroundColor Green
1..3 | ForEach-Object { [console]::Beep(880, 300); Start-Sleep -Milliseconds 200 }
