import os
import re
import io
import subprocess
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor
from typing import Optional
from fastapi import FastAPI, UploadFile, File, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response, JSONResponse
from pydantic import BaseModel

app = FastAPI(title="Local Dual-Android ADB Microservice", version="4.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

executor = ThreadPoolExecutor(max_workers=4)

ADB_PATH = r"C:\Users\Default.L-HCG-9FVVGS3\AppData\Local\Android\Sdk\platform-tools\adb.exe"

DEV_MAP = {
    "1": "emulator-5554",
    "2": "emulator-5556",
    "emulator-5554": "emulator-5554",
    "emulator-5556": "emulator-5556"
}

def resolve_device(dev: str) -> str:
    return DEV_MAP.get(str(dev).lower(), "emulator-5554")

def run_adb(cmd: str, target_device: Optional[str] = None) -> str:
    dev_flag = f"-s {target_device} " if target_device else ""
    full_cmd = f'"{ADB_PATH}" {dev_flag}{cmd}'
    res = subprocess.run(full_cmd, shell=True, capture_output=True, text=True)
    return res.stdout + res.stderr

@app.get("/health")
def health():
    output = run_adb("devices")
    dev1_ready = re.search(r"emulator-5554\s+device", output) is not None
    dev2_ready = re.search(r"emulator-5556\s+device", output) is not None
    return {
        "status": "ok" if (dev1_ready and dev2_ready) else "booting",
        "emulator_1": "online" if dev1_ready else "offline/booting",
        "emulator_2": "online" if dev2_ready else "offline/booting",
        "adb_output": output.strip()
    }

@app.post("/install")
async def install_apk(file: UploadFile = File(...), device: str = Query("all")):
    temp_path = f"C:\\Windows\\Temp\\{file.filename}"
    with open(temp_path, "wb") as f:
        f.write(await file.read())
    
    targets = ["emulator-5554", "emulator-5556"] if device.lower() == "all" else [resolve_device(device)]
    
    def do_install(target):
        out = run_adb(f'install -r "{temp_path}"', target_device=target)
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
    full_cmd = f'"{ADB_PATH}" -s {target} exec-out screencap -p'
    res = subprocess.run(full_cmd, shell=True, capture_output=True)
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
