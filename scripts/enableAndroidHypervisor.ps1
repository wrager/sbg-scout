#requires -Version 5.1
#requires -RunAsAdministrator
# Run once with UAC elevation. Never restarts the computer automatically.
$ErrorActionPreference = 'Stop'
$logDirectory = Join-Path $env:USERPROFILE 'AppData\Local\SBGScout\logs'
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
try {
    $result = Enable-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform -All -NoRestart
    $result | Format-List | Out-String | Set-Content -LiteralPath (Join-Path $logDirectory 'hypervisor.txt')
    Write-Host "Windows Hypervisor Platform enabled. Restart needed: $($result.RestartNeeded)"
} catch {
    $_ | Out-String | Set-Content -LiteralPath (Join-Path $logDirectory 'hypervisor.txt')
    throw
}
