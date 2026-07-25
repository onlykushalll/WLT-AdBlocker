import os
APK = r'C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\android\app\build\outputs\apk\debug\app-debug.apk'
AAR = r'C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\android\app\libs\wlt.aar'
AAR_BAK = AAR + '.bak'
print(f'APK exists: {os.path.exists(APK)}')
if os.path.exists(APK):
    print(f'APK size: {os.path.getsize(APK)}')
print(f'AAR exists: {os.path.exists(AAR)}')
print(f'AAR_BAK exists: {os.path.exists(AAR_BAK)}')
