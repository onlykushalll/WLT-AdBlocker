@echo off
setlocal
set PATH=C:\Users\Default.L-HCG-9FVVGS3\go\bin;%PATH%
set GOROOT=C:\Program Files\Go
set GOPATH=C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\gopath
set GOBIN=C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\gobin

mkdir %GOBIN% 2>nul
mkdir %GOPATH% 2>nul

echo === Installing gomobile to Desktop/gobin ===
go install golang.org/x/mobile/cmd/gomobile@latest
echo INSTALL_RC=%errorlevel%

echo === Testing alt gomobile ===
%GOBIN%\gomobile.exe version 2>&1
echo TEST_RC=%errorlevel%
echo DONE
