@echo off
cd /d C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\android
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot
set ANDROID_HOME=C:\Users\Default.L-HCG-9FVVGS3\AppData\Local\Android\Sdk
set ANDROID_SDK_ROOT=%ANDROID_HOME%
set PATH=%JAVA_HOME%\bin;%PATH%
echo BUILD_START > C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\build-status.txt
echo %DATE% %TIME% >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\build-status.txt
call gradlew.bat :app:assembleDebug --no-daemon --console=plain > C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\build-log.txt 2>&1
if errorlevel 1 (
  echo BUILD_FAILED >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\build-status.txt
) else (
  echo BUILD_SUCCESS >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\build-status.txt
  if exist app\build\outputs\apk\debug\app-debug.apk (
    copy app\build\outputs\apk\debug\app-debug.apk C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\WLT-Adblocker-debug.apk /Y
    echo APK_COPIED >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\build-status.txt
  )
)
echo %DATE% %TIME% >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\build-status.txt
