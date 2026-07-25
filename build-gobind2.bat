@echo off
setlocal
set PATH=C:\Users\Default.L-HCG-9FVVGS3\go\bin;%PATH%
set GOROOT=C:\Program Files\Go
set GOPATH=C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\gopath

mkdir C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\tmpmod 2>nul
cd /d C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\tmpmod

echo module tmpmod > go.mod
echo go 1.22 >> go.mod

echo === Get mobile dep ===
go get golang.org/x/mobile/cmd/gobind@latest 2>&1

echo === Build wlt-gobind ===
go build -o C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\gobin\wlt-gobind.exe golang.org/x/mobile/cmd/gobind 2>&1
echo BUILD_RC=%errorlevel%

echo === Build wlt-gomobile ===
go build -o C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\gobin\wlt-gomobile.exe golang.org/x/mobile/cmd/gomobile 2>&1
echo BUILD2_RC=%errorlevel%

echo === Test wlt-gobind ===
C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\gobin\wlt-gobind.exe -lang=java -outdir=NUL github.com/wlt/adblocker 2>&1
echo TEST_RC=%errorlevel%

echo === Test wlt-gomobile ===
C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\gobin\wlt-gomobile.exe version 2>&1
echo TEST2_RC=%errorlevel%

echo DONE
