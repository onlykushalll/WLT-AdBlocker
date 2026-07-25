@echo off
setlocal
set PATH=C:\Users\Default.L-HCG-9FVVGS3\go\bin;%PATH%
set GOROOT=C:\Program Files\Go
set GOPATH=C:\Users\Default.L-HCG-9FVVGS3\go

echo REINSTALL > C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt

echo === Reinstalling gomobile + gobind ===
go install golang.org/x/mobile/cmd/gomobile@latest 2>&1
go install golang.org/x/mobile/cmd/gobind@latest 2>&1
echo INSTALL_DONE=%errorlevel%

echo === Test gomobile ===
C:\Users\Default.L-HCG-9FVVGS3\go\bin\gomobile.exe version 2>&1
echo GOMOBILE_TEST=%errorlevel%

echo === Test gobind ===
C:\Users\Default.L-HCG-9FVVGS3\go\bin\gobind.exe -version 2>&1
echo GOBIND_TEST=%errorlevel%

echo ALL_DONE >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\final-status.txt
