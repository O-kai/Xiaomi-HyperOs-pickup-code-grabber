#!/usr/bin/env bash
# 安装 release APK 到已连接设备。
#
# 说明 1：这台 HyperOS 设备上，shell 身份执行 adb install 会被系统判成
#   INSTALL_FAILED_USER_RESTRICTED（安装确认被系统直接取消），
#   走 root 的 pm install 才能装上，所以这里统一用 root。
# 说明 2：Git Bash 下 adb 需要 Windows 路径，用 cygpath 转换。
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB:-D:/develop/android-sdk/platform-tools/adb.exe}"
APK="$(cygpath -w "$ROOT/app/build/outputs/apk/release/app-release.apk")"
TMP=/data/local/tmp/pickup_release.apk
"$ADB" push "$APK" "$TMP" >/dev/null
"$ADB" shell su -c "pm install -r -g $TMP"
"$ADB" shell su -c "rm -f $TMP"
"$ADB" shell dumpsys package io.github.okaidev.pickupcode | grep -E 'versionCode|versionName' | head -2
