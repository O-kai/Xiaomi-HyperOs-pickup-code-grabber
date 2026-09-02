# 13 · 关键日志与节点归档（P0→P2 里程碑）

> 目的：把调试过程中最有价值的日志证据与关键节点归纳留存，作为后续维护/发布的对照基线。
> 原始日志随会话滚动，本文为提炼版。时间：2026-09-03（HyperOS 4.0 / Android 17 实机）。
> 注：日志中的发件人手机号与驿站地址已脱敏。

---

## 一、最终链路（v2.0.0 运行态）

```
短信(普通/小米网络短信)
   ↓ ① 截获（S8，短信库写入必经点）
PROVIDER-INSERT: date=1788386666701 sender=138****8888
SMS: sender=... | parts=1 | body=【菜鸟驿站】...取件码为86-4-8888
   ↓ ② 转发（Provider 唤醒，规避后台冻结）
EVENT dispatched (provider), result=Bundle[mParcelledData.dataSize=56]
   ↓ ③ 模块 App 处理
PROVIDER-CALL: sender=... ts=...
sqlite exit=0 out=uid=0(root) context=u:r:magisk:s0
TODO OK: 📦 取件码 86-4-8888｜菜鸟驿站｜示例市·示例驿站（地址已脱敏）｜2026-09-03 06:04
PROVIDER-CALL: 已写入待办 [86-4-8888]
   ↓ ④ 结果
todo.db id=143 custom_sort_id=39845888（堆栈顶部）✅ 用户确认待办 UI 可见
```

## 二、关键节点（含踩坑与解决，全部有日志依据）

| # | 节点 | 现象/日志 | 结论 |
|---|---|---|---|
| 1 | **官方 API 根治** | 旧版全进程 AbstractMethodError；官方 api-82.jar 含 `callbacks/XC_LoadPackage$LoadPackageParam` | 废弃手工 stub，用官方 jar 编译 → 入口正常（`handleLoadPackage` 每进程出现） |
| 2 | **入口不触发（HyperOS 4.0）** | S2/S3 安装成功但从未触发；MiuiMms APK 逆向发现真入口 `SmsReceiver`/`HighPrivilegedSmsReceiver`/`BeidouSmsReceiver` | 补 S4-S7；**但网络短信依然不走接收器** → 转向结果级方案 |
| 3 | **网络短信不走广播** | 网络短信（source 列 base64 + account 字段）直接写库，不经过 mms 广播 | 用户直觉"抓结果"= **S8 Hook SmsProvider.insert**，100% 必经 |
| 4 | **Provider 无 Context** | `EVENT dispatch skipped: no app context` | 反射 `ActivityThread.currentApplication()` 取 Context |
| 5 | **86 秒延迟** | S8 捕获后迟迟不处理 | Provider 路径整条入库 → 立即 flush（flushNow） |
| 6 | **MIUI 冻结拦截** | 广播对"进程未运行"的模块 App 静默无效（result=0 无错误） | 弃广播为主通道，改 **ContentProvider.call**（Provider 唤醒不受冻结限制） |
| 7 | **su 127（执行失败）** | `sqlite exit=127 err=...sqlite3: inaccessible or not found` | Termux 二进制在 App su 子进程域不可执行 → 复制 sqlite3+依赖库至 `/data/local/tmp/pickup_sqlite`，`LD_LIBRARY_PATH` 写在命令串内 |
| 8 | **su 后无法打开 DB** | `unable to open database file`（uid=0 root magisk 域却打不开） | **挂载命名空间**：App 的 Zygisk su 继承 App 视图 → **`su -M`（全局挂载命名空间）** 解决 |
| 9 | **目录权限** | /data/user/0/com.miui.notes mode 700 | 模块写入前 chmod 711/771/666（自愈式），App 属主不变 |

## 三、验证样例（实机日志摘录）

**端到端成功（真实短信，用户确认待办可见）**：
```
[06:02:31] S8 OK (com.android.providers.telephony.SmsProvider) @com.android.providers.telephony
[06:02:31] S8 OK (com.android.providers.telephony.MiuiTelephonyProviderImpl) @com.android.providers.telephony
[06:04:26] SMS: sender=138****8888 | parts=1 | body=【菜鸟驿站】您有1个包裹在示例市·示例驿站（地址已脱敏），取件码为86-4-8888
[06:04:27] PROVIDER-INSERT: date=1788386666701 sender=138****8888
[06:04:27] EVENT dispatched (provider), result=Bundle[mParcelledData.dataSize=56]
           ↓（模块App）
TODO OK: 📦 取件码 86-4-8888｜菜鸟驿站｜示例市·示例驿站（地址已脱敏）｜2026-09-03 06:04
```
**待办库终态**：`id=143 custom_sort_id=39845888`（=MAX+0x100000，堆栈顶部）；总数 37→38。

## 四、本里程碑失物清单（均已从工作区清理）

- 反编译缓存目录（apkx/mmsx/tpx）、APK 副本（notes/mms/telephony，约 120MB）
- 探针/诊断脚本（*.sh）、SQL 草稿、数据库副本、dex 字符串转储
- 保留：`backup/todo-db-backup-20260903-044104.db`（一次性安全快照）、`artifact/`（构建产物）、`tools/`（adb+sqlite3）

## 五、诊断方法论沉淀（后续排查直接复用）

1. 设备端命令一律走**脚本文件**（引号转义地狱；ASCII+LF）
2. 证据三件套：**LSPosed 模块日志**（/data/adb/lspd/log/，持久）+ **logcat**（模块 Log.i）+ **数据库直查**（content query / sqlite3）
3. 系统组件真相：**拉 APK → dex 字符串扫描**（快速定位真实类名/URI/SQL，本文路线）
4. 日志从"看不到"入手二分：入口层（handleLoadPackage 是否出现）→ 钩子层（OK 行）→ 触发层（SMS 行）→ 处理层（EVENT/TODO 行）
