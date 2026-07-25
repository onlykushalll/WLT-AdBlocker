@echo off
echo === FRESH STATUS ===
type C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\fresh-status.txt
echo.
echo === DESKTOP APK (fresh timestamp check) ===
dir /T:W "C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\WLT-Adblocker-debug.apk"
echo.
echo === CURRENT TIME ===
echo %DATE% %TIME%
echo.
echo === BUILD LOG TAIL ===
powershell -Command "Get-Content 'C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\fresh-build-log.txt' -Tail 10"
echo READ_FRESH_DONE
