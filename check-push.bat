@echo off
cd /d C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker
git log --oneline -5
echo ---
git remote -v
echo ---
git branch -a
echo ---
powershell -Command "git log --oneline -1 origin/main 2>&1"
echo CHECK_PUSH_DONE
