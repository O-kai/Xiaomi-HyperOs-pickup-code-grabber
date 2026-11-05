# 取件码助手 (PickupCodeGrabber)

一个 **LSPosed 模块**：从短信和 App 通知里认出取件码，存进本地库，
首页顶部就是「待取件」，桌面小组件常驻；需要时再同步进系统待办。

支持 **小米 / 红米（MIUI · HyperOS）** 与 **一加 / OPPO（ColorOS 16）**，
需要 root（Magisk / KernelSU）+ LSPosed。

## ✨ 特性

- **两个来源**：短信 Hook（不申请短信权限）+ App 通知提取（菜鸟、菜鸟裹裹、京东、淘宝、拼多多、顺丰、丰巢、抖音等）
- **本地库是事实源**：带状态、地点、时间，能搜索、筛选、导出；超过一天没取的标「滞留 N 天」，只提醒不删
- **写入目标两处，默认都开**：
  - 系统待办：小米笔记待办 / ColorOS 日历待办（可跟账号云同步，同账号的其他设备也能收到）
  - 软件内部记录：首页「待取件」和桌面小组件读的就是它
- **桌面小组件**：每行固定高度、一行一条；「显示取件码」可关，关掉只显示件数
- **识别规则 v9**：覆盖主流驿站写法；电商那种「提醒你有件但不给码」的推送不会被误判
- **自定义规则**：自建驿站 / 学校自提柜可以按号码或正则单独写一条
- **一键链路测试**：不消耗真实短信，验证「解析 → 写入 → 去重 → 通知」整条链路
- **隐私**：全程本地处理，不收集任何数据；唯一联网行为是检查更新（3 天一次）

## 🔧 环境要求

| 项目 | 要求 |
|---|---|
| 系统 | HyperOS / MIUI，或 ColorOS 16；Android 9+ |
| Root | Magisk 或 KernelSU |
| 框架 | LSPosed 或兼容实现 |
| 作用域 | `android`、`com.android.phone`、`com.android.mms`、`com.android.providers.telephony`；小米设备另加 `com.miui.notes` |

⚠️ 「Android 系统」在作用域列表**最底部、不带推荐角标**，必须勾选；
不是名字很像的「系统框架」（`system`）。

## 📲 安装

1. 下载 APK 安装；
2. LSPosed 启用模块，按上表勾作用域；
3. **重启手机**；
4. 打开 App → 点开首页状态卡 → 点「点击体检」，全绿即完成。

## 📖 文档与反馈

- 完整说明：[GitHub README](https://github.com/O-kai/Xiaomi-HyperOs-pickup-code-grabber#readme)
- 更新日志：[CHANGELOG](https://github.com/O-kai/Xiaomi-HyperOs-pickup-code-grabber/blob/main/CHANGELOG.md)
- 反馈：QQ 群 901543676（备注「取件码助手」）｜ [酷安](https://coolapk.com/u/1222904) ｜ [Issues](https://github.com/O-kai/Xiaomi-HyperOs-pickup-code-grabber/issues)

## ⚠️ 声明

- 实测：Redmi K90 Pro Max / HyperOS 4.0 / Android 17；ColorOS 16 的部分适配路径无实机验证；
- 直写系统待办库：只增行 / 定点标记，不删改已有数据。跨设备靠系统账号同步，
  同步由系统负责（没登录账号或关掉同步就不会传），模块不介入这个过程；
- 与小米 / OPPO 无关联，MIT 许可，使用风险自担。
