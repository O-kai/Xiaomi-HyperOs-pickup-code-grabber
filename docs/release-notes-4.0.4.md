4.0.4 是两次社区提交的合并，都来自 **@ByteJuno**（ColorOS 用户，自带真机验证）。
这一版没有新功能，只修两个「明明能用却被判成不能用」的地方。

### 🩺 ColorOS 用户的「唤醒体检」不再误报

体检里有一项「ColorOS 唤醒」，用来判断模块被系统冻结后还能不能被叫醒。
它原来去系统 JAR 里找 `ContentProviderHelperExtImpl.class` 这个文件——
可 Android 的系统 JAR 里根本没有 `.class` 文件，装的是 `classes*.dex`。

结果就是：**所有 ColorOS 机器都会被判成「找不到适配目标，唤醒不会生效」**，
哪怕实际上完全正常。

现在改成解析 DEX 的类定义（`class_defs → type_ids → string_ids`），按完整类名判断
这个类到底在不在；顺手把文案改准了：

- 「静态找到类」只说明适配目标还在，**不等于** Hook 装上了，更**不等于**冻结后真能唤醒
- 没找到也不再断言「失效」——可能是系统预优化、DEX 格式或路径差异，
  提示结合本次开机的 WAKE 日志复核

### 🎛 设置里的开关终于点得动了

4.0.3 有一批用户反馈「写入目标那几个开关点不动」。原因是自绘的 `Switch` 自己处理触摸：

- 只要手指有一丁点移动就当成「拖动」，抬手不切换——手抖一下就点不动
- 切换逻辑写在抬手事件里，无障碍点击、键盘、程序调用都切不了

现在触摸交回给系统的 `View` 处理（touch slop / pressed / cancel 都是标准行为），
`performClick()` 成为唯一切换入口；补上了开关的无障碍语义（class / checkable / checked）
与键盘焦点；写入目标整行都能点（点标题、点右边空白都算），不再是只有小开关本身。

关闭「软件内部记录」的二次确认期间，开关会正确禁用；点取消、按返回、点弹窗外面，
开关都恢复原来的状态，不会卡在「关着一半」。

### ⚠️ 升级注意

1. **装完重启手机**：抓取逻辑跑在系统进程里，LSPosed 只在进程启动时注入；
2. 桌面上已有的小组件不用重加，这一版没有改布局；
3. ColorOS 用户升级后，体检里那一项应该从红变绿——如果还是红的，
   把「ColorOS 唤醒」那一行的说明和本次开机的 WAKE 日志一起发出来。

### ✅ 文件校验

- 文件名：`pickup-code-grabber-4.0.4-release.apk`
- SHA-256：`19c8b0392a39978a95e3a8b0533bb8b1890e78a7158e059822f3f33ca1e840fd`

### 📖 相关链接

- [README](https://github.com/O-kai/Xiaomi-HyperOs-pickup-code-grabber#readme)
- [CHANGELOG](https://github.com/O-kai/Xiaomi-HyperOs-pickup-code-grabber/blob/main/CHANGELOG.md)
- 反馈：QQ 群 901543676（备注「取件码助手」）｜ [酷安](https://coolapk.com/u/1222904)

### 🙏 致谢

- **@ByteJuno** —— 本版两笔修复的作者。通知通道校验、Provider 唤醒放行、
  短信 Hook 类名、DEX 门禁探测、设置开关交互，ColorOS 这几条线基本都是他在推。

### ⚠️ 声明

- 本版代码由社区在 ColorOS 真机验证；维护者手上没有 ColorOS 机器，
  只做了代码审阅与 HyperOS 侧回归；
- 直写系统待办库：只增行 / 定点标记，不删改已有数据；跨设备靠系统账号同步；
- 与小米 / OPPO 无官方关联，MIT 许可，使用风险自担。
