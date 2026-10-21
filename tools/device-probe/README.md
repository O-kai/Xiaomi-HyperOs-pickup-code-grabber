# tools/device-probe · 真机探针

在已连接的设备上验证「短信 → 识别 → 写入待办」全链路。用于改规则、改通知通道后的真机复核，不消耗真实短信。

## 前置条件

- `adb` 可用（默认走 PATH；也可用环境变量 `ADB_PATH` 指定绝对路径）
- 设备已 root（读小米笔记待办库需要）
- 模块已安装并注入（`probe.py doctor` 会逐项检查）
- 模块内置 sqlite3 位于 `/data/local/tmp/pickup_sqlite/sqlite3`（首次使用模块后才会生成）

## 用法

```bash
# 检查设备侧前置条件
python probe.py doctor

# 注入一条短信并自动判定
python probe.py inject "包裹待签收【取件码-6-4-3009】，手机号已加密" --code 6-4-3009

# 列出小米笔记里最新的待办（确认真的写进去了）
python probe.py list --limit 10

# 截屏（保存到 tools/device-probe/shots/，该目录已 gitignore）
python probe.py ui
```

## 为什么用 ContentProvider 注入而不是发真短信

模块暴露了 `content://io.github.okaidev.pickupcode.provider` 的 `onSms` 方法，
内部走的判定与写入链路和真实短信**完全相同**（同一个 `TodoWriter.handle`），
但不消耗真实短信、不污染短信库，适合反复回归。

## 注意：去重

同一取件码写入过之后会命中指纹去重，`inject` 返回 `result=[]`。
这是**正常行为**，不是识别失败——验证时请用没测过的码。

## 判定「识别成功」还是「去重命中」

- 返回 `result=[码]` → 识别成功并写入
- 返回 `result=[]` → 两种可能：没识别出来，或该码已存在
  - 换一个新码再测即可区分
  - 也可以用 `probe.py list` 看待办里是否已有该码
