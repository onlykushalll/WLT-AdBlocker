@echo off
set PATH=C:\Users\Default.L-HCG-9FVVGS3\go\bin;%PATH%
go version
echo GO_WORKS=%errorlevel%
go env GOROOT
go env GOPATH
echo DONE
