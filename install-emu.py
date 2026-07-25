import requests, json, time, re, os

BASE = 'https://ollama.kushalneedsmcp.online'
APK = r'C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\WLT-Adblocker-debug.apk'

print(f'APK: {os.path.getsize(APK)/1024/1024:.1f} MB')

# Health
r = requests.get(f'{BASE}/health', timeout=15)
print(f'Health: {r.json()["status"]}')
if r.json()['status'] != 'ok':
    print('EMULATORS NOT READY')
    exit(1)

# Install on device 2
print('Installing on device 2...')
with open(APK, 'rb') as f:
    r = requests.post(f'{BASE}/install?device=2', files={'file': f}, timeout=900)
print(f'Status: {r.status_code}')
resp = r.json()
print(json.dumps(resp, indent=2)[:500])

time.sleep(3)

# Verify
r = requests.post(f'{BASE}/shell', json={'command': '/system/bin/pm list packages | grep wlt', 'device': '2'}, timeout=30)
out = r.json().get('output', '')
print(f'\nWLT installed: {out if out else "FAILED"}')

if out:
    # Launch
    print('\n=== LAUNCH ===')
    r = requests.post(f'{BASE}/launch', json={'package_name': 'com.wlt.adblocker.debug', 'device': '2'}, timeout=15)
    print(f'Launch: {r.json()}')
    
    time.sleep(5)
    
    # Screenshot
    r = requests.get(f'{BASE}/screen?device=2', timeout=15)
    print(f'Screenshot: {len(r.content)} bytes')
    
    # Layout
    r = requests.get(f'{BASE}/layout?device=2', timeout=15)
    xml = r.json().get('raw_xml', '')
    texts = re.findall(r'text="([^"]+)"', xml)
    print(f'Visible: {texts[:25]}')
    
    # Logcat
    r = requests.get(f'{BASE}/logcat?device=2&lines=100&filter_tag=AndroidRuntime', timeout=15)
    crashes = r.json().get('output', '')
    print(f'Crashes: {"NONE" if not crashes else crashes[:500]}')
    
    # WLT logs
    r = requests.get(f'{BASE}/logcat?device=2&lines=200', timeout=15)
    logs = r.json().get('output', '')
    wlt_logs = [l for l in logs.split('\\n') if 'wlt' in l.lower() or 'WltVpn' in l or 'KotlinBlock' in l or 'GoBlock' in l or 'Blocklist' in l]
    print(f'\nWLT logs ({len(wlt_logs)} lines):')
    for l in wlt_logs[:10]:
        print(f'  {l[:150]}')
    
    print(f'\n=== TEST RESULTS ===')
    print(f'1. Install: {"PASS" if out else "FAIL"}')
    print(f'2. Launch:  {"PASS" if not crashes else "FAIL"}')
    print(f'3. UI:      {"PASS" if texts else "FAIL"}')
    print(f'4. No crash:{"PASS" if not crashes else "FAIL"}')

print('\nDONE')
