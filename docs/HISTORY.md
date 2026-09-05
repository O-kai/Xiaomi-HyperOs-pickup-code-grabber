# 完整生命周期演进史（HISTORY）

> 本文记录「取件码助手」从零到对外开源的完整故事：旧版成败、核心困境、v2 重构的探索与决策、
> 以及沉淀下来的方法论。写给：想了解本项目为何这样设计的开发者，以及想复用其经验的后来人。
> 注：文中涉及的测试短信内容与设备信息均已脱敏；内部研发材料（旧需求文档 / 旧工程）未随仓库公开，
> 本页是它们经过脱敏后的精华汇总。

---

## 一、缘起：为什么做这个模块（v1 时代）

作者日常有大量包裹，取件短信散落在短信 App 里：驿站、快递柜、快递公司各自发送，取件码格式五花八门
（`16-4-9626`、`86-4-8888`、`22-2-3579`、纯数字……），翻找低效、取件码易过期。
第一代产品目标朴素：**短信一到，取件码自动出现在一个统一、可勾选完成的地方** ——
最终选型为小米笔记的「待办」（系统预装、自带完成态、就在手边）。

### 一代方案（v12 前 → v16）

| 特性 | 实现 | 结局 |
|---|---|---|
| 短信捕获 | Xposed Hook 短信广播 | 在 HyperOS 4.0 上**不触发**（类名/路径已变） |
| App 推送捕获 | NotificationListenerService（菜鸟/丰巢/京东等） | 只有推送、无短信时可用；需常驻授权 |
| 存储 | `/sdcard/Documents/pickup_codes.json`（用户可读） | 保留思路 |
| 可选便签同步 | root 直写便签库（按笔记 ID） | 可行但脆弱 |
| 构建 | 手写 stub（`IXposedHookLoadPackage` + 手工 `XC_LoadPackage`） | **全错的方向** |

版本演进：v12 前（通知监听时代）→ v13（图标/同步/通知优化）→ v14/v15（手工构建管线 + 详细日志）
→ v16（定位 `AbstractMethodError`，未部署）。

**当时的困境**：模块在 LSPosed 管理器里显示「已激活」，但 `handleLoadPackage` 从未被调用。
12 个手工构建脚本 + 反复「修补 stub」的错误方向（v14–v16）都无果。

---

## 二、核心困境的破案：手工 stub 必错（历史最大坑）

终于定位：LSPosed 运行时要求入口的签名是
`handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage$LoadPackageParam)`，
该类的**真实定义只在官方 `xposed-api-82.jar`** 中。只要编译期不用官方 jar，手写 stub 要么签名不对
（→ AbstractMethodError），要么字段不全（→ 编译期/运行期意外）。

> **结论：一切手工 stub 都是错的。** 废弃「修 stub」路线（v14–v16），
> 统一改用官方 API 编译（Gradle `compileOnly 'de.robv.android.xposed:api:82'`，
> 或 Termux 下载官方 `api-82.jar` 一次到位）。此坑是 v2 重构的起点。

---

## 三、v2 重构全过程（2026-09-03 里程碑）

### P0 实机侦察

- 环境确认为：Redmi K90 Pro Max / HyperOS 4.0 / Android 17 / Magisk + LSPosed；
- 逆向确认包名、Provider authority、`todo.db` 表结构（全部字段与默认值）、
  `MiuiMms`/笔记 APK 内真实类名（dex 字符串扫描法，见「方法论」）；
- **重要发现**：处理器为 `todo.db`（rollback journal，无 WAL，直写无 WAL 坑）；
  笔记 App 的待办页直接读 `todo.db`（`WHERE is_finish=0 AND local_status != 3`）——
  这就是「直写立即生效」的根基。

### P1 捕获：网络短信逼出「结果级」方案

- HyperOS 4.0 上 S1（AOSP 广播接收器）类名不存在；S2/S3 安装成功但**从未触发**；
- APK 逆向找到小米真入口：`SmsReceiver` / `HighPrivilegedSmsReceiver` / `BeidouSmsReceiver`（S4–S6）；
- **但小米网络短信不经过任何广播**（source 列 base64 + account 字段直接写库）→
  最终选定 **S8：Hook `SmsProvider.insert` / `bulkInsert`** —— 短信库写入必经点，
  **普通 + 网络短信 100% 覆盖**，成为主通道（文档 14 的 S8）。

### P2 提取 + 写待办：一串硬仗

| 问题 | 现象 | 解法 |
|---|---|---|
| Provider 无 Context | `EVENT dispatch skipped: no app context` | 反射 `ActivityThread.currentApplication()` |
| 86 秒延迟 | S8 捕获后迟迟不处理 | Provider 路径整条入库 → 立即 flush |
| MIUI 冻结拦截 | 广播对未运行进程静默无效 | 改 `ContentProvider.call` 唤醒（不受冻结限制） |
| su 127 | `sqlite3: inaccessible or not found` | sqlite3+依赖库部署到 `/data/local/tmp/pickup_sqlite/`，`LD_LIBRARY_PATH` 写进命令串 |
| su 后打不开 DB | `unable to open database file` | **`su -M`**（全局挂载命名空间）—— App 的 Zygisk su 继承 App 视图，普通 su 看不到其他数据目录 |
| 目录权限 700 | 无法访问 `com.miui.notes` 数据目录 | 写入前自愈式 chmod 711/771/666（App 属主不变） |

端到端验证：真实取件短信 → 截获 → 提取（`86-4-8888`）→ 去重 → 直写 `todo.db`
（`custom_sort_id = MAX + 0x100000`，堆栈置顶）→ 通知 → **用户确认待办 UI 可见** ✅

### P3–P5：增强落地（v2.1.0 → v2.1.1）

模板三档、通知+点击复制、「已取件」动作按钮、一键测试、黑名单；
v2.1.1 打磨设置页交互（黑名单预览/修改、随机测试码、布局修正），
并移除了当时的桌面小组件（回归精简）。详见 CHANGELOG。

---

## 四、诊断方法论（复用价值）

1. **证据三件套**：LSPosed 模块日志（持久）+ logcat（模块 `Log.i`）+ 数据库直查；
2. **日志二分定位**：入口层（`handleLoadPackage` 是否出现）→ Hook 层（`S8 OK` 是否出现）→
   触发层（`SMS:` 行）→ 处理层（`TODO:`/`EVENT:` 行）；
3. **系统组件真相**：拉官方 APK → dex 字符串扫描 → 快速定位真实类名 / URI / SQL；
4. **设备端命令走脚本文件**（引号转义地狱；ASCII + LF）；
5. **一次性下对依赖**：官方 api jar 一次到位的成本远低于反复修补 stub。

## 五、构建环境踩坑

- Termux HOME 路径是 `files/home`（非 `files/usr/home`）；
- `d8` 依赖 PATH 里有 `java`；`javac` 加 `-encoding UTF-8`；
- **resources.arsc 必须 stored + 对齐**：不能整体重压缩 APK（否则安装失败），
  用「增量打包」（在 `aapt2 link` 产物上追加 `classes.dex` + `assets/xposed_init`）；
- 升级安装 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` = 签名不一致，卸载重装。

## 六、开源与未来

- 对外以 MIT 开源；首次对外发布前做了：私密信息脱敏（测试短信中的地址 / 手机号已替换）、
  补充标准 Gradle 工程、README / LICENSE / CHANGELOG / 兼容矩阵 / 免责声明；
- 计划中的演进：待办表结构自检与自动降级、通知监听副通道（无通知依赖场景）、
  免打扰时段、备份恢复、自定义正则、历史统计（详见 `docs/11-release-plan.md`）；
- 欢迎在 GitHub 提 Issue / PR 反馈实机兼容情况（请注明设备型号、MIUI/HyperOS 版本）。

## 七、上架与发布里程碑（2026-09-05）

- **v2.2.0「一键部署版」**：内置 sqlite3 + 部署体检 + LSPosed 作用域自动比对，酷安发布后项目进入正轨；
- **LSPosed 官方仓库三轮过审记**（踩坑价值最高的一段）：
  1. \com.pickupcode.grabber\ → 拒（#1725：com.* 前缀要求域名所有权 TXT 验证，我们不拥有 pickupcode.com）；
  2. \io.github.okai.pickupcode\ → 拒（#1747/#1748：GitHub 用户名 o-kai 含连字符，无法作为 Android 包名；
     而 okai 用户名已被 2012 年注册的休眠账号占用；官方建议改用组织或 o_kai 下划线方案）；
  3. **创建组织 \okaidev\（成员可见性 Public）→ \io.github.okaidev.pickupcode\ 自动过审**（#1751），
     走的是官方 README 明文支持的 io.github.{org} 免验证路线（bot 的 checkOrg 查公开组织成员）；
- **镜像仓库自动化运营**：SUMMARY / README / Release（官方 tag 规范 <versionCode>-<versionName>）
  已实现发版即自动同步；官方 bot 轮询到"带 APK 的 Release"后自动跑 Tag 工作流同步官网；
- **v2.5.0 / v2.5.1**：排查问题向导（六层决策树 + 自动修复）+ 打赏页（双码合成图）+
  检查更新（唯一联网行为，3 天一次，INTERNET 权限透明化说明）；
- **发布管线经验**：
  - uploads.github.com 对含点号/连字符的长文件名查询串会 404 → 先以短名 apk.bin 上传，
    再 PATCH /releases/assets/{id} 改名为正式文件名（稳定可复现）；
  - Windows PowerShell 5.1 的 Invoke-RestMethod 发 PATCH + JSON 字符串体时实际以 PUT 发出（405），
    需改用 Invoke-WebRequest -Method Patch -Body 字节数组；
  - fine-grained PAT 无法访问组织仓库（组织未开放），经典 token（repo scope）可正常读写镜像仓库。
