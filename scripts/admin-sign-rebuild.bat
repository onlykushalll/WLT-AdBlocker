@echo off
echo Requesting admin privileges for cert store access...
echo This will add the WLT code signing cert to Trusted Root and Trusted Publishers.
echo.
echo A UAC prompt may appear — please click YES to allow.
echo.

REM Check if we have admin
net session >nul 2>&1
if %errorlevel% neq 0 (
  echo Not admin — re-launching elevated...
  powershell -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)

echo Admin privileges confirmed!
echo.
echo === ADDING CERT TO TRUSTED STORES ===
powershell -ExecutionPolicy Bypass -NoProfile -Command "
  $cert = Get-ChildItem -Path Cert:\CurrentUser\My -CodeSigningCert | Where-Object { $_.Subject -match 'WLT' } | Select-Object -First 1;
  if (-not $cert) {
    $cert = New-SelfSignedCertificate -Type CodeSigningCert -Subject 'CN=WLT-Adblocker Code Signing' -CertStoreLocation 'Cert:\LocalMachine\My' -KeyUsage DigitalSignature -KeyAlgorithm RSA -KeyLength 2048 -NotAfter (Get-Date).AddYears(5);
  };
  Write-Host \"Using cert: $($cert.Subject)\";
  foreach ($storeName in @('Root', 'TrustedPublisher')) {
    $store = New-Object System.Security.Cryptography.X509Certificates.X509Store($storeName, 'LocalMachine');
    $store.Open('ReadWrite');
    $existing = $store.Certificates | Where-Object { $_.Thumbprint -eq $cert.Thumbprint };
    if (-not $existing) { $store.Add($cert); Write-Host \"Added to LocalMachine\$storeName\" } else { Write-Host \"Already in LocalMachine\$storeName\" };
    $store.Close();
  };
  $goBin = 'C:\Users\Default.L-HCG-9FVVGS3\go\bin';
  foreach ($exe in @('gomobile.exe', 'gobind.exe', 'go.exe', 'gofmt.exe')) {
    $path = Join-Path $goBin $exe;
    if (Test-Path $path) {
      $result = Set-AuthenticodeSignature -FilePath $path -Certificate $cert -TimestampServer 'http://timestamp.digicert.com';
      Write-Host \"Signed $exe : $($result.Status)\";
    };
  };
  $goRoot = 'C:\Users\Default.L-HCG-9FVVGS3\go';
  foreach ($path in @('$goRoot\pkg\tool\windows_amd64\cgo.exe', '$goRoot\pkg\tool\windows_amd64\compile.exe', '$goRoot\pkg\tool\windows_amd64\link.exe', '$goRoot\pkg\tool\windows_amd64\asm.exe', '$goRoot\pkg\tool\windows_amd64\buildid.exe', '$goRoot\pkg\tool\windows_amd64\pack.exe')) {
    $resolvedPath = $ExecutionContext.InvokeCommand.ExpandString($path);
    if (Test-Path $resolvedPath) {
      $result = Set-AuthenticodeSignature -FilePath $resolvedPath -Certificate $cert -TimestampServer 'http://timestamp.digicert.com';
      Write-Host \"Signed $(Split-Path $resolvedPath -Leaf) : $($result.Status)\";
    };
  };
  Write-Host 'ADMIN_SIGN_DONE';
"
echo SIGN_DONE

echo === GOMOBILE BIND ===
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot
set ANDROID_NDK_HOME=C:\Users\Default.L-HCG-9FVVGS3\AppData\Local\Android\Sdk\ndk\27.0.12077973
set ANDROID_HOME=C:\Users\Default.L-HCG-9FVVGS3\AppData\Local\Android\Sdk
set PATH=%JAVA_HOME%\bin;C:\Users\Default.L-HCG-9FVVGS3\go\bin;%PATH%

cd /d C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\wlt-core
gomobile bind -target=android/arm64,android/arm,android/386,android/amd64 -androidapi=21 -o ../android/app/libs/wlt.aar . > C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\aar-log.txt 2>&1
if errorlevel 1 (
  echo AAR_FAILED > C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt
  type C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\aar-log.txt >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt
  goto :end
)
echo AAR_SUCCESS > C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt

echo === GRADLE BUILD ===
cd /d C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\android
call gradlew.bat :app:assembleDebug --no-daemon --console=plain > C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\build-log.txt 2>&1
if errorlevel 1 (
  echo APK_FAILED >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt
  goto :end
)
echo APK_SUCCESS >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt
copy /Y app\build\outputs\apk\debug\app-debug.apk C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\WLT-Adblocker-debug.apk > nul
echo APK_COPIED >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt

:end
echo ALL_DONE >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt
echo.
echo Build complete! Check final-status.txt for result.
pause
