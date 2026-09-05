# 对外发布指南（Publishing Guide）

> 本文件汇总本项目对外分发的全部渠道、材料与操作步骤。
> 渠道事实核实日期：2026-09-03（modules.lsposed.org 在线核实）。
> 作者：@O-kai ｜ 仓库：https://github.com/O-kai/Xiaomi-HyperOs-pickup-code-grabber

---

## 0. 总览

| # | 渠道 | 用途 | 状态 |
|---|---|---|---|
| 1 | GitHub Releases | 主分发（APK + 更新说明） | 已启用（标准流程，见 RELEASE-PROCESS.md） |
| 2 | modules.lsposed.org（LSPosed 官方模块仓库） | 官方收录，LSPosed 管理器可浏览 | ✅ **已上架**（2026-09-05，审核 #1751，镜像仓库 `io.github.okaidev.pickupcode`） |
| 3 | 酷安 | 国内社区曝光主渠道 | ✅ 已发布（[链接](https://www.coolapk.com/feed/73558591)） |
| 4 | Telegram（LSPosed 相关群） | 海外/资讯分发 | 暂缓（文案已备，见 §4） |
| 5 | Gitee 镜像 | GitHub 访问不便的用户 | 指南已备（见 §5，未执行） |

**发布节奏建议**：每版本 = GitHub 打 tag → 正式签名 APK 出 Release →（可选）酷安更新动态 + TG 消息（一行）→ LSPosed 仓库自动同步。Gitee 走镜像自动同步，无需手动。

---

## 1. GitHub Releases（主分发）

> **现行标准流程见 [RELEASE-PROCESS.md](RELEASE-PROCESS.md)**（本地更新 → 归纳 → 推送 → tag →
> 正式签名 APK → Release，已实测）。本节保留要点与踩坑记录。

**流程**：正式分发 APK 由维护者用生产 keystore 本地签名（`artifact/pickup-code-grabber-vX.Y.Z-release.apk`），
由自动化助手验签后挂到 Release；CI（Build APK）只做构建验证并产出 debug 测试包（Actions 产物，不发布）。
Release 说明模板（v2.1.1 已采用）：

```
## 取件码助手 vX.Y.Z（PickupCodeGrabber）

LSPosed 模块：自动提取快递取件短信取件码，写入小米笔记待办（一码一条、新码置顶）。

### 本次更新
（逐条列出：用户可见变更 + 修复 + 行为变化）

### 环境要求
小米/红米 + Root(Magisk) + LSPosed；Android 12+；需要设备内可用 sqlite3（见 README 部署准备）。

### 文件校验
- 文件名：pickup-code-grabber-vX.Y.Z.apk
- SHA-256：<校验和，发布时自动写入>

### 文档
使用 / FAQ / 风险说明：<README 链接>
完整开发历程：<docs/15-journey.md 链接>
更新日志：<CHANGELOG.md 链接>

### 声明
- 仅实测 <设备矩阵>；其他版本兼容性欢迎反馈
- 直写小米笔记数据库：不承诺云同步；只增行/定点标记，不删改用户已有数据
- 非小米官方作品，MIT 许可，使用风险自担
```

> ⚠️ **CI/推送踩坑记录（实测）**：
> - `gradlew` 必须带可执行位（Windows 上 `git update-index --chmod=+x gradlew` 后提交），
>   否则 Linux runner 报 `Permission denied`；
> - **删除标签后重推同一提交，GitHub 会去重不再触发 CI**；需要重发时请：改提交（或
>   `git commit --allow-empty`）让 SHA 变化后再打标签；
> - 推送 `.github/workflows/` 需要 token 具备 **Workflows: Read and write** 权限；
> - 手动触发 CI（`workflow_dispatch`）需要 **Actions: Read and write** 权限（当前未开，可选）。

---

## 2. modules.lsposed.org（LSPosed 官方模块仓库，推荐必上）

**机制**（已核实）：模块仓库会为每个模块建立 `github.com/Xposed-Modules-Repo/<包名>` 镜像，
内容/版本同步自**你仓库的 GitHub Releases**（区分 stable / beta / snapshot，
即 Release 是否标记 *Pre-release*）。

**提交步骤**（v2.3.0 起按官方 issue 流程）：
1. 官方流程：在 **Xposed-Modules-Repo/submission** 仓库发 issue，
   标题 `[submission] <包名>`，机器人自动建镜像仓库并邀请你为管理员；
2. **包名所有权规则（首次提交踩坑记录）**：`com.*` 前缀需要 TXT 记录验证域名
   （首次提交 `com.pickupcode.grabber` 因未拥有 pickupcode.com 被拒，见 issue #1725）；
   无域名用 `io.github.<GitHub用户名>` 前缀免验证 → 但用户名 `o-kai` 含连字符不可用作包名，
   `okai` 又被占用（appeal #1748 官方结论）→ **按官方选项创建组织 `okaidev`（成员公开）**，
   应用 ID 最终定为 **`io.github.okaidev.pickupcode`**（组织前缀走 bot 的 checkOrg 自动验证）；3. 提交后在 issue 页关注机器人回复；镜像仓库建成后需要在里面放 `SUMMARY`（首页摘要）与
   `README.md`（完整说明），Release Tag 格式 `<versionCode>-<versionName>`（如 `240-2.4.0`）
   并附 APK 资产，机器人自动同步展示到 modules.lsposed.org；
4. 模块信息：
   - 模块包名：`io.github.okaidev.pickupcode`
   - 源码仓库：`https://github.com/O-kai/Xiaomi-HyperOs-pickup-code-grabber`
   - 简介（提交页展示，与 README 首段一致）：
     *"自动提取快递取件短信取件码，一条一码写入小米笔记待办（堆栈置顶），通知可复制/一键已取件；仅支持小米 MIUI/HyperOS + Root + LSPosed，零网络零短信权限。"*

---

## 3. 酷安（国内主渠道）

### 3.1 帖子文案（长文，可直接发「酷安 · LSPosed/安卓折腾」话题）

**标题**：把快递取件码自动搬进小米笔记待办 —— 取件码助手（LSPosed 模块）

**正文**：

> 分享一个自己写的 LSPosed 模块：【取件码助手】。
>
> 📦 收到的取件短信（菜鸟/丰巢/京东/顺丰…）越来越多，取件码总被淹没。这个模块做的事：
> 短信一到 → 自动提取取件码 → **一条一码写入小米笔记待办（新码自动置顶）** → 弹出通知，
> 点通知直接复制取件码、或一键「已取件」。
>
> ✨ 特点：
> · 在短信库**写入必经点**捕获，普通短信和小米网络短信 100% 覆盖
> · 提取引擎支持「取件码为 16-4-9626, 15-3-2194」多码簇、`凭22-2-3579到…取件` 等真实格式
> · 码+地点双重去重，不重复写；模板三档可自定义
> · 黑名单过滤验证码/银行/广告；一键自测不花短信费
> · **零网络、零短信权限**——模块本体不申请 READ_SMS/RECEIVE_SMS，Hook 层直接取数，数据全本地
>
> 🔧 环境要求：小米/红米 + Root(Magisk) + LSPosed（Android 12+）
> 顺手写了完整开发历程：早期「僵尸模块」（手工 stub 导致 AbstractMethodError、
> Hook 从未真正被调用）→ 换官方 api-82.jar 根治 → HyperOS 4.0 上挨个踩坑
>（网络短信不走广播 → 抓短信库写入点；MIUI 冻结 → Provider 唤醒；su 打不开库 →
> `su -M` 全局挂载命名空间）——都写在 GitHub 的 docs/HISTORY.md 里，欢迎围观技术细节。
>
> ⬇️ 下载：https://github.com/O-kai/Xiaomi-HyperOs-pickup-code-grabber/releases
> 📖 使用/安装：仓库 README（作用域勾选 system/com.android.phone/com.android.mms/com.miui.notes）
>
> ⚠️ 仅实测 Redmi K90 Pro Max / HyperOS 4.0 / Android 17；其他版本请反馈兼容性。
> 直写小米笔记数据库属敏感操作，建议先看 README 的「已知限制」；模块非小米官方出品。
> 欢迎 star / 提 issue 反馈。

### 3.2 发帖注意事项
- 图片：直接引用 README 的四张截图（设置页/待办/通知/桌面组件）；
- 酷安发布链接友好：正文只放 GitHub 链接，不放网盘；
- 回复模板（遇提问）：先看 FAQ → 让用户把 `adb logcat -s PICKUPDEBUG` 输出贴出来。

---

## 4. Telegram（短文，可发 LSPosed 相关频道/群）

```
📦 取件码助手 v2.1.1（LSPosed 模块）

自动提取快递取件短信 → 一码一条写入小米笔记待办（堆栈置顶）→ 通知点击复制 / 一键已取件。

• 短信库写入必经点捕获：普通 + 小米网络短信 100% 覆盖
• 零网络、零短信权限（Hook 层取数，本地处理）  • 码+地点去重、模板三档、黑名单
• 仅支持 MIUI/HyperOS + Magisk + LSPosed（Android 12+，实测 HyperOS 4.0）

下载 / 安装 / 完整开发历程：github.com/O-kai/Xiaomi-HyperOs-pickup-code-grabber
```

---

## 5. Gitee 镜像（国内直连）

方式一（推荐，零维护）：Gitee **镜像仓库**
1. gitee.com 注册/登录 → 右上角 + → 「从 GitHub/GitLab 导入仓库」；
2. URL 填 `https://github.com/O-kai/Xiaomi-HyperOs-pickup-code-grabber`，选择**公开**；
3. 导入后进入仓库 → 管理 → **镜像仓库** → 开启「从 GitHub 拉取」+ 选择拉取周期（如每天）；
   Gitee 会定期同步源码；Release/APK 建议在 Gitee release 手动挂（镜像通常不同步附件）。

方式二（手动）：每次发布后在 Gitee 仓库手动「强制同步」。

> 注意：Gitee 对 GitHub 镜像的同步频率有限制；若需快速发布大版本，手动同步一次即可。
> 欢迎在 README 顶部加 Gitee 徽章链接。

---

## 6. 检查清单（每次发布前）

- [ ] 源码同步：`git add -A && git commit -m "release vX" && git push`
- [ ] 私密信息：新截图/新文档中无手机号、地址、真实取件码以外的个人信息
- [ ] 版本号：Manifest versionName / gradle defaultConfig / README / CHANGELOG 四处一致
- [ ] 签名：正式 keystore（勿入库）；debug 包需注明
- [ ] 兼容矩阵：如有新实测设备/系统，更新 README 表格
- [ ] 渠道：GitHub Release → LSPosed 仓库（自动同步）→ 酷安简更 → TG 一行
- [ ] Gitee：如发布热更新，手动同步一次
