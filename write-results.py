import os, sys
results = []
results.append(f'AAR exists: {os.path.exists(r"C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\android\app\libs\wlt.aar")}')
results.append(f'AAR_BAK exists: {os.path.exists(r"C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\android\app\libs\wlt.aar.bak")}')
APK = r'C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\android\app\build\outputs\apk\debug\app-debug.apk'
results.append(f'APK exists: {os.path.exists(APK)}')
if os.path.exists(APK):
    results.append(f'APK size: {os.path.getsize(APK)}')
# Check if build log exists
log = r'C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\build-log.txt'
if os.path.exists(log):
    with open(log, 'r') as f:
        results.append(f'Build log (last 500): {f.read()[-500:]}')
with open(r'C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\results.txt', 'w') as f:
    f.write('\n'.join(results))
print('RESULTS_WRITTEN')
