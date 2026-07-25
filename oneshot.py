import os, sys, time, json, requests, subprocess

BASE = 'https://ollama.kushalneedsmcp.online'
ANDROID = r'C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\android'
AAR = os.path.join(ANDROID, 'app', 'libs', 'wlt.aar')
AAR_BAK = AAR + '.bak'
APK_OUT = os.path.join(ANDROID, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk')

# 1. Remove AAR
if os.path.exists(AAR) and not os.path.exists(AAR_BAK):
    os.rename(AAR, AAR_BAK)
    print('AAR removed')
elif os.path.exists(AAR_BAK):
    print('AAR already removed')

# 2. Build
env = os.environ.copy()
env['JAVA_HOME'] = r'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'
env['ANDROID_HOME'] = r'C:\Users\Default.L-HCG-9FVVGS3\AppData\Local\Android\Sdk'
env['PATH'] = env['JAVA_HOME'] + '\bin;' + env.get('PATH', '')

print('Building...')
r = subprocess.run([os.path.join(ANDROID, 'gradlew.bat'), ':app:assembleDebug', '--no-daemon', '--console=plain'], cwd=ANDROID, env=env, capture_output=True, text=True, timeout=300)
print(f'Build: {r.returncode}')
if r.returncode != 0:
    print(r.stdout[-1000:])
    sys.exit(1)

# 3. Restore AAR
if os.path.exists(AAR_BAK):
    os.rename(AAR_BAK, AAR)
    print('AAR restored')

# 4. Check APK
if not os.path.exists(APK_OUT):
    print('NO APK')
    sys.exit(1)
size = os.path.getsize(APK_OUT)
print(f'APK: {size/1024/1024:.1f} MB')

# 5. Install
print('Installing...')
with open(APK_OUT, 'rb') as f:
    r = requests.post(f'{BASE}/install?device=2', files={'file': f}, timeout=300)
print(f'Install: {r.text[:300]}')

time.sleep(2)

# 6. Verify
r = requests.post(f'{BASE}/shell', json={'command': '/system/bin/pm list packages | grep wlt', 'device': '2'}, timeout=15)
out = r.json().get('output', '')
print(f'Installed: {out if out else "FAILED"}')

if not out:
    sys.exit(1)

# 7. Launch
print('Launching...')
r = requests.post(f'{BASE}/launch', json={'package_name': 'com.wlt.adblocker.debug', 'activity': 'com.wlt.adblocker.MainActivity', 'device': '2'}, timeout=15)
print(f'Launch: {r.json()}')

time.sleep(5)

# 8. Screenshot
r = requests.get(f'{BASE}/screen?device=2', timeout=15)
print(f'Screenshot: {len(r.content)} bytes')

# 9. Layout
r = requests.get(f'{BASE}/layout?device=2', timeout=15)
xml = r.json().get('raw_xml', '')
import re
texts = re.findall(r'text="([^"]+)"', xml)
print(f'Texts: {texts[:25]}')

# 10. Logcat
r = requests.get(f'{BASE}/logcat?device=2&lines=100&filter_tag=AndroidRuntime', timeout=15)
crashes = r.json().get('output', '')
print(f'Crashes: {crashes if crashes else "NONE"}')

print('DONE')
