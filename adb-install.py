import requests
import json
import time
import os

BASE = 'https://ollama.kushalneedsmcp.online'
APK = r'C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\WLT-Adblocker-debug.apk'

print(f'APK exists: {os.path.exists(APK)}')
print(f'APK size: {os.path.getsize(APK)} bytes')

# Method 1: Use the /install API endpoint
print('\n=== Method 1: /install API ===')
try:
    with open(APK, 'rb') as f:
        files = {'file': ('WLT-Adblocker-debug.apk', f, 'application/vnd.android.package-archive')}
        r = requests.post(f'{BASE}/install?device=2', files=files, timeout=300)
    print(f'Status: {r.status_code}')
    print(f'Response: {r.text[:500]}')
except Exception as e:
    print(f'Error: {e}')

time.sleep(3)

# Verify
print('\n=== Verify ===')
r = requests.post(f'{BASE}/shell', json={'command': 'pm list packages', 'device': '2'}, timeout=15)
output = r.json().get('output', '')
wlt_lines = [l for l in output.split('\n') if 'wlt' in l.lower()]
if wlt_lines:
    print(f'WLT installed: {wlt_lines}')
else:
    print('WLT NOT in package list')
    # Show last 10 packages
    all_lines = output.strip().split('\n')
    print(f'Last 5 packages: {all_lines[-5:]}')
    print(f'Total packages: {len(all_lines)}')

print('\nDONE')
