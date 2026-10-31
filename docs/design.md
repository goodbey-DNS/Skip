# SkipAd 设计文档

> 本文档是整个项目的演化蓝图。每个里程碑开工前先在这里补细节，避免边写边漂。
> 对应用户三大关切：① 更大程度的广告跳过能力；② 截屏/录屏辅助生成跳过脚本；③ 跳过按钮位置变化的应对。

## 1. 总体架构

```
┌─────────────────────────────────────────────────────┐
│  UI 层（MainActivity / 快照查看器 / 规则编辑器）      │
├─────────────────────────────────────────────────────┤
│  引擎层                                              │
│   SkipAccessibilityService                           │
│   ├─ 事件节流器（150ms 时间窗 + 包名黑名单粗筛）      │
│   ├─ NodeScanner（五层匹配，见 §2）                  │
│   ├─ ClickPerformer（ACTION_CLICK → 祖先 → 手势）    │
│   └─ 快照/截屏采集器（M3，takeScreenshot + dump）     │
├─────────────────────────────────────────────────────┤
│  数据层                                              │
│   SkipRule（规则模型）  StatsStore（统计/日志）       │
│   RuleStore（M2：JSON 规则导入导出/订阅）             │
└─────────────────────────────────────────────────────┘
```

## 2. 五层匹配策略（回应"更大程度实现广告跳过"）

按命中率与安全度排序，逐层降级：

| 层 | 手段 | 覆盖场景 | 状态 |
|---|---|---|---|
| L1 | 多语言关键字包含匹配（跳过/跳過/スキップ/건너뛰기/skip + contentDescription） | 80% 常规开屏；**位置怎么变都能找到**（按特征找节点，坐标跟随节点） | ✅ M1 |
| L2 | 倒计时正则（"3s 跳过""5 秒后跳过""Skip 5s"） | L1 覆盖不到的倒排文案 | ✅ M1 |
| L3 | per-App viewId 规则（`com.xxx:id/skip_btn`） | 图片型/无文本按钮 | M2 |
| L4 | per-App 相对坐标（**角落锚定 + 屏幕比例**，非绝对像素） | WebView 渲染的开屏（节点树里只有 WebView 壳） | M2 |
| L5 | 端侧 OCR（ML Kit / Paddle），对 L4 场景截图识别"跳过"字样后按识别框点击 | WebView 内动态广告位 | M4 远期 |

防误触三保险（所有层共用，硬约束）：
1. 文本长度上限（24 字）：广告正文里提"跳过"的不是按钮；
2. 可点击祖先面积占屏上限（12%）：广告大图上印的假"跳过"直接排除；
3. 会话冷却（同窗口 4s）：跳过成功后不再动作，防误点二级页。

## 3. 稳定性设计

- 事件风暴：`TYPE_WINDOW_CONTENT_CHANGED` 一次开屏可触发上百个 → 全局 150ms 节流 + 包名黑名单（systemui/launcher 先排除）+ 遍历双上限（800 节点 / 400ms）。
- **桌面动态拉黑**（0.3.0）：静态包名黑名单会漏厂商桌面（荣耀桌面漏了 → 主屏「SkipAd」图标文字含 skip 被连点 6 次），改为 `CATEGORY_HOME` 动态解析全部桌面应用拉黑，桌面无开屏广告还可省遍历开销。
- `rootInActiveWindow` 优先于 `event.source`（后者在 CONTENT_CHANGED 中可能是局部节点）。
- 不调用 `AccessibilityNodeInfo.recycle()`：API 33+ 已废弃，低版本不回收仅轻微内存压力，规避 recycle 误用崩溃。
- 服务被杀：分级保活（§3.1）。未授权时 UI 检测"服务未连接"→ 引导跳设置页；授权静默后自动复活。

### 3.1 无障碍保活（✅ 0.2.0 实装，提前自 M3/M4）

无障碍服务被 ROM 杀 / 开机拦绑定 / 升级被强制关闭，是这类 App 的头号痛点。分级方案：

| 模式 | 前提 | 行为 |
|---|---|---|
| 静默复活 | adb 授 WRITE_SECURE_SETTINGS（signature 级，正常安装拿不到） | 掉线时把服务写回 enabled_accessibility_services + accessibility_enabled=1，系统自动重绑 |
| 提醒模式 | 零授权（默认） | 掉线发本地通知，点按跳系统无障碍设置 |

- 触发源收敛到 `KeepAlive.checkAndRecover`：**服务 `onUnbind`（黄金几秒，延迟 1.5s 自检）**、BOOT_COMPLETED / MY_PACKAGE_REPLACED 广播、15 分钟周期 Job（JobScheduler persisted，跨重启）、UI「立即自检」；
- **三重防震荡**（真机实测教训）：复活冷却 30s（系统重绑过渡期 `isRunningNow` 滞后 false，否则震荡成活循环）、`scheduleJob` 幂等（重排周期 Job 会触发立即首跑，与重绑滞后叠加成反馈回路）、两段式写入仅在"配置在但没绑定"场景启用；
- **两段式写入**是关键细节：开机被 ROM 拦绑定时配置里服务还在，值不变系统不重新解析——先写"去掉自己"再写"加回自己"制造一次变更，系统才会重新绑定；
- 边界：用户主动关闭在静默模式下也会被拉起（授权即视为接受）；强停后 App 进 stopped 态、一切调度失效，打开一次 App 恢复；
- 零依赖：用原生 JobScheduler 不引 WorkManager；不需要前台服务（服务绑定期间进程本身受系统保护）。



目标：把"写一条 per-App 规则"的成本从"会无障碍开发"降到"点两下屏幕"。

### 4.1 节点快照 + 截屏（M3 核心，走无障碍自带能力，零新权限）

- `AccessibilityService.takeScreenshot()`（**API 30+**，无障碍服务原生 API，**不需要 MediaProjection、不需要存储权限**，回调给 Bitmap）；
- 同时 dump 当前窗口节点树（text / contentDescription / viewIdResourceName / className / bounds / clickable）成 JSON；
- 两者时间对齐打包成一份"快照"（GKD 同款思路），存 App 私有目录；
- **快照查看器**：App 内页展示截图，节点 bounds 画半透明框叠加，点选任一节点即生成对应规则（viewId 优先、文本兜底、desc 兜底），一键保存并立即生效；
- 触发方式：通知栏常驻"抓取快照"动作 / 无障碍快捷方式（AccessibilityShortcut，长按音量键）。

### 4.2 录屏时序分析（M3.5，降级实现）

- 原始方案：MediaProjection 录制整个开屏过程 → 逐帧回放定位"跳过按钮首次出现的时刻"→ 自动给规则加 `actionDelay`；
- 降级方案（零权限优先）：**高频快照序列**——在检测到目标 App 开屏 Activity 时自动连续抓 N 张截图（如 500ms × 10 张），回放时人工/半自动选帧，效果等价且不引 MediaProjection；
- 录屏方案保留为可选高级功能（用户显式授权时才启用）。

### 4.3 快照分享

导出快照（截图+JSON 打包）为单文件，便于换机调试与社区互助定位问题节点。

## 5. 规则格式（M2 冻结）

```json5
{
  "version": 1,
  "rules": [
    {
      "id": "com.xxx.splash",
      "app": "com.xxx",                 // 缺省 = 全局规则
      "activityPrefix": "Ad",           // 可选：限定 Activity（粗筛提性能）
      "match": {
        "textContains": ["跳过"],
        "viewId": "com.xxx:id/skip",
        "regex": ["(?i)skip\\s*\\d"]
      },
      "anchor": { "corner": "topRight", "xRatio": 0.92, "yRatio": 0.08 },  // L4 坐标：四角锚定+比例，换分辨率不失效
      "maxAreaRatio": 0.12,
      "actionDelay": 0,
      "enabled": true
    }
  ]
}
```

- 位置变化的应对总结：**L1/L2/L3 按特征找节点，天然跟随位置**；只有 L4 用坐标，而 L4 用"四角锚定 + 比例"，同一广告 SDK 四角随机也只需把 corner 写成候选数组；
- 社区兼容评估：GKD 订阅是类 CSS 选择器体系（GPL 项目，只借鉴思路不搬代码），LiTiaotiao 社区规则是文本 DSL——M2 先定自家 JSON，M4 评估写转换器白嫖社区规则。

## 6. 已知红线与不做清单

- 不申请 INTERNET / 存储 / 位置权限，永远；保活相关的 RECEIVE_BOOT_COMPLETED / POST_NOTIFICATIONS / WRITE_SECURE_SETTINGS 属运营性权限（不碰用户数据，后者还必须 adb 手动授予才生效），不在红线内；
- 不做"自动抢红包/砍价/拉活"等灰产自动化，专注跳广告与关弹窗；
- 不上架国内应用商店（李跳跳 2023 年前车之鉴），发布渠道 = GitHub Release + APK；
- GPL 项目的代码不抄（GKD），MIT 项目（Android-Touch-Helper）可借鉴，借鉴处在 CHANGELOG 致谢。

## 7. 里程碑

| 节点 | 内容 | 状态 |
|---|---|---|
| M1 | 骨架 + L1/L2 通用引擎 + 状态/统计 UI + 零权限 | ✅ 2026-10-22（2026-10-23 首次编译打通并真机部署） |
| M1.5 | 无障碍保活（分级：静默复活 / 提醒模式，提前自 M3/M4） | ✅ 2026-10-23 |
| M1.6 | UI 二级页重构 + SQLite 分应用统计/排行榜 + 电池优化/自启动直达 + 沉浸式 | ✅ 2026-10-23 |
| M1.7 | 悬浮 Tab 信息架构（首页/设置/关于，砍保活二级页，新增关于页） | ✅ 2026-10-23 |
| M1.8 | 真·玻璃 Tab 栏（31+ 跨窗口模糊管线）+ 底部全沉浸 + compileSdk 37 | ✅ 2026-10-23 |
| M2 | per-App 规则（L3 viewId + L4 锚定坐标）+ 规则导入导出 | |
| M3 | 快照 + 截屏采集器 + 快照查看器 + 规则点选生成 | |
| M3.5 | 快照序列时序分析（录屏降级方案） | |
| M4 | 社区规则转换器 / 选择器子集 / OCR / 保活引导页 | |
