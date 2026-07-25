@echo off
setlocal
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot
set ANDROID_HOME=C:\Users\Default.L-HCG-9FVVGS3\AppData\Local\Android\Sdk
set PATH=%JAVA_HOME%\bin;%PATH%

echo FRESH_BUILD_START > C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\fresh-status.txt
echo %DATE% %TIME% >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\fresh-status.txt

REM Delete old APK first to guarantee freshness
del /Q "C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\WLT-Adblocker-debug.apk" 2>nul
del /Q "C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\android\app\build\outputs\apk\debug\app-debug.apk" 2>nul

cd /d C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\android
call gradlew.bat :app:assembleDebug --no-daemon --console=plain > C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\fresh-build-log.txt 2>&1
if errorlevel 1 (
  echo BUILD_FAILED >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\fresh-status.txt
  goto :end
)
echo BUILD_SUCCESS >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\fresh-status.txt

copy /Y app\build\outputs\apk\debug\app-debug.apk C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\WLT-Adblocker-debug.apk > nul
echo COPIED_TO_DESKTOP >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\fresh-status.txt
echo %DATE% %TIME% >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\fresh-status.txt

:end
echo ALL_DONE >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\fresh-status.txt
