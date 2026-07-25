@echo off
set PATH=C:\Users\Default.L-HCG-9FVVGS3\go\bin;%PATH%
echo === GOMOBILE VERSION ===
gomobile version 2>&1
echo GOMOBILE_RC=%errorlevel%
echo === GOBIND VERSION ===
gobind -version 2>&1
echo GOBIND_RC=%errorlevel%
echo DONE
