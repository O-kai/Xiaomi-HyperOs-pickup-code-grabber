#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从已连接的安卓手机里提取某个 App 的**官方启动图标**，直接落到本目录（与 assets/appicons 同步）。

为什么需要这个脚本：部分 App（例如「菜鸟裹裹」com.cainiao.guoguo）没有可公开下载的图标素材——
iOS 端已下架、国内各家商店（应用宝/豌豆荚/小米/酷安）的页面都是前端异步渲染或已改版，
程序化拿不到。而**你手机上就装着它**，从已安装的 APK 里取图标是最准、最省事的做法。

前置条件
--------
* 手机开启「开发者选项 → USB 调试」，用数据线连电脑，弹窗点「允许」
* 电脑上有 adb（Android SDK platform-tools）与 aapt2（build-tools）；脚本会自动在
  %LOCALAPPDATA%\\Android\\Sdk 下寻找，找不到时回退到 PATH

用法
----
    python extract-from-device.py com.cainiao.guoguo cainiao-guoguo
                                ^包名              ^输出文件名（不含扩展名）

之后：
    app/assets/appicons/<名字>.png   ← 脚本会自动同步一份
    prototype/icons/<名字>.png       ← 同时写一份给原型用
重新打包即可看到新图标：gradlew assembleDebug
"""
import glob
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
ASSETS = os.path.join(REPO, 'app', 'assets', 'appicons')


def find_tool(name, pats):
    for p in pats:
        hits = sorted(glob.glob(p), reverse=True)
        if hits:
            return hits[0]
    hit = shutil.which(name)
    return hit


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    pkg, out_name = sys.argv[1], sys.argv[2]

    sdk = os.environ.get('ANDROID_HOME') or os.path.join(
        os.environ.get('LOCALAPPDATA', ''), 'Android', 'Sdk')
    adb = find_tool('adb', [os.path.join(sdk, 'platform-tools', 'adb.exe')])
    aapt2 = find_tool('aapt2', [os.path.join(sdk, 'build-tools', '*', 'aapt2.exe')])
    if not adb or not aapt2:
        print('找不到 adb 或 aapt2，请安装 Android SDK 的 platform-tools 与 build-tools')
        sys.exit(2)

    devices = subprocess.run([adb, 'devices'], capture_output=True, text=True).stdout
    serials = [l.split()[0] for l in devices.splitlines()[1:] if l.strip().endswith('device')]
    if not serials:
        print('没有已授权的设备。请在手机上允许 USB 调试后重试。')
        print(devices)
        sys.exit(2)
    serial = serials[0]
    print('使用设备:', serial)

    line = subprocess.run([adb, '-s', serial, 'shell', 'pm', 'path', pkg],
                          capture_output=True, text=True).stdout.strip()
    if 'package:' not in line:
        print('设备上没找到这个包：', pkg, '\n', line)
        sys.exit(2)
    apks = [l.replace('package:', '').strip() for l in line.splitlines() if l.startswith('package:')]
    base = [a for a in apks if 'base.apk' in a] or apks
    print('APK:', base[0])

    with tempfile.TemporaryDirectory() as tmp:
        local = os.path.join(tmp, 'base.apk')
        subprocess.run([adb, '-s', serial, 'pull', base[0], local],
                       capture_output=True, text=True, check=True)
        badging = subprocess.run([aapt2, 'dump', 'badging', local],
                                 capture_output=True, text=True).stdout
        # application-icon-640:'res/xxx.png'（取密度最高的一条）
        cands = [(int(m.group(1)), m.group(2)) for m in
                 __import__('re').finditer(r"application-icon-(\d+):'([^']+)'", badging)]
        if not cands:
            print('没解析到启动图标条目，badging 输出片段：\n', badging[:400])
            sys.exit(1)
        cands.sort()
        path_in_apk = cands[-1][1]
        print('图标资源:', path_in_apk)
        with zipfile.ZipFile(local) as z:
            data = z.read(path_in_apk)
        ext = os.path.splitext(path_in_apk)[1] or '.png'
        dst_proto = os.path.join(HERE, out_name + ext)
        os.makedirs(ASSETS, exist_ok=True)
        dst_asset = os.path.join(ASSETS, out_name + ext)
        for dst in (dst_proto, dst_asset):
            with open(dst, 'wb') as f:
                f.write(data)
            print('已写入:', dst, '(%.1f KB)' % (len(data) / 1024))

    print('\n完成。重新打包后生效：gradlew assembleDebug')
    print('若原先是 .svg 兜底图，现在有了同名 .png，页面会优先用 PNG。')


if __name__ == '__main__':
    main()
