@echo off
echo === APK CONTENTS (native libs + blocklists) ===
"C:\Users\Default.L-HCG-9FVVGS3\AppData\Local\Android\Sdk\build-tools\34.0.0\aapt.exe" list "C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\WLT-Adblocker-debug.apk" | findstr /C:"lib/" | findstr /C:"libgojni"
echo.
echo === BLOCKLISTS IN APK ===
"C:\Users\Default.L-HCG-9FVVGS3\AppData\Local\Android\Sdk\build-tools\34.0.0\aapt.exe" list "C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\WLT-Adblocker-debug.apk" | findstr /C:"blocklists"
echo.
echo === APK BADGING (version + package) ===
"C:\Users\Default.L-HCG-9FVVGS3\AppData\Local\Android\Sdk\build-tools\34.0.0\aapt.exe" dump badging "C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\WLT-Adblocker-debug.apk" | findstr /C:"package" | head -1
echo AAPT_CHECK_DONE
