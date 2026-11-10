4.1.0 是**第三家 ROM** 的适配：荣耀 MagicOS。来自社区 **@GXR-SX** 的整包提交，
在荣耀 MagicOS 11 / Android 17 真机上把「短信 → 待办 → 已取件 → 小组件刷新」四条路径都跑通了。

小米、一加 / OPPO 用户这一版可以不动——荣耀的代码是独立分支，只在检测到 MagicOS 时启用。

### 荣耀和小米、ColorOS 差别不小

| | 小米 | ColorOS | 荣耀 MagicOS |
|---|---|---|---|
| 待办库 | 小米笔记 `todo.db` | 日历 `tasks.db` | `com.android.providers.calendar/task.db` |
| 表 | `todo` | `Tasks` | `Tasks`（62 列） |
| 完成标记 | `is_finish` | `finish_time` | `complete` + `date_completed` |
| 逻辑删除 | — | `deleted` | `delete_flag` |
| 写入入口 | root 直写 | root 直写 | **有 Provider**：`com.android.calendar.task` |

前两家只能 `su sqlite3` 硬写；荣耀有现成的 Provider，能走正门。

### 荣耀上：优先走 Provider，不一定要 root

- 通过 `content://com.android.calendar.task/tasks` 写入，只要日历读写权限
- Provider 内部会 `notifyChange`，**桌面小组件就是靠这个即时刷新的**，
  顺带还会把待办写进系统搜索索引
- 没给权限或 Provider 拒收，才回落到 `su sqlite3` 直写 `task.db`，写完补一次 `notifyChange`

### 表结构不写死，运行时读

荣耀的表有 62 列，而且不同 MagicOS 版本还在变。所以不硬编码列名：先 `PRAGMA table_info`
读出真实存在的列，再按列拼 SQL；认不出的结构直接失败并报出真实列名，不猜。

写入语句末尾带 `changes() > 0` 的标记，只认 `PICKUP_OK`——SQLite 对「被 WHERE 挡掉」
的 INSERT 是不报错的，没有这个标记，一次空转会被当成写成功，去重指纹照常落盘，
这条取件码就再也写不进去了。

### 顺带修了一个跨 ROM 的老问题

去重指纹只记「写过」，不记「还在不在」。用户在日历里把待办删掉之后，
驿站重发同一条短信会被永久跳过——表现就是「取件记录里有、待办里没有」。

现在命中指纹时先查一次「还有没有未完成的待办」：全被删掉就重写，已取件则不重写。

### 不再因为「版本号变了」就让你重启

首页那张「建议重启一次」以前每版必弹，哪怕你已经重启过了。
现在只在真的需要时出现：比较「本次开机时间」和「APK 安装时间」——
开机晚于安装，说明系统进程里已经是新代码，就不提示了。

### 错题本更干净了

「疑似漏抓错题本」以前只要短信里有快递字样就记，结果一台机器上 20 条全是
「订单签收提醒 / 商品派送通知 / 骑士已接单 / 包裹已寄出」这类状态通知——
它们本来就没有取件码，记下来只会让人以为漏抓很严重。

现在要求**短信里至少有一个码形 token** 才记。

规则集同时升到 **v11**：从这些样本里提炼出 9 个纯状态通知词
（订单签收提醒 / 订单物流 / 骑士接单 / 包裹已寄出 / 商品退货提醒 / 上门取件 等），
这类消息连解析都不再走。语料补了 15 条反例，RuleLab 125 条全过。

顺带修了详情页：
样本一多正文会把底部按钮顶出屏幕，看起来像「手动上报 / 清空」没了；
现在正文限高、按钮常驻。

### 捕获侧

短信主通道（`SmsProvider.insert`）荣耀沿用，只补了两处：

- `com.hihonor.mms`（荣耀独立后的「信息」包名，旧的 `com.android.mms` 在这代机器上不存在）
- `CallerPolicy` 白名单——不加的话，短信抓到了也唤醒不了模块

### 顺手加固

`CallerPolicy` 原来只按包名放行。白名单里 `com.huawei.mms` 这种「本机根本没有」的名字，
可以被任意 App 占用后调 Provider 注入假取件码。现在再加一道：必须是系统应用，
或者用户当前设置的默认短信 App。

### ⚠️ 升级注意

1. **装完重启手机**：短信解析代码在系统进程里；
2. 荣耀用户首次打开会申请日历读写权限——给了就不需要 root，拒绝则回落直写；
3. 小米 / 一加用户不用做任何操作，行为与 4.0.6 一致。

### 📖 相关链接

- [README](https://github.com/O-kai/Xiaomi-HyperOs-pickup-code-grabber#readme)
- [CHANGELOG](https://github.com/O-kai/Xiaomi-HyperOs-pickup-code-grabber/blob/main/CHANGELOG.md)
- 反馈：QQ 群 901543676（备注「取件码助手」）｜ [酷安](https://coolapk.com/u/1222904)

### 🙏 致谢

- **@GXR-SX** —— 荣耀 MagicOS 适配：Provider 写入、表结构运行时自适应、
  短信包名与调用方白名单、小组件即时刷新。

### ⚠️ 声明

- 荣耀侧由社区在 MagicOS 11 真机验证；维护者没有荣耀设备，只做代码审阅与其它 ROM 回归
- 直写系统待办库：只增行 / 定点标记，不删改已有数据
- 与小米 / OPPO / 荣耀均无官方关联，MIT 许可，使用风险自担
