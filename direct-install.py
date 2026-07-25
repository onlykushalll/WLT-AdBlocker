import requests
import subprocess
import json
import time

BASE = 'https://ollama.kushalneedsmcp.online'
ADB = r'C:\Users\Default.L-HCG-9FVVGS3\AppData\Local\Android\Sdk\platform-tools\adb.exe'
APK = r'C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\WLT-Adblocker-debug.apk'

print('=== Direct adb install ===')
# The emulators are connected via the Colab microservice, not local adb
# But the /install API works - it just needs the file to be smaller
# Or we can split the approach:
# 1. Use the API to push the APK in chunks
# 2. Or use a smaller APK

# Actually, the /install endpoint DOES work - it uploaded our test file.
# The issue is the 68MB file timing out over the tunnel.
# Let's try with a longer timeout and proper content type

print(f'APK size: {__import__("os").path.getsize(APK)} bytes')
print('Uploading...')

try:
    with open(APK, 'rb') as f:
        files = {'file': ('WLT.apk', f, 'application/vnd.android.package-archive')}
        r = requests.post(f'{BASE}/install?device=2', files=files, timeout=600)
    print(f'Status: {r.status_code}')
    resp = r.json()
    print(json.dumps(resp, indent=2))
except Exception as e:
    print(f'Upload error: {e}')

time.sleep(2)

# Verify
print('\n=== Verify ===')
r = requests.post(f'{BASE}/shell', json={'command': 'pm list packages | grep wlt', 'device': '2'}, timeout=15)
out = r.json().get('output', '')
print(f'WLT packages: {out if out else "NONE"}')

if out:
    print('\n=== LAUNCH ===')
    r = requests.post(f'{BASE}/shell', json={'command': 'monkey -p com.wlt.adblocker.debug -c android.intent.category.LAUNCHER 1', 'device': '2'}, timeout=15)
    print(f'Launch: {r.json()}')
    
    time.sleep(5)
    
    print('\n=== SCREENSHOT ===')
    r = requests.get(f'{BASE}/screen?device=2', timeout=15)
    print(f'Screenshot: {len(r.content)} bytes')
    
    print('\n=== LAYOUT ===')
    r = requests.get(f'{BASE}/layout?device=2', timeout=15)
    xml = r.json().get('raw_xml', '')
    import re
    texts = re.findall(r'text="([^"]+)"', xml)
    print(f'Visible text: {texts[:25]}')
    
    print('\n=== LOGCAT ===')
    r = requests.get(f'{BASE}/logcat?device=2&lines=50&filter_tag=AndroidRuntime', timeout=15)
    crashes = r.json().get('output', '')
    print(f'Crashes: {crashes if crashes else "NONE"}')

print('\nDONE')
