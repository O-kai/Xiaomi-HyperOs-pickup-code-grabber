# 应用图标（官方图标 + 自绘兜底）

```
icons/
├── cainiao.png / jd.png / taobao.png / pinduoduo.png / sf.png   ← 官方应用图标（当前使用）
├── fallback/       自绘占位图标（不含任何第三方素材，可安全分发）
├── fetch-icons.py  官方图标抓取脚本（可重跑）
└── official-sources.json  图标来源记录（App 名 / bundleId / 原始地址 / 抓取时间）
```

页面引用方式（PNG 优先，缺失时自动回退到自绘 SVG）：

```html
<img class="app-ico" src="icons/taobao.png" alt="淘宝" width="38" height="38"
     onerror="this.onerror=null;this.src='icons/fallback/taobao.svg'">
```

## 图标来源与状态

| 行 | 文件 | 来源 | 状态 |
| --- | --- | --- | --- |
| 菜鸟 | `cainiao.png` | App Store 官方美术图（`com.cainiao.cnwireless`） | ✅ 官方 |
| 菜鸟裹裹 | `fallback/cainiao-guoguo.svg` | 自绘（iOS 端「菜鸟裹裹」已并入「菜鸟」，无独立官方图标） | ⚠️ 占位 |
| 京东 | `jd.png` | App Store 官方美术图（`com.360buy.jdmobile`） | ✅ 官方 |
| 淘宝 | `taobao.png` | App Store 官方美术图（`com.taobao.taobao4iphone`） | ✅ 官方 |
| 拼多多 | `pinduoduo.png` | App Store 官方美术图（`com.xunmeng.pinduoduo`） | ✅ 官方 |
| 顺丰速运 | `sf.png` | App Store 官方美术图（`com.sf-express.waybillcn`） | ✅ 官方 |

规格：180×180 RGB PNG（显示 38dp、圆角 11dp、`border-radius` 由 CSS 施加）。
重新抓取：`python fetch-icons.py`（已存在的文件跳过，`--force` 强制重下）。

## 关于另外两个图标源的实测结论

- **iconfont.cn**：下载需登录，匿名请求一律被拦。
  实测：搜索页返回 2.8KB 反爬空壳（无图标数据、不下发 ctoken）；搜索接口返回 HTML 而非 JSON；
  图标详情接口直接 `{"code":500,"error_code":"LOGIN REQUIRED"}`。
  若你想用 iconfont 的图标：登录后下载 SVG/PNG，按上表文件名放进本目录即可（同名覆盖，无需改代码）。
- **thesvg.org**：可访问（GitHub Pages，仓库 [glincker/thesvg](https://github.com/glincker/thesvg)），
  路径为 `/icons/<slug>/<变体>.svg`。全量 7,436 个 slug 比对结果：**只有 `taobao`**
  （天猫 / 京东 / 拼多多 / 菜鸟 / 顺丰均无收录），因此无法作为整套图标来源。

## 授权提示

官方图标是各公司的**品牌资产（商标）**，用于原型与自用没有问题；
若要随 APK 一起分发，建议改用 `fallback/` 下的自绘图标，或删掉 5 个 PNG、让页面自动回退 —— 两者都不含第三方素材。

## 菜鸟裹裹（com.cainiao.guoguo）为什么拿不到官方图标

已逐一试过并排除的公开来源：

| 来源 | 结果 |
| --- | --- |
| App Store（bundleId `com.cainiao.guoguo` 直查） | 该应用已彻底下架，接口返回「查无此应用」（iOS 端「菜鸟裹裹」已并入「菜鸟」） |
| 应用宝 / 豌豆荚 / 小米商店 / 酷安 | 页面均为前端异步渲染或已改版，HTML 里没有该应用的图标地址（搜索接口返回 SSR 空壳） |
| 小米商店老接口 `app.market.xiaomi.com/apm/*` | 服务还在，但参数校验拒绝、且无 detail 端点 |
| iconfont | 下载需登录（详见上文） |

**当前状态**：用户已提供该图标（`cainiao-guoguo.svg`，1024 视框、蓝底 + 白色图形），
已由 `tools/gen-android-icons.py` 转成矢量图 `app/res/drawable/app_cainiao_guoguo.xml` 并被 App 加载，
不再使用自绘近似图。`fallback/cainiao-guoguo.svg` 仍保留作为「无第三方素材」版本的兜底。

如果日后想换成从设备提取的 1:1 位图，仍可用下面的脚本（位图优先级高于矢量）：

```bash
# 手机开 USB 调试并授权，然后：
python prototype/icons/extract-from-device.py com.cainiao.guoguo cainiao-guoguo
```

脚本会自动 `pm path` → 拉取 APK → 用 `aapt2 dump badging` 找到最高密度的启动图标 →
写入 `prototype/icons/cainiao-guoguo.png` 与 `app/assets/appicons/cainiao-guoguo.png`，
下次打包即生效（PNG 优先于兜底矢量，无需改代码）。
