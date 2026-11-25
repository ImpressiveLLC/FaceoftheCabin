# Every operation run_ladder.ps1 performs on the real machine lives here: WSL, processes, memory reads, power state, the
# desktop and the speaker. run_ladder.ps1 loads the host_ops.ps1 next to it, so a test copies the script beside a stub file
# and this one is never loaded. As a second guard, every function here refuses to run when LADDER_TEST_NO_HOST is set.

function Assert-HostOpsAllowed {
    if ($env:LADDER_TEST_NO_HOST) { throw 'real host operation refused: LADDER_TEST_NO_HOST is set (tests must use a stub host_ops.ps1)' }
}

function Invoke-Wsl { Assert-HostOpsAllowed; & wsl @args }

function Get-HostAvailableBytes { Assert-HostOpsAllowed; return [int64](Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory * 1024 }

function Get-DockerDesktopProcessNames { return @('Docker Desktop', 'com.docker.backend', 'com.docker.build') }

function Test-DockerDesktopRunning { Assert-HostOpsAllowed; return [bool](Get-Process -Name 'Docker Desktop' -ErrorAction SilentlyContinue) }

# Stops Docker Desktop and its helpers; returns the names that were running.
function Stop-DockerDesktop {
    Assert-HostOpsAllowed
    foreach ($n in (Get-DockerDesktopProcessNames)) {
        if (Get-Process -Name $n -ErrorAction SilentlyContinue) {
            Get-Process -Name $n -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
            $n
        }
    }
}

function Set-KeepAwake([bool]$On) {
    Assert-HostOpsAllowed
    if (-not ('Native.Power' -as [type])) {
        Add-Type -Namespace Native -Name Power -MemberDefinition '[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint f);'
    }
    # ES_CONTINUOUS | ES_SYSTEM_REQUIRED, or ES_CONTINUOUS alone to release. A bare hex literal is Int32 and throws.
    $flags = if ($On) { [uint32]2147483649 } else { [uint32]2147483648 }
    [void][Native.Power]::SetThreadExecutionState($flags)
}

# Read-only machine facts for -Preflight.
function Get-HostMachineFacts([string]$OutRoot) {
    Assert-HostOpsAllowed
    $facts = [ordered]@{}
    $cs = Get-CimInstance Win32_ComputerSystem
    $facts.physical_bytes = [int64]$cs.TotalPhysicalMemory
    $facts.windows_available_bytes = Get-HostAvailableBytes
    $facts.pagefile_used_mb = @(Get-CimInstance Win32_PageFileUsage | ForEach-Object { $_.CurrentUsage })
    $wslconfig = Join-Path $env:USERPROFILE '.wslconfig'
    $facts.wslconfig = if (Test-Path $wslconfig) { @(Get-Content $wslconfig | Where-Object { $_ -match '^\s*[a-z]' }) } else { @() }
    $drive = (Split-Path -Qualifier $OutRoot).TrimEnd(':')
    $facts.out_drive_free_bytes = [int64](Get-PSDrive $drive).Free
    $facts.top_processes_by_working_set = @(Get-Process | Group-Object Name | ForEach-Object {
            [pscustomobject]@{ name = $_.Name; n = $_.Count; ws_mb = [int](($_.Group | Measure-Object WorkingSet64 -Sum).Sum / 1MB) }
        } | Sort-Object ws_mb -Descending | Select-Object -First 15)
    return $facts
}

function Get-DesktopPath { Assert-HostOpsAllowed; return [Environment]::GetFolderPath('Desktop') }

function Send-FinishSignal { Assert-HostOpsAllowed; 1..3 | ForEach-Object { [console]::Beep(880, 300); Start-Sleep -Milliseconds 200 } }
