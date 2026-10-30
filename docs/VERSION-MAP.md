# 版本号规范

> 本文件是版本号的**唯一规范**。全项目所有涉及版本的地方都以此为准。

## 一、两个字段，各管一件事

`version.properties`（仓库根目录）：

```properties
VERSION_NAME=4.0.2
VERSION_CODE=402
```

| 字段 | 值 | 用途 | 约束 |
|---|---|---|---|
| `VERSION_NAME` | `4.0.2` | **给人看的**点分版本号，界面上一律显示 `V4.0.2` | 必须能拆成三段数字 |
| `VERSION_CODE` | `402` | **给 Android 用的**整数，单调递增 | 只增不减，永远大于已发布的任何一个 |

**两者不互相推导，各改各的。**

- `VERSION_CODE` 只用于：Android 的升级判定、规则集 `minAppVersionCode` 兼容性闸门。
  它**永远不出现在界面上**。
- `VERSION_NAME` 只用于：界面展示、诊断报告、更新说明、Git 标签。

> **为什么不做「一个数推另一个」的推导？**
> 推导方案（`major*10000 + minor*100 + patch`）看起来更"干净"，但它改的是整个版本体系的骨架：
> 连带要动规则闸门口径、历史标签、文档基线，而 App 里真正需要点分版本号的只有展示层。
> 一个字段只表达一件事，改一处不会牵连另一处——这比"不可能对不上"更实际。
> （4.0.2 曾短暂改过推导方案，因改动过大、牵动过多而回归。）

## 二、三段语义

| 段 | 含义 | 什么时候 +1 | 例 |
|---|---|---|---|
| 第一段 | **大版本** | 不兼容的架构或功能变更 | `3.x.x → 4.0.0` |
| 第二段 | **小版本** | 新增功能 | `4.0.0 → 4.1.0` |
| 第三段 | **修复版本** | 修 bug | `4.1.0 → 4.1.1` |

版本号示例：`4.0.2` = 第四个大版本 · 第 0 个小版本 · 第 2 次修复。

## 三、规则的唯一出口

全项目取版本号**只有一处**知道格式：`AppVersion`。

| 方法 | 用途 | 返回 |
|---|---|---|
| `AppVersion.dotted(ctx)` | 点分版本号 | `4.0.2` |
| `AppVersion.label(ctx)` | 界面显示 | `V4.0.2` |
| `AppVersion.code(ctx)` | Android 内部整数 | `402` |
| `AppVersion.compare(a, b)` | 版本大小比较 | `>0` / `<0` / `0` |
| `AppVersion.isNewer(a, b)` | 是否更新 | boolean |
| `AppVersion.stripSuffix(s)` | 去掉历史后缀 | `4.0.0-rc2 → 4.0.0` |

**新增用到版本号的地方，一律走 `AppVersion`，不要自己读 `PackageInfo`。**

### 历史后缀

早期版本名带过 `-internal` / `-rc2`。**现在不再产生这种后缀**，
但解析与比较仍保留一层容错——那些历史 APK 已经装在用户手机上，
解析时崩掉、或比较结果恒为 0（「自动检查更新」永远认为已是最新）是不能接受的。

## 四、构建期守卫

`app/build.gradle` 的 `checkVersionConsistency` 任务（`preBuild` 自动依赖）：

1. `AndroidManifest.xml` 里**不许**写死 `android:versionCode` / `android:versionName`；
2. `VERSION_CODE` 不得低于已发布的 `310`（3.1.0）——它必须单调递增。

## 五、规则集兼容性闸门

`rules/rules.json` 里的 `minAppVersionCode` 表示
「App 的 `VERSION_CODE` 低于这个值时，不能用这套规则」。

它用的是 **`VERSION_CODE` 口径**（不是点分版本号），
当前值 `293` 对应历史上最早的规则集。

## 六、Git 标签

- 公开历史标签：`v2.x` / `v3.x`（已推送，**不可改写**）
- 当前版本标签：`v4.0.2`
- **不再使用** `internal/*` 阶梯标签与 `v*-rc*` 预发布标签——
  旧体系已废弃，标签里带后缀会让「这个版本是什么」变得含糊。

## 七、发布检查清单

1. `version.properties` 改 `VERSION_NAME`（如需发新版）
2. `version.properties` 改 `VERSION_CODE`（必须大于上一个）
3. 规则集如有不兼容变更，同步 `rules/rules.json` 的 `minAppVersionCode`
4. `CHANGELOG.md` 补条目
5. 构建 → 真机验收 → 打 `v<版本号>` 标签
