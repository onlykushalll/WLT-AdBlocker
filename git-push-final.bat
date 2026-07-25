@echo off
cd /d C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker
git add -A
git commit -m "Production-grade docs + deep clean — ready for testing"
git push -u origin main --force
echo PUSH_DONE
del /Q git-push-final.bat
