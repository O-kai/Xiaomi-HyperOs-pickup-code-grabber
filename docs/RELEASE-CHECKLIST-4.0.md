# 4.0 发布检查清单（一次性推送到 GitHub）

> 面向的操作：把本地 `release/4.0`（含 4.0 全套重构、社区 PR 移植与 4.0.3 收口）
> 一次性推到 `main`，同时打 tag、发 Release、更新 README。
>
> **顺序不能乱**：先保证代码对，再动文档，最后推送——推送后再改文档会多出无意义的提交。

---

## 0. 推送前必做（代码层）

```bash
cd D:/project/WorkBuddyData/取件码助手/pickup-code-grabber-4.0

# ① 回归全绿（14 套、511 项断言）
bash tools/run_all_tests.sh

# ② 版本号口径核对（必须是 4.0.3 / 403 两个字段，不要单体系推导）
cat version.properties

# ③ 工作区干净、tag 指向 HEAD（本轮已重打，落后提交数应为 0）
git status --short
git rev-list --count v4.0.3..HEAD

# ④ 真机冒烟（装最新 release 包后冷启动）
#    期望：0 崩溃、0 次 su、完整体检 7 项全通过
```

---

## 1. 文档层（推送前改完）

| 文件 | 要做什么 |
|---|---|
| `README.md` | 4.0 的功能、截图、安装步骤、与 3.1.0 的差异。**这是唯一面向新用户的入口，必须是最新的** |
| `CHANGELOG.md` | 顶部应有 `## [4.0.3]`；4.0.2 的轮次记录保留在下方 |
| `docs/DECISIONS.md` | D-17（Java+UiKit，不用 Compose）已记；补 D-20 小组件资源 ID 规则 |
| `docs/WIDGET-HYPEROS-2026-10-30.md` | 已补「资源 ID 决定是否重建」的机制 |
| `docs/PR-REVIEW-2026-11-05.md` | PR 处置记录（已执行完毕，评论链接在内） |
| `docs/legacy/` | 4.0 之前的 20 份文档已归档，带说明页；**不要删，也不要再往主目录搬** |

> 酷安文章单独写，不进仓库。

---

## 2. 推送（用 SSH，不要用 HTTPS）

HTTPS 走 Windows 凭据管理器，非交互环境下取不到密码；SSH 密钥已验证可用
（`ssh -T git@github.com` → `Hi O-kai!`）。

```bash
# 快进 main（release/4.0 是 origin/main 的直接后代，不需要 force）
git push git@github.com:O-kai/Xiaomi-HyperOs-pickup-code-grabber.git release/4.0:main

# 打标签（CI 会构建 debug APK 作为产物）
git push git@github.com:O-kai/Xiaomi-HyperOs-pickup-code-grabber.git v4.0.3
```

**不要用 `--force`**：`origin/main` 是 `release/4.0` 的祖先，普通 push 就是快进。

---

## 3. 推送后

- [ ] GitHub Actions 绿（`assembleDebug` 产物可下载）
- [ ] 建 Release `v4.0.3`，挂**本地用生产 keystore 签名的 release APK**（CI 只出 debug）
- [ ] Release 说明直接复用 CHANGELOG 的 4.0.3 段落
- [ ] 5 个 PR 已关闭（#14/#13/#12/#9 completed，#8 not_planned），无需再动
- [ ] **LSPosed 镜像仓库**（文件与 tag 已推，只差发布 Release；见 `docs/RELEASE-MANUAL-STEPS.md`）（`Xposed-Modules-Repo/io.github.okaidev.pickupcode`）：
      - 更新 `SUMMARY`（一行摘要）与 `README.md`，内容见 `docs/mirror/`
      - 发 Release：**tag 必须是 `403-4.0.3`**（`versionCode-versionName`），附同一个 APK
      - 发完 5~60 分钟内，模块商店页会自动刷新
- [ ] 操作细节与截图见 `docs/RELEASE-MANUAL-STEPS.md`

---

## 4. 已知的、发布时要写进说明的事

| 事项 | 说明 |
|---|---|
| **升级后建议重启一次** | `NotiHook` 跑在 `system_server`，LSPosed 只在进程启动时注入。不重启的话通知通道还是旧代码 |
| **小组件要重加吗** | 从 4.0.1 升上来的旧组件会因资源 ID 变化自动重建；从 3.1.0 升上来的建议移除重加 |
| **权限** | 需要 root（Magisk / KernelSU）+ LSPosed；作用域要勾「系统框架」与「电话/短信」 |
| **卸载残留** | sqlite3 已搬进应用私有目录，卸载随数据删除；`/data/local/tmp/pickup_sqlite` 会留 3 个配置文本（约 5KB），系统进程要靠它们读设置 |
| **ColorOS** | 代码已适配（Provider 唤醒 + Hook 类名 + 配置标签），但维护者无实机，属「未验证」状态 |

---

## 5. 回滚方案（万一）

```bash
# 推送前的 main 位置先在本地留个记号
git branch backup/main-before-4.0 origin/main

# 万一要回滚（需要 force，慎用）
# git push --force-with-lease git@github.com:... origin/backup/main-before-4.0:main
```
