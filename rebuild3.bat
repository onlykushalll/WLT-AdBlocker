@echo off
setlocal

echo COPY_GO > C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt
mkdir C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\gobin 2>nul
copy /Y C:\Users\Default.L-HCG-9FVVGS3\go\bin\gomobile.exe C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\gobin\ > nul
copy /Y C:\Users\Default.L-HCG-9FVVGS3\go\bin\gobind.exe C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\gobin\ > nul
copy /Y C:\Users\Default.L-HCG-9FVVGS3\go\bin\go.exe C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\gobin\ > nul
copy /Y C:\Users\Default.L-HCG-9FVVGS3\go\bin\gofmt.exe C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\gobin\ > nul
copy /Y C:\Users\Default.L-HCG-9FVVGS3\go\pkg\tool\windows_amd64\cgo.exe C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\gobin\ > nul
copy /Y C:\Users\Default.L-HCG-9FVVGS3\go\pkg\tool\windows_amd64\compile.exe C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\gobin\ > nul
copy /Y C:\Users\Default.L-HCG-9FVVGS3\go\pkg\tool\windows_amd64\link.exe C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\gobin\ > nul
copy /Y C:\Users\Default.L-HCG-9FVVGS3\go\pkg\tool\windows_amd64\asm.exe C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\gobin\ > nul
echo COPIED >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt

echo SIGNING >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt
powershell -ExecutionPolicy Bypass -NoProfile -File C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\sign-gobin.ps1 > C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\sign-log.txt 2>&1
echo SIGNED >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt

set GOROOT=C:\Users\Default.L-HCG-9FVVGS3\go
set GOPATH=C:\Users\Default.L-HCG-9FVVGS3\go
set PATH=C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\gobin;C:\Users\Default.L-HCG-9FVVGS3\go\bin;%PATH%
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot
set ANDROID_NDK_HOME=C:\Users\Default.L-HCG-9FVVGS3\AppData\Local\Android\Sdk\ndk\27.0.12077973
set ANDROID_HOME=C:\Users\Default.L-HCG-9FVVGS3\AppData\Local\Android\Sdk

echo GOMOBILE >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt
cd /d C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\wlt-core
C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\gobin\gomobile.exe bind -target=android/arm64,android/arm,android/386,android/amd64 -androidapi=21 -o ../android/app/libs/wlt.aar . > C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\aar-log.txt 2>&1
if errorlevel 1 (
  echo AAR_FAILED >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt
  type C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\aar-log.txt >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt
  goto :end
)
echo AAR_SUCCESS >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt

echo GRADLE >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt
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
