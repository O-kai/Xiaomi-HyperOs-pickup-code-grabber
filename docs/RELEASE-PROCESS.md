# 标准发布流程（Maintainer 自动流转协议）

> 本文约定 **O-kai（本地开发） ↔ 自动化助手（本仓库维护）** 之间的发布协作方式，
> 目标：本地改完 → 助手自动归纳 → 推送 → 打 tag → 挂正式签名 APK → 发 Release。
> 全部步骤均已实测（v2.1.1 首发验证通过）。

---

## 一、分工与权限模型

| 环节 | 谁做 | 说明 |
|---|---|---|
| 功能开发 / 实机验证 | O-kai（本地工作区） | 工作区：`D:\project\sms-pickup-module` |
| 正式签名 APK | O-kai（Termux + 生产 keystore） | 产物约定放入 `artifact/pickup-code-grabber-vX.Y.Z-release.apk` |
| 源码同步 / 脱敏 / 版本号 / CHANGELOG / 提交 | 自动化助手 | 公开仓库：`D:\project\Xiaomi-HyperOs-pickup-code-grabber` |
| 推送 / 打 tag / 建 Release / 上传 APK | 自动化助手 | 使用本地 `D:\project\_credentials\gh-token.txt` 的 fine-grained PAT |
| 构建验证 + 测试 APK | GitHub Actions | 仅产出 debug 测试包（Actions 产物），**不直接发 Release** |
| LSPosed 模块仓库同步 | 系统自动 | 从 GitHub Releases 自动镜像（modules.lsposed.org） |
| 酷安 / Telegram / Gitee | O-kai 手动 | 文案模板在 `docs/PUBLISHING.md` |

**Token 权限现状（够用）**：Contents: Read and write（源码/Release/资产）+
Workflows: Read and write（推 workflow 文件）。**不需要**更多权限；
仅当想让我"手动触发 CI"（workflow_dispatch）时才需补 Actions: Read and write（可选）。
CI 内部使用 GitHub 自动提供的仓库级 GITHUB_TOKEN，与本地 token 无关。

> 🔐 签名密钥永远只存在你的设备/keystore 里，**不进入仓库、不上传 GitHub**。
> 这就是 CI 不发正式包的原因：正式包由你本地生成、我负责挂载。

---

## 二、触发信号（怎么喊我发版）

二选一，任选其一：

1. **直接说**：「v2.2.0 可以发了」——我立即走下方流程；
2. **约定信号（推荐，防遗漏）**：把签名 APK 放进 `artifact/pickup-code-grabber-vX.Y.Z-release.apk`，
   我会在每次收到你消息时核查该目录；若发现新 APK 就自动理解为待发布。

## 三、标准流程（每个版本执行一次）

```
① 核查 APK：apksigner 验签（v2/v3、证书 CN）+ aapt 核对版本/包名/minSdk + SHA-256
② 同步源码：工作区 app/, build/ → 公开仓库（增量；保留公开仓库的 README/docs/Gradle 层）
③ 脱敏检查：grep 手机号/地址/敏感路径（发布前必做；见 §四 检查项）
④ 版本号一致：Manifest versionCode/versionName == gradle defaultConfig == README 文案
⑤ 归纳总结：
   - 对比差异 → 写 CHANGELOG 条目（谁改了什么、为什么）
   - 汇总 core 变更 → 写 Release Notes（发布说明，含 更新点/环境/安装/校验和）
⑥ 提交 + 推送：git add -A → commit（feat/fix/docs 前缀）→ push origin main
⑦ 打 tag：git tag vX.Y.Z（指向发布提交）→ push origin vX.Y.Z
   ⚠️ 删除标签重推同一提交会被 GitHub 去重（不触发 CI）——必须用新提交/新 tag
⑧ 建 Release（API，正式签名 APK 作为唯一资产，说明含 SHA-256）
⑨ 通知渠道：LSPosed 模块仓库自动同步；提醒 O-kai 发酷安（可选）；Gitee 镜像自动同步
```

## 四、发布前检查清单（自动化助手执行）

- [ ] 新文件/新文档中无：手机号（`1[3-9]\d{9}`）、驿站/地址、真实笔记内容、keystore/密钥
- [ ] `backup/`、`tools/`、`legacy/`、本轮调试产物未混入
- [ ] Manifest / gradle / README / CHANGELOG 版本号四处一致
- [ ] APK 签名：v2/v3 有效、证书为生产 keystore（非 `CN=…,O=Debug`）
- [ ] Release Notes：更新点 / 环境要求 / 安装步骤 / 文件校验（名称+大小+SHA-256）

## 五、命名约定

| 对象 | 约定 | 示例 |
|---|---|---|
| 源码 tag | `v` + 版本号 | `v2.2.0` |
| 本地签名 APK | `pickup-code-grabber-v{ver}-release.apk`（artifact/） | `pickup-code-grabber-v2.2.0-release.apk` |
| 发布资产名 | `pickup-code-grabber-v{ver}.apk` | `pickup-code-grabber-v2.2.0.apk` |
| commit | `feat:` / `fix:` / `docs:` / `chore:` 前缀 | `feat: 新增备份与恢复` |

## 六、回滚与应急

- **发错版**：GitHub Releases → Edit → 删除资产/降级说明即可（tag 保留）；源码回退用新提交，不要强推 main；
- **CI 挂了**：不影响发布（CI 只产测试包）；本地 `assembleDebug` 可独立验证；
- **token 失效**：重新生成 fine-grained PAT（Contents RW + Workflows RW）放到
  `D:\project\_credentials\gh-token.txt`，告诉我一声即可。

## 七、⚠️ 已踩过的坑（务必遵守）

1. **API 传中文必须是 UTF-8 字节**：`Invoke-RestMethod -Body ([Text.Encoding]::UTF8.GetBytes($json))`
   且 `-ContentType 'application/json; charset=utf-8'`。
   直接用字符串 Body 会被 ASCII 编码 → 所有中文变成 `?`（v2.1.1 Release 曾中招，已修复）。
   发布脚本里一律用字节流；
2. `gradlew` 提交前 `git update-index --chmod=+x gradlew`（CI Linux 需要）；
3. 删除标签重推同一提交 = GitHub 去重不触发 CI，必须用新提交/SHA；
4. 推送 `.github/workflows/` 需要 token 有 Workflows: Read and write；
5. 建 Release 后必须回读 API 校验正文（写文件 + 数 `?` 字符），通过才算完成。
