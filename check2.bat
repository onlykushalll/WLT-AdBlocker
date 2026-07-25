@echo off
echo GOMOBILE_EXISTS
where gomobile 2>nul
echo GOMOBILE_DONE
echo NDK_DIR_CHECK
if exist "C:\Users\Default.L-HCG-9FVVGS3\AppData\Local\Android\Sdk\ndk" (dir /b "C:\Users\Default.L-HCG-9FVVGS3\AppData\Local\Android\Sdk\ndk") else (echo NDK_NOT_FOUND)
echo AAR_INFO
dir "C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\android\app\libs\wlt.aar"
echo END_CHECK2
