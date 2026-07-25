@echo off
echo === GIT CONFIG ===
git config --global user.name "onlykushalll"
git config --global user.email "onlykushalll@users.noreply.github.com"
git config --global init.defaultBranch main

echo === INIT REPO ===
cd /d C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker
if not exist .git (
  git init
  git branch -M main
)

echo === ADD REMOTE ===
git remote remove origin 2>nul
git remote add origin https://github.com/onlykushalll/WLT-AdBlocker.git

echo === ADD ALL ===
git add -A

echo === COMMIT ===
git commit -m "WLT-AdBlocker: Phase 1-8 complete — Go core, 71 scriptlets, 888 domains, regex, domain age, JA4+, DNS cache, DoT blocking"

echo === PUSH ===
git push -u origin main --force
echo PUSH_DONE
