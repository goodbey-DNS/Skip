# Skip

> 基于 Android 无障碍服务的开屏广告自动跳过工具 —— 类李跳跳的**开源自用版**。
> 零权限（连 INTERNET 都不申请）、零联网、所有识别本地完成。

## 原理

三步：`AccessibilityService` 监听窗口变化 → 在节点树中按规则匹配"跳过"按钮（多语言关键字 + 倒计时正则，带防误触约束）→ `performAction(ACTION_CLICK)` 模拟点击（失败时降级祖先点击 / 坐标手势）。

## 功能

- **M1（当前）**：通用关键字引擎，覆盖中/繁/日/韩/英"跳过"文案与倒计时变体；
- 防误触三保险：文本长度上限 + 可点击祖先面积上限 + 同窗口会话冷却；
- 主界面：服务开启引导、累计跳过统计、最近跳过日志；
- 位置无关：按特征找节点而非固定坐标，跳过按钮换位置照样命中。

## 快速开始

```bash
git clone https://github.com/notEatOranges/SkipAd.git
# Android Studio 打开，或命令行：
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

安装后打开 App → 点"去开启无障碍服务" → 系统设置里找到 "SkipAd 开屏跳过" 开启 → 回 App 确认状态变绿，即已生效。

## 路线图

完整设计见 [docs/design.md](docs/design.md)：五层匹配策略、per-App 规则（viewId / 角落锚定比例坐标）、**节点快照 + 截屏调试工具**（无障碍 `takeScreenshot`，零权限）、快照序列时序分析（录屏的降级方案）、社区规则兼容。

## 隐私

- 不申请 INTERNET / 存储 / 位置权限，永远；仅有 5 个与功能直接相关的运营性权限（开机广播、悬浮窗、通知、忽略电池优化、`WRITE_SECURE_SETTINGS`，最后一项需电脑 `adb` 手动授予才生效）；
- 无网络能力 = 物理上不可能上传任何数据；
- 统计存本机 SQLite（`skipad.db`），保活日志存 SharedPreferences，快照与诊断控件树存应用私有目录；统计与日志可一键清空，快照在规则页逐条删除。

## 致谢与声明

- 思路致敬：[李跳跳]（已停更）、[GKD](https://github.com/gkd-kit/gkd)（GPL-3.0，本项目仅借鉴设计思想，未复制任何代码）、[Android-Touch-Helper](https://github.com/zfdang/Android-Touch-Helper)（MIT）； skiphttps://github.com/GuoXiCheng/SKIP  
说明：仅是借鉴思路，未使用代码，纯个人学习使用！！！！！！
- **本项目仅供学习交流与个人使用，禁止用于商业或非法用途，不上架任何应用商店。** 纯属个人测试娱乐，

## License

[MIT](LICENSE)
