# 对外发布指南（Publishing Guide）

> 本文件汇总本项目对外分发的全部渠道、材料与操作步骤。
> 渠道事实核实日期：2026-09-03（modules.lsposed.org 在线核实）。
> 作者：@O-kai ｜ 仓库：https://github.com/O-kai/Xiaomi-HyperOs-pickup-code-grabber

---

## 0. 总览

| # | 渠道 | 用途 | 状态 |
|---|---|---|---|
| 1 | GitHub Releases | 主分发（APK + 更新说明） | 已启用（CI 自动或手动） |
| 2 | modules.lsposed.org（LSPosed 官方模块仓库） | 官方收录，LSPosed 管理器可浏览 | 待提交（见 §2） |
| 3 | 酷安 | 国内社区曝光主渠道 | 文案已备（见 §3） |
| 4 | Telegram（LSPosed 相关群） | 海外/资讯分发 | 文案已备（见 §4） |
| 5 | Gitee 镜像 | GitHub 访问不便的用户 | 指南已备（见 §5） |

**发布节奏建议**：每版本 = GitHub 打 tag → CI 构建并出 Release → 酷安动态（简版）+ TG 消息（一行）→ LSPosed 仓库自动同步。Gitee 走镜像自动同步，无需手动。

---

## 1. GitHub Releases（主分发）

**手动流程**：
1. `git tag v2.1.1 && git push origin v2.1.1` → CI 自动构建 APK 并创建 Release；
2. 或手动：Releases → Draft a new release → 选择 tag → 上传 `pickup-code-grabber-2.1.1-debug.apk`（正式版请用生产 keystore 签名后替换）；
3. Release 说明模板：

```
## 取件码助手 v2.1.1

LSPosed 模块：自动提取快递取件短信取件码，写入小米笔记待办（一码一条、新码置顶）。

### 本次更新
- 黑名单「预览 + 修改/保存」二段式 UI
- 一键测试改为随机取件码（永不撞去重，可反复自测）
- 移除桌面小组件（回归精简）

### 环境要求
小米/红米 + Root(Magisk) + LSPosed；Android 12+；需要设备内可用 sqlite3（见 README 部署准备）。

> 安装：卸载旧签名版本后安装本 APK。
```

---

## 2. modules.lsposed.org（LSPosed 官方模块仓库，推荐必上）

**机制**（已核实）：模块仓库会为每个模块建立 `github.com/Xposed-Modules-Repo/<包名>` 镜像，
内容/版本同步自**你仓库的 GitHub Releases**（区分 stable / beta / snapshot，
即 Release 是否标记 *Pre-release*）。

**提交步骤**：
1. 确保 GitHub 上已有：带完整 README 的源码仓库 + 至少一个公开 Release（含 APK）；
2. 打开 **https://modules.lsposed.org/submission/** （或模块仓库首页 "Submit Module"）；
3. 填写：
   - 模块包名：`com.pickupcode.grabber`
   - 源码仓库：`https://github.com/O-kai/Xiaomi-HyperOs-pickup-code-grabber`
   - 首页/项目页：`https://github.com/O-kai/Xiaomi-HyperOs-pickup-code-grabber`
   - 简介（提交页会展示，建议与 README 首段一致）：
     *"自动提取快递取件短信取件码，一条一码写入小米笔记待办（堆栈置顶），通知可复制/一键已取件；仅支持小米 MIUI/HyperOS + Root + LSPosed，零网络零短信权限。"*
4. 提交后观察 `modules.lsposed.org` 是否出现本模块；通常以 Release 为触发点同步。

**注意**：若提交页要求先创建 `Xposed-Modules-Repo/com.pickupcode.grabber` 镜像仓库，
按页面指引一键创建即可（该组织仓库由系统管理）。

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
