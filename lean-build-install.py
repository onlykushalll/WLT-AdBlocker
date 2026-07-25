import requests
import json
import time
import os
import subprocess

BASE = "https://ollama.kushalneedsmcp.online"
ANDROID = r"C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\android"
AAR = os.path.join(ANDROID, "app", "libs", "wlt.aar")
AAR_BAK = AAR + ".bak"

# Step 1: Temporarily remove wlt.aar
print("=== Removing wlt.aar temporarily ===")
if os.path.exists(AAR):
    os.rename(AAR, AAR_BAK)
    print(f"Renamed to {AAR_BAK}")
else:
    print("wlt.aar not found, building without it")

# Step 2: Build lean APK
print("\n=== Building lean APK ===")
env = os.environ.copy()
env["JAVA_HOME"] = r"C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
env["ANDROID_HOME"] = r"C:\Users\Default.L-HCG-9FVVGS3\AppData\Local\Android\Sdk"
env["PATH"] = env["JAVA_HOME"] + "\bin;" + env.get("PATH", "")

result = subprocess.run(
    [os.path.join(ANDROID, "gradlew.bat"), ":app:assembleDebug", "--no-daemon", "--console=plain"],
    cwd=ANDROID, env=env, capture_output=True, text=True, timeout=300
)
print(f"Build exit code: {result.returncode}")
print(f"Build output (last 500 chars): {result.stdout[-500:]}")
if result.stderr:
    print(f"Build errors: {result.stderr[-500:]}")

# Step 3: Restore wlt.aar
if os.path.exists(AAR_BAK):
    os.rename(AAR_BAK, AAR)
    print("Restored wlt.aar")

# Step 4: Check APK size
apk_path = os.path.join(ANDROID, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
if os.path.exists(apk_path):
    size = os.path.getsize(apk_path)
    print(f"\nLean APK size: {size} bytes ({size/1024/1024:.1f} MB)")
    
    # Step 5: Install via API
    print("\n=== Installing lean APK ===")
    with open(apk_path, "rb") as f:
        r = requests.post(f"{BASE}/install?device=2", files={"file": f}, timeout=300)
    print(f"Install status: {r.status_code}")
    print(f"Install response: {r.text[:500]}")
    
    time.sleep(2)
    
    # Step 6: Verify
    r = requests.post(f"{BASE}/shell", json={"command": "pm list packages | grep wlt", "device": "2"}, timeout=15)
    out = r.json().get("output", "")
    print(f"\nWLT installed: {out if out else 'NOT FOUND'}")
    
    if out:
        print("\n=== LAUNCH ===")
        r = requests.post(f"{BASE}/launch", json={"package_name": "com.wlt.adblocker.debug", "activity": "com.wlt.adblocker.MainActivity", "device": "2"}, timeout=15)
        print(f"Launch: {r.json()}")
        
        time.sleep(5)
        
        print("\n=== SCREENSHOT ===")
        r = requests.get(f"{BASE}/screen?device=2", timeout=15)
        print(f"Screenshot: {len(r.content)} bytes")
        
        print("\n=== LAYOUT ===")
        r = requests.get(f"{BASE}/layout?device=2", timeout=15)
        xml = r.json().get("raw_xml", "")
        import re
        texts = re.findall(r'text="([^"]+)"', xml)
        print(f"Texts: {texts[:25]}")
        
        print("\n=== LOGCAT ===")
        r = requests.get(f"{BASE}/logcat?device=2&lines=50&filter_tag=AndroidRuntime", timeout=15)
        crashes = r.json().get("output", "")
        print(f"Crashes: {crashes if crashes else 'NONE'}")
else:
    print("APK not found after build!")

print("\nDONE")
