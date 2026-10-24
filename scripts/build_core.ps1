<#
.SYNOPSIS
    Builds the native Rust core library and stages binaries for Windows and Android.
#>
param(
    [ValidateSet("Debug", "Release")]
    [string]$Configuration = "Release",
    [switch]$BuildAndroid
)

$ErrorActionPreference = "Stop"

# Ensure Cargo is in PATH
if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
    if (Test-Path "$env:USERPROFILE\.cargo\bin\cargo.exe") {
        $env:PATH = "$env:USERPROFILE\.cargo\bin;$env:PATH"
    } else {
        throw "Cargo is not installed or not in PATH."
    }
}

$rootDir = Split-Path -Parent $PSScriptRoot
$coreDir = Join-Path $rootDir "core"
$windowsStagingDir = Join-Path $rootDir "windows\FileSync.App\Native\libs\x64"
$androidStagingDir = Join-Path $rootDir "android\app\src\main\jniLibs\arm64-v8a"

$cargoProfile = if ($Configuration -eq "Release") { "--release" } else { "" }
$targetSubdir = if ($Configuration -eq "Release") { "release" } else { "debug" }

Write-Host "=== [1/2] Building filesync_core for Windows (x64) ===" -ForegroundColor Cyan
Push-Location $coreDir
try {
    if ($cargoProfile) {
        cargo build --release
    } else {
        cargo build
    }
} finally {
    Pop-Location
}

# Stage Windows DLL
if (-not (Test-Path $windowsStagingDir)) {
    New-Item -ItemType Directory -Path $windowsStagingDir -Force | Out-Null
}
$builtDll = Join-Path $coreDir "target\$targetSubdir\filesync_core.dll"
Copy-Item -Path $builtDll -Destination $windowsStagingDir -Force
Write-Host "Staged Windows DLL -> $windowsStagingDir\filesync_core.dll" -ForegroundColor Green

# Build Android if requested
if ($BuildAndroid) {
    Write-Host "=== [2/2] Building filesync_core for Android (arm64-v8a) ===" -ForegroundColor Cyan
    Push-Location $coreDir
    try {
        cargo ndk -t arm64-v8a build $cargoProfile
        if (-not (Test-Path $androidStagingDir)) {
            New-Item -ItemType Directory -Path $androidStagingDir -Force | Out-Null
        }
        $builtSo = Join-Path $coreDir "target\aarch64-linux-android\$targetSubdir\libfilesync_core.so"
        if (Test-Path $builtSo) {
            Copy-Item -Path $builtSo -Destination $androidStagingDir -Force
            Write-Host "Staged Android .so -> $androidStagingDir\libfilesync_core.so" -ForegroundColor Green
        }
    } finally {
        Pop-Location
    }
}

Write-Host "=== Core Build & Staging Complete ===" -ForegroundColor Green
