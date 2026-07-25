@echo off
echo === BUILD STATUS ===
type C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\build-status.txt
echo.
echo === APK FILE INFO ===
dir "C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\android\app\build\outputs\apk\debug\app-debug.apk"
echo.
echo === DESKTOP APK INFO ===
dir "C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\WLT-Adblocker-debug.apk"
echo.
echo === BUILD LOG TAIL ===
powershell -Command "Get-Content 'C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\build-log.txt' -Tail 15"
