@echo off
echo === STATUS FILE ===
type C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt
echo.
echo === AAR LOG TAIL ===
powershell -Command "Get-Content 'C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\aar-log.txt' -Tail 5" 2>nul
echo.
echo === AAR TIMESTAMP ===
dir /T:W C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\android\app\libs\wlt.aar
echo.
echo === APK TIMESTAMP ===
dir /T:W C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\WLT-Adblocker-debug.apk
