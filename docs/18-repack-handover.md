# v2.7.0 重打包交接单（2026-09-09，给负责打包的对话）

> **背景**：v2.7.0 源码已全部推送 GitHub（remote main = `8d5ec77` + 本次脚本/文档提交）。
> 之前上传的 APK 是 Termux 旧脚本产的残缺包（70KB，缺 assets），已从 Release 摘除。
> 现在需要用 **PC Gradle 路线**重新打包并上传，步骤如下。

## 一、要重打的版本

- 版本：**v2.7.0**（versionCode 270，包名 io.github.okaidev.pickupcode，源码无需改动）
- Release 已存在：tag `v2.7.0`（id 见 GitHub），说明已加"临时说明"警示段——**重传 APK 后把该警示段删掉**

## 二、构建环境（全部现成，在 D:\project\_build-tools\）

| 组件 | 路径 |
|---|---|
| JDK 17 | `D:\project\_build-tools\jdk\jdk-17.0.20.1+1` |
| Android SDK | `D:\project\_build-tools\sdk\`（build-tools 34.0.0 / platform-34 / licenses 已接受） |
| Gradle | 项目 wrapper（8.6，CI 同款）；`_build-tools\gradle-8.9` 备用 |
| 生产 keystore | `D:\project\sms-pickup-module\release\pickup-release.keystore` + `keystore-pass.txt`（同目录） |
| 镜像 | settings.gradle 已配 aliyun（无需额外设置） |

## 三、构建命令（在公开仓库目录执行）

```powershell
cd D:\project\Xiaomi-HyperOs-pickup-code-grabber
$env:JAVA_HOME = "D:\project\_build-tools\jdk\jdk-17.0.20.1+1"
$env:ANDROID_HOME = "D:\project\_build-tools\sdk"
$pass = (Get-Content "D:\project\sms-pickup-module\release\keystore-pass.txt" -Raw).Trim()
.\gradlew.bat assembleRelease "-PPICKUP_KEYSTORE=D:\project\sms-pickup-module\release\pickup-release.keystore" "-PPICKUP_KEYSTORE_PASS=$pass" "-PPICKUP_KEY_ALIAS=pickupcode"
```

产物：`app\build\outputs\apk\release\app-release.apk`

## 四、验包三件套（上传前必做）

```powershell
$bt = "D:\project\_build-tools\sdk\build-tools\34.0.0"
& "$bt\apksigner.bat" verify --print-certs app\build\outputs\apk\release\app-release.apk
# 期望：V3.0 Signer: CN=PickupCodeGrabber（非 Debug）
& "$bt\aapt2.exe" dump badging app\build\outputs\apk\release\app-release.apk | Select-Object -First 2
# 期望：versionCode='270' versionName='2.7.0'
# 大小直觉：≈3.5MB（<100KB = assets 缺失，禁止上传！）
# SHA-256 记录下来，要写进 Release 说明
```

## 五、上传（GitHub Release 替换资产）

Release tag `v2.7.0` 已存在（无 APK 资产），直接上传新 APK：

```powershell
$token = (Get-Content "D:\project\_credentials\gh-token.txt" -Raw).Trim()
$repo = "O-kai/Xiaomi-HyperOs-pickup-code-grabber"
# 取 release id
$rel = curl.exe -s -H "Authorization: Bearer $token" "https://api.github.com/repos/$repo/releases/tags/v2.7.0"
$relId = ($rel | ConvertFrom-Json).id
# 上传（注意 uploads.github.com 域）
curl.exe -s -X POST -H "Authorization: Bearer $token" -H "Content-Type: application/vnd.android.package-archive" `
  --data-binary "@app\build\outputs\apk\release\app-release.apk" `
  "https://uploads.github.com/repos/$repo/releases/$relId/assets?name=pickup-code-grabber-v2.7.0.apk"
# 用 artifact 里的命名：复制到 D:\project\sms-pickup-module\artifact\pickup-code-grabber-v2.7.0-release.apk
```

**上传后**：
1. 删除 Release 说明顶部的"⚠️ 2026-09-09 临时说明"警示段（PATCH body）；
2. 更新说明里的 SHA-256 与大小为新值；
3. 回读 Release 校验中文无 `?` 损坏（协议 §七-5）。

## 六、发布后清单

- [ ] LSPosed 镜像（Xposed-Modules-Repo/io.github.okaidev.pickupcode）会自动同步（延迟数小时），确认 tag `270-2.7.0` 出现且 size ≈3.5MB
- [ ] 手机上 adb install -r 覆盖验证一遍（打赏页能显示图、一键测试正常）
- [ ] （用户已确认）等 Vv-Ww 在一加 9 Pro 上做 ColorOS 回归
- [ ] （用户自行发）酷安新帖 + 老帖置顶评论（素材在 `D:\project\sms-pickup-module\酷安素材\`）

## 七、注意事项（踩过的坑）

1. **别用系统 JDK 25 的 javac**——会让 d8 8.2.2 崩 NPE，用 `_build-tools\jdk\17`；
2. **git push 协议不通**——源码改动走 Contents API（本次源码已全部推完，重打包只需推新 APK）；
3. Release 资产上传走 `uploads.github.com`（实测可达）；
4. v2.6.1 用户不受影响（坏包只在本 Release 存在过几小时，且 LSPosed 镜像未同步）。
