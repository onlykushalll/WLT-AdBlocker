@echo off
setlocal

set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot
set ANDROID_NDK_HOME=C:\Users\Default.L-HCG-9FVVGS3\AppData\Local\Android\Sdk\ndk\27.0.12077973
set ANDROID_HOME=C:\Users\Default.L-HCG-9FVVGS3\AppData\Local\Android\Sdk
set GOROOT=C:\Program Files\Go
set GOPATH=C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\gopath
set PATH=C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\gobin;C:\Users\Default.L-HCG-9FVVGS3\go\bin;%JAVA_HOME%\bin;%PATH%

echo === Create gobind wrapper (wlt-gobind.exe called AS gobind.exe) ===
copy /Y C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\gobin\wlt-gobind.exe C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\gobin\gobind.exe > nul 2>&1
copy /Y C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\gobin\wlt-gomobile.exe C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\gobin\gomobile.exe > nul 2>&1

echo BUILD_START > C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt
echo %DATE% %TIME% >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt

echo === GOMOBILE INIT ===
cd /d C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\wlt-core
C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\gobin\gomobile.exe init 2>&1

echo === GOMOBILE BIND ===
C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\gobin\gomobile.exe bind -target=android/arm64,android/arm,android/386,android/amd64 -androidapi=21 -o ../android/app/libs/wlt.aar . > C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\aar-log.txt 2>&1
if errorlevel 1 (
  echo AAR_FAILED >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt
  type C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\aar-log.txt >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt
  goto :end
)
echo AAR_SUCCESS >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt
echo %DATE% %TIME% >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt

echo === GRADLE ===
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
echo %DATE% %TIME% >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt
echo ALL_DONE >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt
