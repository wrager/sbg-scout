#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$ToolsRoot = (Join-Path $env:USERPROFILE 'AppData\Local\SBGScout')
)

$ErrorActionPreference = 'Stop'
$ToolsRoot = [IO.Path]::GetFullPath($ToolsRoot)
$downloads = Join-Path $ToolsRoot 'downloads'
New-Item -ItemType Directory -Force -Path $downloads | Out-Null
# Git Bash puts GNU tar first in PATH; it treats "C:" as a remote host.
$curl = Join-Path $env:SystemRoot 'System32\curl.exe'
$tar = Join-Path $env:SystemRoot 'System32\tar.exe'

function Install-Archive {
    param($Name, $Url, $Hash, $Algorithm, $Destination, $Executable)
    if (Test-Path -LiteralPath (Join-Path $Destination $Executable)) {
        Write-Host "$Name already installed: $Destination"
        return
    }
    $archive = Join-Path $downloads "$Name.zip"
    if (!(Test-Path -LiteralPath $archive) -or
        (Get-FileHash -LiteralPath $archive -Algorithm $Algorithm).Hash -ne $Hash) {
        Write-Host "Downloading $Name..."
        & $curl --fail --silent --show-error --location --retry 3 --output $archive $Url
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $Name" }
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm $Algorithm).Hash -ne $Hash) {
        throw "Checksum mismatch: $archive"
    }
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    & $tar -xf $archive -C $Destination --strip-components 1
    if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath (Join-Path $Destination $Executable))) {
        throw "Extraction failed: $Name"
    }
    # Only remove the exact verified download, never another user's cache.
    Remove-Item -LiteralPath $archive
}

Install-Archive 'jdk21' `
    'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip' `
    'f9d6e191ab098c0d416e7d588a24420a8621cd2f4720dab2459b8b7b2d2d8b4e' `
    'SHA256' (Join-Path $ToolsRoot 'jdk21') 'bin\java.exe'
Install-Archive 'jdk17' `
    'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip' `
    'e53a79c3c3d86865bd7e787903884331068e71321714ffd44f145785affc7cb0' `
    'SHA256' (Join-Path $ToolsRoot 'jdk17') 'bin\java.exe'
Install-Archive 'cmdline-tools-23' `
    'https://dl.google.com/android/repository/commandlinetools-win-16111833_latest.zip' `
    '57d04f2d75eb8e8fffc5000a987e5de4b5a63e9d' `
    'SHA1' (Join-Path $ToolsRoot 'sdk\cmdline-tools\23.0') 'bin\sdkmanager.bat'

. (Join-Path $PSScriptRoot 'androidEnvironment.ps1') -ToolsRoot $ToolsRoot
$android = Join-Path $env:ANDROID_HOME 'cmdline-tools\23.0\bin\android.exe'
# Command-line Tools 23 ships Android CLI. Call it directly: the sdkmanager.bat
# compatibility shim can split semicolon package IDs on Windows.
1..30 | ForEach-Object { 'y' } | & $android "--sdk=$env:ANDROID_HOME" sdk install `
    'platform-tools' 'platforms/android-37.0@2.0.0' 'build-tools/36.0.0' 'emulator' 'system-images/android-33/default/x86_64@2.0.0'
if ($LASTEXITCODE -ne 0) { throw "SDK installation failed ($LASTEXITCODE)" }
foreach ($package in @('platform-tools', 'emulator', 'platforms\android-37.0', 'build-tools\36.0.0', 'system-images\android-33\default\x86_64')) {
    if (!(Test-Path -LiteralPath (Join-Path $env:ANDROID_HOME "$package\source.properties"))) {
        throw "SDK installer did not install $package"
    }
}

$avdName = 'sbg-scout-api33'
$avdmanager = Join-Path $env:ANDROID_HOME 'cmdline-tools\23.0\bin\avdmanager.bat'
if (!(Test-Path -LiteralPath (Join-Path $env:ANDROID_AVD_HOME "$avdName.ini"))) {
    'no' | & $avdmanager create avd --name $avdName --package 'system-images;android-33;default;x86_64' --device pixel_4
    if ($LASTEXITCODE -ne 0) { throw 'AVD creation failed' }
}

Write-Host "Installed in $ToolsRoot"
Write-Host 'Next: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/android.ps1 doctor'
