@echo off
cd /d C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker
git add -A
git commit -m "Phase 9: Per-app intelligence — categorization, blocked services, IP->domain lookup, analytics"
git push -u origin main --force
echo PUSH_RC=%errorlevel%
echo DONE
