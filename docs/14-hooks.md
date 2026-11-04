# 14 · Hook 点清单与捕获链路（v2.0.0 定稿，v2.1.x 沿用，v3.1.1 修正 ColorOS 类名）

## 一、Hook 点清单

| 编号 | 类 / 方法 | 进程（作用域） | 目的 | 状态 |
|---|---|---|---|---|
| S1 | `com.android.internal.telephony.InboundSmsHandler$SmsBroadcastReceiver.onReceive`（Android 12+ / ColorOS）<br>`com.android.internal.telephony.SmsBroadcastReceiver.onReceive`（旧 AOSP） | phone / system | 系统级短信广播 | v3.1.1 修正：新版是 `InboundSmsHandler` 的**内部类**（在 `/system/framework/telephony-common.jar`），旧顶层类已不存在，此前在 ColorOS 上必然 NOT FOUND |
| S2 | `SmsReceiverService.handleSmsMessage` | com.android.mms | 短信应用服务入口（旧路径） | 保留（多 ROM 兼容） |
| S3 | `SmsReceiverService.onReceive` | com.android.mms | 同上 | 保留 |
| S4 | `SmsReceiver.onReceive` | com.android.mms | **小米真实广播入口（逆向确认）** | 保留 |
| S5 | `com.android.mms.transaction.PrivilegedSmsReceiver.onReceive`（ColorOS / 新 AOSP）<br>`com.android.mms.transaction.HighPrivilegedSmsReceiver.onReceive`（旧 MIUI） | com.android.mms | 高权限短信接收器 | v3.1.1 修正：ColorOS 的类名**没有 "High" 前缀**，两个候选都挂 |
| S6 | `BeidouSmsReceiver.onReceive` | com.android.mms | 北斗短信（小米特有） | 保留 |
| S7 | `SmsReceiverService.handleSmsReceived` | com.android.mms | 小米内部处理流程 | 保留 |
| **S8** | `SmsProvider.insert` / `MiuiTelephonyProviderImpl.insert` / `bulkInsert` | **com.android.providers.telephony（与 phone 共用 uid 1001）** | **短信库写入必经点（主通道，100% 覆盖普通+网络短信）** | ✅ 主用 |
| **S9** | `com.android.mms.receiver.SmsReceiver.onReceive` | com.android.mms | **ColorOS manifest 中真正注册的短信接收器** | v3.1.1 新增 |
| S10 | `com.android.mms.receiver.SmsDeliverReceiver.onReceive` | com.android.mms | ColorOS 短信投递接收器 | v3.1.1 新增 |
| S11 | `com.oplus.mms.datamodel.SmsReceivedReceiver.onReceive` | com.android.mms | Oplus 自有短信接收器 | v3.1.1 新增 |

其他进程：仅记录日志（handleLoadPackage 分流判断），不注册 Hook。

## 二、捕获与下游链

```
S8 截获 ContentValues(body/address/date)
  → SmsBridge.handleContentValues（flushNow 立即处理）
  → 进程内去重指纹（sender|body.hash, LRU 200）
  → obtainContext()（优先 onReceive 参数 Context；兜底反射 ActivityThread.currentApplication()）
  → ContentResolver.call(TodoProvider, "onSms", bundle{sender,body,ts})
  → 模块 App TodoProvider.call：
       PickupExtractor.extract → 逐码处理
       TodoWriter.handle：去重(码|地点, prefs) → 模板构建 → su -M sqlite3 直写 todo.db
       Notifier（通知，点击 LauncherActivity"copy"extra → 剪贴板 + 打开便签）
```

## 三、作用域（LSPosed 勾选）

`system`（框架） + `com.android.phone` + `com.android.providers.telephony`（短信库 Provider，与 phone 共用 uid 1001）+ `com.android.mms`（短信 App）+ `com.miui.notes`（小米待办）+ `com.coloros.note`（ColorOS 便签，预留）。

> 注：ColorOS 的待办写入走「`su -M sqlite3` 直写日历库」，不需要 hook `com.coloros.calendar`，因此作用域无需勾选日历。
> 另：`com.android.phone` 与 `com.android.providers.telephony` 同为 **uid 1001**，KernelSU/Magisk 的 root 授权按 uid 记，给其中一个授权即对两者生效，无需重复授权。

## 四、关键运行前提

- root（Magisk），模块 App 授权一次
- `/data/local/tmp/pickup_sqlite/` 存在 sqlite3 及其依赖库（构建后由部署脚本保证；版本：3.53.4）
- 写入命令形态：`su -M -c "chmod…; LD_LIBRARY_PATH=/data/local/tmp/pickup_sqlite/lib /data/local/tmp/pickup_sqlite/sqlite3 /data/user/0/com.miui.notes/databases/todo.db < <sql文件>"`

---

## 五、ColorOS Provider 唤醒门禁（v4.0.2 第九轮，来自 PR #13）

在 **system_server** 里 hook `com.android.server.am.ContentProviderHelperExtImpl`
（ColorOS 的 `oplus-services.jar`），四道门禁任一返回 true 就不会拉起 Provider。

| 编号 | 方法 | 作用 |
|---|---|---|
| W1 | `interceptStartProvider` | 关联启动 / 启动配额 / 链式启动 |
| W2 | `hookHansProviderIfNeeded` | 场景限制 / 恶意行为策略 |
| W3 | `hookbeforeInactiveProviderGet` | 恶意行为策略 |
| W4 | `hookPreloadProviderBlock` | 资源预加载拦截 |

**只放行一种情况**：被拉起的 `ProviderInfo.packageName` 是本模块，
且调用方是 system（1000）或 phone（1001）。其余调用保持系统原判定，不扩大任何权限。

`hookAllMethods` 按方法名挂，因此还加了一道返回类型检查：
只有返回 `boolean`/`Boolean` 的方法才会 `setResult(Boolean.FALSE)`，
避免某个 ROM 把同名方法改成 `void` 时破坏系统调用。

**非 ColorOS**：`XposedHelpers.findClassIfExists` 返回 null → 直接 return，
不注册任何 hook，只留一行 `WAKE skip` 日志。HyperOS 上零参与。

**为什么需要它**：KernelSU 内核禁止 UID < 2000（system 除外）获取 su，
短信进程是 1001，开不了「冻结免疫直写」；而 4.0 又删掉了导出的广播入口，
Provider 是唯一通道——唤不醒就彻底断链。

**日志**：`WAKE OK: N provider gate(s) hooked`（装机后）、
`WAKE allowed: gate=... caller=... uid=...`（实际放行，10 秒限频）。
