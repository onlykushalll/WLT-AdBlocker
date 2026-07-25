import requests
import json
import time
import os

BASE = 'https://ollama.kushalneedsmcp.online'
APK = r'C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\WLT-Adblocker-debug.apk'

print(f'APK: {os.path.getsize(APK)} bytes ({os.path.getsize(APK)/1024/1024:.1f} MB)')

# Health check
r = requests.get(f'{BASE}/health', timeout=15)
print(f'Health: {r.json()["status"]}')

# Install on BOTH devices
print('Installing on both devices...')
with open(APK, 'rb') as f:
    r = requests.post(f'{BASE}/install?device=all', files={'file': f}, timeout=900)
print(f'Status: {r.status_code}')
resp = r.json()
print(json.dumps(resp, indent=2))

time.sleep(3)

# Verify on device 2
r = requests.post(f'{BASE}/shell', json={'command': '/system/bin/pm list packages | grep wlt', 'device': '2'}, timeout=30)
out = r.json().get('output', '')
print(f'\nDevice 2 WLT: {out if out else "NOT FOUND"}')

# Verify on device 1
r = requests.post(f'{BASE}/shell', json={'command': '/system/bin/pm list packages | grep wlt', 'device': '1'}, timeout=30)
out1 = r.json().get('output', '')
print(f'Device 1 WLT: {out1 if out1 else "NOT FOUND"}')

if out:
    # Launch on device 2
    print('\n=== LAUNCH on Device 2 ===')
    r = requests.post(f'{BASE}/launch', json={'package_name': 'com.wlt.adblocker.debug', 'device': '2'}, timeout=15)
    print(f'Launch: {r.json()}')
    
    time.sleep(5)
    
    # Screenshot
    r = requests.get(f'{BASE}/screen?device=2', timeout=15)
    print(f'Screenshot: {len(r.content)} bytes')
    
    # Layout
    r = requests.get(f'{BASE}/layout?device=2', timeout=15)
    xml = r.json().get('raw_xml', '')
    import re
    texts = re.findall(r'text="([^"]+)"', xml)
    print(f'Visible text: {texts[:25]}')
    
    # Logcat
    r = requests.get(f'{BASE}/logcat?device=2&lines=100&filter_tag=AndroidRuntime', timeout=15)
    crashes = r.json().get('output', '')
    print(f'Crashes: {"NONE - CLEAN LAUNCH" if not crashes else crashes[:500]}')
    
    print('\n=== TEST RESULT ===')
    print(f'APP INSTALL: {"PASS" if out else "FAIL"}')
    print(f'APP LAUNCH: {"PASS" if not crashes else "FAIL"}')
    print(f'UI VISIBLE: {"PASS" if texts else "FAIL"}')

print('\nDONE')
