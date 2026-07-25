import requests
import json
import time
import sys

BASE = 'https://ollama.kushalneedsmcp.online'
APK = r'C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\WLT-Adblocker-debug.apk'

print('=== Installing WLT APK on emulator-5556 ===')
try:
    with open(APK, 'rb') as f:
        r = requests.post(f'{BASE}/install?device=2', files={'file': f}, timeout=180)
    print('Install response:', r.text[:500])
except Exception as e:
    print(f'Install error: {e}')
    sys.exit(1)

time.sleep(3)

print('\n=== Verify install ===')
r = requests.post(f'{BASE}/shell', json={'command': 'pm list packages', 'device': '2'}, timeout=15)
output = r.json().get('output', '')
if 'wlt' in output.lower():
    print('SUCCESS: WLT is installed!')
    # Find exact package name
    for line in output.split('\n'):
        if 'wlt' in line.lower():
            print(f'  Package: {line.strip()}')
else:
    print('FAILED: WLT not found in package list')
    sys.exit(1)

print('\n=== Launch WLT ===')
r = requests.post(f'{BASE}/shell', json={'command': 'monkey -p com.wlt.adblocker.debug -c android.intent.category.LAUNCHER 1', 'device': '2'}, timeout=15)
print('Launch:', r.json().get('output', 'FAILED'))

time.sleep(5)

print('\n=== Screenshot ===')
r = requests.get(f'{BASE}/screen?device=2', timeout=15)
print(f'Screenshot: {len(r.content)} bytes')
with open(r'C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\wlt-screen.png', 'wb') as f:
    f.write(r.content)

print('\n=== Layout ===')
r = requests.get(f'{BASE}/layout?device=2', timeout=15)
xml = r.json().get('raw_xml', '')
print(f'Layout: {len(xml)} chars')
import re
texts = re.findall(r'text="([^"]+)"', xml)
print(f'Visible text: {texts[:25]}')

print('\n=== Logcat ===')
r = requests.get(f'{BASE}/logcat?device=2&lines=50&filter_tag=AndroidRuntime', timeout=15)
print('Crashes:', r.json().get('output', 'None'))

print('\nDONE')
