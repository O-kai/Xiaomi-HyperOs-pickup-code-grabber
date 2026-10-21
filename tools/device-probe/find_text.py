import re
import subprocess
import sys

ADB = r"D:\develop\android-sdk\platform-tools\adb.exe"
XML = r"D:\project\Xiaomi-HyperOs-pickup-code-grabber\tools\device-probe\shots\_d2.xml"

keywords = sys.argv[1:] if len(sys.argv) > 1 else ["规则", "黑名单", "过滤", "启用"]

subprocess.run([ADB, "shell", "uiautomator", "dump", "/data/local/tmp/d.xml"],
               capture_output=True, timeout=120)
subprocess.run([ADB, "pull", "/data/local/tmp/d.xml", XML],
               capture_output=True, timeout=120)

with open(XML, encoding="utf-8", errors="replace") as f:
    t = f.read()

pat = re.compile(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
for m in pat.finditer(t):
    txt = m.group(1)
    if not txt:
        continue
    if any(k in txt for k in keywords):
        x1, y1, x2, y2 = map(int, m.groups()[1:])
        print("%-28s center=(%d,%d)" % (txt[:28], (x1 + x2) // 2, (y1 + y2) // 2))
