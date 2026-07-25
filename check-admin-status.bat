@echo off
echo === SIGN LOG ===
type C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\sign-log.txt
echo.
echo === CHECK CERT IN LOCALMACHINE ===
powershell -Command "Get-ChildItem Cert:\LocalMachine\Root | Where-Object { $_.Subject -match 'WLT' } | Select-Object Subject"
powershell -Command "Get-ChildItem Cert:\LocalMachine\TrustedPublisher | Where-Object { $_.Subject -match 'WLT' } | Select-Object Subject"
echo CHECK_DONE
