@echo off
cd /d C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker
git add -A
git commit -m "4 CRITICAL BUG FIXES: VPN route + blocklist loading + Go engine class + DoH circular dependency"
git push -u origin main --force
echo DONE
del /Q git-push-all.bat check.bat build.bat install-emu.py 2>nul
