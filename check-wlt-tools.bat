@echo off
echo === WLT-GOBIND ===
C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\gobin\wlt-gobind.exe -lang=java -outdir=NUL github.com/wlt/adblocker 2>&1
echo RC1=%errorlevel%
echo.
echo === WLT-GOMOBILE ===
C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\gobin\wlt-gomobile.exe version 2>&1
echo RC2=%errorlevel%
echo DONE
