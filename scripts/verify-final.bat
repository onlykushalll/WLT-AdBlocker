@echo off
echo === NATIVE LIBS ===
"C:\Users\Default.L-HCG-9FVVGS3\AppData\Local\Android\Sdk\build-tools\34.0.0\aapt.exe" list "C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\WLT-Adblocker-debug.apk" | findstr /C:"libgojni"
echo.
echo === BLOCKLISTS ===
"C:\Users\Default.L-HCG-9FVVGS3\AppData\Local\Android\Sdk\build-tools\34.0.0\aapt.exe" list "C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\WLT-Adblocker-debug.apk" | findstr /C:"blocklists"
echo.
echo === PACKAGE ===
"C:\Users\Default.L-HCG-9FVVGS3\AppData\Local\Android\Sdk\build-tools\34.0.0\aapt.exe" dump badging "C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\WLT-Adblocker-debug.apk" | findstr /C:"package:"
echo VERIFY_DONE
