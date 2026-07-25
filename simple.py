import os, sys, time, requests

BASE = 'https://ollama.kushalneedsmcp.online'
ANDROID = r'C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\android'
AAR = os.path.join(ANDROID, 'app', 'libs', 'wlt.aar')
AAR_BAK = AAR + '.bak'
APK_OUT = os.path.join(ANDROID, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk')

# Restore AAR if needed
if os.path.exists(AAR_BAK):
    os.rename(AAR_BAK, AAR)
    print('AAR restored')

# Remove AAR for lean build
if os.path.exists(AAR):
    os.rename(AAR, AAR_BAK)
    print('AAR removed for lean build')

# Build with gradle directly
import subprocess
env = os.environ.copy()
env['JAVA_HOME'] = r'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'
env['ANDROID_HOME'] = r'C:\Users\Default.L-HCG-9FVVGS3\AppData\Local\Android\Sdk'
env['PATH'] = env['JAVA_HOME'] + '\bin;' + env.get('PATH', '')

print('Building lean APK...')
p = subprocess.Popen(
    [os.path.join(ANDROID, 'gradlew.bat'), ':app:assembleDebug', '--no-daemon', '--console=plain'],
    cwd=ANDROID, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True
)
# Stream output
for line in p.stdout:
    print(line, end='')
p.wait()
print(f'Build exit: {p.returncode}')

# Restore AAR
if os.path.exists(AAR_BAK):
    os.rename(AAR_BAK, AAR)
    print('AAR restored')

if p.returncode != 0 or not os.path.exists(APK_OUT):
    print('BUILD FAILED')
    sys.exit(1)

size = os.path.getsize(APK_OUT)
print(f'Lean APK: {size/1024/1024:.1f} MB')

# Install
print('Installing via API...')
with open(APK_OUT, 'rb') as f:
    r = requests.post(f'{BASE}/install?device=2', files={'file': f}, timeout=600)
print(f'Status: {r.status_code}')
print(f'Response: {r.text[:500]}')

time.sleep(2)

# Verify
r = requests.post(f'{BASE}/shell', json={'command': '/system/bin/pm list packages | grep wlt', 'device': '2'}, timeout=30)
out = r.json().get('output', '')
print(f'Installed: {out if out else "FAILED"}')

if out:
    print('LAUNCHING...')
    r = requests.post(f'{BASE}/launch', json={'package_name': 'com.wlt.adblocker.debug', 'activity': 'com.wlt.adblocker.MainActivity', 'device': '2'}, timeout=15)
    print(f'Launch: {r.json()}')
    time.sleep(5)
    r = requests.get(f'{BASE}/screen?device=2', timeout=15)
    print(f'Screenshot: {len(r.content)} bytes')
    r = requests.get(f'{BASE}/layout?device=2', timeout=15)
    xml = r.json().get('raw_xml', '')
    import re
    texts = re.findall(r'text="([^"]+)"', xml)
    print(f'Texts: {texts[:25]}')
    r = requests.get(f'{BASE}/logcat?device=2&lines=100&filter_tag=AndroidRuntime', timeout=15)
    crashes = r.json().get('output', '')
    print(f'Crashes: {"NONE" if not crashes else crashes[:300]}')
    print('TEST: PASS' if not crashes else 'TEST: FAIL')

print('DONE')
