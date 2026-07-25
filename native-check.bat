@echo off
echo === ALL NATIVE LIBS IN APK ===
"C:\Users\Default.L-HCG-9FVVGS3\AppData\Local\Android\Sdk\build-tools\34.0.0\aapt.exe" list "C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\WLT-Adblocker-debug.apk" > C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\apk-contents.txt 2>&1
echo === GO JNI LIBS (libgojni.so) ===
findstr /C:"libgojni" C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\apk-contents.txt
echo.
echo === APK BADGING ===
"C:\Users\Default.L-HCG-9FVVGS3\AppData\Local\Android\Sdk\build-tools\34.0.0\aapt.exe" dump badging "C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\WLT-Adblocker-debug.apk" > C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\apk-badging.txt 2>&1
findstr /C:"package:" C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\apk-badging.txt
echo NATIVE_CHECK_DONE
