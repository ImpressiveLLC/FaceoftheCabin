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

# Pick a Python that has the packages the tool needs.
$python = $null
foreach ($candidate in @('python', 'py')) {
    if (-not (Get-Command $candidate -ErrorAction SilentlyContinue)) { continue }
    & $candidate -c "import jsonschema, yaml, dateutil" 2>$null
    if ($LASTEXITCODE -eq 0) { $python = $candidate; break }
}
if (-not $python) {
    Write-Host "No Python with the needed packages was found. Run once:" -ForegroundColor Red
    Write-Host "  python -m pip install -r `"$(Join-Path $package 'requirements.txt')`""
    exit 1
}

Write-Host "Starting the grading tool at http://127.0.0.1:$port  (leave this window open; Ctrl+C stops it)"
& $python -B $server
