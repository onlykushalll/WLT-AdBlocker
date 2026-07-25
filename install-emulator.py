import requests
import sys
import time

APK_PATH = r'C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\WLT-Adblocker-debug.apk'
BASE = 'https://ollama.kushalneedsmcp.online'

print('=== Step 1: Health Check ===')
r = requests.get(f'{BASE}/health', timeout=15)
print(r.json())
if r.json().get('status') != 'ok':
    print('Emulators not ready')
    sys.exit(1)

print('\n=== Step 2: Install APK on Device 2 ===')
with open(APK_PATH, 'rb') as f:
    r = requests.post(f'{BASE}/install?device=2', files={'file': f}, timeout=120)
print(r.json())

print('\n=== Step 3: Verify Install ===')
r = requests.post(f'{BASE}/shell', json={'command': 'pm list packages -3 | grep wlt', 'device': '2'}, timeout=15)
print('Installed:', r.json().get('output', 'NOT FOUND'))

print('\n=== Step 4: Launch WLT ===')
r = requests.post(f'{BASE}/launch', json={'package_name': 'com.wlt.adblocker.debug', 'activity': 'com.wlt.adblocker.MainActivity', 'device': '2'}, timeout=15)
print('Launch:', r.json())

time.sleep(3)

print('\n=== Step 5: Screenshot ===')
r = requests.get(f'{BASE}/screen?device=2', timeout=15)
with open('wlt-launch.png', 'wb') as f:
    f.write(r.content)
print(f'Screenshot saved: {len(r.content)} bytes')

print('\n=== Step 6: UI Layout ===')
r = requests.get(f'{BASE}/layout?device=2', timeout=15)
xml = r.json().get('raw_xml', '')
print(f'Layout XML length: {len(xml)}')
print(xml[:2000])

print('\n=== Step 7: Logcat (check for crashes) ===')
r = requests.get(f'{BASE}/logcat?device=2&lines=50&filter_tag=AndroidRuntime', timeout=15)
print('Logcat:', r.json().get('output', 'No output'))

print('\nDONE')
