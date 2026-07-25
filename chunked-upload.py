
import requests
import base64
import time
import os

BASE = "https://ollama.kushalneedsmcp.online"
APK = r"C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\WLT-Adblocker-debug.apk"

size = os.path.getsize(APK)
print(f"APK size: {size} bytes ({size/1024/1024:.1f} MB)")

CHUNK = 50 * 1024  # 50KB chunks
sent = 0

with open(APK, "rb") as f:
    chunk_num = 0
    while True:
        data = f.read(CHUNK)
        if not data:
            break
        b64 = base64.b64encode(data).decode()
        # Write chunk to device
        cmd = f'echo -n "{b64}" | base64 -d >> /data/local/tmp/wlt.apk'
        r = requests.post(f"{BASE}/shell", json={{"command": cmd, "device": "2"}}, timeout=30)
        sent += len(data)
        chunk_num += 1
        if chunk_num % 100 == 0:
            print(f"  Sent {sent/1024/1024:.1f} MB / {size/1024/1024:.1f} MB ({sent*100/size:.0f}%)")
        
        if chunk_num % 200 == 0:
            time.sleep(0.5)  # Small delay to avoid overwhelming

print(f"\nUpload complete: {sent} bytes sent in {chunk_num} chunks")

# Verify file size on device
r = requests.post(f"{BASE}/shell", json={{"command": "ls -la /data/local/tmp/wlt.apk", "device": "2"}}, timeout=15)
print(f"Device file: {{r.json().get('output', 'ERROR')}}")

# Install
print("\n=== Installing ===")
r = requests.post(f"{BASE}/shell", json={{"command": "pm install /data/local/tmp/wlt.apk", "device": "2"}}, timeout=120)
print(f"Install: {{r.json().get('output', 'FAILED')}}")

# Verify
r = requests.post(f"{BASE}/shell", json={{"command": "pm list packages | grep wlt", "device": "2"}}, timeout=15)
print(f"Installed: {{r.json().get('output', 'NOT FOUND')}}")

print("\nDONE")
