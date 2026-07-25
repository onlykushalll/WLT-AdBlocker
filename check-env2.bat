@echo off
echo === GO VERSION === > C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\env-report.txt 2>&1
where go >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\env-report.txt 2>&1
go version >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\env-report.txt 2>&1
echo. >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\env-report.txt 2>&1
echo === GOMOBILE === >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\env-report.txt 2>&1
where gomobile >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\env-report.txt 2>&1
echo. >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\env-report.txt 2>&1
echo === NDK === >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\env-report.txt 2>&1
if exist "C:\Users\Default.L-HCG-9FVVGS3\AppData\Local\Android\Sdk\ndk" (dir /b "C:\Users\Default.L-HCG-9FVVGS3\AppData\Local\Android\Sdk\ndk" >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\env-report.txt 2>&1) else echo NDK NOT FOUND >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\env-report.txt 2>&1
echo. >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\env-report.txt 2>&1
echo === JAVA === >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\env-report.txt 2>&1
java -version >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\env-report.txt 2>&1
echo DONE >> C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\env-report.txt 2>&1
