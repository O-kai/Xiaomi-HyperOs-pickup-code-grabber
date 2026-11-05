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

## 为什么卡住

`Xposed-Modules-Repo` 是组织仓库。当前这个 fine-grained token 对它有读、没有写：

```
POST /repos/Xposed-Modules-Repo/io.github.okaidev.pickupcode/releases
→ 403 Resource not accessible by personal access token
```

同一个 token 在主仓库（`O-kai/...`）建 Release 是成功的，所以不是权限勾选的问题，
而是**这个 token 没有被授权到这个组织仓库**。两种可能，处理方式不同：

- **A. token 的「Repository access」里没勾这个仓库** → 你自己两下就能加（见下）
- **B. 已经勾了，但组织要求审批** → 必须组织 owner 点通过，否则永远 403

另外一个事实：这个组织仓库的 **Actions 是关掉的**（`/actions` 直接 404，工作流注册了也 0 次运行），
所以「让仓库自己发 Release」这条路走不通，临时加的工作流已经撤掉。

---

## 路径一：网页发（最快，两分钟，不用改 token）

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

## 路径二：把 token 权限补对（以后我就能全自动）

### 第 1 步：先看 token 是不是没勾这个仓库

1. 打开 https://github.com/settings/tokens?type=beta
2. 找到那个 token（名字里带 WorkBuddy / 取件码），点进去
3. 看 **Repository access**
   - 如果是 **Only select repositories**：看列表里有没有
     `Xposed-Modules-Repo/io.github.okaidev.pickupcode`
     - **没有** → 这就是原因。点右边的下拉，加上它 → `Save`。**完事，不用审批。**
     - **有** → 往下走第 2 步
   - 如果是 **All repositories** → 直接走第 2 步

顺手确认下面的 **Permissions → Repository permissions → Contents** 是
**Read and write**（不是 Read-only）。

### 第 2 步：确认自己是不是组织 owner

打开 https://github.com/orgs/Xposed-Modules-Repo/people?query=role%3Aowner

- **列表里有你（O-kai）** → 走 2A
- **列表里没有你** → 走 2B，你批不了，别在这上面耗时间

### 2A. 你是 owner：去批准这个 token

1. 打开 https://github.com/organizations/Xposed-Modules-Repo/settings/personal-access-tokens
2. 看 **Pending requests**（待处理请求）
   - 有 `WorkBuddy...` 那条 → 点 **Review** → **Approve**
   - **一条都没有** → 说明这个 token 还没向组织发起过请求。回到第 1 步，
     把 **Contents** 改成 `Read and write` 保存；然后随便调一次写接口（或者告诉我一声，我来调），
     请求才会出现在这里。
3. 批准后不用等，立刻生效。

### 2B. 你不是 owner：这条路走不通

组织仓库的 fine-grained token 必须由组织 owner 批准。你不是 owner，就永远批不下来。
能用的替代只有两个：

- **路径一（网页发）**——每次发版手动点一下，两分钟；
- **classic token**——https://github.com/settings/tokens/new 勾 `repo` 生成一个，
  贴给我。classic token 不受 fine-grained 审批限制，但前提是这个组织没有禁用 classic token
  （有些组织会禁）。能不能用，试一次就知道。

---

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
