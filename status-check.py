import os, sys

# Check APK
APK = r'C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\WLT-Adblocker-debug.apk'
print(f'Desktop APK: {os.path.exists(APK)} ({os.path.getsize(APK) if os.path.exists(APK) else 0} bytes)')

# Check AAR
AAR = r'C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\android\app\libs\wlt.aar'
AAR_BAK = AAR + '.bak'
print(f'wlt.aar: {os.path.exists(AAR)}')
print(f'wlt.aar.bak: {os.path.exists(AAR_BAK)}')

# Check build APK
BUILD_APK = r'C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\android\app\build\outputs\apk\debug\app-debug.apk'
print(f'Build APK: {os.path.exists(BUILD_APK)} ({os.path.getsize(BUILD_APK) if os.path.exists(BUILD_APK) else 0} bytes)')

# Check if any install scripts left output
for f in ['results.txt', 'build-log.txt', 'final-status.txt']:
    p = os.path.join(r'C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker', f)
    if os.path.exists(p):
        with open(p, 'r') as fh:
            content = fh.read()[:300]
        print(f'{f}: {content}')
    else:
        print(f'{f}: NOT FOUND')
