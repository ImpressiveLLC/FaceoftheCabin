# Start the grading screen. Works in Windows PowerShell 5.1 and PowerShell 7.
# From the folder you cloned the repository into:   .\docs\ai-assistant\training\intent-slot\grading\start.ps1
# If PowerShell says scripts are disabled, run it as:  powershell -ExecutionPolicy Bypass -File .\docs\ai-assistant\training\intent-slot\grading\start.ps1
# It (1) checks you are on main, (2) pulls the latest, (3) stops the old grading tool wherever it is running,
# (4) starts the tool in THIS window. Leave this window open while you grade; Ctrl+C stops the tool.
$ErrorActionPreference = 'Stop'
$port = 8766
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = (& git -C $here rev-parse --show-toplevel).Trim()
$package = Join-Path $here '..'
$server = Join-Path $here 'server.py'

$branch = (& git -C $repo rev-parse --abbrev-ref HEAD).Trim()
if ($branch -ne 'main') {
    Write-Host "This folder ($repo) is on branch '$branch', not main." -ForegroundColor Red
    Write-Host "Grading must start from main. Run:  git -C `"$repo`" switch main"
    Write-Host "If git says main is already open in another folder, run this script from that folder instead (see: git worktree list)."
    exit 1
}

Write-Host "Pulling the latest main..."
& git -C $repo pull --ff-only
if ($LASTEXITCODE -ne 0) {
    Write-Host "git pull failed. Fix that first (tell Code if unsure); the tool was not started." -ForegroundColor Red
    exit 1
}

# Stop an old grading tool that is holding the port, but never some other program.
$listeners = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
foreach ($owner in ($listeners | Select-Object -ExpandProperty OwningProcess -Unique)) {
    $cmd = (Get-CimInstance Win32_Process -Filter "ProcessId=$owner").CommandLine
    if ($cmd -match 'grading[\\/]server\.py') {
        Write-Host "Stopping the old grading tool (process $owner)..."
        Stop-Process -Id $owner -Force
    } else {
        Write-Host "Port $port is used by something that is not the grading tool (process $owner). Close it and run this again." -ForegroundColor Red
        exit 1
    }
}

# Run a native command and hand back only its exit code. Windows PowerShell 5.1 turns any stderr text from a native
# command into a terminating error when $ErrorActionPreference is 'Stop', so the probe relaxes it for its own lifetime.
function Get-ExitCode([string[]]$command) {
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $rest = @($command | Select-Object -Skip 1)
        & $command[0] @rest 2>&1 | Out-Null
        return $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $saved
    }
}

# Pick a Python that really runs (not the Microsoft Store stub), then make sure it has the packages the tool needs.
$python = $null
foreach ($candidate in @(@('python'), @('py', '-3'))) {
    if (-not (Get-Command $candidate[0] -ErrorAction SilentlyContinue)) { continue }
    if ((Get-ExitCode ($candidate + @('-c', 'import sys'))) -eq 0) { $python = $candidate; break }
}
if (-not $python) {
    Write-Host "No working Python was found. Install Python 3 from python.org (tick 'Add to PATH'), then run this again." -ForegroundColor Red
    exit 1
}
$needed = @('-c', 'import jsonschema, yaml, dateutil')
if ((Get-ExitCode ($python + $needed)) -ne 0) {
    $requirements = Join-Path $package 'requirements.txt'
    Write-Host "Installing the packages the tool needs, once (jsonschema, PyYAML, python-dateutil) into $($python -join ' ')..."
    $rest = @($python | Select-Object -Skip 1)
    & $python[0] @rest -m pip install --quiet -r $requirements
    if ($LASTEXITCODE -ne 0 -or (Get-ExitCode ($python + $needed)) -ne 0) {
        Write-Host "Could not install them. Run this yourself, then start again:" -ForegroundColor Red
        Write-Host "  $($python -join ' ') -m pip install -r `"$requirements`""
        exit 1
    }
}

Write-Host "Starting the grading tool at http://127.0.0.1:$port  (leave this window open; Ctrl+C stops it)"
$rest = @($python | Select-Object -Skip 1)
& $python[0] @rest -B $server
