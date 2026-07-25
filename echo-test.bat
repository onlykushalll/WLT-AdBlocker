@echo off
echo HELLO_FROM_WLT
go version
echo GOMOBILE_CHECK
gomobile version 2>&1
echo NDK_CHECK
if exist "C:\Users\Default.L-HCG-9FVVGS3\AppData\Local\Android\Sdk\ndk" (echo NDK_EXISTS) else (echo NDK_MISSING)
echo END_CHECK
