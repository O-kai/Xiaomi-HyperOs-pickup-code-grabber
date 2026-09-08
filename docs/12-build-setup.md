# 12 · v2 构建环境与流程（2026-09-03 打通；2026-09-09 修正）

## ⚠️ 两条构建路线（先读这段，别再踩坑）

| 路线 | 用途 | 状态 |
|---|---|---|
| **PC Gradle（正式发布用）** | v2.2.0 起所有 release APK（3.5MB，含全量 assets） | ✅ 标准路线 |
| Termux 手工脚本（`build/build_termux.sh`） | 早期 v2.0 时代的 21KB 包 | ⚠️ 2026-09-09 已修复 assets 全量打包，可出完整包；但正式发布一律走 PC Gradle |

**2026-09-09 事故记录**：v2.7.0 曾误用 Termux 旧脚本打包（只装 `assets/xposed_init`，
漏掉 `donate_qr.png` 与 sqlite3 二进制套件 → 70KB 残缺包）。已修复脚本第 5 步为全量
`cp -r assets`；**教训：正式包必须用 PC Gradle 构建，Termux 脚本仅作无 PC 环境的应急**。

## 一、PC Gradle 构建（正式发布路线）

环境（全部在 `D:\project\_build-tools\`，已就位）：

| 组件 | 路径 |
|---|---|
| JDK 17 | `_build-tools\jdk\jdk-17.0.20.1+1` |
| Gradle 8.6 | 项目 gradle wrapper 自动拉取（CI 同款；`_build-tools\gradle-8.9` 为备用） |
| Android SDK | `_build-tools\sdk\`（build-tools 34.0.0 + platform android-34 + licenses 已接受） |
| 镜像 | `settings.gradle` 内已配 aliyun（maven.aliyun.com，国内可达） |

构建（在公开仓库 `D:\project\Xiaomi-HyperOs-pickup-code-grabber`）：

```powershell
$env:JAVA_HOME = "D:\project\_build-tools\jdk\jdk-17.0.20.1+1"
$env:ANDROID_HOME = "D:\project\_build-tools\sdk"
$env:GRADLE_USER_HOME = "D:\project\_build-tools\gradle-home"   # 若首次未设，见下方说明
.\gradlew.bat assembleRelease "-PPICKUP_KEYSTORE=D:\project\sms-pickup-module\release\pickup-release.keystore" "-PPICKUP_KEYSTORE_PASS=<密码见 release/keystore-pass.txt>" "-PPICKUP_KEY_ALIAS=pickupcode"
# 产物：app\build\outputs\apk\release\app-release.apk → 改名 artifact\pickup-code-grabber-vX.Y.Z-release.apk
```

**验包三件套**（发布前必做）：

```powershell
# 1) 签名（V3 + 生产证书 CN=PickupCodeGrabber）
D:\project\_build-tools\sdk\build-tools\34.0.0\apksigner.bat verify --print-certs <apk>
# 2) 版本/包名（versionCode 270 / 2.7.0 / io.github.okaidev.pickupcode）
D:\project\_build-tools\sdk\build-tools\34.0.0\aapt2.exe dump badging <apk>
# 3) 大小直觉检查：v2.7.0 完整包应 ≈3.5MB；若 <100KB = assets 缺失，禁止发布
```

## 二、Termux 应急路线（脚本已修复，仅无 PC 时用）

`build/build_termux.sh`（2026-09-09 修复版：第 5 步全量拷贝 assets）：

```bash
su -c '/data/data/com.termux/files/usr/bin/bash /sdcard/Download/pickup-v2/build/build_termux.sh --release'
```

产物 `/sdcard/Download/pickup-v2/pickup-code-grabber.apk`；**构建后同样跑上面验包三件套**。

## 三、安装/迭代回路（adb 驱动）

```
改代码(PC 工作区 app/src) → 同步到公开仓库 → PC Gradle 构建 →
adb install -r → LSPosed 激活/重启 → 验证（uiautomator dump / PICKUPDEBUG 日志）
```

## 四、历史踩坑记录（本环境）

| 坑 | 现象 | 解决 |
|---|---|---|
| **Termux 脚本只装部分 assets** | 70KB 残缺包：打赏图打不开、sqlite3 部署失败 | 2026-09-09 修复第 5 步全量 `cp -r`；正式包一律走 PC Gradle |
| 文件路径笔误 | `!! 缺少 android.jar` | Termux HOME = `files/home`（非 `files/usr/home`） |
| PATH 缺 java | `d8: exec: java: not found` | 脚本头 `export PATH="$PREFIX/bin:$PATH"` |
| arsc 压缩 | 安装失败 `resources.arsc stored uncompressed and aligned` | 增量打包（不重新压缩整包） |
| 签名冲突 | `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 卸载旧版（原版旧签名）后安装 |
| 中文源码乱码 | （预防性） | javac 加 `-encoding UTF-8` |
| **PC d8 8.2.2 + JDK25 javac** | `NullPointerException` in d8 | 用 `_build-tools\jdk\17` 的 javac（别用系统 JDK 25） |
| **git 协议不通** | `git push` 超时 | 走 GitHub Contents API 逐文件推送（token 在 `_credentials\gh-token.txt`） |
| **GitHub Release 资产域不通** | 上传 asset 超时 | uploads.github.com 走 API 正常（实测可用） |

## 五、发布回路（详见 RELEASE-PROCESS.md）

PC Gradle 构建 → 验包三件套 → Contents API 推源码 → git tag（API 建 tag）→
Release（API 建贴 + 上传 APK）→ 回读校验中文无 `?` 损坏 → LSPosed 镜像自动同步（延迟数小时）。

