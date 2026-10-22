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

Assert-Equal ((Invoke-Checked 'cmd.exe' @('/c', 'echo one&echo two') -TimeoutSeconds 30) -join ',') 'one,two' 'returns output lines'
$consoleReply = Invoke-Checked 'powershell.exe' @('-NoProfile', '-Command', '[Console]::Out.Write("sbg-scout-api33`r`r`nOK`r`r`n")') -TimeoutSeconds 30
Assert-Equal ($consoleReply -join ',') 'sbg-scout-api33,OK' 'splits emulator console replies that end lines with CR CR LF'
Assert-Fails { Invoke-Checked 'cmd.exe' @('/c', 'echo broken 1>&2& exit 3') -TimeoutSeconds 30 } 'exit code 3: broken'
$watch = [Diagnostics.Stopwatch]::StartNew()
$timeout = $null
try { Invoke-Checked 'powershell.exe' @('-NoProfile', '-Command', 'Start-Sleep -Seconds 60') -TimeoutSeconds 1 } catch [TimeoutException] { $timeout = $_ }
if (!$timeout -or $watch.Elapsed.TotalSeconds -gt 20) { throw "Expected a timeout within 20 s, got '$timeout' after $($watch.Elapsed)" }
$watch.Restart()
$timeout = $null
try { Invoke-Checked 'cmd.exe' @('/c', 'start /b ping -n 12 127.0.0.1') -TimeoutSeconds 30 } catch [TimeoutException] { $timeout = $_ }
if (!$timeout -or $watch.Elapsed.TotalSeconds -gt 10) { throw "Expected an output timeout within 10 s, got '$timeout' after $($watch.Elapsed)" }
Write-Host 'PASS: bounded native commands return output, propagate failures and time out'

$adb = 'fake-adb'
$script:startProcessCalls = @()
$script:serverExitCode = 0
$script:serverExits = $true
function Start-Process {
    [CmdletBinding()]
    param([string]$FilePath, [string[]]$ArgumentList, [string]$WindowStyle, [switch]$PassThru, [switch]$Wait,
        [switch]$NoNewWindow, [string]$RedirectStandardOutput, [string]$RedirectStandardError)
    $script:startProcessCalls += , $PSBoundParameters
    $process = [pscustomobject]@{ ExitCode = $script:serverExitCode }
    $process | Add-Member ScriptMethod WaitForExit {
        param($Timeout)
        Assert-Equal $Timeout 30000 'bounds adb server startup'
        return $script:serverExits
    }
    return $process
}
Start-AdbServer
$call = $script:startProcessCalls[0]
Assert-Equal "$($call.FilePath) $($call.ArgumentList)" 'fake-adb start-server' 'starts the adb server'
Assert-Equal (($call.Keys | Sort-Object) -join ',') 'ArgumentList,FilePath,PassThru,WindowStyle' 'neither waits for the server nor redirects its output'
Assert-Equal $call.WindowStyle 'Hidden' 'uses a hidden window instead of the caller console'
$script:serverExitCode = 1
Assert-Fails { Start-AdbServer } 'exit code 1'
$script:serverExitCode = 0
$script:serverExits = $false
Assert-Fails { Start-AdbServer } 'within 30 seconds'
Remove-Item Function:Start-Process
Write-Host 'PASS: adb server starts detached from redirected output'

$avdName = 'sbg-scout-api33'
$adbTimeoutSeconds = 30
$consoleTimeoutSeconds = 10
$emulatorStopSeconds = 1
$script:devices = @('List of devices attached', 'phone123 device', 'emulator-5554 device', 'emulator-5580 device', 'emulator-5582 offline')
$script:queriedDevices = @()
$script:api = '33'
$script:webview = 'Current WebView package (name, version): (com.android.webview, 101.0.4951.41)'
$script:animationWrites = 0
$script:hangingConsole = $null
Set-Item Function:Invoke-Checked {
    param([string]$Program, [string[]]$Arguments, [int]$TimeoutSeconds)
    if (($Arguments[0] -eq 'devices' -or $Arguments[2] -eq 'emu') -and $TimeoutSeconds -le 0) {
        throw "Unbounded adb call: $Arguments"
    }
    if ($Arguments[0] -eq 'devices') { return $script:devices }
    if (($Arguments[2..4] -join ' ') -eq 'emu avd name') {
        $script:queriedDevices += $Arguments[1]
        if ($Arguments[1] -eq $script:hangingConsole) { throw [TimeoutException]::new('console did not answer') }
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
$script:hangingConsole = 'emulator-5554'
Assert-Equal (Find-ScoutEmulator) 'emulator-5580' 'skips an emulator whose console does not answer'
$script:hangingConsole = $null
$script:devices = @('List of devices attached', 'phone123 device', 'emulator-5554 device')
Assert-Equal (Find-ScoutEmulator) $null 'does not fall back to a different device'
Write-Host 'PASS: device selection excludes phones, unrelated AVDs and unresponsive consoles'

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
$script:adbServerStarts = 0
$script:consoleHangs = $false
$script:killHangs = $false
$script:killIgnored = $false
Set-Item Function:Start-AdbServer { $script:adbServerStarts++ }
Set-Item Function:Invoke-Checked {
    param([string]$Program, [string[]]$Arguments, [int]$TimeoutSeconds)
    if (($Arguments[0] -eq 'devices' -or $Arguments[2] -eq 'emu') -and $TimeoutSeconds -le 0) {
        throw "Unbounded adb call: $Arguments"
    }
    if ($Arguments[0] -eq 'devices') { return $script:devices }
    if (($Arguments[2..4] -join ' ') -eq 'emu avd name') {
        if ($Arguments[1] -eq 'emulator-5580') {
            if ($script:consoleHangs) { throw [TimeoutException]::new('console did not answer') }
            return @('sbg-scout-api33', 'OK')
        }
        return @('unrelated-avd', 'OK')
    }
    if (($Arguments[2..3] -join ' ') -eq 'emu kill') {
        if ($script:killHangs) { throw [TimeoutException]::new('console did not answer') }
        $script:stoppedEmulators += $Arguments[1]
        if (!$script:killIgnored) {
            $script:devices = @($script:devices | Where-Object { $_ -notmatch '^emulator-5580\s' })
        }
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
    Assert-Equal $script:adbServerStarts 3 'starts the adb server before every shutdown'
    Write-Host 'PASS: online shutdown keeps graceful adb targeting'

    $script:devices = @('List of devices attached', 'emulator-5554 device', 'emulator-5580 device')
    $script:consoleHangs = $true
    Write-HostState
    Stop-ScoutEmulator
    Assert-Equal $script:killedHosts.Count 2 'stops the owned tree when the console does not answer'
    Assert-Equal $script:stoppedEmulators.Count 1 'does not send kill to an unidentified emulator'
    Assert-Equal (Test-Path -LiteralPath $hostStatePath) $false 'cleans state after forced shutdown'
    $script:consoleHangs = $false
    $script:killHangs = $true
    Write-HostState
    Stop-ScoutEmulator
    Assert-Equal $script:killedHosts.Count 3 'stops the owned tree when kill does not answer'
    $script:killHangs = $false
    Write-Host 'PASS: unresponsive console falls back to the verified host process tree'

    $script:killIgnored = $true
    Write-HostState
    Stop-ScoutEmulator
    Assert-Equal ($script:stoppedEmulators -join ',') 'emulator-5580,emulator-5580' 'asks the console to stop first'
    Assert-Equal $script:killedHosts.Count 4 'stops the owned tree when the emulator keeps running'
    Assert-Fails { Stop-ScoutEmulator } 'did not stop'
    Assert-Equal $script:killedHosts.Count 4 'does not kill processes without verified ownership'
    $script:killIgnored = $false
    $script:devices = @('List of devices attached', 'emulator-5554 device')
    Write-Host 'PASS: an emulator that ignores kill is stopped only through verified ownership'

    $script:hostWaitResult = $false
    Write-HostState
    Assert-Fails { Stop-ScoutEmulator } 'has not finished stopping'
    Assert-Equal (Test-Path -LiteralPath $hostStatePath) $true 'keeps state when shutdown fails'
    Write-Host 'PASS: failed shutdown preserves ownership state for retry'
} finally {
    if (Test-Path -LiteralPath $hostStatePath) { Remove-Item -LiteralPath $hostStatePath }
}

$listing = @(
    'INSTRUMENTATION_STATUS: class=com.example.FirstTest', 'INSTRUMENTATION_STATUS: current=1',
    'INSTRUMENTATION_STATUS: stream=', 'com.example.FirstTest:', 'INSTRUMENTATION_STATUS: test=opensScreen',
    'INSTRUMENTATION_STATUS_CODE: 1', 'INSTRUMENTATION_STATUS: class=com.example.FirstTest',
    'INSTRUMENTATION_STATUS: stream=.', 'INSTRUMENTATION_STATUS: test=opensScreen', 'INSTRUMENTATION_STATUS_CODE: 0',
    'INSTRUMENTATION_STATUS: class=com.example.SecondTest', 'INSTRUMENTATION_STATUS: test=savesPreference',
    'INSTRUMENTATION_STATUS_CODE: 1', 'INSTRUMENTATION_STATUS: class=com.example.SecondTest',
    'INSTRUMENTATION_STATUS: test=savesPreference', 'INSTRUMENTATION_STATUS_CODE: 0',
    'INSTRUMENTATION_RESULT: stream=', 'OK (2 tests)', 'INSTRUMENTATION_CODE: -1'
)
$expectedTests = 'com.example.FirstTest#opensScreen,com.example.SecondTest#savesPreference'
Assert-Equal ((ConvertFrom-InstrumentationListing $listing) -join ',') $expectedTests 'reads tests from a dry-run listing'
$script:gradleCalls = @()
Set-Item Function:Invoke-Gradle {
    param([string[]]$Tasks)
    $script:gradleCalls += ($Tasks -join ' ')
    '> Task :app:installInstr'
    'BUILD SUCCESSFUL in 9s'
}
Set-Item Function:Invoke-Checked {
    param([string]$Program, [string[]]$Arguments, [int]$TimeoutSeconds)
    Assert-Equal ($Arguments -join ' ') ('-s emulator-5580 shell am instrument -w -r -e log true ' +
        'com.github.wrager.sbgscout.instr.test/androidx.test.runner.AndroidJUnitRunner') 'lists tests without running them'
    if ($TimeoutSeconds -le 0) { throw 'Unbounded test listing' }
    return $listing
}
$env:ANDROID_SERIAL = 'emulator-5580'
Assert-Equal ((Get-InstrumentationTests) -join ',') $expectedTests 'lists the installed tests'
Assert-Equal ($script:gradleCalls -join ';') 'installInstr installInstrAndroidTest' 'installs the app and test APKs first'
Write-Host 'PASS: full test runs list tests through a dry run'

$realRepoRoot = $repoRoot
$repoRoot = Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString())
try {
    $resultsDirectory = Join-Path $repoRoot 'app\build\outputs\androidTest-results\connected\instr'
    New-Item -ItemType Directory -Force -Path $resultsDirectory | Out-Null
    Set-Content -LiteralPath (Join-Path $resultsDirectory 'TEST-sbg-scout-api33(AVD) - 13.xml') -Value (
        "<?xml version='1.0' encoding='UTF-8' ?>`n" +
        '<testsuites tests="6" failures="0" errors="0" skipped="0"><testsuite name="A" tests="6" /></testsuites>')
    Assert-Equal (Get-ConnectedTestCount) 6 'counts executed tests from the JUnit report'
} finally {
    Remove-Item -LiteralPath $repoRoot -Recurse -Force
    $repoRoot = $realRepoRoot
}
Write-Host 'PASS: executed tests are counted from the JUnit report'

$shardBaseMegabytes = 7000
$shardTestMegabytes = 450
$script:freeCommit = 9700
Set-Item Function:Get-FreeCommitMegabytes { $script:freeCommit }
Assert-ShardMemory 6
Assert-Fails { Assert-ShardMemory 7 } 'Only 9700 MB of Windows commit memory is free, a shard of 7 tests needs about 10150 MB'
$script:freeCommit = 6999
Assert-Fails { Assert-ShardMemory 0 } 'needs about 7000 MB'
$script:freeCommit = 100000
Write-Host 'PASS: a shard starts only with enough free commit memory'

$script:events = @()
$script:failingTest = $null
$script:executedShortfall = 0
$script:lastSelection = @()
$script:listedTests = @('A#one', 'A#two', 'B#one', 'B#two', 'C#one', 'C#two', 'D#one')
Set-Item Function:Stop-ScoutEmulator { $script:events += 'stop' }
Set-Item Function:Start-ScoutEmulator { $script:events += 'start' }
Set-Item Function:Assert-TestDevice { $script:events += 'assert' }
Set-Item Function:Get-InstrumentationTests { $script:events += 'list'; $script:listedTests }
Set-Item Function:Get-ConnectedTestCount { $script:lastSelection.Count - $script:executedShortfall }
Set-Item Function:Invoke-Gradle {
    param([string[]]$Tasks)
    Assert-Equal $Tasks[0] 'connectedInstrAndroidTest' 'runs instrumentation tests'
    Assert-Equal $Tasks.Count 3 'passes only the test selection'
    Assert-Equal $Tasks[1] '-Pandroid.testInstrumentationRunnerArguments.filter=com.github.wrager.sbgscout.e2e.infra.SelectedTestsFilter' 'selects tests with the filter'
    $selection = $Tasks[2] -replace '^-Pandroid\.testInstrumentationRunnerArguments\.selectedTests=', ''
    $script:lastSelection = @($selection -split ':')
    $script:events += "gradle $selection"
    if ($script:failingTest -and $script:lastSelection -contains $script:failingTest) { throw 'There were failing tests.' }
}
$TestsPerShard = 3
Invoke-ShardedTests
Assert-Equal ($script:events -join ';') ('stop;start;assert;list;stop;start;assert;gradle A#one:A#two:B#one;stop;' +
    'start;assert;gradle B#two:C#one:C#two;stop;start;assert;gradle D#one;stop') 'runs equal shards on fresh emulators'
$script:events = @()
$script:failingTest = 'C#one'
Assert-Fails { Invoke-ShardedTests } 'Test shard 2/3 failed: There were failing tests'
Assert-Equal ($script:events -join ';') ('stop;start;assert;list;stop;start;assert;gradle A#one:A#two:B#one;stop;' +
    'start;assert;gradle B#two:C#one:C#two;stop') 'stops the emulator after a failed shard and skips the rest'
$script:failingTest = $null
$script:executedShortfall = 2
Assert-Fails { Invoke-ShardedTests } 'Test shard 1/3 failed: ran 1 of 3 tests'
$script:executedShortfall = 0
$script:events = @()
Set-Item Function:Assert-ShardMemory {
    param([int]$TestCount)
    $script:events += "memory $TestCount"
    if ($script:events -like 'gradle *') { throw 'Only 5000 MB of Windows commit memory is free' }
}
Assert-Fails { Invoke-ShardedTests } 'Test shard 2/3 failed: Only 5000 MB'
Assert-Equal ($script:events -join ';') ('stop;memory 0;start;assert;list;stop;memory 3;start;assert;gradle A#one:A#two:B#one;stop;' +
    'memory 3;stop') 'checks memory before every emulator start and does not boot without it'
Set-Item Function:Assert-ShardMemory { param([int]$TestCount) }
$script:events = @()
$script:listedTests = @()
Assert-Fails { Invoke-ShardedTests } 'No instrumentation tests'
Assert-Equal ($script:events -join ';') 'stop;start;assert;list;stop' 'stops the listing emulator when nothing can run'
Write-Host 'PASS: full test runs restart the emulator between equal shards and verify executed tests'
# The negative native-command test deliberately leaves LASTEXITCODE=7.
# GitHub's PowerShell runner otherwise propagates it after this script succeeds.
exit 0
