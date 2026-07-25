import requests
import sys
import time

APK_PATH = r'C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\WLT-Adblocker-debug.apk'
BASE = 'https://ollama.kushalneedsmcp.online'

# Step 1: Health
r = requests.get(f'{BASE}/health', timeout=15)
print('Health:', r.json())

# Step 2: Install
print('\nInstalling APK...')
with open(APK_PATH, 'rb') as f:
    r = requests.post(f'{BASE}/install?device=2', files={'file': f}, timeout=180)
print('Install result:', r.text[:500])

time.sleep(2)

# Step 3: Verify
r = requests.post(f'{BASE}/shell', json={'command': 'pm list packages -3', 'device': '2'}, timeout=15)
print('\nInstalled 3rd party packages:')
print(r.json().get('output', 'NONE'))

# Step 4: Find correct activity
r = requests.post(f'{BASE}/shell', json={'command': 'pm dump com.wlt.adblocker.debug | grep -A 1 "android.intent.action.MAIN"', 'device': '2'}, timeout=15)
print('\nMain activity:')
print(r.json().get('output', 'NOT FOUND'))

# Step 5: Launch with monkey
r = requests.post(f'{BASE}/shell', json={'command': 'monkey -p com.wlt.adblocker.debug -c android.intent.category.LAUNCHER 1', 'device': '2'}, timeout=15)
print('\nLaunch result:')
print(r.json().get('output', 'FAILED'))

time.sleep(5)

# Step 6: Screenshot
r = requests.get(f'{BASE}/screen?device=2', timeout=15)
print(f'\nScreenshot: {len(r.content)} bytes')

# Step 7: Layout
r = requests.get(f'{BASE}/layout?device=2', timeout=15)
xml = r.json().get('raw_xml', '')
print(f'Layout: {len(xml)} chars')
# Find text elements
import re
texts = re.findall(r'text="([^"]+)"', xml)
print('Visible text:', texts[:20])

# Step 8: Logcat
r = requests.get(f'{BASE}/logcat?device=2&lines=30&filter_tag=AndroidRuntime', timeout=15)
print('\nLogcat (crashes):')
print(r.json().get('output', 'No crashes'))

print('\nDONE')
