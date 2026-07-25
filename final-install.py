import requests
import json
import time
import os

BASE = "https://ollama.kushalneedsmcp.online"
APK = r"C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\WLT-Adblocker-debug.apk"

print(f"APK size: {os.path.getsize(APK)} bytes")

# Try install with 900 second (15 min) timeout
print("Uploading APK (15 min timeout)...")
try:
    with open(APK, "rb") as f:
        files = {"file": ("WLT.apk", f, "application/vnd.android.package-archive")}
        r = requests.post(f"{BASE}/install?device=2", files=files, timeout=900)
    print(f"Status: {r.status_code}")
    resp = r.json()
    print(json.dumps(resp, indent=2))
    
    # Check if success
    results = resp.get("results", {})
    dev2 = results.get("emulator-5556", {})
    if dev2.get("success"):
        print("\n=== INSTALL SUCCESS! ===")
    else:
        print(f"\n=== INSTALL FAILED: {dev2.get('output', 'unknown')} ===")
except Exception as e:
    print(f"Error: {e}")

# Verify
time.sleep(2)
r = requests.post(f"{BASE}/shell", json={"command": "pm list packages | grep wlt", "device": "2"}, timeout=15)
out = r.json().get("output", "")
print(f"\nWLT installed: {out if out else 'NOT FOUND'}")

if out:
    print("\n=== LAUNCHING WLT ===")
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
    print(f"Visible text: {texts[:25]}")
    
    print("\n=== LOGCAT ===")
    r = requests.get(f"{BASE}/logcat?device=2&lines=50&filter_tag=AndroidRuntime", timeout=15)
    crashes = r.json().get("output", "")
    print(f"Crashes: {crashes if crashes else 'NONE'}")

print("\nDONE")
