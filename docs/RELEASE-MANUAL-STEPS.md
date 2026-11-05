# 4.0.3 发布状态与剩下的那一步

> 更新于 2026-11-06 04:35。

## 已经自动做完的

| 项目 | 状态 |
|---|---|
| 主仓库 `main` 推送 | ✅ `30002fc`（快进，无 force） |
| 主仓库 tag | ✅ `v4.0.3` → `30002fc` |
| **主仓库 Release** | ✅ https://github.com/O-kai/Xiaomi-HyperOs-pickup-code-grabber/releases/tag/v4.0.3 |
| Release 附件 | ✅ `pickup-code-grabber-4.0.3-release.apk`（6,036,418 B，`digest` 已核对一致） |
| 镜像仓库 `SUMMARY` / `README.md` | ✅ 已换成 4.0.3 版（SSH 推送，`8be8291`） |
| 镜像仓库 tag | ✅ `403-4.0.3` 已推上去 |
| **镜像仓库 Release** | ❌ **差这一步**（见下） |

## 为什么卡住（已定位到根因）

```
POST /repos/Xposed-Modules-Repo/io.github.okaidev.pickupcode/releases
→ 403 Resource not accessible by personal access token
```

查了一圈，**不是权限没勾**：

1. `GET /user/repos` 里能看到这个镜像仓库 → 它在 token 的授权范围内；
2. 同一个 token 在主仓库能建 Release、能改简介（Administration 也行）；
3. `GET /orgs/Xposed-Modules-Repo/memberships/O-kai` → **「You must be a member」**。

也就是说：**维护者是「外部协作者」，不是组织成员**。
组织仓库的 fine-grained token 必须由**组织管理员批准**，而我们既不是成员、也不是管理员，
这条请求永远不会被批 —— 换多少个 fine-grained token 都一样（已经用两个验证过）。

**为什么以前能发？** 因为以前用的是 **classic token**（`ghp_`，40 位）。
classic token 不需要组织批准，直接继承协作者权限。那个 token 在
`D://project//_credentials//gh-token.txt`，现在已经失效（`Bad credentials`）。

> 顺带纠正一个印象：镜像仓库**没有**自动同步。`git log` 里每次同步
> （v2.5.1 / v2.6.0 / v2.7.0 …）的提交人都是 O-kai 本人、时间都对得上发版日，
> 是当时的脚本用 API 推的，不是机器人。

---

## 路径一：网页发（最快，两分钟）

https://github.com/Xposed-Modules-Repo/io.github.okaidev.pickupcode/releases/new?tag=403-4.0.3

tag `403-4.0.3` 已经存在，页面会自动选中。

| 字段 | 填什么 |
|---|---|
| Release title | `4.0.3` |
| Describe this release | 贴 `docs/release-notes-4.0.3.md` 全文（或只留前两段） |
| Attach binaries | `artifacts/pickup-code-grabber-4.0.3-release.apk` |
| 预发布 | 不勾 |

> ⚠️ **改了正文或标题再发**。LSPosed 的机器人只认「Release 内容有变动」，
> 单独换附件不会触发；发布时填了标题和正文才算一次真正的更新。
> 发完 5~60 分钟内模块页会刷成 4.0.3。

---

## 路径二：给一个 classic token（以后全自动）

1. 打开 https://github.com/settings/tokens/new?scopes=repo&description=pickup-release
2. **Expiration** 随便选（90 天够用）
3. 确认勾选的是 **repo**（整块，含子项）
4. Generate token → 复制 `ghp_` 开头那一串
5. 存成 `D://project//WorkBuddyData//取件码助手//github的token//gh-classic.txt`（只放 token 一行）

存好告诉我，我立刻把 Release、附件、以及以后每次发版都做完。
classic token 不受组织审批限制，这也正是以前一直用的方式。

## 我这边准备好的物料

- Release 正文：`docs/release-notes-4.0.3.md`（主仓库 Release 用的就是这份，一字不差）
- 镜像仓库内容：`docs/mirror/SUMMARY`、`docs/mirror/README.md`（已经推上去了，不用再手动改）
- APK：`artifacts/pickup-code-grabber-4.0.3-release.apk`
- SHA-256：`d2c5d3d2a4e4031f9d1449bedd3c412b991330a688e36206eb19ea975e52d475`

## 发完之后怎么确认

1. 模块页：https://modules.lsposed.org/module/io.github.okaidev.pickupcode/
   顶部版本变成 4.0.3、简介变成「自动提取取件码：App 内记一份、首页待取件、桌面小组件，
   可同步进系统待办」就算成功（机器人 5~60 分钟）。
2. 手机上的 LSPosed → 模块 → 取件码助手 → 应能看到 4.0.3 与更新按钮。

---

## 可选：仓库门面还有两处 token 改不了

改这两个需要 token 有 **Administration: Read and write**（现在只有 Contents）。
不急，但现在的简介还停留在 3.x 的说法。

**About（右上角齿轮 → Edit）**

| 字段 | 填什么 |
|---|---|
| Description | `取件码助手 · LSPosed 模块：从短信与 App 通知里提取取件码，App 内留一份、桌面小组件常驻，可写进系统待办（小米 / ColorOS）。零短信权限，全程本地。` |
| Website | `https://modules.lsposed.org/module/io.github.okaidev.pickupcode` |
| Topics | `hyperos` `coloros` `lsposed` `xposed` `magisk` `miui` `android` `pickup-code` `sms` `xiaomi-notes` `xposed-module` |

**Wiki / Projects 两个空标签**：Settings → Features → 取消勾选 Wikis 与 Projects。
现在仓库顶部有这两个空页面，点进去什么都没有。

> 如果愿意给 token 补 **Administration: Read and write**，这两处我也能一次性改完。
