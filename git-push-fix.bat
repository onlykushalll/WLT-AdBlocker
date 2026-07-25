@echo off
cd /d C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker
git add -A
git commit -m "CRITICAL FIX: VPN blocking all traffic + blocklists not loaded + Go engine class name"
git push -u origin main --force
echo DONE
del /Q git-push-fix.bat
