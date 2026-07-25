@echo off
type C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\status.txt
echo ---
powershell -Command "Get-Content 'C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\build-log.txt' -Tail 10"
