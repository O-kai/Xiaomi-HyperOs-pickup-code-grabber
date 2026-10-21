"""按文本精确点击 / 查找控件（解决靠猜坐标效率低的问题）。

用法：
  python tap.py "文本"          # 点击包含该文本的控件
  python tap.py "文本" --find   # 只打印坐标不点击
  python tap.py "文本" --exact  # 精确匹配
"""
import re
import subprocess
import sys
import time

ADB = r"D:\develop\android-sdk\platform-tools\adb.exe"
DUMP_LOCAL = r"D:\project\Xiaomi-HyperOs-pickup-code-grabber\tools\device-probe\shots\_dump.xml"


def dump():
    subprocess.run([ADB, "shell", "uiautomator", "dump", "/data/local/tmp/_d.xml"],
                   capture_output=True, timeout=120)
    subprocess.run([ADB, "pull", "/data/local/tmp/_d.xml", DUMP_LOCAL],
                   capture_output=True, timeout=120)
    with open(DUMP_LOCAL, encoding="utf-8", errors="replace") as f:
        return f.read()


def find(text, exact=False):
    xml = dump()
    hits = []
    for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        t = m.group(1)
        ok = (t == text) if exact else (text in t)
        if ok:
            x1, y1, x2, y2 = map(int, m.groups()[1:])
            hits.append((t, (x1 + x2) // 2, (y1 + y2) // 2))
    return hits


def main():
    if len(sys.argv) < 2:
        sys.exit("用法: python tap.py <文本> [--find] [--exact]")
    text = sys.argv[1]
    only_find = "--find" in sys.argv
    exact = "--exact" in sys.argv

    hits = find(text, exact)
    if not hits:
        print("未找到：%s" % text)
        sys.exit(1)

    for t, x, y in hits:
        print("%s -> (%d, %d)" % (t, x, y))

    if not only_find:
        t, x, y = hits[0]
        subprocess.run([ADB, "shell", "input", "tap", str(x), str(y)], timeout=60)
        print("已点击 (%d, %d)" % (x, y))
        time.sleep(1.5)


if __name__ == "__main__":
    main()
