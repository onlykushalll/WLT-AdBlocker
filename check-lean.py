import os
APK = r'C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\android\app\build\outputs\apk\debug\app-debug.apk'
if os.path.exists(APK):
    print(f'SIZE:{os.path.getsize(APK)}')
else:
    print('NOT_FOUND')
