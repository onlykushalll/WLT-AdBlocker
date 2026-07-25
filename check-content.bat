@echo off
echo === APK NATIVE LIBS (should have 4 ABIs from fresh wlt.aar) ===
cd /d C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\android\app\build\intermediates\merged_native_libs\debug\out\lib
if exist arm64-v8a (echo arm64-v8a: YES) else (echo arm64-v8a: NO)
if exist armeabi-v7a (echo armeabi-v7a: YES) else (echo armeabi-v7a: NO)
if exist x86 (echo x86: YES) else (echo x86: NO)
if exist x86_64 (echo x86_64: YES) else (echo x86_64: NO)
echo.
echo === WLT GO LIBRARY IN EACH ABI ===
if exist arm64-v8a\libgojni.so (echo arm64 libgojni.so: YES - dir) else (echo arm64 libgojni.so: NO)
if exist armeabi-v7a\libgojni.so (echo arm libgojni.so: YES) else (echo arm libgojni.so: NO)
if exist x86\libgojni.so (echo x86 libgojni.so: YES) else (echo x86 libgojni.so: NO)
if exist x86_64\libgojni.so (echo x86_64 libgojni.so: YES) else (echo x86_64 libgojni.so: NO)
echo.
echo === BLOCKLIST DOMAINS COUNT ===
cd /d C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\android\app\src\main\assets\blocklists
powershell -Command "(Get-Content wlt-*.txt | Where-Object { $_ -notmatch '^#' -and $_.Trim() -ne '' }).Count"
echo.
echo === TRACKERS BLOCKLIST (should be 159 domains) ===
powershell -Command "(Get-Content wlt-trackers.txt | Where-Object { $_ -notmatch '^#' -and $_.Trim() -ne '' }).Count"
echo CONTENT_CHECK_DONE
