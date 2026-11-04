# PR #9 评论（可直接粘贴到 GitHub）

> 标题：fix(hooks): ColorOS 短信捕获适配——修正 S1/S5 类名，新增 S9–S11
> 结论：**已在仓库里**（提交 `61b9699`，2026-10-16，随 3.1.1 发布），可以关闭。

---

感谢 @ByteJuno，这份适配是对的，而且**已经合进来了**。

## 核对结果（逐条对照）

| 你的改动 | 当前代码 | 位置 |
|---|---|---|
| S5 两个候选类名 | 已同时挂 `PrivilegedSmsReceiver`（S5）与 `HighPrivilegedSmsReceiver`（S5H） | `XposedEntry.java:155-158` |
| S1 改成 `InboundSmsHandler$SmsBroadcastReceiver` + 旧顶层类双候选 | 已按「两个都试，命中即挂」实现，全未命中时单独打一行提示 | `XposedEntry.java:196-215` |
| S9 `com.android.mms.receiver.SmsReceiver` | 已有 | `XposedEntry.java:165` |
| S10 `SmsDeliverReceiver` | 已有 | `XposedEntry.java:166` |
| S11 `com.oplus.mms.datamodel.SmsReceivedReceiver` | 已有 | `XposedEntry.java:167` |
| 文档 `docs/14-hooks.md` 的 S1/S5/S9–S11 表格与作用域说明 | 已同步（含 KSU 对 UID<2000 的限制说明） | `docs/14-hooks.md` |

对应提交：

```
61b9699 2026-10-16 fix(hooks): 修正 ColorOS 上失效的 Hook 类名，并补上真正注册的接收器
```

这条也写进了 CHANGELOG 的 `[3.1.1]` 条目：「修正 ColorOS 上的 Hook 点类名
（S1 / S5，新增 S9–S11）」。

## 说明

你 PR 里的 `SmsReceiver` 注释提到「`com.android.mms.transaction.SmsReceiver`
虽然类存在，但不在 AndroidManifest 里，系统广播不会调用它」——
这条我们原样保留了，因为它太容易误导后来人（看着像能挂、实际永不触发），
值得一直留在代码注释里。

这个 PR 我关闭了，但作者署名保留在 CHANGELOG。
如果 ColorOS 17 上还有新的类名变化，欢迎基于 `release/4.0` 再提。
