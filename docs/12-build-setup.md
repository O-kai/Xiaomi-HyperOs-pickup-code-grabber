# 12 · v2 构建环境与流程（2026-09-03 已打通）

## 一、构建环境（全部在手机 Termux，已就绪）

| 组件 | 路径/说明 |
|---|---|
| JDK | Termux `openjdk` 21.0.12（/data/data/com.termux/files/usr/bin/javac） |
| aapt2 / d8 / apksigner / zip / unzip / keytool | Termux 官方包（已装） |
| android.jar | `~/android-sdk/platforms/android-34/android.jar`（API 34） |
| **xposed-api-82.jar（官方）** | `~/xposed-api-82.jar`（25KB；来源：阿里云镜像 `maven.aliyun.com/repository/public/de/robv/android/xposed/api/82/api-82.jar`；官方 api.xposed.info 国内超时） |
| keystore | `~/.pickup-debug.keystore`（debug 签名，首次构建自动生成） |

**关键点**：使用官方 api jar 编译 → `handleLoadPackage(XC_LoadPackage.LoadPackageParam)`
签名天然正确 → 历史 AbstractMethodError 从根上消除，不再有任何手工 stub。

## 二、构建脚本

`build/build_termux.sh`（驻留仓库，推送到手机 `/sdcard/Download/pickup-code-grabber/` 后执行）：

```bash
su -c '/data/data/com.termux/files/usr/bin/bash /sdcard/Download/pickup-code-grabber/build/build_termux.sh'
```

脚本内路径均可用环境变量覆盖（见脚本头部注释）。流程：aapt2 compile → aapt2 link →
javac（`--release 11 -encoding UTF-8`，classpath android.jar + xposed jar）→ d8（`--min-api 28`）
→ **增量打包**（在 linked.apk 上追加 classes.dex + assets/xposed_init，保护 resources.arsc
不压缩且对齐）→ apksigner 签名。

产物：`/sdcard/Download/pickup-code-grabber/pickup-code-grabber.apk`（~21KB）

## 三、历史踩坑记录（本环境）

| 坑 | 现象 | 解决 |
|---|---|---|
| 文件路径笔误 | `!! 缺少 android.jar` | Termux HOME = `files/home`（非 `files/usr/home`） |
| PATH 缺 java | `d8: exec: java: not found` | 脚本头 `export PATH="$PREFIX/bin:$PATH"` |
| arsc 压缩 | 安装失败 `resources.arsc stored uncompressed and aligned` | 增量打包（不重新压缩整包） |
| 签名冲突 | `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 卸载旧版（原版旧签名）后安装 |
| 中文源码乱码 | （预防性） | javac 加 `-encoding UTF-8` |

## 四、安装/迭代回路（均由 AI 通过 adb 驱动）

```
改代码(仓库 app/src) → push 到 /sdcard/Download/pickup-code-grabber/app →
su bash build/build_termux.sh → pull apk → adb install -r → 用户 LSPosed 激活/重启 → 验证
```

## 五、P1 验证步骤（用户操作）

1. LSPosed → 模块 → 取件码助手 → 启用；作用域：系统框架 / com.android.phone /
   com.android.mms / com.miui.notes
2. 重启手机
3. 发送一条测试短信给本机
4. 检查：`adb logcat -s PICKUPDEBUG`（希望看到 `S1/S2/S3 OK` + `handleLoadPackage` +
   `SMS: sender=... body=...`）
