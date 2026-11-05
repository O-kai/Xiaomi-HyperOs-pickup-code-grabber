# 4.0.3 发布：剩下两步需要手动做

> 代码已经推上去了（`main` 与 tag `v4.0.3` 都在 GitHub 上了）。
> 但当前这个 fine-grained token 只有 **Contents: Read**，建 Release / 改文件都会返回 403。
> 所以下面两步请在网页上点，或者换一个带 **Contents: Read and write** 的 token 交给我。
>
> 两步加起来大约三分钟。

---

## 第一步：主仓库建 Release

打开这个地址（tag 已经存在，页面会自动填好）：

https://github.com/O-kai/Xiaomi-HyperOs-pickup-code-grabber/releases/new?tag=v4.0.3

| 字段 | 填什么 |
|---|---|
| Choose a tag | `v4.0.3`（已存在，直接选） |
| Release title | `取件码助手 v4.0.3 · 写入目标回归两处 + 简洁模式 + 规则 v9` |
| Describe this release | 把 `docs/release-notes-4.0.3.md` 的全部内容粘进去 |
| Attach binaries | 上传 `artifacts/pickup-code-grabber-4.0.3-release.apk` |
| 勾选 | 不勾「This is a pre-release」 |

发布前核对附件 SHA-256：

```
d2c5d3d2a4e4031f9d1449bedd3c412b991330a688e36206eb19ea975e52d475
```

---

## 第二步：LSPosed 镜像仓库

仓库地址：https://github.com/Xposed-Modules-Repo/io.github.okaidev.pickupcode

### 2.1 换掉 SUMMARY（一行）

点开 `SUMMARY` → 编辑 → 整行替换成：

```
自动提取取件码：App 内记一份、首页待取件、桌面小组件，可同步进系统待办
```

### 2.2 换掉 README.md

点开 `README.md` → 编辑 → 全选删除 → 粘贴 `docs/mirror/README.md` 的全部内容。

### 2.3 发 Release

https://github.com/Xposed-Modules-Repo/io.github.okaidev.pickupcode/releases/new

| 字段 | 填什么 |
|---|---|
| Tag | `403-4.0.3`（**必须是这个格式**：versionCode-versionName） |
| Release title | `4.0.3` |
| Describe | 贴 `docs/release-notes-4.0.3.md`，或只留前两段也行 |
| 附件 | `artifacts/pickup-code-grabber-4.0.3-release.apk` |

发完之后 LSPosed 的机器人会在 5~60 分钟内把模块页刷新。

> 注意：tag 只写 `v4.0.3` 或 `4.0.3` 都不行。LSPosed 只认 `403-4.0.3` 这个格式，
> 写错了机器人不会更新，模块商店里还是旧版本。

---

## 我这边已经做完的

- 代码推到 `main`（快进，无 force）：`0ca137c..a609159`
- tag `v4.0.3` 指向 `a609159`
- 构建并校验 APK（生产 keystore 签名）
- README 重写并换掉 5 张截图
- 酷安两篇文案：`docs/COOLAPK-4.0.3.md`
- 镜像仓库的 README / SUMMARY 内容：`docs/mirror/`

## 如果愿意换个 token

在 https://github.com/settings/personal-access-tokens 里给这个 token 加上
**Contents: Read and write**（两个仓库都要授权），然后告诉我，剩下两步我直接做完。
