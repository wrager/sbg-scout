#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('doctor', 'build', 'verify', 'emulator', 'run', 'test', 'logs', 'stop')]
    [string]$Command = 'doctor',
    [string]$ToolsRoot = (Join-Path $env:USERPROFILE 'AppData\Local\SBGScout'),
    [string]$TestClass = 'com.github.wrager.sbgscout.e2e.flows.smoke.GameLoadsSmokeTest',
    [switch]$AllTests,
    [switch]$ShowWindow,
    [switch]$Offline,
    [ValidateRange(30, 600)][int]$BootTimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'androidEnvironment.ps1') -ToolsRoot $ToolsRoot
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$emulator = Join-Path $env:ANDROID_HOME 'emulator\emulator.exe'
$avdName = 'sbg-scout-api33'
$logsDirectory = Join-Path $ToolsRoot 'logs'
$hostStatePath = Join-Path $logsDirectory 'emulator-host.json'

function Invoke-Checked {
    param([string]$Program, [string[]]$Arguments)
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed with exit code $LASTEXITCODE" }
}

function Invoke-Gradle {
    param([string[]]$Tasks)
    $javaPaths = @($env:JAVA_HOME, (Join-Path $ToolsRoot 'jdk17')) -join ','
    # Keep this property on the command line; never write machine paths to the repository.
    Invoke-Checked (Join-Path $repoRoot 'gradlew.bat') ($Tasks + @(
        "-Porg.gradle.java.installations.paths=$($javaPaths.Replace('\', '/'))",
        '-Pkotlin.compiler.execution.strategy=in-process',
        '--console=plain', '--max-workers=2', '--no-daemon'
    ))
}

function Find-ScoutEmulator {
    $devices = @(Invoke-Checked $adb @('devices'))
    foreach ($device in $devices) {
        if ($device -match '^(emulator-\d+)\s+device$') {
            $serial = $Matches[1]
            $name = @(Invoke-Checked $adb @('-s', $serial, 'emu', 'avd', 'name'))
            if ($name -contains $avdName) { return $serial }
        }
    }
}

function Start-ScoutEmulator {
    $process = $null
    Invoke-Checked $adb @('start-server') | Out-Host
    $serial = Find-ScoutEmulator
    if (!$serial) {
        Invoke-Checked $emulator @('-accel-check') | Out-Host
        if (!(Test-Path -LiteralPath (Join-Path $env:ANDROID_AVD_HOME "$avdName.ini"))) {
            throw 'AVD missing. Run scripts/setupAndroid.ps1 first.'
        }
        # The fixed port prevents accidentally installing to another emulator or a real phone.
        $serial = 'emulator-5580'
        $devices = @(Invoke-Checked $adb @('devices'))
        if ($devices -match '^emulator-5580\s') { throw 'Emulator port 5580 is occupied by another device.' }
        New-Item -ItemType Directory -Force -Path $logsDirectory | Out-Null
        # Without output redirection Start-Process uses ShellExecute on Windows.
        # The detached host owns log redirection so agent output capture can finish
        # while the emulator continues running between commands.
        $hostScript = Join-Path $PSScriptRoot 'androidEmulatorHost.ps1'
        $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $hostScript + '"'),
            '-ToolsRoot', ('"' + $ToolsRoot + '"'))
        if ($ShowWindow) { $arguments += '-ShowWindow' }
        $process = Start-Process -FilePath powershell.exe -ArgumentList $arguments -WindowStyle Hidden -PassThru
        @{ pid = $process.Id; startedUtcTicks = $process.StartTime.ToUniversalTime().Ticks.ToString() } |
            ConvertTo-Json | Set-Content -LiteralPath $hostStatePath
        Write-Host "Starting $avdName (PID $($process.Id)); logs: $logsDirectory"
    }
    $deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
    do {
        if ($process -and $process.HasExited) {
            throw "Emulator host exited before Android booted. See $logsDirectory"
        }
        $devices = @(Invoke-Checked $adb @('devices'))
        if ($devices -match "^$serial\s+device$") {
            $boot = @(Invoke-Checked $adb @('-s', $serial, 'shell', 'getprop', 'sys.boot_completed'))
            if ($boot -contains '1') {
                $name = @(Invoke-Checked $adb @('-s', $serial, 'emu', 'avd', 'name'))
                if ($name -notcontains $avdName) { throw "Unexpected AVD on $serial" }
                $env:ANDROID_SERIAL = $serial
                Write-Host "Ready: $avdName ($serial)"
                return
            }
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Emulator did not boot within $BootTimeoutSeconds seconds. See $logsDirectory"
}

function Assert-TestDevice {
    $api = @(Invoke-Checked $adb @('-s', $env:ANDROID_SERIAL, 'shell', 'getprop', 'ro.build.version.sdk'))
    $webview = (Invoke-Checked $adb @('-s', $env:ANDROID_SERIAL, 'shell', 'dumpsys', 'webviewupdate')) -join "`n"
    if ($api -notcontains '33') { throw 'Tests require Android API 33.' }
    if ($webview -notmatch 'Current WebView package.*\b101\.') {
        Write-Host $webview
        throw 'Tests require WebView 101. Check the API 33 default image.'
    }
    foreach ($setting in 'window_animation_scale', 'transition_animation_scale', 'animator_duration_scale') {
        Invoke-Checked $adb @('-s', $env:ANDROID_SERIAL, 'shell', 'settings', 'put', 'global', $setting, '0') | Out-Host
    }
}

function Stop-ScoutEmulator {
    Invoke-Checked $adb @('start-server') | Out-Host
    $serial = Find-ScoutEmulator
    $hostProcess = $null
    if (Test-Path -LiteralPath $hostStatePath) {
        $state = Get-Content -Raw -LiteralPath $hostStatePath | ConvertFrom-Json
        $candidate = Get-Process -Id $state.pid -ErrorAction SilentlyContinue
        # A saved PID alone is not ownership: Windows can reuse it after exit.
        if ($candidate -and $candidate.StartTime.ToUniversalTime().Ticks.ToString() -eq $state.startedUtcTicks) {
            $hostProcess = $candidate
        }
    }
    if ($serial) {
        Invoke-Checked $adb @('-s', $serial, 'emu', 'kill')
        $deadline = (Get-Date).AddSeconds(30)
        do {
            $remaining = @(Invoke-Checked $adb @('devices')) -match "^$serial\s"
            if (!$remaining) { break }
            Start-Sleep -Seconds 1
        } while ((Get-Date) -lt $deadline)
        if ($remaining) { throw "$serial did not stop within 30 seconds." }
    } elseif ($hostProcess) {
        # After a failed boot there may be no identifiable online AVD. Stop only
        # our verified host and its emulator children, including QEMU.
        Write-Host "Stopping unresponsive $avdName (host PID $($hostProcess.Id))."
        Invoke-Checked (Join-Path $env:SystemRoot 'System32\taskkill.exe') @(
            '/PID', $hostProcess.Id.ToString(), '/T', '/F'
        ) | Out-Host
    } else {
        Write-Host "$avdName has no online device or owned host process."
    }
    # adb disconnects before QEMU releases the AVD and log files.
    if ($hostProcess -and !$hostProcess.WaitForExit(30000)) { throw 'Emulator host has not finished stopping.' }
    if (Test-Path -LiteralPath $hostStatePath) { Remove-Item -LiteralPath $hostStatePath }
}

Push-Location $repoRoot
try {
    foreach ($required in @("$env:JAVA_HOME\bin\java.exe", "$ToolsRoot\jdk17\bin\java.exe", $adb, $emulator)) {
        if (!(Test-Path -LiteralPath $required)) { throw "Missing $required. Run scripts/setupAndroid.ps1 first." }
    }
    switch ($Command) {
        'doctor' {
            Write-Host "Tools: $ToolsRoot"
            Write-Host "SDK: $env:ANDROID_HOME"
            Write-Host "Gradle cache: $env:GRADLE_USER_HOME"
            Invoke-Checked "$env:JAVA_HOME\bin\java.exe" @('-version')
            Invoke-Checked "$ToolsRoot\jdk17\bin\java.exe" @('-version')
            Invoke-Checked $adb @('version')
            Invoke-Checked $emulator @('-list-avds')
            if (!(Test-Path -LiteralPath (Join-Path $env:ANDROID_AVD_HOME "$avdName.ini"))) {
                throw 'AVD missing. Run scripts/setupAndroid.ps1 first.'
            }
            foreach ($package in @('platforms\android-37.0', 'build-tools\36.0.0', 'system-images\android-33\default\x86_64')) {
                if (!(Test-Path -LiteralPath (Join-Path $env:ANDROID_HOME "$package\source.properties"))) {
                    throw "Missing SDK package: $package"
                }
            }
            Invoke-Gradle @('--version')
            Invoke-Checked $emulator @('-accel-check')
        }
        'build' { Invoke-Gradle @('assembleDebug') }
        'verify' {
            Invoke-Checked 'powershell.exe' @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', 'scripts/android.test.ps1')
            Invoke-Checked 'node' @('scripts/syncAgentSkills.mjs', '--check')
            Invoke-Checked 'node' @('--test', 'scripts/syncAgentSkills.test.mjs')
            Invoke-Gradle @('ktlintCheck', 'detekt', 'lintInstr', 'testInstrUnitTest', 'assembleDebug', 'assembleInstr', 'assembleRelease')
        }
        'emulator' { Start-ScoutEmulator }
        'run' {
            Invoke-Gradle @('assembleDebug')
            Start-ScoutEmulator
            Invoke-Checked $adb @('-s', $env:ANDROID_SERIAL, 'install', '-r', 'app/build/outputs/apk/debug/app-debug.apk')
            if ($Offline) {
                Invoke-Checked $adb @('-s', $env:ANDROID_SERIAL, 'shell', 'svc', 'wifi', 'disable')
                Invoke-Checked $adb @('-s', $env:ANDROID_SERIAL, 'shell', 'svc', 'data', 'disable')
                Write-Host 'Emulator network disabled. Enable Wi-Fi in Android settings when you need network access again.'
            }
            Invoke-Checked $adb @('-s', $env:ANDROID_SERIAL, 'shell', 'am', 'start', '-W', '-n',
                'com.github.wrager.sbgscout.debug/com.github.wrager.sbgscout.GameActivity')
        }
        'test' {
            Start-ScoutEmulator
            Assert-TestDevice
            $tasks = @('connectedInstrAndroidTest')
            if (!$AllTests) { $tasks += "-Pandroid.testInstrumentationRunnerArguments.class=$TestClass" }
            Invoke-Gradle $tasks
        }
        'logs' {
            Invoke-Checked $adb @('start-server') | Out-Host
            $serial = Find-ScoutEmulator
            if (!$serial) { throw "$avdName is not running." }
            New-Item -ItemType Directory -Force -Path $logsDirectory | Out-Null
            $logPath = Join-Path $logsDirectory 'logcat.txt'
            Invoke-Checked $adb @('-s', $serial, 'logcat', '-d', '-t', '2000') | Set-Content -LiteralPath $logPath
            Write-Host "Logcat: $logPath"
        }
        'stop' { Stop-ScoutEmulator }
    }
} catch {
    Write-Error $_ -ErrorAction Continue
    exit 1
} finally {
    Pop-Location
}
