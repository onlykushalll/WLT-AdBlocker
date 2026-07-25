@echo off
echo === FULL APK CONTENTS (lib/ entries only) ===
findstr /C:"lib/" C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\apk-contents.txt
echo.
echo === WLT.AAR CONTENTS ===
cd /d C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\android\app\libs
jar tf wlt.aar | findstr /C:"jni/"
echo READ_CONTENTS_DONE
