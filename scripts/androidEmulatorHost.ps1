#requires -Version 5.1
# Detached host: do not inherit an agent's captured stdout pipe into Emulator.
param(
    [string]$ToolsRoot = (Join-Path $env:USERPROFILE 'AppData\Local\SBGScout'),
    [switch]$ShowWindow
)

. (Join-Path $PSScriptRoot 'androidEnvironment.ps1') -ToolsRoot $ToolsRoot
$logsDirectory = Join-Path $ToolsRoot 'logs'
New-Item -ItemType Directory -Force -Path $logsDirectory | Out-Null
$arguments = @('-avd', 'sbg-scout-api33', '-port', '5580', '-memory', '2048', '-no-snapshot',
    '-gpu', 'swiftshader', '-no-boot-anim', '-noaudio', '-camera-back', 'none', '-camera-front', 'none')
if (!$ShowWindow) { $arguments += '-no-window' }
& "$env:ANDROID_HOME\emulator\emulator.exe" @arguments `
    1> (Join-Path $logsDirectory 'emulator.stdout.log') `
    2> (Join-Path $logsDirectory 'emulator.stderr.log')
exit $LASTEXITCODE
