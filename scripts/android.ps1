#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('doctor', 'build', 'verify', 'emulator', 'run', 'test', 'logs', 'stop')]
    [string]$Command = 'doctor',
    [string]$ToolsRoot = (Join-Path $env:USERPROFILE 'AppData\Local\SBGScout'),
    [string]$TestClass = 'com.github.wrager.sbgscout.e2e.flows.smoke.GameLoadsSmokeTest',
    [switch]$AllTests,
    [ValidateRange(1, 64)][int]$Shards = 16,
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
$adbTimeoutSeconds = 30
$consoleTimeoutSeconds = 10
$emulatorStopSeconds = 30

function Invoke-Checked {
    param([string]$Program, [string[]]$Arguments, [int]$TimeoutSeconds = 0)
    if ($TimeoutSeconds -le 0) {
        & $Program @Arguments
        if ($LASTEXITCODE -ne 0) { throw "$Program failed with exit code $LASTEXITCODE" }
        return
    }
    $process = New-Object Diagnostics.Process
    $process.StartInfo.FileName = $Program
    $process.StartInfo.Arguments = ($Arguments | ForEach-Object {
            if ($_ -match '[\s"]') { '"' + $_.Replace('"', '\"') + '"' } else { $_ }
        }) -join ' '
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    [void]$process.Start()
    $output = $process.StandardOutput.ReadToEndAsync()
    $errors = $process.StandardError.ReadToEndAsync()
    <# Вывод закрывается, только когда его закроют и потомки процесса: запущенный
       клиентом сервер adb держит его бессрочно, поэтому чтение тоже ограничено.
       🤖 Generated with Claude Opus 5.5 #>
    $finished = $process.WaitForExit($TimeoutSeconds * 1000) -and
        [Threading.Tasks.Task]::WaitAll([Threading.Tasks.Task[]]@($output, $errors), 5000)
    if (!$finished) {
        if (!$process.HasExited) { $process.Kill() }
        throw [TimeoutException]::new("$Program $($process.StartInfo.Arguments) did not finish within $TimeoutSeconds seconds.")
    }
    if ($process.ExitCode -ne 0) {
        throw "$Program failed with exit code $($process.ExitCode): $($errors.Result.Trim())"
    }
    $output.Result -split '\r?\n' | Where-Object { $_ }
}

function Start-AdbServer {
    <# Сервер adb переживает команду: запущенный из процесса с перенаправленным выводом,
       он наследует pipe, и вызывающий агент ждёт его завершения. Поэтому клиент
       запускается через ShellExecute без перенаправления и без -Wait, который ждёт
       и дочерний сервер.
       🤖 Generated with Claude Opus 5.5 #>
    $process = Start-Process -FilePath $adb -ArgumentList 'start-server' -WindowStyle Hidden -PassThru
    if (!$process.WaitForExit(30000)) { throw 'adb start-server did not finish within 30 seconds.' }
    if ($process.ExitCode -ne 0) { throw "adb start-server failed with exit code $($process.ExitCode)" }
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
    $devices = @(Invoke-Checked $adb @('devices') -TimeoutSeconds $adbTimeoutSeconds)
    foreach ($device in $devices) {
        if ($device -match '^(emulator-\d+)\s+device$') {
            $serial = $Matches[1]
            try {
                $name = @(Invoke-Checked $adb @('-s', $serial, 'emu', 'avd', 'name') -TimeoutSeconds $consoleTimeoutSeconds)
            } catch [TimeoutException] {
                Write-Host "$serial emulator console did not respond; its AVD is unknown."
                continue
            }
            if ($name -contains $avdName) { return $serial }
        }
    }
}

function Stop-EmulatorByConsole {
    param([string]$Serial)
    try {
        Invoke-Checked $adb @('-s', $Serial, 'emu', 'kill') -TimeoutSeconds $consoleTimeoutSeconds | Out-Host
    } catch [TimeoutException] {
        Write-Host "$Serial emulator console did not accept kill."
        return $false
    }
    $deadline = (Get-Date).AddSeconds($emulatorStopSeconds)
    do {
        $remaining = @(Invoke-Checked $adb @('devices') -TimeoutSeconds $adbTimeoutSeconds) -match "^$Serial\s"
        if (!$remaining) { return $true }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    return $false
}

function Start-ScoutEmulator {
    $process = $null
    Start-AdbServer
    $serial = Find-ScoutEmulator
    if (!$serial) {
        Invoke-Checked $emulator @('-accel-check') | Out-Host
        if (!(Test-Path -LiteralPath (Join-Path $env:ANDROID_AVD_HOME "$avdName.ini"))) {
            throw 'AVD missing. Run scripts/setupAndroid.ps1 first.'
        }
        # The fixed port prevents accidentally installing to another emulator or a real phone.
        $serial = 'emulator-5580'
        $devices = @(Invoke-Checked $adb @('devices'))
        if ($devices -match '^emulator-5580\s') {
            throw 'Emulator port 5580 is occupied by another device or by an unresponsive emulator; try stop.'
        }
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
                $name = @(Invoke-Checked $adb @('-s', $serial, 'emu', 'avd', 'name') -TimeoutSeconds $consoleTimeoutSeconds)
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

function Invoke-ShardedTests {
    <# Рендерер SwiftShader в QEMU не освобождает память после каждого теста, и на всём
       наборе эмулятор исчерпывает память Windows. Поэтому каждая часть набора идёт
       на свежем процессе эмулятора, который останавливается и при падении тестов.
       🤖 Generated with Claude Opus 5.5 #>
    Stop-ScoutEmulator
    for ($shard = 0; $shard -lt $Shards; $shard++) {
        Write-Host "Test shard $($shard + 1)/$Shards"
        try {
            Start-ScoutEmulator
            Assert-TestDevice
            Invoke-Gradle @('connectedInstrAndroidTest',
                "-Pandroid.testInstrumentationRunnerArguments.numShards=$Shards",
                "-Pandroid.testInstrumentationRunnerArguments.shardIndex=$shard")
        } catch {
            throw "Test shard $($shard + 1)/$Shards failed: $_"
        } finally {
            Stop-ScoutEmulator
        }
    }
}

function Stop-ScoutEmulator {
    Start-AdbServer
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
    $stopped = $serial -and (Stop-EmulatorByConsole $serial)
    if (!$stopped) {
        if ($hostProcess) {
            <# Без опознанного AVD (сбой загрузки, зависшая консоль, emu kill без эффекта)
               останавливается только подтверждённый хост и его дочерние процессы, включая QEMU.
               🤖 Generated with Claude Opus 5.5 #>
            Write-Host "Stopping unresponsive $avdName (host PID $($hostProcess.Id))."
            Invoke-Checked (Join-Path $env:SystemRoot 'System32\taskkill.exe') @(
                '/PID', $hostProcess.Id.ToString(), '/T', '/F'
            ) | Out-Host
        } elseif ($serial) {
            throw "$serial did not stop, and there is no owned host process to terminate."
        } else {
            Write-Host "$avdName has no online device or owned host process."
        }
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
            Invoke-Checked 'powershell.exe' @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', 'scripts/checkPowerShell.ps1')
            Invoke-Checked 'node' @('scripts/syncAgentSkills.mjs', '--check')
            Invoke-Checked 'node' @('--test', 'scripts/syncAgentSkills.test.mjs', 'scripts/checkRelease.test.mjs', 'scripts/updateBundledSvp.test.mjs', 'scripts/planChecks.test.mjs')
            Invoke-Checked 'node' @('scripts/checkRelease.mjs')
            Invoke-Checked 'node' @('scripts/checkSyntax.mjs')
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
            if ($AllTests) {
                Invoke-ShardedTests
            } else {
                Start-ScoutEmulator
                Assert-TestDevice
                Invoke-Gradle @('connectedInstrAndroidTest', "-Pandroid.testInstrumentationRunnerArguments.class=$TestClass")
            }
        }
        'logs' {
            Start-AdbServer
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
