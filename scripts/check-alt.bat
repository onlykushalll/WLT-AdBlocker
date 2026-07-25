@echo off
set GOBIN=C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\gobin
echo === ALT GOMOBILE ===
%GOBIN%\gomobile.exe version 2>&1
echo RC=%errorlevel%
echo === ALT GOBIND ===
%GOBIN%\gobind.exe -lang=java -outdir=NUL github.com/wlt/adblocker 2>&1
echo RC2=%errorlevel%
echo DONE
