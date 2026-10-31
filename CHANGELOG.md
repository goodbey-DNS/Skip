# CHANGELOG

> 规矩：每轮修改必须记录（改了什么/为什么/影响面/踩坑），换电脑先读本文件再动手。

## 0.16.3 - 2026-11-01 · 关键字的相对长度闸 + 分隔符归一化 + 坐标重试

**改了什么**
- **文本长度闸从绝对值改成相对值**（`NodeScanner.allowedLen`）。原来是 `maxTextLength = 24` 的绝对上限，对「跳过」这种两字词等于放进一整句广告语——正文里提到"跳过"的句子也能过闸。现在改成 `min(24, 命中短语长度 + 8)`：命中「跳过」只允许 10 字，「跳过广告，进入APP」（9 字）通过，「跳过广告可以领取新人红包」（12 字）拦下。viewId 与正则命中没有确定短语，仍走绝对上限。
- **分隔符归一化**（`NodeScanner.normalizeForMatch`）。匹配前去掉空白、零宽字符、全角空格、中点、常见括号与竖线：「跳 过」「[跳过]」「跳·过」「跳\n过」现在都能命中，之前一个都匹配不上，是纯漏跳。弹窗通道的 E3/E4/E5/E7 四条证据也一起走归一化。
- **坐标规则重试 4 拍**（`SkipAccessibilityService.schedulePointTaps`）。原来是 `delayMs` 与 `delayMs+1000` 两次；现在 0/600/1200/1800 四拍。原因是开屏出现时界面还没就绪，第一下常落在入场动画上，落空后再没有机会。闸门只在第一拍记一次账，后三拍是同一轮重试，不被冷却挡掉。
- **盲点时间盒**：坐标规则在窗口出现超过 8 秒后不再执行（`POINT_MAX_WINDOW_AGE_MS`）。按坐标点是没有文本证据的盲点，窗口存活太久说明早不是开屏了，这时点下去是拿用户的操作界面赌运气。只作用于坐标规则，文字/控件匹配的规则有自己的形状闸。
- 版本 0.16.3 / versionCode 50。

**为什么**
- 用户要求重看 GKD 与 Android-Touch-Helper，取长补短。核对了这两个项目的实际实现（`SkipAdRules.java` 的 `KEYWORD_MAX_EXTRA_LENGTH`、`normalizeSeparators`、`findCompositeKeyword`、`PACKAGE_POSITION_CLICK_RETRY`），挑出四条改动成本低、收益直接的落地。

**影响面/备注**
- 相对闸用离线脚本验证（`check_len_gate.py`，复刻同一套规则）：`跳过`/`跳过 5s`/`跳 过`/`[跳过]`/`跳·过`/`跳\n过`/`Skip 3s`/`跳过广告，进入APP` 全部 hit；`跳过广告可以领取新人红包`（12 字）、`跳过广告并进入主界面浏览更多精彩内容`（18 字）被拒。
- 未采纳的两条：兄弟节点拼接关键字（要改 `collectSnaps` 的树遍历，单独一轮）、扫描背压队列（现有间隔节流够用）。
- 未采纳：无障碍披露确认（不上架应用商店，无政策要求）。

## 0.16.2 - 2026-11-01 · 权限全量审计 + 改出 release 包 + 推 tag 自动发 Release

**改了什么**
- 权限全量审计（以 `dumpsys package` 的 requested/install/runtime 三段为准，不看源码猜）：实际生效就 5 个，没有第三方库偷偷合并进来的（无 WAKE_LOCK / FOREGROUND_SERVICE / INTERNET）。逐个核对调用点，全部在用，删不掉：

  | 权限 | 实际用途 | 去掉的后果 |
  |---|---|---|
  | `RECEIVE_BOOT_COMPLETED` | `keepalive/BootReceiver.kt:18` 开机 / 覆盖安装后立刻尝试复活 | 开机后要等一个 Job 周期（15 分钟）才恢复，升级完无障碍被系统关掉也不会自动拉回 |
  | `SYSTEM_ALERT_WINDOW` | `util/CaptureBubble.kt:60,87` + `ui/RulesActivity.kt:56-65` 抓取悬浮窗 | 只能下拉通知栏抓快照，广告出现时来不及点 |
  | `POST_NOTIFICATIONS` | `keepalive/KeepAlive.kt:208` 掉线提醒、`SkipAccessibilityService.kt:818` 抓取完成提示 | 掉线不提醒，静默失效 |
  | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | `ui/KeepAliveSettingsActivity.kt:68,91` 一键申请电池白名单 | 只能手动进系统设置翻 |
  | `WRITE_SECURE_SETTINGS` | `keepalive/KeepAlive.kt:123-140` 静默复活写回无障碍开关 | 静默模式失效（该权限 signature 级，普通安装拿不到，必须电脑 adb 手动给） |

  同时确认没有的：`INTERNET` / `ACCESS_NETWORK_STATE` / 存储读写（备份走 SAF + 应用私有目录）/ `QUERY_ALL_PACKAGES`（`<queries>` 只声明 LAUNCHER 与 HOME 两类 intent）/ `REQUEST_INSTALL_PACKAGES`。
- **CI 出包从 `assembleDebug` 改为 `assembleRelease`**。以前装到手机上的是 debug 包，`android:debuggable=true`（`dumpsys package` 里 `flags=[ DEBUGGABLE … ]`，`run-as` 直接能进），拿到手机的人可读私有数据、attach 调试器改内存。对一个常驻无障碍服务的 App 这不能留。签名仍是同一个 ci keystore，覆盖安装不受影响。
- 工作流加「已签名 + 非 debuggable」硬校验：`apksigner verify --print-certs` 输出 SHA-256（原来带 `|| true`，等于没校验）；`KEYSTORE_B64` 缺失直接失败；`aapt2 dump badging` 里出现 `application-debuggable` 直接失败。
- Release 步骤补 `name` / `prerelease: false`，`draft: false` 保持。推 `v*` tag 即自动发 Release，APK 匿名可下。
- 版本 0.16.2 / versionCode 49。

**为什么**
- 用户要求「把 App 内所有权限检查一遍，确保不需要的全部禁用」+「每次推 tag 都用 CI 自动发 Release，APK 可直接匿名下载」。审计结论是没有冗余权限，但发现出的是 debuggable 包，属于比多余权限更实际的口子。

**影响面/备注**
- 换成 release 包后 `run-as` 不可用，读 `rules.json` 要改用 App 内的备份导出（0.15.0 已有）。
- 不混淆（`minifyEnabled false`），崩溃栈仍可读。
- 该 App 至今 Release 停在 v0.10.1，0.11–0.16 都没打 tag，所以「手动下载更新」此前拿不到最新版；本轮补 `v0.16.2` tag。

## 0.16.1 - 2026-11-01 · 备份导入的容错与认人

**改了什么**
- `RuleStore.read()` / `KeywordStore.read()` 改成逐条解析（`parseOne` + `parseAll`）。以前 `getString("id")` / `getString("app")` / `getString("kind")` 是必填，**一条残缺就把整份读成空列表**，界面上看着像规则全没了；更糟的是此时随手点一下开关或删除，`write(context, read().filter{…})` 会把空列表写回磁盘，原数据真丢。现在缺 app / kind 的单条丢弃并计数（不知道作用域，留着反而会变成全局规则），缺 id 的补 `legacy-<位置>-<createdAt>` 稳定 id，其余照常加载。
- `RuleStore.importFrom` / `KeywordStore.importFrom` 改成「解析成对象 → 规范化 write → 返回真实可用条数」。原来是整份合法 JSON 数组就原样写回，残缺条目能骗过校验：提示恢复 5 条，实读 0 条。
- `Backup.restore` 真读 `manifest.json` 的 `app` / `version`。原来只判断文件是否存在，任何带 manifest.json 的 zip 都能过闸；现在 app 写了但不是 SkipAd 直接拒。三块数据各自 `runCatching`，某块坏了不连累另外两块（原来一处抛错整份恢复归零）。
- `RuleStore.write` / `KeywordStore.write` 改原子写（先写 `.tmp` 再 rename）。直接 `writeText` 若中途被杀会留下半截 JSON，那份文件连合法数组都不是。
- `SnapshotStore.loadNodes` / `loadMeta` 同样改 opt 取值 + 逐条兜；缺 `pkg` 时从目录名 `<ts>_<pkg>` 兜底。
- 恢复结果从 Toast 改成对话框，把「跳过 N 条」「哪一块文件损坏」「备份来源版本」都写清楚。
- 版本 0.16.1 / versionCode 48。

**为什么**
- 用户问「导入没做版本兼容处理是否需要现在完善」。核查结论：加字段那类兼容本来就有（读取全部走 `optXxx`，缺字段走默认值），不需要另立版本号；真正的坑是坏条目连带整份归零、以及 manifest 闸门形同虚设。

**影响面/备注**
- 真机实测（Redmi 22081212C / Android 15 / 0.16.1）：
  - 5 条规则（1 条缺 app、1 条缺 kind、1 条缺 id、1 条正常、1 条非对象）→ 规则页显示 3 条、不崩；改动作后文件被规范化写回，坏条目被清掉，缺 id 补成 `legacy-4-1761954700000`。
  - `bad-mixed.zip` 导入 → 「规则 2 条、关键字 2 条、快照 0 份 / 规则跳过 3 条 / 关键字跳过 1 条 / 来自 0.12.0」，落盘 JSON 规范。
  - `foreign.zip`（manifest app=OtherApp）→ 「恢复失败：不是可识别的备份」，原规则未被覆盖。
  - `broken-json.zip`（规则文件截断）→ 「规则文件不是合法 JSON，原有规则未被改动」，原 2 条规则完好。
- 未验证：规则页「删除」按钮用 adb 点不动（`clickable=true`、bounds 正确，`input tap` / 同点 swipe / `input touchscreen tap` 三种都无响应，也无确认框弹出），判断是该 TextView 在 row 里的触摸区域问题，早于本次改动就存在；`write` 写路径已由「改动作」实测覆盖。
- 测试数据已清理：`rules.json` / `keywords.json` 还原为 `[]`，`backup/` 下测试期间生成的 zip 全部删除。

## 0.16.0 - 2026-11-01 · 备份恢复（导入）

**改了什么**
- 「数据与维护 → 备份与导出」新增 **从备份恢复**（系统文件选择器，SAF 选 zip，不申请存储权限）与 **恢复最近一次备份**（直接吃 backup 目录里最新那份，省得每次翻文件）。
- `Backup.restore()`：读 zip 里的 `rules.json` / `keywords.json` / `snapshots/**/{meta,tree}.json`。
  - 备份里**没有**的那类数据不动（不会因为旧备份把现在的关键字清空）；
  - 快照按目录名合并，不删已有（目录名带时间戳，重名即同一份）；
  - 先校验 `manifest.json`，不是 SkipAd 备份就拒绝。
- 恢复前自动导出一份当前数据到 backup/（后悔药），Toast 里会报这次自保备份的文件名。
- `RuleStore.importFrom` / `KeywordStore.importFrom`：校验 JSON 合法才覆盖，并各自 bump 版本号，让无障碍服务立刻重建扫描器 / 重新取词，不用重装或重启。
- 版本 0.16.0 / versionCode 47。

**为什么**
- 用户指出只有导出没有导入，恢复链路不完整。

**影响面/备注**
- 恢复是覆盖式：同名快照会被备份里的版本替换；其余快照保留。
- 只认 zip，且必须含 manifest/规则/关键字/快照之一，其他 zip 直接拒绝。

## 0.15.0 - 2026-11-01 · 备份导出（数据与维护）+ 构建环境钉版本

**改了什么**
- 新增 `util/Backup`：把 `rules.json`、`keywords.json`、所有快照的 `meta.json` + `tree.json` 打包成 zip，附 `manifest.json`（版本号、时间、文件数、说明），落在 `Android/data/<包名>/files/backup/`。
- **不含 screen.png**：一份全屏图十几 MB，30 份快照就是几百 MB；备份的目的是保住「重建规则所需的最小数据」，节点树足够，截图可重抓。
- 「数据与维护」页新增「备份与导出」卡片 + 导出按钮：打包走 IO 线程，完成后 Toast 报文件名与大小，并拉起系统分享面板（FileProvider，`${applicationId}.fileprovider`），可直接存网盘或发给自己。
- 工作流 `runs-on` 从 `ubuntu-latest` 钉到 **ubuntu-24.04**：那条 “ubuntu-latest will migrate to Ubuntu 26” 是 GitHub 的 notice（不是错误），但迁移会换掉预装 JDK/SDK 组合，钉版本让构建环境可复现。
- 版本 0.15.0 / versionCode 46。

**为什么**
- 用户要求快照生成的规则可导出备份。现在规则全在本机 filesDir，换机或清数据就没了。

**影响面/备注**
- 导出文件在应用外部私有目录，卸载应用会被一起删掉；要长期保留请通过分享面板另存。
- 只做导出，没有导入：恢复需要解压覆盖 filesDir，涉及文件覆盖风险，等明确需求再做。

## 0.14.0 - 2026-11-01 · 快照补全：多窗口 + 结构路径 + 长按取点

**改了什么**
- **抓取覆盖多窗口**：同一个 App 的主窗口与浮层/对话框窗口全部抓（只收本 App 的窗口，避免混入输入法与别的 App 悬浮窗）；总上限从 400 提到 **1500**，单窗口 800。此前只抓活动窗口，应用内浮层弹窗的关闭钮经常根本不在树里。
- **节点记录结构路径**：每个节点存 `path`（窗口根 → 子索引，如 `0/3/1`）与 `winId`，纯图标按钮没有 text/desc/viewId 时也能唯一定位。
- **meta 记录屏幕尺寸** `screenW/screenH`，长按取点做 dp 换算要用。
- **新增规则类型「按结构路径」**：快照点选纯图标控件 → 存 path + 类名 + 尺寸(dp)。运行时服务直接用路径下钻（先活动窗口，再本 App 的其他窗口），并有**类名一致 + 尺寸偏差 ≤40%** 的校验，页面改版导致路径指向别的控件时不点。
- **新增「长按取点」**：在快照截图上长按任意位置，直接取点生成规则，不依赖节点树（H5、自绘控件、图片内部都能点）；坐标存成**相对屏幕中心的 dp**，换分辨率/密度按当前屏幕换算，不再是纯百分比。
- 规则页显示新字段：`路径=...`、`屏幕中心偏移 x,y dp`。
- 版本 0.14.0 / versionCode 43。

**为什么**
- 用户要求“快照要能精确点到纯图标的关闭按钮”。原实现单窗口 + 400 节点上限会漏，且图标节点没有 vid/文字时只能退化成屏幕比例坐标。

**影响面/备注**
- 快照体积变大（1500 节点，tree.json 约 200KB），单份仍有上限保护；`prune` 保留最近 30 份不变。
- 路径规则的稳定性依赖控件层级：App 改版可能失效，失效时不点（有校验）；重抓快照即可重建。
- 长按取点存 dp，同密度设备完全一致；跨密度按比例换算，误差在几 dp 内。

## 0.13.1 - 2026-11-01 · 关键字严格按窗口所属包取用（防跨应用误伤）

**改了什么**
- 原来关键字在事件入口按「前台包名」加载一次，但弹窗通道会扫**多个窗口**（最多 3 个，含浮层窗口），那些窗口可能属于别的包——等于把 A 应用的关键字套到 B 应用的窗口上。校验「跳过」这类泛词时，这就是实打实的跨应用误伤。
- 改成 `keywordsFor(pkg)` 按**窗口自己的包名**取词，带版本号缓存（改关键字即整份失效）；`tryPopupClose` 和 `findTargetInAnyWindow`（验证路径）都按窗口包名取。
- 加了 V 级日志 `kw <pkg>#<winId> hits=N excludes=M`，只在有词时打印，便于确认「这个窗口用的是谁的词」。
- 版本 0.13.1 / versionCode 42。

**为什么**
- 用户明确要求：针对某个 App 的关键字不能跨应用生效。关键字会放宽位置限制，跨应用生效等于把误点面放大到全部 App。

**影响面/备注**
- 「所有应用」条目仍是显式全局生效（用户主动选的），不接受这一点就不要建全局条目。

## 0.13.0 - 2026-11-01 · 自定义关键字（命中词提高识别 / 排除词防误点）

**改了什么**
- 新增 `KeywordStore`（`files/keywords.json`）与规则页「关键字」区块：每个作用对象一条，含**命中词**与**排除词**，可开关、可删；作用对象在弹窗里选（所有应用 + 已安装可启动应用，不用手打包名）。
- 引擎接入：`findPopupCloseTarget` 增加 `extraClose` / `extraExclude` 两个参数。
  - 命中词走新的 **E7** 证据：文字包含该词、控件尺寸像按钮（小控件 ≤22% 屏宽，或卡片级 ≤92% 宽 × ≤12% 高）即命中，不受位置限制，强度与 E5 同级（tier 2）。
  - 排除词并入 `businessWordIn` 的两道闸（中央区 + 卡片内），**排除优先**：命中就整窗放弃。
- 关键字改动即时生效：`KeywordStore` 自带版本号，服务按 `关键字版本|包名` 重新取词，不用重装或重启服务。
- 约束写死在存储层：单条 ≤10 字、每类 ≤20 条、去空去重；排除词优先级高于命中词。
- 版本 0.13.0 / versionCode 41。

**为什么**
- 内置词表只覆盖「关闭/跳过/×/我知道了」这类通用说法，App 自己的措辞只能靠几何证据硬猜，那正是误点高发区。用户知道某 App 的按钮叫什么，让他直接告诉引擎，是成本最低的覆盖率提升。

**影响面/备注**
- 关键字只作用于弹窗通道，不影响开屏跳过。
- 命中词会放宽位置限制，写「关闭」这种泛词等于把全屏任意位置的关闭类文字都当按钮——风险由用户自己承担，UI 上已写明「仍要求控件像按钮」。

## 0.12.1 - 2026-11-01 · Gradle 9 工具链（消掉 out of date 提示）

- Gradle wrapper 8.7 → **9.8.2**；AGP 8.2.2 → **9.5.0**；compileSdk 34 → **36**；CI JDK 17 → **21**（AGP 9 要求）。
- AGP 9 内置 Kotlin：删除 `org.jetbrains.kotlin.android` 插件（AGP 会直接报 `Failed to apply plugin` 并提示移除），`kotlinOptions {}` 换成顶层 `kotlin { compilerOptions { jvmTarget = ... } }`，jvmTarget 默认跟随 `compileOptions.targetCompatibility`。
- 工作流：安装 `platforms;android-36` + `build-tools;36.0.0`。
- 结果：`Gradle version is out of date` 提示消失（grep 日志 0 次），构建全绿，0.12.1 装机后服务连接、引擎扫描、界面渲染均正常。
- 残留（非阻塞）：`Deprecated Gradle features were used ... incompatible with Gradle 10`，来自 AGP/settings 脚本，Gradle 9 下不影响构建。
- 版本 0.12.1 / versionCode 40。

## 0.12.0 - 2026-10-31 · 弹窗候选打分 + 图片弹窗 E6 + 弱证据二次确认

**改了什么**
- **候选打分**：原来「BFS 先遇到谁就点谁」，低证据命中可能抢在高证据前面。现在按强度排序取最优——E3（文字就是关闭且在中下）> E5（关闭某活动）> E4（卡片全宽按钮）> E1（卡片边缘小控件）> E2（角落小图）> E6（图片弹窗唯一小控件），同档取面积更小的那个（更像按钮本体）。
- **E2 放宽**：不再只认「节点数 ≤60」，窗口本身没铺满屏幕（对话框/浮层）也算弹窗窗口。首页那种节点几百的浮层以前完全进不了 E2。
- **E6 图片弹窗**：整窗只有一个居中大卡片（面积 8%~55%、宽 30%~92%、高 12%~55%）且它下沿**只有一个**小可点控件时命中。针对没有营销文案的纯图片弹窗，此前三条证据都要文字或卡片文案，这类必漏。
- **弱证据二次确认**：E2 / E6 这类几何证据要求连续两次（间隔 ≤2.5s）命中同一控件同一位置才动手，首次命中只记账并 450ms 后补扫一次。弹窗插入瞬间布局未稳，单帧判断最容易点偏。
- **歧义放弃**：弱证据同时出现两个以上候选时直接不点，日志记 `popup-ambiguous`。
- **顽固窗口静默**：点 3 次仍没关掉的窗口，该窗口静默 30 秒，不再反复去点。
- 版本 0.12.0 / versionCode 39。

**为什么**
- 用户要求继续提高识别率、继续降误点。识别率的短板集中在「无文案的图片弹窗」和「满屏节点的浮层」；误点风险集中在「单帧弱证据」和「多点候选挑错」。

**影响面/备注**
- 弹窗通道单次扫描仍在 500 节点上限内，E6 只复用同一次 BFS 结果，不额外读节点。
- 弱证据多等一次扫描（≥450ms），换来「点错一次」的概率下降；强证据（E3/E5/E4/E1）不受影响，仍是即时命中。

## 0.11.2 - 2026-10-31 · E5 放开位置闸，修掉京东「关闭浮层活动」漏点

- 实测京东首页那个「关闭浮层活动」：`Button`，bounds `[1064,2170][1220,2222]`，宽 12.8% 屏宽、高 1.9% 屏高，中心 x=93.6%——尺寸没问题，卡在关闭钮位置闸的 92% 上限（它贴在屏幕右缘）。
- 新增 `closeSizeOk()`：只做尺寸与类名闸（宽 ≤22% 屏宽、高 ≤9% 屏高），不看位置；E5 改用它，E3/E4/E1/E2 维持原闸。
- E5 仍要求「文字以关闭开头 + 含活动/广告/浮层/弹窗/推荐/推广/营销」，业务词排除在前。
- 版本 0.11.2 / versionCode 38。

## 0.11.1 - 2026-10-31 · E5 关闭广告浮层 + 工作流升到 node24 Action

**改了什么**
- **E5 证据**：文字以「关闭」开头且含「活动/广告/浮层/弹窗/推荐/推广/营销」时直接命中，不再要求落在屏幕中央偏下。京东首页顶部的「关闭浮层活动」就是这么漏掉的（`startsWith("关闭")` 成立，但 top 区域被位置闸挡下，且页面营销文案分散、卡片包围盒不成立）。
- **服务连接日志**改成读 `PackageInfo.versionName`，不再写死版本号（0.11.0 装上去还打着 `service-connected 0.10.1`）。
- **工作流**：`actions/checkout@v7`、`actions/setup-java@v6`、`gradle/actions/setup-gradle@v6`、`actions/upload-artifact@v7`、`softprops/action-gh-release@v3`，全部 node24 运行时，消掉 Node 20 停用提示；Setup Gradle 加 `continue-on-error`，GitHub 缓存服务偶发 5xx 时只影响速度、不标红（日志里那条 “Our services aren’t available” 就是缓存服务抖动，构建本身是成功的）。
- 版本 0.11.1 / versionCode 37。

**影响面/备注**
- E5 放宽了位置，但仍是「小控件形状 + 明确关闭语义 + 广告词」三重条件，且业务词排除在前；风险面是某些 App 的「关闭活动面板」会被点掉（本就要关）。

## 0.11.0 - 2026-10-31 · 弹窗通道提覆盖：按窗口冷却、全宽关闭按钮、卡片内业务词、新增窗口触发

**改了什么**
- **冷却粒度**：弹窗点击冷却从「整 App 20 秒」改为**按窗口**——同窗口 4 秒内不重复点、最多 3 次，另加 1200ms 全局最小间隔（让 800ms 验证先跑完）。原来一个 App 里连着两个弹窗，第二个会被 20 秒冷却直接放过。
- **E4 证据**：卡片下沿 16% 范围内的全宽文字按钮（「我知道了」「以后再说」「暂不开启」）也可命中，宽度放开到 92% 屏宽、高度到 12% 屏高。这类按钮过不了 `closeCandidate` 的小控件形状闸，此前必然漏。
- **业务词优先**：业务词表从 17 个扩到 23 个（补收货、实名、绑定、注销、退款、提现、转账、银行卡、订单）；判断从「只看屏幕中央区」改为**两道**——先中央区早退，算出营销卡片后再在卡片范围内查一遍。命中业务词整窗放弃，宁可漏营销弹窗。
- **新增窗口触发**：无障碍配置加 `typeWindowsChanged`，`WINDOWS_CHANGE_ADDED` 时只走弹窗通道（400ms 间隔），解决浮层弹窗不改变活动 root、只靠 WSC/WCC 会漏的问题。
- **多窗口扫描**：弹窗通道不再只看 `rootInActiveWindow`，改为扫所有可交互窗口（活动窗口优先，最多 3 个），浮层弹窗常在独立窗口里。
- 手动执行坐标规则后的静默期独立成 `popupSuppressUntil`（8 秒），不再借用弹窗冷却字段。
- 版本 0.11.0 / versionCode 36。

**为什么**
- 用户要求「最大限度自动识别弹窗、最低限度误点」。上一版的漏点集中在：整 App 长冷却、全宽按钮形状闸、业务词只在中央区、浮层窗口不在活动 root；误点集中在业务词表太窄。

**影响面/备注**
- 弹窗通道扫描成本上升（最多 3 棵树 × 500 节点），但只在窗口变化/新增/SUBTREE 变化时触发，且有 400–1800ms 间隔；`NodeFactsCache` 让同一节点的属性只读一遍。
- 业务词命中即整窗放弃，代价是「开通会员立减」这类含业务词的营销弹窗会被放过（漏），这是刻意的取舍。
- 新增 `typeWindowsChanged` 后事件量略增，`THROTTLE_MS` 300ms 仍是第一道闸。

## 0.10.1 - 2026-10-31 · 服务连接留痕（排障用）

- `onServiceConnected` / `onUnbind` 各打一行日志（`service-connected 0.10.1`），并加 5 秒一次的事件心跳（`event <type> <pkg>`），确认装包后无障碍事件是否真的在进来；`builtinScanner` 未初始化时弹窗通道改为报 W 而不是静默返回。
- 为什么：0.10.0 装到机器上之后日志一片空白，无法判断是"没收到事件"还是"没命中"；实测根因是 HyperOS 在重新安装后把 `enabled_accessibility_services` 写成同值不触发重绑，需要先删再写才会重新连接（可用 `adb shell settings delete secure enabled_accessibility_services` 后重设，或在系统设置里关再开一次）。
- 版本 0.10.1 / versionCode 35。

## 0.10.0 - 2026-10-31 · 规则按 App 页面隔离 + 冷却限次 + 返回键/只匹配 + 相对坐标 + 节点属性缓存

**改了什么**
- **规则隔离**：`nodeRulesAsScannerRules` 此前完全不看 `app` 字段，一条淘宝规则会在所有 App 里生效（`maxAreaRatio` 还放宽到 0.3），是明确的误触隐患。现在节点规则与坐标规则都按 **包名 + 页面（Activity 类名，支持只写后缀）** 过滤，扫描器按 `规则版本|包名|页面` 重建；快照详情页新建的规则默认只在该快照的 Activity 生效，规则页显示生效范围。
- **冷却与次数上限**：规则加 `cooldownMs`（默认 1000ms）与 `maxCount`（默认 0 = 不限），动作前过闸，换页面清零。此前只有 900ms 延迟，页面卡住会连点。
- **动作类型**：`action` = click / back / none。back 走 `GLOBAL_ACTION_BACK`（有些弹窗只有返回键能关），none 只记命中不动手。规则页每条规则有「改动作」循环切换。
- **相对坐标**：坐标规则可挂锚点控件（viewId 或文字），落点 = 锚点中心 X + dx、锚点底边 Y + dy，单位 dp，锚点取命中的最小面积节点；找不到锚点才退回屏幕比例。快照详情页新增「相对此控件（推荐无文字按钮）」入口，默认 0,0 = 点该控件本身。
- **节点属性缓存**：新增 `NodeFacts`/`NodeFactsCache`，每个节点的 text/desc/viewId/clickable/visible/bounds 在一次事件内只跨进程读一遍，开屏通道与弹窗通道共用；TTL 1.2s、上限 1500 条、换 App 或换页清空。
- 版本 0.10.0 / versionCode 34。

**为什么**
- 接社区规则库（李跳跳 214 个 App / 528 条）之前必须先有包名隔离，否则等于把 528 条规则全局广播；
- 参考 GKD 的 `activityIds`、`actionCd`、`actionMaximum`、`position` 表达式与 `A11yQueryContext` 节点缓存四套机制，取其思路不搬其代码。

**影响面/备注**
- **行为变化**：老规则没有 `activity` 字段，按「全应用」处理，但从此只在自己那条 `app` 里生效；之前若有跨 App 误点，属本次修正。
- 相对坐标的 dx/dy 是 dp 不是比例，换密度不同的机型仍准；锚点若在新版 App 里改版消失，规则退回屏幕比例（有比例时），否则静默不执行。
- 节点缓存按 `AccessibilityNodeInfo` 的 equals（windowId + sourceNodeId）命中，主线程使用，不加锁。

## 0.8.0 - 2026-10-23 · 抓取悬浮窗（GKD 交互）+ 快照可视化点选 + 快照管理

**改了什么**
- **抓取悬浮窗**：SYSTEM_ALERT_WINDOW 权限 + CaptureBubble（TYPE_APPLICATION_OVERLAY 小圆钮，可拖动）；开启后常驻屏幕，点一下 → 圆钮自隐 → 抓取当前页 → 自动弹回，**不用再下拉通知栏**（广告 3 秒倒计时不再被通知栏路径耽误）；
- 快照列表显示 **App 图标名**（AppLabels，无文本广告也能认出是哪个 App）+ **删除**（带确认）；
- 快照详情改 **可视化点选**：截图上叠加全部节点框（可点击绿框/其余灰框），点图上任意元素即选中（红框高亮，选包含点且面积最小的最具体节点），底部信息条展示详情 + 生成规则（viewId/文本/坐标三选一）；
- tree.json 补 w/h 字段；版本 0.8.0 / versionCode 19。

**为什么**
- 用户实测反馈四连：抓快照抓到通知栏（0.7.2 已修收起逻辑但通知栏路径本身太慢）；快照列表只有包名分不清 App；快照不能删；节点平铺看不懂对不上元素。

**影响面/备注**
- 新增 SYSTEM_ALERT_WINDOW 权限（悬浮窗必需，规则页开关引导授权，或 adb appops）；开关偏好持久化，无障碍服务连接时自动恢复悬浮窗；
- 通知栏「抓取快照」保留（通知栏收起逻辑 0.7.2 已修，两条路径并存）。

## 0.7.2 - 2026-10-23 · 热修：抓快照抓到通知栏的问题

- 用户实测：从通知栏点「抓取快照」时，前台是通知栏自身——截图截到通知栏、控件树抓的也是通知栏节点，毫无用处；
- 修复：触发时若检测到前台为 com.android.systemui（通知栏），先 performGlobalAction(GLOBAL_ACTION_BACK) 收起通知栏，延迟 600ms 再截屏抓树；非通知栏触发则立即抓；
- 兜底：活动窗口仍是系统层时，从窗口列表里找第一个非系统/非自家的应用窗口抓取；成功/失败均有 Toast 与快照日志反馈；
- 版本 0.7.2 / versionCode 18。


## 0.7.1 - 2026-10-23 · 设置页重构：分组列表化，专页专责

**改了什么**
- 设置 Tab 从「所有内容平铺一页」重构为**分组入口列表**：广告跳过（跳过规则与快照）/ 保活（保活与后台）/ 通用（数据与维护），每行标题+副标题+箭头，点击进对应二级页；
- 新增 KeepAliveSettingsActivity（保活与后台：模式/adb 授权/电池优化/自启动/通知/保活日志/边界说明）与 MaintenanceActivity（数据与维护：内存占用/清缓存/清统计）——设置页不再堆内容；
- 首页不变：服务状态+立即自检+广告排行榜；版本 0.7.1 / versionCode 17。

**为什么**
- 用户令：「设置界面乱糟糟，什么东西都铺在这里，你看哪个软件这样设置？」

**影响面/踩坑**
- 纯 UI 重排，无逻辑变化；MainActivity 移除设置页相关字段与处理器（modeText/battery/notif/kaLog/mem/copy/settings_check），保活逻辑迁 KeepAliveSettingsActivity，维护逻辑迁 MaintenanceActivity。

## 0.7.0 - 2026-10-23 · GKD-lite：规则系统 + 快照点选生成规则（用户点选元素自动生成跳过规则）

**改了什么**
- **规则系统（M2）**：`RuleStore` JSON 落盘，规则 = App + 匹配条件（viewId / 文本包含 / 屏幕比例坐标）+ 启用开关；引擎**用户规则优先**于内置关键字（node 类规则复用 NodeScanner，版本号变化自动重建）；
- **快照抓取（M3 前置）**：服务常驻通知带「抓取快照」按钮（动态 receiver，NOT_EXPORTED），点击后 `takeScreenshot`（API 30+，config 已加 canTakeScreenshot）+ 400 节点上限的控件树 JSON 落盘 `snapshots/<时间戳>_<包名>/`（保留 30 份）；
- **快照点选生成规则（M3-lite）**：`RulesActivity`（规则列表：开关/删除；快照列表）+ `SnapshotDetailActivity`（广告截图 + 节点列表，可点击节点排前），点选节点 → 三选一生成规则：按控件 ID / 按文本 / 按坐标（比例坐标，跨分辨率有效）——规则立即生效；
- 设置页新增「快照与规则 ›」入口；版本 0.7.0 / versionCode 16。

**为什么**
- 用户令：「李跳跳 GKD 都是有规则来实现广告跳过的你咋没有？为什么没有那种选择页面元素模拟点击自动生成规则的功能？」——这就是 GKD 的核心闭环：抓快照 → 点选元素 → 生成规则 → 引擎自动跳。

**影响面**
- 新增 2 Activity + 1 receiver（动态注册，无清单权限变化）；规则/快照存 App 私有目录；无联网。

**踩坑/备注**
- window.root.className 是根视图类名（FrameLayout），Activity 类名要从 STATE_CHANGED 事件取（0.5.4 已修的点，本次快照 meta 复用）；
- 酷安广告页实测是 **virtualNode 虚拟节点**（连文本都没有，ids 是哈希）——文本/viewId 引擎对它失明，坐标点按规则是唯一解，印证 per-App 坐标规则的必要性；
- 动态 NOT_EXPORTED 接收器收不到 adb shell 的广播（仅同应用），远程测试需走通知按钮路径。

## 0.6.0 - 2026-10-23 · 引擎升级：viewId 结构匹配（GKD 路数）

**改了什么**
- SkipRule 新增 `viewIds` 字段；内置 `ad-skip-viewid` 规则：**viewIdResourceName 含 "skip"（忽略大小写）即命中**，无需任何文本；
- 国产广告 SDK 的跳过按钮普遍**有 id 无文本**（穿山甲 `tt_splash_skip_btn`、快手 `ksad_*_skip`、各家 `xxx:id/skip_btn`）——这正是酷安/邮箱大师此前零匹配的根因，文本引擎对它们天生失明，GKD 订阅规则的主力手法就是 viewId/结构匹配；
- viewId 命中**免文本长度检查与右上角位置闸**（id 里带 skip 的就是按钮本体）；仍要求可点击祖先 + 可见 + 面积 ≤12%；
- 诊断采样加入 `vid=` 记录：无文本广告的证据入口（下次漏网广告的树里能看到真实 viewId，按它补规则）；
- 版本 0.6.0 / versionCode 15。

**为什么**
- 用户令"好好学学李跳跳 GKD"。

**影响面/备注**
- viewId 匹配全设备生效、无位置假设；覆盖率待真实广告样本验证（酷安/邮箱大师广告填充有时段性，自动化冷启动两轮未出广告，靠日常使用积累样本）；
- SkipRule.patterns 改为有默认值（纯 viewId 规则可省略 patterns）。

## 0.5.4 - 2026-10-23 · 坐标点按跑通：一嗨租车真机实跳（截图证据）

**改了什么**
- per-App 右上角坐标点按（李跳跳同款兜底）全链路跑通：酷安 / 网易邮箱大师 / 一嗨租车（com.ehai）冷启动切入后 0.9s/1.9s/2.9s 朝 (93%W, 6.5%H) 真实手势点三下，专治跳过按钮无可读文本的广告；
- **广告在场闸**：每次点按前核验前台 Activity 类名，含 splash/ad/launch（如一嗨的 LoadingActivity）才真点——无广告的冷启动零误触（实测三次点按全被正确拦截）；
- 版本 0.5.4 / versionCode 14。

**为什么**
- 用户三连实测：酷安/邮箱大师零反应（跳过按钮无可读文本，关键字引擎天然失明）；一嗨租车假记账。关键字路线修到头后，坐标点按是确定性方案。

**影响面**
- 三个 App 的**每次冷启动**都会尝试点按（有广告就跳，没广告被闸门拦下）；坐标点按不记账（无法核验），统计只收文本引擎的验证跳过；其他 App 仍走关键字引擎。

**踩坑/备注（本版三连坑，全是真机调试实锤）**
- ① 调度必须用 `event.packageName` 且放在 root/黑名单判定**之前**：App 切换瞬间 rootInActiveWindow 还是旧窗口（桌面），包名判成桌面直接 return，点按永不调度；
- ② 前台切换事件常在 **4ms 内连发**（桌面→App），任何时间节流（哪怕 300ms）都会恰好吞掉切换事件——该路径只写 map 零开销，节流已删；
- ③ 广告在场闸不能用 `window.root.className`——那是根**视图**类名（恒 FrameLayout 之类），永远不含 splash/ad，闸门会拦掉一切；正确来源是 STATE_CHANGED 事件的 **Activity 类名**；
- 实测证据：一嗨租车 LoadingActivity 开屏（"跳过 3"按钮）→ corner#1 (93%W,6.5%H) ok=true → +1.5s 截图广告已消失、+5s 已进登录页；
- 已知边界：点按坐标固定 (93%, 6.5%)，个别广告按钮位置偏移的 App 后续 per-App 规则化（M2）。

## 0.4.4 - 2026-10-23 · 结构闸门：启动窗口闸 + 右上角锚定闸

- 实锤统计污染源：Via 浏览器开着含「跳过」字样的 ZCode 页面被匹配记账 2 次（假）；com.ehai 4 条为 0.4.1 假账；
- **启动窗口闸**：每 App 仅"新窗口出现后 8s 内"扫描（开屏广告只在此时段存在），浏览器长驻页面不再空转误触；
- **右上角锚定闸**：命中节点中心须在上半屏偏右（cx>40%W, cy<60%H），页面中部「跳过 xxx」文本直接拒绝。

## 0.4.3 - 2026-10-23 · 核验改全窗口扫描 + 控件树落盘诊断

- 一嗨租车假记账根因之一：开屏广告可能是 overlay 窗口，rootInActiveWindow 扫不到 → 误判已消失；verifySkip 改遍历 windows 找目标，全窗口无命中才记账；
- match/giveup 时完整控件树（文本/viewId/可点击/坐标）+ 窗口清单落盘 filesDir/diag/（run-as 可拉）；
- no-match 日志降 VERBOSE 防刷屏（实测 Via 浏览器含「跳过」字样会 13 次/秒全量扫描）。

## 0.4.2 - 2026-10-23 · 玻璃 Tab 栏重做：快照自模糊（不依赖 ROM 开关）

**改了什么**
- `GlassTabBar`（子窗口 blurBehind）**废弃**——真机上系统跨窗口模糊静默不渲染，用户看到的只是 65% 白透明贴片，设置页文字从后面透上来糊成一片（用户实测差评："明明就是透明效果"）；
- 新方案 `GlassBarView`（**快照自模糊，BlurView 库同款原理**）：挂在内容层 OnPreDraw 上，每帧把内容层 1/8 缩微截屏 → CPU 盒式模糊×3 → 放大垫底，叠 70% 白 + 圆角裁剪 + 描边；不依赖任何 ROM 模糊开关，效果全设备一致；继承 LinearLayout，三个 Tab 文字 weight 平分浮在玻璃上；
- 布局：三个页面 ScrollView 包进独立 `blur_content` 容器作模糊目标（**目标绝不能包含玻璃栏自己，否则 draw 自递归 StackOverflow**——首版实测）；
- 版本 0.4.2 / versionCode 7。

**为什么**
- 用户令：Tab 栏必须是真磨砂玻璃（酷安观感），透明贴片不算数；0.4.0 的子窗口方案在荣耀上没效果。

**影响面**
- 滚动期间每帧多一次内容层小区域绘制 + 百余像素的模糊，实测设置页长列表滚动流畅；玻璃栏必须为 `blur_content` 的兄弟节点。

**踩坑/备注**
- 首版把模糊目标设成含玻璃栏自己的 content_frame → `GlassBarView.onDraw` 里 `target.draw()` 无限自递归，StackOverflowError 启动即崩（logcat 栈里全是 onDraw 互调）；
- 玻璃栏从 LinearLayout 换 FrameLayout 时，子 Tab 文字的 `layout_width=0dp + weight` 失效 → 零宽全隐身（截图文字消失），改回继承 LinearLayout 解决；
- 手动安装的 platform-37 已随 0.4.0 就位，compileSdk 37 保留，为将来真上 `SurfaceView.setBlurRegions` 留着（那需要独立玻璃面板 overlay 场景）。

## 0.4.1 - 2026-10-23 · 点击后验证制 + 手势补刀 + 全链路诊断日志（修"记录了但没跳过"）

**改了什么**
- **真机实锤的失败模式**（用户报：一嗨租车广告没跳过、记录却上了）：`performAction(ACTION_CLICK)` 返回 true ≠ 广告被跳过——国产广告 SDK 普遍不理会无障碍点击、只认真实触摸，而旧版把"返回 true"当成功记账；
- **点击后验证制**：点击后 800ms 重扫当前窗口——目标已消失/换了 App → 才记一次跳过；目标还在 → 用 `dispatchGesture` 在目标中心**手势补刀**（模拟真实触摸，最多补 2 次），3 次都没点掉就不记账（诚实统计，失败只进诊断日志）；
- **全链路诊断日志**（TAG=`SkipAdEngine`）：每个非黑名单窗口的扫描结果全量输出——节点数、采样文本（前 20 条）、命中文案、拒绝原因（文本超长/无可点击祖先/祖先面积超限）——酷安、网易邮箱大师"零记录"类的失败从此有据可查；
- 版本 0.4.1 / versionCode 6。

**为什么**
- 用户实测三连失败：酷安/邮箱大师零反应零记录（疑似跳过按钮无可读文本，待诊断日志确认）；一嗨租车假成功（本次修的）。

**影响面**
- 统计口径变了：只记"验证消失"的真跳过，失败不记；补刀手势只点命中目标的中心，配合会话冷却不产生风暴。

**踩坑/备注**
- 验证的"消失判定"不能拿事件的 className 和重扫根节点的 className 比（前者是 Activity 名后者是布局类名，永不相等 → 永远误判已消失），只比包名 + 是否仍命中；
- NodeScanner 把拒绝原因分支从合并 if 拆成 when，文案直接进 lastDebug；
- 真机回归待用户解锁手机后进行（PIN 锁 adb 无法越过），测试入口：冷启动酷安 / 网易邮箱大师(com.netease.mail) / 一嗨租车。

## 0.4.0 - 2026-10-23 · 真·玻璃 Tab 栏（系统跨窗口模糊）+ 底部全沉浸 + compileSdk 37

**改了什么**
- **玻璃悬浮 Tab 栏**：`GlassTabBar` 用依附子窗口（TYPE_APPLICATION_ATTACHED_DIALOG）+ `FLAG_BLUR_BEHIND` + `blurBehindRadius`，走系统对话框同一条 Kawase 模糊管线，真实模糊栏后滚动的页面内容；**三个 Tab 文字放进玻璃窗口内**（玻璃必然盖住底下视图，文字必须在玻璃上面），窗口 NOT_FOCUSABLE＝可点不可焦点，点击回调切页；无模糊环境自动加浓白底退化；
- **底部全沉浸**：删掉底部 inset 垫片，页面内容直接滚到手势条和 Tab 栏底下（尾部留 96dp+inset 清空区），玻璃栏浮在手势条上方 14dp；
- **compileSdk 34→37**（targetSdk 仍 34）：AGP 8.2.2 + `android.suppressUnsupportedCompileSdk=37` 压警告，不冒险升 AGP/Gradle；platform-37 手动解压进 SDK 并手写 package.xml（api-level 整数 37）；
- 版本 0.4.0 / versionCode 5。

**为什么**
- 用户令：Tab 栏要「安卓17的玻璃，不是毛玻璃」≈ 酷安效果；底部安全区也要真沉浸（内容延伸到底）。

**影响面**
- Tab 文字从布局迁进子窗口：`selectTab` 双写（布局 Tab 兜底 + 玻璃 Tab），`currentTab` 统一状态；玻璃创建失败/低版本自动退回自绘底色的普通 Tab 栏，功能无损；无权限变化。

**踩坑/备注**
- **Android 17 玻璃 API 考据**（SDK 37 的 api-versions.xml 对比 36.1 差集）：37 新增 `android.view.BlurRegion` / `RoundedRectBlurRegion` / `SurfaceView.setBlurRegions()`——玻璃片语义，但 SurfaceView 在单窗口打孔模式下"身后"没有可模糊层，实测渲染成黑块；其正确场景是独立 overlay 的玻璃片。当前采用 **31+ 跨窗口模糊管线**（`mBlurEnabled=true` 实测可用，dumpsys 可见 `AtchDlg` 窗口 `blurBehindRadius=98`），后续做独立玻璃面板时再上 BlurRegion；
- **子窗口创建时机**：onCreate 阶段 `decorView.windowToken` 还是 null，addView 必 BadToken——必须推迟到首帧 attach 后（decorView.post 重试），就绪后回调里再隐藏布局 Tab 栏，失败保持兜底底色；
- Android 12+ 有些 ROM 把跨窗口模糊藏在开发者选项，关掉时 `isCrossWindowBlurEnabled=false`，本 App 自动加浓白底不破相；
- platform-37 的 source.properties 里 ApiLevel 写作 "37.0"（minor 版本化），手动装平台时 package.xml 的 api-level 要写整数 37。

## 0.3.1 - 2026-10-23 · 悬浮 Tab 信息架构（首页 / 设置 / 关于）

**改了什么**
- **单 Activity 三 Tab + 悬浮底部胶囊栏**（自定义实现，零新依赖）：首页＝服务状态 + 立即自检 + 广告排行榜；设置＝服务保活（模式/adb 授权/自检）+ 系统权限（电池优化/通知/自启动直达）+ 保活日志 + 应用维护（内存/清缓存/清统计）+ 边界说明；关于＝版本、定位、**隐私红线与权限清单**、开发路线（M1→M4 勾选清单）、开源信息（GitHub/MIT）；
- **砍掉 KeepAliveActivity 二级页**（内容摊平进设置 Tab，层级从"首页→二级页"降为"Tab 直达"）；最近记录保留二级页（长列表适合独立页，从首页排行榜卡进入）；
- Tab 切换原地显隐 + 胶囊高亮（bg_tab_on 浅绿底），悬浮栏 elevation 浮于内容上、随 inset 避开手势条；版本 0.3.1 / versionCode 4。

**为什么**
- 用户令：「菜单层级好混乱，加个悬浮 tab 栏，首页/设置/关于，好好梳理」——原结构首页塞了入口行 + 两个二级页（最近记录/保活）确实绕。

**影响面**
- KeepAliveActivity 及其布局删除（git rm）；Main 视图切换为显隐不重建，刷新逻辑统一在 onResume；无数据/权限变化。

**踩坑/备注**
- 对「桌面为何触发跳过」的结论沉淀：桌面没有广告，是通用关键字规则把**本 App 桌面图标文字里的 "skip"** 当跳过按钮点了（0.3.0 的 HOME 动态拉黑已根治，桌面整体不再扫描）；
- adb 远程验证教训：`input tap` 前必须先 `dumpsys window` 确认前台是我们自己的 App——本轮盲点坐标点进了用户正在用的酷安切了人家的 Tab；坐标换算按 截图像素 × (实际分辨率/截图分辨率) 算。

## 0.3.0 - 2026-10-23 · UI 二级页重构 + 统计数据库化 + 电池/自启动 + 沉浸式

**改了什么**
- **信息架构**：最近记录、保活设置收进二级页（`RecentActivity` / `KeepAliveActivity`），「立即自检」按需求留在首页；保活页补齐：模式与边界说明、adb 授权、**申请忽略电池优化**（新权限 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS，弹系统确认框，状态实时显示）、**去自启动管理**（荣耀管家 `hihonor.intent.action.HSM_STARTUPAPP_MANAGER` → 已知组件 → 应用信息页逐级兜底，荣耀组件实测探测自真机）、去通知设置；保活页明示边界：**只保活 SkipAd 自己，无法替其他 App 保活**；
- **广告排行榜（分应用统计）**：新增 `StatsDb`（SQLiteOpenHelper，零外部依赖）单表事件流，聚合出累计次数/涉及应用数/分应用计数；首页 Top10 行 = 名次 + **真实应用图标 + 应用名 + 比例计数条**，超出部分汇总"还有 N 个应用"；`prune` 只留最近 1000 条事件；
- **最近记录页**：每行图标 + 应用名 + 时间 + 命中文案（图标名称经 `AppLabels` 进程内缓存，卸载应用回退包名+系统默认图标）；清空按钮与首页一致走警示弹窗（清空不可恢复）；
- **应用维护卡**：实时内存占用（自身进程 PSS，一位小数）+ 数据库体积；「清除内存缓存」（仅图标缓存，无数据影响）与「清空统计数据」（红色按钮 + AlertDialog 警示：累计/排行/最近记录全部归零不可恢复）；
- **沉浸式**：主题状态栏/导航栏透明 + `Ui.edgeToEdge`（systemBars+displayCutout inset 落到专用垫片 View），三页全部生效，浅色背景配深色状态栏图标；
- 修复：**桌面图标被当跳过按钮**——HOME 类应用动态拉黑（`CATEGORY_HOME` 解析，不枚举包名，全桌面通吃）；版本 0.3.0 / versionCode 3。

**为什么**
- 用户令：二级页收纳 + 统计样式重做（分应用/图标/排行榜）+ 本地数据库 + 内存占用与清缓存（带影响提示）+ 电池忽略优化 + 自启动入口 + 沉浸式，一次性全做。

**影响面**
- 统计从 SharedPreferences 迁 SQLite，旧统计（3 次误报）未迁移直接清零起算；保活日志环仍在 SP；事件表 1000 条上限，库体积有界；新增权限 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 属运营性，红线（无 INTERNET/存储/位置）不变。

**踩坑/备注**
- **真机抓到自点 bug**：荣耀桌面（com.hihonor.android.launcher）不在 M1 静态黑名单里，主屏上的「SkipAd」图标文字含 skip，被引擎连点 6 次（App 不断自己打开）；修复后桌面待 12 秒零记录；
- Kotlin 字符串模板会把 SQL 里的 `$_id` 当变量解析（Unresolved reference），建表语句改用列名常量；
- 内存 PSS 用 `ActivityManager.getProcessMemoryInfo`，系统对高频调用有节流，本 App 只在 onResume 刷新一次，无碍；
- 荣耀启动管理页无公开直达开关，action + 组件链兜底到应用信息页已在真机登记组件名。

## 0.2.0 - 2026-10-23 · 无障碍保活 + 首次真机部署

**改了什么**
- 新增 `keepalive/` 三件套：`KeepAlive`（保活中枢：配置态/运行态双检查、静默复活写入、周期 Job 调度、掉线本地通知、复活冷却阀）、`KeepAliveJobService`（JobScheduler 原生 15 分钟周期自检，persisted 跨重启，刻意不引 WorkManager 保持零重型依赖）、`BootReceiver`（BOOT_COMPLETED + MY_PACKAGE_REPLACED 兜底，开机/升级后立即尝试恢复）；
- 服务本体加 `onUnbind` 钩子：被解绑后的黄金几秒进程还活着，延迟 1.5s 自检——静默模式当场拉起，提醒模式立刻发通知；周期 Job 继续兜底"进程被整体杀掉"的场景；
- 分级保活：①**静默复活** = 电脑 adb 授权 WRITE_SECURE_SETTINGS 后，检测到自身服务掉线就把服务名写回 enabled_accessibility_services 并置 accessibility_enabled=1，系统自动重绑；写回分两路——配置缺失走单次追加（干净拉起），配置在但没绑定（开机被 ROM 拦）才走两段式制造变更；②**提醒模式** = 默认零授权，掉线发本地通知引导去设置页；
- 防震荡三阀：复活冷却 30s（`isRunningNow` 在系统重绑过渡期会滞后返回 false，没有冷却会震荡成每秒几十次的活循环，真机首测实锤后修）、`scheduleJob` 幂等（`getPendingJob` 已注册不重排，掐断"重连→重排 Job→Job 立即首跑"的反馈回路）、两段式写入仅在陈旧绑定场景启用；
- UI 新增「无障碍保活」卡：授权状态、一键复制 adb 授权命令、立即自检按钮；Android 13+ 首次打开请求通知权限；MainActivity 服务状态判断统一走 KeepAlive；StatsStore 抽出通用 logEvent（保活事件与跳过记录同环展示），服务连接/复活均留痕；
- Manifest：新增 RECEIVE_BOOT_COMPLETED / POST_NOTIFICATIONS / WRITE_SECURE_SETTINGS（tools:ignore ProtectedPermissions）；**红线不变——无 INTERNET / 存储 / 位置**；versionCode 2 / versionName 0.2.0；
- 构建配置：Gradle wrapper **8.2→8.7**（本机 JDK 21 最高只配 Gradle 8.2 不兼容，AGP 8.2.2/Kotlin 1.9.22 不动）；wrapper 发行包源改腾讯云镜像；settings.gradle 加阿里云镜像源（google/central 镜像在前、官方源兜底）——国内直连 mavenCentral 慢到卡死首建（102MB 缓存跑了 14 分钟没完，换镜像后全量解析+编译 76 秒）。

**为什么**
- 用户令：装机前先集成无障碍保活（服务被 ROM 杀 / 升级被关是类李跳跳 App 的头号痛点），原排 M3/M4 提前落地。

**影响面**
- 系统设置只写 enabled_accessibility_services / accessibility_enabled 两项，且仅在检测到自身服务掉线时写；授权静默后用户手动关闭也会被周期任务拉起（UI 有说明，撤销授权即回到提醒模式）；被一键强停后 App 处于 stopped 态、一切调度失效，需打开一次 App 恢复。

**踩坑/备注**
- **真机首测抓到两个保活 bug 并当场修掉**（荣耀 BKQ-AN10 / Android 17 实测）：① 热循环——最初"固定两段式写入 + 每次连接都重排 Job + isRunningNow 过渡期滞后"三者叠加，杀服务后日志出现同秒几十对"掉线→复活→连接"，极端时设置值被写空；修复=冷却阀 + 幂等调度 + 两段式只用于陈旧绑定。② 修掉热循环后暴露无快速触发——周期 Job 最长 15 分钟才跑，杀掉后服务干等；修复=服务加 `onUnbind` 黄金几秒自检。**最终复测通过：09:34:57 杀 → 09:35:00 自动复活，仅一次，30 秒稳定**；
- 首次构建在 Windows 工作站打通：本机 SDK 缺 android-34 / build-tools 34.0.0，已用 cmdline-tools 11 补齐；开发机 JAVA_HOME 是 JDK 21（DevEco JBR），故升 Gradle wrapper；本机 local.properties 指 D:\Program Files\Android-SDK\Sdk（gitignore 内，仅本机）；M1 代码一次编译通过率很高，仅 JobInfo/JobScheduler 导错包（在 android.app.job 非 android.app）、FEEDBACK_ALL 应为 FEEDBACK_ALL_MASK 两处小错；
- **已知行为（M2 待解）**：全局关键字会命中系统页面里含"跳过"的可点击节点（实测误点系统设置 3 次），4s 窗口冷却兜住了频次；per-App 规则（M2）才是根治；
- WRITE_SECURE_SETTINGS 是 signature 级：清单声明 + `adb shell pm grant` 手动给才生效，属"用户显式授权"而非悄悄索权；Android 13+ 侧载应用的「受限设置」会拦设置页里的手动开关，但 adb settings put / 本 App 静默写入不受影响；
- BOOT_COMPLETED receiver 用 exported=true：protected broadcast 仅系统发得出，无安全面、跨 ROM 兼容最稳；
- 荣耀 ROM 还需用户在 启动管理 里放行自启动（UI 有提示）；adb 调试坑：Git Bash 非登录 shell 会丢 MSYS_NO_PATHCONV，`adb shell` 里的 `/sdcard` 路径被转换成本地盘路径，必须命令前补 `MSYS_NO_PATHCONV=1`。

## 0.1.0 - 2026-10-22 · 节点1：M1 骨架 + 通用跳过引擎

**改了什么**
- 全新仓库。Kotlin + minSdk 26 / targetSdk 34 / AGP 8.2.2 / Gradle 8.2 / Kotlin 1.9.22，单模块，依赖仅 core-ktx + appcompat；
- 核心链路落地：`SkipAccessibilityService`（事件节流 150ms + 包名黑名单 + 会话冷却 4s）→ `NodeScanner`（BFS 遍历，双上限 800 节点/400ms；多语言关键字 + 倒计时正则；text/contentDescription 双查；沿父链找 clickable 祖先）→ `ClickPerformer`（ACTION_CLICK → 祖先逐级 → dispatchGesture 坐标兜底）；
- 防误触三保险：节点文本 ≤24 字、可点击祖先面积 ≤12% 屏、同窗口冷却；
- UI：服务状态卡（含跳转系统设置引导）、跳过统计、最近日志（50 条 ring buffer，SharedPreferences）；
- 文档：`docs/design.md`（五层匹配/快照截屏方案/规则格式/红线清单）、README、本 CHANGELOG；
- **Manifest 零权限**：不申请 INTERNET/存储/任何权限（隐私红线，永久保持）。

**为什么**
- 用户的类李跳跳自用需求；M1 先用"通用关键字 + 防误触约束"覆盖 ~80% 常规开屏，后续节点再加 per-App 规则与调试工具。

**影响面**
- 全新仓库，无存量影响。未经真机验证，预期下一节点在 Android Studio 首次构建并真机跑通。

**踩坑/备注**
- 本节点在 VM103（无 Android SDK/JDK）上完成，**代码未编译验证**：gradle wrapper 已用 Gradle 8.2 官方发行版生成，明天任何电脑 clone 后 Android Studio 直接可开；
- 有意不调用 `AccessibilityNodeInfo.recycle()`（API 33+ 已废弃，低版本不回收仅轻微内存压力，见 NodeScanner 注释）；
- `isAccessibilityTool` 属性写在服务配置 XML（API 31+ 强制声明要求，低版本系统忽略该属性不报错）；
- 图标用纯 XML adaptive icon（mipmap-anydpi-v26），minSdk 26 恰好全覆盖，无需 PNG 兜底；
- colors.xml 初版手滑混入一行 string 占位符，已修（历史见 git 历史）。
