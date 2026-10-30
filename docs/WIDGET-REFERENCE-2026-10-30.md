# 社区改版参考与取舍（2026-10-30）

> 参考对象：`wangzhen9331/xiaomi-hyperos-pickup-code-grabber`
> 性质：**v4.6.3 发布包的 apktool 反编译 smali 修改版**，不是原始工程源码（README 自述）。
> 处理原则：只吸收设计结论，不复制实现；数据与隐私口径以本项目为准。

---

## 一、它做了什么

在官方 v4.6.3 基础上补了一套「取件码卡片 + 桌面小部件」：

| 组件 | 作用 |
|---|---|
| `PickupStore` / `PickupStore$Item` | SQLite（`pickup.db`）存记录：`code`（UNIQUE）、`carrier`、`place`、`body`、`ts`、`done`、`done_ts` |
| `PickupWidgetProvider` | 小部件骨架：布局选择、点击、尺寸变化、两段式刷新 |
| `PickupWidgetService` | RemoteViewsService，负责列表条目渲染 |
| `WidgetActionReceiver` | 接收 `WIDGET_DONE` 广播，标记已取 |
| `WidgetPrefs` + `WidgetSettingsActivity` | 小部件设置：字号滑杆、内容模板（`{code}{carrier}{place}{time}{body}`） |
| `widget_layout / widget_item / widget_layout_empty` | 透明底、白字带阴影，表头 + ListView + 空状态 |

---

## 二、对照与取舍

| 维度 | 它的做法 | 我们的做法 | 结论 |
|---|---|---|---|
| 列表形态 | `ListView` + `RemoteViewsService`，可滚动 | 固定行 + 高度自适应（1–5 行） | **保持固定**：可滚动小部件在部分桌面/系统版本上刷新不可靠，且与“扫一眼就能看到”的定位不符 |
| 尺寸适配 | 以 `minHeight < 140dp` 二分：紧凑 / 非紧凑 | 按高度算行数、按宽度收行数 | **采用思路，做得更细**：行数连续自适应，不只有两档 |
| 刷新时机 | 立即刷 + 700ms 补刷 | 立即刷 + 600ms 补刷 | **采用**：系统下发尺寸/数据存在时序差，补刷能消除“加了却没显示” |
| 空状态 | 切换整份空布局 + `setEmptyView` | 同布局内切换空视图，点击可进 App | 保持我们的做法（少一份布局，维护成本低） |
| 点击语义 | 点条目 = 直接标记已取 | 点码复制、点 ✓ 已取 | **不采用**：误触即完成，代价不可逆；复制是更高频动作 |
| 外观设置 | 字号滑杆 + 内容模板 | 字号三档 + 是否显示地点 + 最多条数 | **采用精简版**：不引入模板，避免把短信正文带上桌面 |
| 数据模型 | `code UNIQUE`，冲突忽略；保存 `body` 全文 | `event_hash` 去重 + 活跃码去重；**默认不存正文** | **不采用**：`code` 唯一会让重复出现的取件码永远进不来；存正文与隐私口径冲突 |
| 升级策略 | `onUpgrade` 直接 DROP 重建 | Room 迁移 | **不采用**：会丢用户记录 |
| 图标/预览 | `previewImage` 用应用图标 | 生成与实际布局一致的真预览图 | 保持我们的做法 |
| 容错 | 系统调用全部 try-catch，失败只记日志 | 同样包裹，并加 `WIDGET refresh` 日志 | 一致，额外补了可观测性 |

---

## 三、本次实际落地的改动

1. **行数自适应**：`onAppWidgetOptionsChanged` + 按 `OPTION_APPWIDGET_MIN_HEIGHT` 计算 1–5 行；
   宽度 < 150dp 时再收敛到 2 行。布局从 3 行扩到 5 行。
2. **两段式刷新**：`refreshAsync` 立即刷一次，600ms 后补刷一次（`removeCallbacks` 去重）。
3. **小组件设置页**（`WidgetSettingsActivity`）：字号小/标准/大、是否显示地点、最多 2–5 条，
   改动即时生效；设置页与「添加到桌面」对话框都能进入。
4. **修复**：关闭「显示地点」时，原来会把带 weight 的占位列 `GONE`，导致 ✓ 贴到取件码旁边、
   右侧留白；改为保留视图、清空文本。
5. **数据接口**：新增 `pendingSnapshotAsync`，一次拿到「前 N 条 + 待取总数」，避免两次异步查询对不齐。

---

## 四、仍未验证 / 已知限制

- 真机上**还没有实际添加过小组件**（HyperOS 4.0 会静默取消 `requestPinAppWidget`），
  所以行数自适应、字号、点击复制的实际渲染仍需手动添加一次后确认。
- 它的「透明底 + 白字 + 阴影」风格没有采用：浅色壁纸上不可读。
  如果后续要做，应作为独立主题选项，而不是默认。
