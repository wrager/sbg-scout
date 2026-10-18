#requires -Version 5.1
# Exercise device targeting and failure handling without an SDK, emulator or Pester.
$ErrorActionPreference = 'Stop'
$tokens = $null
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile(
    (Join-Path $PSScriptRoot 'android.ps1'), [ref]$tokens, [ref]$parseErrors)
if ($parseErrors) { throw ($parseErrors | Out-String) }
# Import only function declarations; never execute the CLI entry point in these tests.
foreach ($definition in $ast.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] }, $false)) {
    . ([scriptblock]::Create($definition.Extent.Text))
}

function Assert-Equal($Actual, $Expected, $Message) {
    if ($Actual -cne $Expected) { throw "$Message : expected '$Expected', got '$Actual'" }
}
function Assert-Fails([scriptblock]$Action, [string]$Pattern) {
    $failure = $null
    try { & $Action | Out-Null } catch { $failure = $_.ToString() }
    if (!$failure -or $failure -notmatch $Pattern) { throw "Expected failure '$Pattern', got '$failure'" }
}

Assert-Fails { Invoke-Checked 'powershell.exe' @('-NoProfile', '-Command', 'exit 7') } 'exit code 7'
Write-Host 'PASS: native command failures propagate'

$adb = 'fake-adb'
$avdName = 'sbg-scout-api33'
$script:devices = @('List of devices attached', 'phone123 device', 'emulator-5554 device', 'emulator-5580 device', 'emulator-5582 offline')
$script:queriedDevices = @()
$script:api = '33'
$script:webview = 'Current WebView package (name, version): (com.android.webview, 101.0.4951.41)'
$script:animationWrites = 0
Set-Item Function:Invoke-Checked {
    param([string]$Program, [string[]]$Arguments)
    if ($Arguments[0] -eq 'devices') { return $script:devices }
    if (($Arguments[2..4] -join ' ') -eq 'emu avd name') {
        $script:queriedDevices += $Arguments[1]
        if ($Arguments[1] -eq 'emulator-5580') { return @('sbg-scout-api33', 'OK') }
        return @('unrelated-avd', 'OK')
    }
    if (($Arguments[2..4] -join ' ') -eq 'shell getprop ro.build.version.sdk') { return $script:api }
    if (($Arguments[2..4] -join ' ') -eq 'shell dumpsys webviewupdate') { return $script:webview }
    if (($Arguments[2..4] -join ' ') -eq 'shell settings put') { $script:animationWrites++; return }
    throw "Unexpected fake command: $Arguments"
}

Assert-Equal (Find-ScoutEmulator) 'emulator-5580' 'selects only the named AVD'
Assert-Equal ($script:queriedDevices -join ',') 'emulator-5554,emulator-5580' 'ignores phones and offline devices'
$script:devices = @('List of devices attached', 'phone123 device', 'emulator-5554 device')
Assert-Equal (Find-ScoutEmulator) $null 'does not fall back to a different device'
Write-Host 'PASS: device selection excludes phones and unrelated AVDs'

$env:ANDROID_SERIAL = 'emulator-5580'
Assert-TestDevice
Assert-Equal $script:animationWrites 3 'configures animations on the validated emulator'
$script:api = '34'
Assert-Fails { Assert-TestDevice } 'API 33'
$script:api = '33'
$script:webview = 'Current WebView package (name, version): (com.android.webview, 130.0.0.0)'
Assert-Fails { Assert-TestDevice } 'WebView 101'
Assert-Equal $script:animationWrites 3 'does not configure an incompatible device'
Write-Host 'PASS: tests reject incompatible API and WebView versions'

$hostStatePath = [IO.Path]::GetTempFileName()
$script:hostProcess = [pscustomobject]@{ Id = 4321; StartTime = [datetime]'2026-01-01T00:00:00Z' }
$script:hostWaitCalls = 0
$script:hostWaitResult = $true
$script:hostProcess | Add-Member ScriptMethod WaitForExit {
    param($Timeout)
    Assert-Equal $Timeout 30000 'bounds host shutdown wait'
    $script:hostWaitCalls++
    return $script:hostWaitResult
}
function Get-Process {
    [CmdletBinding()]
    param([int]$Id)
    Assert-Equal $Id 4321 'queries only the saved host PID'
    return $script:hostProcess
}
function Write-HostState {
    param([string]$StartedTicks = $script:hostProcess.StartTime.ToUniversalTime().Ticks.ToString())
    @{ pid = 4321; startedUtcTicks = $StartedTicks } | ConvertTo-Json | Set-Content -LiteralPath $hostStatePath
}
$script:killedHosts = @()
$script:stoppedEmulators = @()
Set-Item Function:Invoke-Checked {
    param([string]$Program, [string[]]$Arguments)
    if ($Arguments[0] -eq 'start-server') { return }
    if ($Arguments[0] -eq 'devices') { return $script:devices }
    if (($Arguments[2..4] -join ' ') -eq 'emu avd name') {
        if ($Arguments[1] -eq 'emulator-5580') { return @('sbg-scout-api33', 'OK') }
        return @('unrelated-avd', 'OK')
    }
    if (($Arguments[2..3] -join ' ') -eq 'emu kill') {
        $script:stoppedEmulators += $Arguments[1]
        $script:devices = @($script:devices | Where-Object { $_ -notmatch '^emulator-5580\s' })
        return
    }
    if ($Program -eq (Join-Path $env:SystemRoot 'System32\taskkill.exe')) {
        Assert-Equal ($Arguments -join ' ') '/PID 4321 /T /F' 'targets only the owned process tree'
        $script:killedHosts += $Arguments[1]
        return
    }
    throw "Unexpected fake stop command: $Program $Arguments"
}
try {
    Write-HostState
    $script:devices = @('List of devices attached', 'phone123 device', 'emulator-5554 device', 'emulator-5580 offline')
    Stop-ScoutEmulator
    Assert-Equal ($script:killedHosts -join ',') '4321' 'stops the owned offline emulator tree'
    Assert-Equal $script:hostWaitCalls 1 'waits for the stopped host'
    Assert-Equal (Test-Path -LiteralPath $hostStatePath) $false 'cleans state after stopping'
    Assert-Equal $script:stoppedEmulators.Count 0 'does not send adb kill to unrelated devices'
    Write-Host 'PASS: offline shutdown targets the verified host process tree'

    Write-HostState '0'
    Stop-ScoutEmulator
    Assert-Equal $script:killedHosts.Count 1 'does not kill a reused PID'
    Assert-Equal $script:hostWaitCalls 1 'does not wait for an unrelated process'
    Write-Host 'PASS: stale host state cannot terminate a reused PID'

    $script:devices = @('List of devices attached', 'phone123 device', 'emulator-5554 device', 'emulator-5580 device')
    Write-HostState
    Stop-ScoutEmulator
    Assert-Equal ($script:stoppedEmulators -join ',') 'emulator-5580' 'gracefully stops only the named AVD'
    Assert-Equal $script:killedHosts.Count 1 'online shutdown does not force-kill processes'
    Assert-Equal $script:hostWaitCalls 2 'waits for host after adb disconnects'
    Write-Host 'PASS: online shutdown keeps graceful adb targeting'

    $script:hostWaitResult = $false
    Write-HostState
    Assert-Fails { Stop-ScoutEmulator } 'has not finished stopping'
    Assert-Equal (Test-Path -LiteralPath $hostStatePath) $true 'keeps state when shutdown fails'
    Write-Host 'PASS: failed shutdown preserves ownership state for retry'
} finally {
    if (Test-Path -LiteralPath $hostStatePath) { Remove-Item -LiteralPath $hostStatePath }
}
# The negative native-command test deliberately leaves LASTEXITCODE=7.
# GitHub's PowerShell runner otherwise propagates it after this script succeeds.
exit 0
