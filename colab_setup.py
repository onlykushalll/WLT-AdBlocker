import os
import re
import sys
import time
import subprocess

print("==================================================================")
print("🚀 Starting Automated Colab Dual-Android Microservice Setup")
print("==================================================================", flush=True)

# Step 1: Install Dependencies & SDK
print("\n📦 [1/3] Installing System Packages & Android SDK...", flush=True)
subprocess.run("dpkg --configure -a > /dev/null 2>&1", shell=True)
subprocess.run("apt-get update -qq > /dev/null 2>&1", shell=True)
subprocess.run("apt-get install -y -qq openjdk-17-jdk wget unzip curl libglu1-mesa > /dev/null 2>&1", shell=True)
subprocess.run("pip install -q fastapi uvicorn python-multipart requests pydantic", shell=True)

os.environ["ANDROID_HOME"] = "/root/android-sdk"
os.environ["PATH"] += ":/root/android-sdk/cmdline-tools/latest/bin:/root/android-sdk/platform-tools:/root/android-sdk/emulator"

subprocess.run("mkdir -p /root/android-sdk/cmdline-tools /root/.android", shell=True)
subprocess.run("touch /root/.android/repositories.cfg", shell=True)

if not os.path.exists("/root/android-sdk/cmdline-tools/latest/bin/sdkmanager"):
    print("⏳ Downloading Android Cmdline Tools...", flush=True)
    subprocess.run("wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/cmdline.zip", shell=True)
    subprocess.run("unzip -q /tmp/cmdline.zip -d /root/android-sdk/cmdline-tools", shell=True)
    subprocess.run("rm -rf /root/android-sdk/cmdline-tools/latest", shell=True)
    subprocess.run("mv /root/android-sdk/cmdline-tools/cmdline-tools /root/android-sdk/cmdline-tools/latest", shell=True)

print("⏳ Downloading Bulletproof System Image (API 28 Google APIs x86_64)...", flush=True)
subprocess.run("yes | /root/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null 2>&1", shell=True)
subprocess.run('/root/android-sdk/cmdline-tools/latest/bin/sdkmanager "platforms;android-28" "build-tools;28.0.3" "system-images;android-28;google_apis;x86_64" "emulator" "platform-tools" > /dev/null 2>&1', shell=True)
print("✅ Android SDK Installed!", flush=True)

# Step 2: Wiping Stale AVDs & Creating Dual API 28 AVDs
print("\n📱 [2/3] Creating Dual API 28 AVDs & FastAPI Microservice...", flush=True)
if not os.path.exists("/root/.android/avd/colab_android_1.avd"):
    subprocess.run('echo "no" | /root/android-sdk/cmdline-tools/latest/bin/avdmanager create avd -n colab_android_1 -k "system-images;android-28;google_apis;x86_64" -c 2048M --force', shell=True)
    subprocess.run('echo "no" | /root/android-sdk/cmdline-tools/latest/bin/avdmanager create avd -n colab_android_2 -k "system-images;android-28;google_apis;x86_64" -c 2048M --force', shell=True)

    avd1_ini = "/root/.android/avd/colab_android_1.avd/config.ini"
    avd2_ini = "/root/.android/avd/colab_android_2.avd/config.ini"
    ini_addon = "\ndisk.dataPartition.size=2048M\nhw.ramSize=2048\nvm.heapSize=256\n"

    if os.path.exists(avd1_ini):
        with open(avd1_ini, "a") as f:
            f.write(ini_addon)
    if os.path.exists(avd2_ini):
        with open(avd2_ini, "a") as f:
            f.write(ini_addon)

server_code = """
import os
import re
import subprocess
import xml.etree.ElementTree as ET
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
    dev1_ready = re.search(r"emulator-5554\\s+device", output) is not None
    dev2_ready = re.search(r"emulator-5556\\s+device", output) is not None
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
        target_apk = f"/data/local/tmp/{file.filename}"
        run_adb(f"push {temp_path} {target_apk}", target_device=target)
        out = run_adb(f"shell pm install -r {target_apk}", target_device=target)
        run_adb(f"shell rm -f {target_apk}", target_device=target)
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
"""

with open("server.py", "w") as f:
    f.write(server_code)

print("✅ FastAPI Server code updated with container-compatible push+install!", flush=True)

# Step 3: Restart FastAPI Server & Cloudflare
print("\n🚀 [3/3] Restarting Microservice Engine...", flush=True)
subprocess.run("pkill -9 -f uvicorn > /dev/null 2>&1", shell=True)
time.sleep(1)

print("🚀 Launching Updated FastAPI Server on Port 8000...", flush=True)
subprocess.run("nohup uvicorn server:app --host 0.0.0.0 --port 8000 > /tmp/server8000.log 2>&1 &", shell=True)
time.sleep(2)

print("✨ FastAPI Server updated and LIVE!", flush=True)
sys.exit(0)
