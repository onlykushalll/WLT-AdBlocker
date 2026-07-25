import subprocess

print("=== ADB DEVICES ===")
print(subprocess.run("/root/android-sdk/platform-tools/adb devices", shell=True, capture_output=True, text=True).stdout)

print("=== UVICORN LOG ===")
print(subprocess.run("cat /tmp/server8000.log 2>/dev/null | tail -n 20", shell=True, capture_output=True, text=True).stdout)

print("=== TUNNEL LOG ===")
print(subprocess.run("cat /tmp/tunnel.log 2>/dev/null | tail -n 20", shell=True, capture_output=True, text=True).stdout)

print("=== EMULATOR 1 LOG ===")
print(subprocess.run("cat /tmp/emulator1.log 2>/dev/null | tail -n 10", shell=True, capture_output=True, text=True).stdout)

print("=== EMULATOR 2 LOG ===")
print(subprocess.run("cat /tmp/emulator2.log 2>/dev/null | tail -n 10", shell=True, capture_output=True, text=True).stdout)
