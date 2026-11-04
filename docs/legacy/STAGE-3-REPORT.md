# Stage 3 报告：双模式 UI 与前台体验收口

> 版本：**3.9.1-internal / 391**（3.9.0 为双模式首版；3.9.1 重做首启页）
> 分支：`release/4.0`
> 设备：REDMI K90 Pro Max / HyperOS 4.0 / Android 17（SDK 37）
> 状态：简洁/进阶双模式完成并通过真机；小组件待用户手动添加后验证渲染

---

## 一、本阶段交付

### 1. 简洁模式 / 进阶模式（4.0 核心）

| 项 | 实现 |
|---|---|
| 首次启动 | `LauncherActivity` 弹「选择使用方式」，简洁 / 进阶二选一，不可取消 |
| 简洁模式 | 新 `SimpleHomeActivity`：待取件卡片（码 / 地点 / 时间）、点码复制、点「已取」完成；底部「查看全部记录」「添加到桌面小组件」 |
| 进阶模式 | 原四 Tab 界面完全保留（规则、通知来源、写入格式、诊断） |
| 切换 | 简洁页右上角「进阶 ›」；进阶「设置 → 界面模式」可切回简洁，二次确认 |
| 数据 | 两模式共用同一 Room 本地库与设置，切换不重建、不丢数据 |
| 持久化 | `UiMode`（`pickup_ui` / `ui_mode`） |

设计取舍：首启不建界面时用 `uiReady` 标志拦住 `onResume` 的视图刷新——
否则会出现 `NullPointerException: heroTitle`（真机已复现并修复）。

### 2. 前台实时刷新（缺陷修复）

- 现象：App 在前台收到新取件短信，首页仍显示「0 个」。
- 根因：只在 `onResume` 刷新，Provider 在 Binder 线程写入不会触发 `onResume`。
- 修复：`PickupRepository` 增加进程内 `ChangeListener`，`ingest / markDone / delete` 后回调；
  `LauncherActivity`、`PickupListActivity`、`SimpleHomeActivity` 在 `onStart/onStop` 注册/注销。
- 真机：前台插入短信后，首页从 1 → 2 自动更新；点「已取」后 2 → 1 立即更新。

### 3. 体检节流（体验修复）

- 现象：每次回到 App 都弹一次「已被授予超级用户权限」（Magisk），因为 `onResume` 跑完整 `healthCheck`。
- 修复：`refreshHealth()` 加 120 秒缓存，命中缓存直接复用上次结果；长按状态卡强制重查。
- 效果：同一进程内反复切前后台不再重复 su。

### 4. 底栏遮挡（视觉修复）

- 现象：85% 半透明底栏下，卡片文字透出来与底栏文字相撞。
- 修复：底部遮罩改为「底栏覆盖区实底 → 上方 85dp 淡出」，滚动区底部留白 124 → 148dp。
- 真机：底栏干净，内容在接近底栏时自然淡出。

### 5. 桌面小组件

| 项 | 状态 |
|---|---|
| Provider 注册 | 系统已识别（`dumpsys appwidget` 索引 513） |
| 布局 / 预览 | 新增 `previewImage`（`tools/gen_widget_preview.java` 生成）与 `previewLayout` |
| 添加入口 | 列表页与简洁模式均提供；先给手动步骤，再尝试系统固定 |
| 直接固定 | HyperOS 4.0 上 `requestPinAppWidget` 返回成功但桌面 `AddItemActivity` 30ms 内自行结束，**不创建实例** |
| 渲染 / 点击 | **待验证**：需在桌面手动添加一次 |

结论：小组件不是「代码没写完」，是 MIUI 桌面拦截了直接固定。
应用内已改为「可手动完成」的引导，不再无反馈。

### 6. 隐私日志（3.8.1 补验）

重启后复测：`SMS: sender=... | parts=1 | len=30`，不再输出正文；
`TODO OK: code=...` 不再输出整条待办内容。去重、通知、本地库写入均正常。

### 7. 首启模式选择页重做（3.9.1）

原实现是系统 `AlertDialog`：标题 + 两段文字 + 两个文字按钮，和应用的卡片式视觉完全脱节，观感老旧。

改为**全屏首启页**：

- 品牌区（应用名 + 一句话说明）→ 标题「选择使用方式」→ 副标题。
- 左右两张等宽模式卡，各自包含：圆角图标、标题、说明、底部按钮。
- 简洁模式：主色实心按钮 + 「推荐」角标 + 主色描边；进阶模式：浅色底按钮 + 细描边。
- 全部复用 `UiKit` 的配色、圆角、字体，并加 Ripple 按压反馈与入场淡入上移动画。
- 整卡可点、按钮也可点；内容垂直居中，小屏可滚动。

选择系统弹窗还有一个隐患：项目注释里记录过部分 ROM 上 dialog window 会吞触摸事件，
全屏页直接绕开这个问题。

---

## 二、真机验收记录

| 项 | 结果 | 证据 |
|---|---|---|
| 首启模式选择弹窗 | 通过 | `artifacts/mode-chooser3.png` |
| 选择简洁模式 → 简洁首页 | 通过 | `artifacts/simple-home.png` |
| 简洁模式持久化（重启 App 不再询问） | 通过 | `artifacts/simple-home2.png` |
| 简洁模式前台实时刷新（1 → 2） | 通过 | `artifacts/simple-live.png` |
| 简洁 → 进阶回切 | 通过 | `artifacts/advanced-back.png` |
| 设置 → 界面模式 → 切换到简洁 | 通过 | `artifacts/st2.xml` |
| 进阶首页待取件实时刷新（1 → 2） | 通过 | `artifacts/stage2b-after.png` |
| 「已取」实时联动（2 → 1） | 通过 | `artifacts/stage2b-done2.png` |
| 底栏不再透字 | 通过 | `artifacts/stage2b-navfix.png` |
| 3.8.1 隐私日志 | 通过 | logcat `SMS: ... len=30` |
| 首启页（3.9.1）：两卡等高、按钮同一水平线 | 通过 | 控件坐标均为 y=1727–1848 |
| 首启页点「用进阶模式」 | 通过 | 进入四 Tab |
| 首启页点「用简洁模式」 | 通过 | 进入 SimpleHomeActivity |

回归（`tools/run_tests.sh`）：FullReg 20/20、PkgInput 8/8、CallerPolicy 11/11，其余用例无回归。

---

## 三、构建产物

- `app/build/outputs/apk/release/app-release.apk`
- versionName `3.9.1-internal` / versionCode `391`
- 生产证书 `CN=PickupCodeGrabber`

---

## 四、未完成 / 下一步

1. **小组件真机渲染验证**：需手动添加到桌面后确认三行布局、点击复制、点「已取」、事件刷新。
2. **Stage 1 核心分层**：`:core` 纯 JVM、规则单源生成、测试去掉子进程 javac。
3. **深色模式**：简洁页目前固定浅色，需跟随系统或设置项。
4. **跨设备互联 / ColorOS**：标注为未验证限制，不阻塞 4.0。
