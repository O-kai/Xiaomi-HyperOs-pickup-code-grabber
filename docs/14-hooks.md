# 14 · Hook 点清单与捕获链路（v2.0.0 定稿，v2.1.x 沿用）

## 一、Hook 点清单

| 编号 | 类 / 方法 | 进程（作用域） | 目的 | 状态 |
|---|---|---|---|---|
| S1 | `com.android.internal.telephony.SmsBroadcastReceiver.onReceive` | phone / system | 系统级短信广播（AOSP 标准） | ⚠️ HyperOS 4.0 该类的类名不存在（NOT FOUND 属正常，无害） |
| S2 | `SmsReceiverService.handleSmsMessage` | com.android.mms | 短信应用服务入口（旧路径） | 保留（多 ROM 兼容） |
| S3 | `SmsReceiverService.onReceive` | com.android.mms | 同上 | 保留 |
| S4 | `SmsReceiver.onReceive` | com.android.mms | **小米真实广播入口（逆向确认）** | 保留 |
| S5 | `HighPrivilegedSmsReceiver.onReceive` | com.android.mms | 高权限短信接收器 | 保留 |
| S6 | `BeidouSmsReceiver.onReceive` | com.android.mms | 北斗短信（小米特有） | 保留 |
| S7 | `SmsReceiverService.handleSmsReceived` | com.android.mms | 小米内部处理流程 | 保留 |
| **S8** | `SmsProvider.insert` / `MiuiTelephonyProviderImpl.insert` / `bulkInsert` | **com.android.providers.telephony（在 phone 进程内）** | **短信库写入必经点（主通道，100% 覆盖普通+网络短信）** | ✅ 主用 |

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

`system`（框架） + `com.android.phone`（含短信库 Provider）+ `com.android.mms`（短信 App）+ `com.miui.notes`（预留）。

## 四、关键运行前提

- root（Magisk），模块 App 授权一次
- `/data/local/tmp/pickup_sqlite/` 存在 sqlite3 及其依赖库（构建后由部署脚本保证；版本：3.53.4）
- 写入命令形态：`su -M -c "chmod…; LD_LIBRARY_PATH=/data/local/tmp/pickup_sqlite/lib /data/local/tmp/pickup_sqlite/sqlite3 /data/user/0/com.miui.notes/databases/todo.db < <sql文件>"`
