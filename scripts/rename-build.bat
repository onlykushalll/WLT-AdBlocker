@echo off
setlocal
set PATH=C:\Users\Default.L-HCG-9FVVGS3\go\bin;C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\gobin;%PATH%
set GOROOT=C:\Program Files\Go
set GOPATH=C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\gopath
set GOBIN=C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\gobin

echo === Build gobind with different name ===
go build -o %GOBIN%\wlt-gobind.exe golang.org/x/mobile/cmd/gobind
echo BUILD_RC=%errorlevel%

echo === Test renamed gobind ===
%GOBIN%\wlt-gobind.exe -lang=java -outdir=NUL github.com/wlt/adblocker 2>&1
echo TEST_RC=%errorlevel%
echo DONE
