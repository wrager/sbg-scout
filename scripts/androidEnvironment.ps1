#requires -Version 5.1
# Dot-source to use the same tools in PowerShell, Codex and Claude Code.
param([string]$ToolsRoot = (Join-Path $env:USERPROFILE 'AppData\Local\SBGScout'))

$ToolsRoot = [IO.Path]::GetFullPath($ToolsRoot)
$env:JAVA_HOME = Join-Path $ToolsRoot 'jdk21'
$env:ANDROID_HOME = Join-Path $ToolsRoot 'sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = Join-Path $ToolsRoot 'gradle'
# USERPROFILE is stable even when a Windows sandbox redirects LOCALAPPDATA.
$env:ANDROID_USER_HOME = Join-Path $ToolsRoot 'android-user'
$env:ANDROID_EMULATOR_HOME = $env:ANDROID_USER_HOME
$env:ANDROID_AVD_HOME = Join-Path $ToolsRoot 'avd'
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\emulator;$env:ANDROID_HOME\cmdline-tools\23.0\bin;$env:PATH"
foreach ($directory in @($env:GRADLE_USER_HOME, $env:ANDROID_USER_HOME, $env:ANDROID_AVD_HOME)) {
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
}
