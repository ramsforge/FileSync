<#
.SYNOPSIS
    Verifies the development environment for FileSync (Rust, .NET, Android SDK, ADB).
#>

Write-Host "=== Verifying FileSync Development Environment ===" -ForegroundColor Cyan

# 1. Rust
if (Get-Command cargo -ErrorAction SilentlyContinue) {
    $rustv = rustc --version
    Write-Host "[OK] Rust: $rustv" -ForegroundColor Green
} elseif (Test-Path "$env:USERPROFILE\.cargo\bin\cargo.exe") {
    $env:PATH = "$env:USERPROFILE\.cargo\bin;$env:PATH"
    $rustv = & "$env:USERPROFILE\.cargo\bin\rustc.exe" --version
    Write-Host "[OK] Rust: $rustv (added to session PATH)" -ForegroundColor Green
} else {
    Write-Host "[MISSING] Rust toolchain not found." -ForegroundColor Red
}

# 2. .NET SDK
if (Get-Command dotnet -ErrorAction SilentlyContinue) {
    $dotnetv = dotnet --version
    Write-Host "[OK] .NET SDK: $dotnetv" -ForegroundColor Green
} else {
    Write-Host "[MISSING] .NET SDK not found." -ForegroundColor Red
}

# 3. Java
if (Get-Command java -ErrorAction SilentlyContinue) {
    $javav = java -version 2>&1 | Select-Object -First 1
    Write-Host "[OK] Java: $javav" -ForegroundColor Green
} else {
    Write-Host "[WARN] Java not found in PATH." -ForegroundColor Yellow
}

# 4. ADB
if (Get-Command adb -ErrorAction SilentlyContinue) {
    $adbv = adb version | Select-Object -First 1
    Write-Host "[OK] ADB: $adbv" -ForegroundColor Green
} else {
    Write-Host "[INFO] Standalone ADB not in system PATH (will be bundled or accessed via Android SDK)." -ForegroundColor Yellow
}

Write-Host "`nEnvironment verification finished." -ForegroundColor Cyan
