# 4.0 IPC 安全验收清单

> 目标：确认 exported Provider 只服务可信系统进程，旧广播入口已消失，通知动作不能被外部 App 触发。
> 在真机（K90 Pro Max / HyperOS 4.0）上执行；每次改 Provider/CallerPolicy/Manifest 后重跑。

## 0. 前置

```bash
adb shell dumpsys package io.github.okaidev.pickupcode | grep -E 'versionCode|versionName'
adb shell dumpsys package io.github.okaidev.pickupcode | grep -A3 'io.github.okaidev.pickupcode.provider'
```

记录安装版本；确认 Provider 仍存在。

## 1. 未授权 onSms（shell UID 2000）

```bash
adb shell content call \
  --uri content://io.github.okaidev.pickupcode.provider \
  --method onSms \
  --extra sender:s:attacker \
  --extra body:s:'取件码 1-2-3456，请到测试驿站取件'
```

预期：

- 返回 `Bundle[{rc=-3, error=unauthorized}]` 或等价拒绝；
- 通知栏不出现新取件通知；
- 小米笔记/日历不新增待办；
- logcat 出现 `IPC denied: method=onSms uid=2000 ...`。

## 2. 未授权 markDone

```bash
adb shell content call \
  --uri content://io.github.okaidev.pickupcode.provider \
  --method markDone \
  --arg 1-2-3456
```

预期：

- 返回 `rc=-3`；
- 不会把任何已有待办标记完成；
- logcat 出现 `IPC denied: method=markDone uid=2000 ...`。

## 3. 旧广播入口已移除

```bash
adb shell am broadcast \
  -a io.github.okaidev.pickupcode.action.ON_SMS \
  -p io.github.okaidev.pickupcode \
  --es sender attacker \
  --es body '取件码 1-2-3456'
```

预期：

- `Broadcast completed: result=0`，无 receiver；
- 不写待办、不弹通知。

## 4. LauncherActivity 不再接受 done extra

```bash
adb shell am start -n io.github.okaidev.pickupcode/.LauncherActivity \
  --es done 1-2-3456
```

预期：

- 只打开 App；
- 不执行 root SQL、不把待办标记完成；
- `logcat -s PICKUPDEBUG` 无 `MARK_DONE(call)` 相关记录。

## 5. 通知动作仍可用（自己 App 的 PendingIntent）

1. 触发一条真实取件短信，弹出通知。
2. 点通知正文：应复制码 + 打开目标待办 App。
3. 点“已取件 <code>”：应标记完成 + 打开目标 App。
4. 长按通知/从最近任务确认没有额外的透明 Activity 残留。

## 6. logcat 过滤

```bash
adb logcat -d -s PICKUPDEBUG:* | grep -E 'IPC denied|PROVIDER-CALL|MARK_DONE|notify'
```

- 不应出现完整短信正文（4.0 Stage 0 尚未完全移除旧日志，先记录实际输出，Stage 4 收口）。
- 不应出现未授权调用成功写入的记录。

## 7. 结果记录

执行日期：2026-10-30 ｜ 设备：REDMI K90 Pro Max / HyperOS 4.0 / Android 17 ｜ 基线：3.6.0-internal(360)

| 项 | 结果 | 备注 |
|---|---|---|
| onSms 未授权拒绝 | PASS | `Result: Bundle[{rc=-3, error=unauthorized}]`，uid=2000 com.android.shell |
| markDone 未授权拒绝 | PASS | `Result: Bundle[{rc=-3, error=unauthorized}]` |
| 旧广播无 receiver | PASS | `Broadcast completed: result=0` |
| Launcher done 不生效 | PASS | 只打开 App，无 `MARK_DONE` 日志 |
| 通知复制/已取件仍可用 | PASS | 点通知正文 → NotificationActionActivity → 打开小米笔记；受控 `done` → 测试待办 `is_finish=1` |
| 真实 SmsProvider.insert | PASS | root 插入测试短信 `_id=15191` → `77-7-7777` → 待办 `id=322`；重复插入命中去重 |
| 非导出 Activity 外部启动 | PASS | shell 启动 `NotificationActionActivity` 被 `SecurityException` 拒绝 |
| 测试数据清理 | PASS | 测试待办 321/322 已删；测试短信 15191 已删；用户原有短信保留 |
| 无新增待办/通知 |  |  |
