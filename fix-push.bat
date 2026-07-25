@echo off
cd /d C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker
echo === RENAME BRANCH ===
git branch -M main
echo === ADD ALL ===
git add -A
echo === COMMIT ===
git commit -m "WLT-AdBlocker: Phase 1-8 complete"
echo === FORCE PUSH ===
git push -u origin main --force
echo PUSH_RESULT=%errorlevel%
echo DONE
