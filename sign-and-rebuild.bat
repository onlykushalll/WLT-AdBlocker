@echo off
setlocal

echo STEP1_SIGN > C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt
powershell -ExecutionPolicy Bypass -NoProfile -File C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\sign-gomobile.ps1 > C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\sign-log.txt 2>&1
echo SIGN_DONE >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt

set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot
set ANDROID_NDK_HOME=C:\Users\Default.L-HCG-9FVVGS3\AppData\Local\Android\Sdk\ndk\27.0.12077973
set ANDROID_HOME=C:\Users\Default.L-HCG-9FVVGS3\AppData\Local\Android\Sdk
set PATH=%JAVA_HOME%\bin;C:\Users\Default.L-HCG-9FVVGS3\go\bin;%PATH%

echo STEP2_GOMOBILE >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt
cd /d C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\wlt-core
gomobile bind -target=android/arm64,android/arm,android/386,android/amd64 -o ../android/app/libs/wlt.aar ./... > C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\aar-log.txt 2>&1
if errorlevel 1 (
  echo AAR_BUILD_FAILED >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt
  goto :end
)
echo AAR_BUILD_SUCCESS >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt

echo STEP3_GRADLE >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt
cd /d C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\android
call gradlew.bat :app:assembleDebug --no-daemon --console=plain > C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\build-log.txt 2>&1
if errorlevel 1 (
  echo APK_BUILD_FAILED >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt
  goto :end
)
echo APK_BUILD_SUCCESS >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt

copy /Y app\build\outputs\apk\debug\app-debug.apk C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\WLT-Adblocker-debug.apk > nul
echo APK_COPIED >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt

:end
echo ALL_DONE >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt
