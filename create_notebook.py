import json

cell1_code = """# CELL 1: Download Android SDK & Dependencies
import os, subprocess
print('📦 Installing System Dependencies & Android SDK...')
subprocess.run('dpkg --configure -a > /dev/null 2>&1', shell=True)
subprocess.run('apt-get update -qq > /dev/null 2>&1', shell=True)
subprocess.run('apt-get install -y -qq openjdk-17-jdk wget unzip curl libglu1-mesa > /dev/null 2>&1', shell=True)
subprocess.run('pip install -q fastapi uvicorn python-multipart requests pydantic', shell=True)

os.environ['ANDROID_HOME'] = '/root/android-sdk'
os.environ['PATH'] += ':/root/android-sdk/cmdline-tools/latest/bin:/root/android-sdk/platform-tools:/root/android-sdk/emulator'

subprocess.run('mkdir -p /root/android-sdk/cmdline-tools /root/.android', shell=True)
subprocess.run('touch /root/.android/repositories.cfg', shell=True)

if not os.path.exists('/root/android-sdk/cmdline-tools/latest/bin/sdkmanager'):
    print('⏳ Downloading Android Cmdline Tools...')
    subprocess.run('wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/cmdline.zip', shell=True)
    subprocess.run('unzip -q /tmp/cmdline.zip -d /root/android-sdk/cmdline-tools', shell=True)
    subprocess.run('rm -rf /root/android-sdk/cmdline-tools/latest', shell=True)
    subprocess.run('mv /root/android-sdk/cmdline-tools/cmdline-tools /root/android-sdk/cmdline-tools/latest', shell=True)

print('⏳ Downloading Android System Image (API 30 x86_64)...')
subprocess.run('yes | /root/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null 2>&1', shell=True)
subprocess.run('/root/android-sdk/cmdline-tools/latest/bin/sdkmanager "platforms;android-30" "build-tools;30.0.3" "system-images;android-30;google_apis;x86_64" "emulator" "platform-tools" > /dev/null 2>&1', shell=True)
print('✅ Android SDK & System Image Installed!')
"""

cell2_code = """# CELL 2: Create Dual AVDs & server.py
import subprocess
print('📱 Creating Dual AVDs (2GB Data Partition)...')
subprocess.run('echo "no" | /root/android-sdk/cmdline-tools/latest/bin/avdmanager create avd -n colab_android_1 -k "system-images;android-30;google_apis;x86_64" -c 2048M --force', shell=True)
subprocess.run('echo "no" | /root/android-sdk/cmdline-tools/latest/bin/avdmanager create avd -n colab_android_2 -k "system-images;android-30;google_apis;x86_64" -c 2048M --force', shell=True)

server_code = '''import os, re, subprocess
from concurrent.futures import ThreadPoolExecutor
from typing import Optional
from fastapi import FastAPI, UploadFile, File, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response, JSONResponse
from pydantic import BaseModel

app = FastAPI(title="Colab Dual-Android ADB Microservice", version="3.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

executor = ThreadPoolExecutor(max_workers=4)

DEV_MAP = {
    "1": "emulator-5554",
    "2": "emulator-5556",
    "emulator-5554": "emulator-5554",
    "emulator-5556": "emulator-5556"
}

def resolve_device(dev: str) -> str:
    return DEV_MAP.get(str(dev).lower(), "emulator-5554")

def run_adb(cmd: str, target_device: Optional[str] = None) -> str:
    adb_path = "/root/android-sdk/platform-tools/adb"
    dev_flag = f"-s {target_device} " if target_device else ""
    full_cmd = f"{adb_path} {dev_flag}{cmd}"
    res = subprocess.run(full_cmd, shell=True, capture_output=True, text=True)
    return res.stdout + res.stderr

@app.get("/health")
def health():
    output = run_adb("devices")
    dev1_ready = re.search(r"emulator-5554\\\\s+device", output) is not None
    dev2_ready = re.search(r"emulator-5556\\\\s+device", output) is not None
    return {
        "status": "ok" if (dev1_ready and dev2_ready) else "starting",
        "emulator_1": "online" if dev1_ready else "offline/booting",
        "emulator_2": "online" if dev2_ready else "offline/booting",
        "adb_output": output.strip()
    }

@app.post("/install")
async def install_apk(file: UploadFile = File(...), device: str = Query("all")):
    temp_path = f"/tmp/{file.filename}"
    with open(temp_path, "wb") as f:
        f.write(await file.read())
    
    targets = ["emulator-5554", "emulator-5556"] if device.lower() == "all" else [resolve_device(device)]
    
    def do_install(target):
        out = run_adb(f"install -r {temp_path}", target_device=target)
        return target, {"success": "Success" in out, "output": out.strip()}
    
    futures = [executor.submit(do_install, t) for t in targets]
    results = dict(f.result() for f in futures)
    
    if os.path.exists(temp_path):
        try:
            os.remove(temp_path)
        except Exception:
            pass
            
    return {"results": results}

class LaunchReq(BaseModel):
    package_name: str
    activity: Optional[str] = None
    device: str = "2"

@app.post("/launch")
def launch_app(req: LaunchReq):
    target = resolve_device(req.device)
    if req.activity:
        act = req.activity if ("." in req.activity) else f".{req.activity}"
        cmd = f"shell am start -n {req.package_name}/{act}"
    else:
        cmd = f"shell monkey -p {req.package_name} -c android.intent.category.LAUNCHER 1"
    
    out = run_adb(cmd, target_device=target)
    return {"target_device": target, "status": "launched", "output": out.strip()}

@app.get("/logcat")
def get_logcat(device: str = Query("2"), lines: int = 150, filter_tag: Optional[str] = None):
    target = resolve_device(device)
    cmd = f"logcat -d -t {lines}"
    if filter_tag:
        cmd += f" -s {filter_tag}"
    logs = run_adb(cmd, target_device=target)
    return {"target_device": target, "logs": logs}

@app.get("/layout")
def get_layout(device: str = Query("2")):
    target = resolve_device(device)
    run_adb("shell rm -f /sdcard/dump.xml", target_device=target)
    run_adb("shell uiautomator dump /sdcard/dump.xml", target_device=target)
    xml_content = run_adb("shell cat /sdcard/dump.xml", target_device=target)
    return {"target_device": target, "raw_xml": xml_content}

@app.get("/diff_layout")
def diff_layout():
    run_adb("shell rm -f /sdcard/dump.xml", target_device="emulator-5554")
    run_adb("shell uiautomator dump /sdcard/dump.xml", target_device="emulator-5554")
    xml_1 = run_adb("shell cat /sdcard/dump.xml", target_device="emulator-5554")
    
    run_adb("shell rm -f /sdcard/dump.xml", target_device="emulator-5556")
    run_adb("shell uiautomator dump /sdcard/dump.xml", target_device="emulator-5556")
    xml_2 = run_adb("shell cat /sdcard/dump.xml", target_device="emulator-5556")
    
    return {
        "emulator_1_control": xml_1,
        "emulator_2_adblocked": xml_2
    }

@app.get("/screen")
def get_screen(device: str = Query("2")):
    target = resolve_device(device)
    adb_path = "/root/android-sdk/platform-tools/adb"
    res = subprocess.run(f"{adb_path} -s {target} exec-out screencap -p", shell=True, capture_output=True)
    if res.stdout and len(res.stdout) > 100:
        return Response(content=res.stdout, media_type="image/png")
    raise HTTPException(status_code=500, detail="Failed to capture screenshot")

class ShellReq(BaseModel):
    command: str
    device: str = "2"

@app.post("/shell")
def exec_shell(req: ShellReq):
    target = resolve_device(req.device)
    out = run_adb(f"shell {req.command}", target_device=target)
    return {"target_device": target, "output": out.strip()}
'''

with open('/content/server.py', 'w') as f:
    f.write(server_code)

print('✅ FastAPI Server code created successfully!')
"""

cell3_code = """# CELL 3: Boot Dual Emulators & Launch Cloudflare Tunnel Background Daemon
import time, subprocess
print('🚀 Booting Dual Emulators & Starting Services...')
subprocess.run('pkill -9 -f emulator > /dev/null 2>&1', shell=True)
subprocess.run('pkill -9 -f uvicorn > /dev/null 2>&1', shell=True)
subprocess.run('pkill -9 -f cloudflared > /dev/null 2>&1', shell=True)
subprocess.run('/root/android-sdk/platform-tools/adb kill-server > /dev/null 2>&1', shell=True)
subprocess.run('/root/android-sdk/platform-tools/adb start-server > /dev/null 2>&1', shell=True)
time.sleep(2)

subprocess.run('nohup /root/android-sdk/emulator/emulator -avd colab_android_1 -partition-size 2048 -memory 2048 -no-accel -gpu swiftshader_indirect -no-window -no-audio -no-boot-anim -port 5554 > /tmp/emulator1.log 2>&1 &', shell=True)
subprocess.run('nohup /root/android-sdk/emulator/emulator -avd colab_android_2 -partition-size 2048 -memory 2048 -no-accel -gpu swiftshader_indirect -no-window -no-audio -no-boot-anim -port 5556 > /tmp/emulator2.log 2>&1 &', shell=True)

print('⏳ Waiting for Android OS Kernel & Package Manager Service (~45-75 seconds)...')
while True:
    b1 = subprocess.run('/root/android-sdk/platform-tools/adb -s emulator-5554 shell getprop sys.boot_completed', shell=True, capture_output=True, text=True)
    b2 = subprocess.run('/root/android-sdk/platform-tools/adb -s emulator-5556 shell getprop sys.boot_completed', shell=True, capture_output=True, text=True)
    p1 = subprocess.run('/root/android-sdk/platform-tools/adb -s emulator-5554 shell pm path android', shell=True, capture_output=True, text=True)
    p2 = subprocess.run('/root/android-sdk/platform-tools/adb -s emulator-5556 shell pm path android', shell=True, capture_output=True, text=True)
    ready1 = b1.stdout.strip() == '1' and p1.stdout.strip().startswith('package:')
    ready2 = b2.stdout.strip() == '1' and p2.stdout.strip().startswith('package:')
    if ready1 and ready2:
        print('\\n🎉 BOTH Emulators (emulator-5554 & emulator-5556) are 100% ONLINE!')
        break
    print(f'[{\\"E1:Ready\\" if ready1 else \\\"E1:Booting\\\"}, {\\\"E2:Ready\\" if ready2 else \\\"E2:Booting\\\"}]', end=' ', flush=True)
    time.sleep(5)

print('\\n🚀 Launching FastAPI Server on Port 8000...')
subprocess.run('nohup uvicorn server:app --host 0.0.0.0 --port 8000 > /tmp/server8000.log 2>&1 &', shell=True)
time.sleep(3)

print('🌐 Launching Cloudflare Tunnel Background Daemon...')
subprocess.run('wget -q https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64.deb', shell=True)
subprocess.run('dpkg -i cloudflared-linux-amd64.deb > /dev/null 2>&1', shell=True)
with open('/tmp/config.yml', 'w') as f:
    f.write('ingress:\\n  - service: http://localhost:8000\\n  - service: http_status:404\\n')

TUNNEL_TOKEN = 'eyJhIjoiZDU4NWQ3ZmFlZWZhZjgyMTIxNWQ0OTc1ZThkYTFkNzEiLCJzIjoiNnd4N0llMGtWWmlXOElrZS9xajZiMSttcFVSbVEyRzFCQUpFajIzNUs1WT0iLCJ0IjoiYzIyMzU1N2YtZDg4Zi00ZmEzLWI2N2QtYTM2ODM4NTRjZDRjIn0='
subprocess.Popen(f'nohup cloudflared tunnel --config /tmp/config.yml run --token {TUNNEL_TOKEN} > /tmp/tunnel.log 2>&1 &', shell=True)
time.sleep(5)
print('✨ Automated Setup Complete! Cloudflare Tunnel & FastAPI Server are Running detached in Colab background.')
"""

notebook = {
 "cells": [
  {
   "cell_type": "markdown",
   "metadata": {},
   "source": [
    "# 🚀 Google Colab Dual-Android Emulator Microservice (`AndroidEmulator`)\n",
    "This notebook downloads the Android SDK, creates dual AVD emulators with 2GB storage, launches FastAPI, and connects to Cloudflare Tunnel."
   ]
  },
  {
   "cell_type": "code",
   "execution_count": None,
   "metadata": {},
   "outputs": [],
   "source": cell1_code.splitlines(keepends=True)
  },
  {
   "cell_type": "code",
   "execution_count": None,
   "metadata": {},
   "outputs": [],
   "source": cell2_code.splitlines(keepends=True)
  },
  {
   "cell_type": "code",
   "execution_count": None,
   "metadata": {},
   "outputs": [],
   "source": cell3_code.splitlines(keepends=True)
  }
 ],
 "metadata": {
  "language_info": {
   "name": "python"
  }
 },
 "nbformat": 4,
 "nbformat_minor": 2
}

with open(r'c:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\AndroidEmulator.ipynb', 'w', encoding='utf-8') as f:
    json.dump(notebook, f, indent=2)

print("Generated clean AndroidEmulator.ipynb successfully!")
