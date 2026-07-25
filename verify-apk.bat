@echo off
echo === DESKTOP APK TIMESTAMP ===
dir /T:W "C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\WLT-Adblocker-debug.apk"
echo.
echo === BUILD OUTPUT APK TIMESTAMP ===
dir /T:W "C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\android\app\build\outputs\apk\debug\app-debug.apk"
echo.
echo === WLT.AAR TIMESTAMP ===
dir /T:W "C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\android\app\libs\wlt.aar"
echo.
echo === CURRENT TIME ===
echo %DATE% %TIME%
echo.
echo === FINAL STATUS FILE ===
type "C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt"
echo.
echo === APK SIZE (bytes) ===
for %%A in ("C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\WLT-Adblocker-debug.apk") do echo %%~zA
echo.
echo === AAR SIZE (bytes) ===
for %%A in ("C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\android\app\libs\wlt.aar") do echo %%~zA
echo VERIFY_DONE
