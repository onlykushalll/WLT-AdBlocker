@echo off
echo Cleaning up build scripts and logs...
cd /d C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker
mkdir scripts 2>nul
move *.bat scripts\ 2>nul
move *.ps1 scripts\ 2>nul
del /Q *.txt 2>nul
del /Q *.py 2>nul
del /Q *.ipynb 2>nul
del /Q *.png 2>nul
del /Q COLAB_ANDROID_SETUP.md 2>nul
rmdir /s /q tmpmod 2>nul
rmdir /s /q gobin 2>nul
rmdir /s /q gopath 2>nul
echo Cleanup done
