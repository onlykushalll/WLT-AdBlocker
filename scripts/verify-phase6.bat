@echo off
echo === APK NATIVE LIBS ===
"C:\Users\Default.L-HCG-9FVVGS3\AppData\Local\Android\Sdk\build-tools\34.0.0\aapt.exe" list "C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\WLT-Adblocker-debug.apk" | findstr /C:"libgojni"
echo.
echo === BLOCKLISTS IN APK ===
"C:\Users\Default.L-HCG-9FVVGS3\AppData\Local\Android\Sdk\build-tools\34.0.0\aapt.exe" list "C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\WLT-Adblocker-debug.apk" | findstr /C:"blocklists"
echo.
echo === APK BADGING ===
"C:\Users\Default.L-HCG-9FVVGS3\AppData\Local\Android\Sdk\build-tools\34.0.0\aapt.exe" dump badging "C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\WLT-Adblocker-debug.apk" | findstr /C:"package:"
echo VERIFY_DONE
