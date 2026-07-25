import base64
import sys

APK = r'C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\WLT-Adblocker-debug.apk'
with open(APK, 'rb') as f:
    data = f.read()
print(f'APK_SIZE:{len(data)}')
# Output first 100 bytes as hex for verification
print(f'FIRST_BYTES:{data[:20].hex()}')
print(f'LAST_BYTES:{data[-20:].hex()}')
