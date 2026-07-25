# WLT-Adblocker APK build script
$ErrorActionPreference = 'Stop'
$root = 'C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker'
$android = "$root\android"

Write-Host '=== WLT-Adblocker APK Build ==='
Write-Host "Root: $root"
Write-Host "Time: $(Get-Date)"

# Set environment
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'
if (-not (Test-Path $env:JAVA_HOME)) {
  # Try alternate path
  $env:JAVA_HOME = (Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory | Select-Object -First 1).FullName
}
$env:ANDROID_HOME = 'C:\Users\Default.L-HCG-9FVVGS3\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

Write-Host "JAVA_HOME: $env:JAVA_HOME"
Write-Host "ANDROID_HOME: $env:ANDROID_HOME"

# Verify Java
Write-Host '=== Java version ==='
& java -version 2>&1

# Build
Set-Location $android
Write-Host '=== Running Gradle assembleDebug ==='
& .\gradlew.bat :app:assembleDebug --no-daemon --console=plain 2>&1 | Tee-Object -FilePath "$root\build-log.txt"

$apk = "$android\app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $apk) {
  $size = (Get-Item $apk).Length
  $sizeMB = [math]::Round($size / 1MB, 2)
  Write-Host "BUILD SUCCESS: APK is $sizeMB MB at $apk"
  # Copy to Desktop root
  Copy-Item $apk "$root\WLT-Adblocker-debug.apk" -Force
  Write-Host "Copied to: $root\WLT-Adblocker-debug.apk"
} else {
  Write-Host 'BUILD FAILED: APK not found'
  exit 1
}
