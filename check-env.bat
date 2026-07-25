@echo off
echo === GO VERSION ===
where go 2>nul
go version 2>nul
echo.
echo === GOMOBILE ===
where gomobile 2>nul
echo.
echo === NDK ===
if exist "C:\Users\Default.L-HCG-9FVVGS3\AppData\Local\Android\Sdk\ndk" (dir /b "C:\Users\Default.L-HCG-9FVVGS3\AppData\Local\Android\Sdk\ndk") else echo NDK NOT FOUND
echo.
echo === JAVA ===
java -version 2>&1
echo.
echo === WLT.AAR INFO ===
dir "C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\android\app\libs\wlt.aar"
