#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
真机探针工具（device-probe）

用途：在已连接的小米设备上验证「短信 → 识别 → 写入待办」全链路，
      不改动手机数据之外的东西，只在需要时往小米笔记待办里写测试条目。

用法：
  python probe.py inject "<短信原文>" [--sender 1069888877] [--code 期望码]
  python probe.py list   [--limit 10]
  python probe.py doctor
  python probe.py ui                     # 截图到 ./shots/

依赖：adb 在 PATH，或设置环境变量 ADB_PATH；设备已 root（读取待办库需要）。

说明：
  - 「inject」走模块自己的 ContentProvider（content://...pickupcode.provider 的 onSms），
    与真实短信走同一条判定/写入链路，但不会消耗真实短信、不会污染短信库。
  - 同一取件码会被去重（返回 result=[]），验证时请用没测过的码。
  - 「list」读取小米笔记待办库，需要 root + 模块内置 sqlite3
    （/data/local/tmp/pickup_sqlite/sqlite3）。
"""

import argparse
import os
import subprocess
import sys
import time

ADB = os.environ.get("ADB_PATH", "adb")
PKG = "io.github.okaidev.pickupcode"
PROVIDER = "content://%s.provider" % PKG
SQ = "/data/local/tmp/pickup_sqlite/sqlite3"
NOTES_DB = "/data/user/0/com.miui.notes/databases/todo.db"


def run(args, timeout=90):
    """执行命令，返回 (rc, stdout, stderr)，全部 UTF-8 解码。"""
    try:
        p = subprocess.run([ADB] + args, capture_output=True, timeout=timeout)
        return (p.returncode,
                p.stdout.decode("utf-8", "replace"),
                p.stderr.decode("utf-8", "replace"))
    except FileNotFoundError:
        sys.exit("找不到 adb，请设置环境变量 ADB_PATH")
    except subprocess.TimeoutExpired:
        return (-1, "", "timeout")


def require_device():
    rc, out, err = run(["devices"])
    lines = [l for l in out.splitlines()[1:] if l.strip()]
    if not lines:
        sys.exit("没有检测到设备，请先连接手机并确认 adb devices 能看到它")
    print("设备：%s" % lines[0].strip())
    # 每次都现场读设备身份，不靠历史印象（用户明确要求过）
    print("机型：%s" % device_label())


def prop(key):
    """读一个系统属性，读不到返回空串。"""
    rc, out, err = run(["shell", "getprop", key], timeout=30)
    return out.strip() if rc == 0 else ""


def device_label():
    """拼出「市场名（型号 / 代号）· Android X（SDK n）· ROM」这样一行可核对的身份串。"""
    market = prop("ro.product.marketname") or prop("ro.product.model") or "未知机型"
    model = prop("ro.product.model")
    device = prop("ro.product.device")
    rel = prop("ro.build.version.release")
    sdk = prop("ro.build.version.sdk")
    os_ver = prop("ro.mi.os.version.name")
    os_inc = prop("ro.mi.os.version.incremental")
    miui = prop("ro.miui.ui.version.name")

    parts = [market]
    extra = "/".join(x for x in (model, device) if x)
    if extra and extra != market:
        parts.append("(%s)" % extra)
    sysinfo = []
    if rel:
        sysinfo.append("Android %s" % rel)
    if sdk:
        sysinfo.append("SDK %s" % sdk)
    if sysinfo:
        parts.append("· " + " ".join(sysinfo))
    rom = os_ver or miui
    if rom:
        parts.append("· " + rom + ((" " + os_inc) if os_inc else ""))
    return " ".join(parts)


def cmd_info(a):
    """完整设备信息（排查兼容性问题时贴给开发者用）。"""
    require_device()
    print()
    keys = [
        ("市场名", "ro.product.marketname"),
        ("型号", "ro.product.model"),
        ("代号", "ro.product.device"),
        ("品牌", "ro.product.brand"),
        ("厂商", "ro.product.manufacturer"),
        ("Android", "ro.build.version.release"),
        ("SDK", "ro.build.version.sdk"),
        ("HyperOS", "ro.mi.os.version.name"),
        ("OS 增量版本", "ro.mi.os.version.incremental"),
        ("MIUI 版本", "ro.miui.ui.version.name"),
        ("安全补丁", "ro.build.version.security_patch"),
        ("序列号", "ro.serialno"),
    ]
    for label, k in keys:
        print("%-12s %s" % (label, prop(k) or "(无)"))
    rc, out, err = run(["shell", "wm", "size"], timeout=30)
    print("%-12s %s" % ("屏幕", out.strip()))
    rc, out, err = run(["shell", "wm", "density"], timeout=30)
    print("%-12s %s" % ("密度", out.strip()))
    return 0


def cmd_inject(a):
    require_device()
    ts = str(int(time.time() * 1000))
    rc, out, err = run([
        "shell", "content", "call", "--uri", PROVIDER, "--method", "onSms",
        "--extra", "sender:s:" + a.sender,
        "--extra", "body:s:" + a.text,
        "--extra", "ts:l:" + ts,
    ])
    raw = (out + err).strip()
    print("原始返回：%s" % raw[-200:])

    got = None
    if "result=[" in raw:
        seg = raw.split("result=[")[1].split("]")[0].strip()
        got = seg or None

    if got is None:
        print("结果：未提取到取件码")
        print("      （若该码此前已写入过，会因去重返回空——请换一个没测过的码）")
    else:
        print("结果：提取到 %s" % got)

    if a.code:
        if got == a.code:
            print("判定：PASS（与期望一致）")
            return 0
        print("判定：FAIL（期望 %s，实际 %s）" % (a.code, got))
        return 1
    return 0


def cmd_list(a):
    require_device()
    sql = "SELECT content FROM todo ORDER BY create_time DESC LIMIT %d;\n" % a.limit
    local = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_probe_q.sql")
    with open(local, "w", encoding="utf-8", newline="\n") as f:
        f.write(sql)

    rc, out, err = run(["push", local, "/data/local/tmp/_probe_q.sql"])
    if rc != 0:
        print("推送 SQL 失败：%s" % (out + err).strip()[:200])
        return 1
    try:
        os.remove(local)
    except OSError:
        pass

    rc, out, err = run(["shell", "su", "-c",
                        "%s %s < /data/local/tmp/_probe_q.sql" % (SQ, NOTES_DB)])
    body = out.strip()
    if not body:
        print("（没有读到内容）")
        if err.strip():
            print("错误：%s" % err.strip()[:300])
        return 1
    print("--- 小米笔记待办（最新 %d 条）---" % a.limit)
    print(body)
    return 0


def cmd_doctor(a):
    require_device()
    print()
    checks = [
        ("模块是否安装", ["shell", "pm", "list", "packages", PKG]),
        ("通知钩子开关", ["shell", "cat", "/data/local/tmp/pickup_sqlite/enable_noti_hook"]),
        ("内置 sqlite3", ["shell", "ls", "-l", SQ]),
        ("小米笔记是否安装", ["shell", "pm", "list", "packages", "com.miui.notes"]),
        ("root 是否可用", ["shell", "su", "-c", "id"]),
    ]
    for name, args in checks:
        rc, out, err = run(args, timeout=45)
        val = (out + err).strip().replace("\n", " ")[:120]
        ok = bool(val) and "No such" not in val and "not found" not in val
        print("[%s] %-16s %s" % ("OK " if ok else "!! ", name, val or "(空)"))
    return 0


def cmd_ui(a):
    require_device()
    d = os.path.join(os.path.dirname(os.path.abspath(__file__)), "shots")
    os.makedirs(d, exist_ok=True)
    name = time.strftime("ui-%Y%m%d-%H%M%S.png")
    remote = "/sdcard/_probe_ui.png"
    run(["shell", "screencap", "-p", remote])
    rc, out, err = run(["pull", remote, os.path.join(d, name)])
    run(["shell", "rm", "-f", remote])
    if rc == 0:
        print("截图已保存：%s" % os.path.join(d, name))
        return 0
    print("截图失败：%s" % (out + err).strip()[:200])
    return 1


def main():
    ap = argparse.ArgumentParser(description="取件码助手 · 真机探针")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("inject", help="注入一条短信，验证识别链路")
    p.add_argument("text", help="短信原文")
    p.add_argument("--sender", default="1069888877", help="发送方号码")
    p.add_argument("--code", help="期望提取到的取件码（给了就自动判定 PASS/FAIL）")
    p.set_defaults(fn=cmd_inject)

    p = sub.add_parser("list", help="列出小米笔记里最新的待办")
    p.add_argument("--limit", type=int, default=10)
    p.set_defaults(fn=cmd_list)

    p = sub.add_parser("doctor", help="检查设备侧前置条件")
    p.set_defaults(fn=cmd_doctor)

    p = sub.add_parser("info", help="打印设备完整信息（机型/系统/分辨率）")
    p.set_defaults(fn=cmd_info)

    p = sub.add_parser("ui", help="截屏保存到 shots/")
    p.set_defaults(fn=cmd_ui)

    a = ap.parse_args()
    sys.exit(a.fn(a))


if __name__ == "__main__":
    main()
