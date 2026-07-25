import requests
import json
import time
import os

BASE = "https://ollama.kushalneedsmcp.online"
APK = r"C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\WLT-Adblocker-debug.apk"

size = os.path.getsize(APK)
print(f"APK: {size} bytes ({size/1024/1024:.1f} MB)")

# Use streaming upload with chunked transfer encoding
print("Streaming upload...")

session = requests.Session()

# Read the file and upload with explicit Content-Length
with open(APK, "rb") as f:
    data = f.read()

print(f"Read {len(data)} bytes, uploading...")

# Try with explicit headers
headers = {
    "Content-Type": "multipart/form-data; boundary=wltboundary1234567890",
}
body = (
    "--wltboundary1234567890\r\n"
    f'Content-Disposition: form-data; name="file"; filename="WLT.apk"\r\n'
    "Content-Type: application/vnd.android.package-archive\r\n\r\n"
).encode() + data + b"\r\n--wltboundary1234567890--\r\n"

print(f"Body size: {len(body)} bytes")

try:
    r = session.post(
        f"{BASE}/install?device=2",
        data=body,
        headers=headers,
        timeout=900
    )
    print(f"Status: {r.status_code}")
    print(f"Response: {r.text[:500]}")
except Exception as e:
    print(f"Error: {e}")

time.sleep(2)

# Verify
r = requests.post(f"{BASE}/shell", json={"command": "pm list packages | grep wlt", "device": "2"}, timeout=15)
out = r.json().get("output", "")
print(f"\nWLT: {out if out else 'NOT FOUND'}")

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
    print(f"Crashes: {r.json().get('output', 'None')}")

print("\nDONE")
