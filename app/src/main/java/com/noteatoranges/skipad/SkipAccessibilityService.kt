package com.noteatoranges.skipad

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.noteatoranges.skipad.core.BuiltinRules
import com.noteatoranges.skipad.core.ClickPerformer
import com.noteatoranges.skipad.core.CornerSkipFinder
import com.noteatoranges.skipad.core.EngineTrace
import com.noteatoranges.skipad.core.NodeFactsCache
import com.noteatoranges.skipad.core.NodeScanner
import com.noteatoranges.skipad.core.PlainActTracker
import com.noteatoranges.skipad.core.SkipRule
import com.noteatoranges.skipad.core.TransitionDemoter
import com.noteatoranges.skipad.db.KeywordStore
import com.noteatoranges.skipad.db.RuleStore
import com.noteatoranges.skipad.db.SnapshotStore
import com.noteatoranges.skipad.db.StatsDb
import com.noteatoranges.skipad.keepalive.KeepAlive
import com.noteatoranges.skipad.store.StatsStore
import com.noteatoranges.skipad.util.EnginePrefs
import com.noteatoranges.skipad.util.Io
import java.io.File
import java.util.concurrent.Executor

/**
 * 核心无障碍服务：窗口事件 → **只按用户规则**匹配 → 模拟点击。
 *
 * 0.17.0 起引擎只认从快照点选生成的规则（节点 / 结构路径 / 坐标）。内置关键字扫描
 * 与应用内弹窗关闭这两条启发式通道整体停用：它们会在聊天页、输入法窗口这类正常
 * 界面命中短文本和小控件，误点的代价远大于多跳过几个广告。
 *
 * - 点击后验证制：点击 800ms 后在**同一个 App 的**窗口里重扫，目标还在就用真实手势
 *   补刀（最多 2 次），目标消失才记一次跳过；
 * - 规则按 App 隔离：规则的 app 字段决定它只在哪一个应用里生效，跨应用一条都不用；
 * - 快照：悬浮钮 / 通知栏「抓取快照」按钮，截图+控件树落盘，点选生成规则；
 * - 诊断：TAG=DaxiaojieEngine；控件树落盘 filesDir/diag/。
 */
class SkipAccessibilityService : AccessibilityService() {

    private var userScanner: NodeScanner? = null

    /** 开屏窗口用的词表扫描器：内置默认词（可开关）+ 该 App 的命中词，带排除词 */
    private var kwScanner: NodeScanner? = null
    private var kwKey: String? = null
    private var kwPkg: String? = null
    private var clicker: ClickPerformer? = null
    private var lastHandleAt = 0L

    /** 节点属性缓存：同一次事件里多次扫描复用同一份跨进程读取结果 */
    private val factsCache = NodeFactsCache()

    /** 扫描器重建的键（规则版本 + 包名 + 页面）：换 App / 换页面都要重建，否则规则会跨页生效 */
    private var userScannerKey: String? = null

    /**
     * userScanner 是按哪个包构建的。
     *
     * 规则是 per-App 的，而 [findUserTargetInAnyWindow] 会遍历多个窗口。
     * 不看包名就拿同一份扫描器过一遍，等于把 A 应用的规则套到 B 应用的控件上——
     * 命中就点，是实打实的跨应用误点。
     */
    private var userScannerPkg: String? = null

    /** 每条规则的运行态：上次动作时间与累计次数（换页面清零） */
    private class RuleRun(var lastAt: Long = 0L, var count: Int = 0)
    private val ruleState = HashMap<String, RuleRun>()

    /** 每个 App 上次执行规则时的页面，用于判断“换页” */
    private val stateActivity = HashMap<String, String>()

    /** 每个 App 上次记「未命中原因」的时间，同包 [MISS_TRACE_GAP_MS] 内不重复记 */
    private val lastMissAt = HashMap<String, Long>()

    /** 快照广播只注册一次：onServiceConnected 可能被系统重复回调，重复注册会导致一次点击抓多张 */
    private var snapshotRegistered = false

    /** 上次执行动作的窗口键（日志用）与包名（冷却判据）：同一 App 冷却期内不重复匹配 */
    private var lastSkipWindow: String? = null
    private var lastSkipPkg: String? = null
    private var lastSkipAt = 0L
    private var lastFgPkg: String? = null

    /**
     * 上一次出现在屏幕上的包名，**包含桌面与系统界面**。
     *
     * 判断「是不是刚切到这个 App」只能用这个值：桌面在黑名单里，若用 lastFgPkg 比，
     * 从桌面点图标进来时它会仍然是旧 App，开屏窗口永远算不出来。
     */
    private var lastShownPkg: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pending: PendingSkip? = null

    /** 内容变化事件的独立节流时间戳 */
    private var lastContentAt = 0L

    /**
     * 开屏热窗口：App 刚切到前台的头 [SPLASH_WINDOW_MS]。
     *
     * 这段时间里做两件平时不做的事：
     * - 把系统的 notificationTimeout 临时压到 50ms（平时 300ms 省电），让跳过按钮一出现
     *   就被投递过来，而不是等系统把事件合并完；
     * - 起一个主动扫描定时器，不等事件自己来。广告 SDK 的按钮出现时不一定发内容变化事件，
     *   纯事件驱动会整段漏掉——这是"按钮明明在却没有跳过"的主要来源。
     *
     * 窗口一结束就全部还原，常驻开销不变。
     */
    private var hotPkg: String? = null
    private var hotUntil = 0L
    private var hotOpenedAt = 0L
    private var hotScanCount = 0
    private var hotScanTask: Runnable? = null

    /** 系统默认的事件合并窗口（xml 里的 300），热窗口结束后恢复成它 */
    private var baseNotifTimeout = 300L

    /**
     * 服务连接的时刻。
     *
     * 刚连上的头 [SERVICE_WARMUP_MS] 里不开热窗口：此时还不知道屏幕上停的是哪一页，
     * 把它当成"刚切进来的开屏"就是纯赌博——实测服务重启后 5 秒，微博「我的」页
     * 被当成开屏页，右上角的编辑按钮被点了。
     */
    private var connectedAt = 0L

    /**
     * 疑似误点过的页面（包名/Activity），在一段时间内不再自动动手。
     *
     * 判据见 [verifySkipOnce]：点击后目标消失了、但前台换了 App，说明点进的是广告
     * 落地页或应用商店，不是「跳过」。这种页面再试一次大概率还是点错，所以直接静默
     * 一段时间——比调参数可靠，因为它是用真实结果记账的，不是靠猜。
     */
    private val badPageUntil = HashMap<String, Long>()

    /** bad-page 日志的节流时间戳：静默期内事件密集，同一条日志一秒一次足够 */
    private val lastBadPageTrace = HashMap<String, Long>()

    /** 「事件没有包名」诊断日志的节流时间戳 */
    private var lastNoPkgTrace = 0L

    private data class PendingSkip(
        val windowKey: String,
        val pkg: String,
        val detail: String,
        var attempts: Int,
        /** 补刀上限：目标没消失才补，弹窗/路径类只补一次 */
        val maxRetry: Int = MAX_RETRY,
        /**
         * 结构路径规则的路径。
         *
         * 路径规则不在扫描器的规则集里（[RuleStore.nodeRulesAsScannerRules] 把它们排除了），
         * 核验若走 findUserTargetInAnyWindow 会永远拿不到节点，于是每次都当成「已点掉」，
         * 补刀永远不会发生。带上路径就能按路径本身复核。
         */
        val path: String? = null,
        /** 触发这次动作的规则 id：核验只看同一条规则，别把新页面上另一条规则当成「还在」 */
        val ruleId: String = "",
        /**
         * 点击发生时的页面（Activity 类名）。
         *
         * 内置规则的复核是「再扫一遍看同一个词还在不在」，而点完之后页面往往已经换掉，
         * 新页面上出现同样的字（列表项、正文）就会被误判成「目标还在」并触发补刀。
         * 记下页面，换页即视为点掉了。
         */
        val act: String = "",
        /**
         * 点击时那个控件的矩形桶键（见 [rectKeyOf]）。
         *
         * 复核内置规则时要求「同一个位置」还在，而不是「页面上还有这个词」。
         * 微博这类单 Activity 应用开屏页和主界面同名，只看 Activity 判不出换页：
         * 主界面上恰好有个「跳过」的话，会被当成按钮没点掉然后补刀——那是往正文里点。
         */
        val rectKey: String = "",
        /**
         * 点击那一刻页面上有没有广告迹象（SDK 资源名前缀 / 「广告」字样 / 倒计时数字）。
         *
         * 默认 `true` 是故意的：拿不到证据时一律按「这是广告」处理，只在**明确没有任何
         * 广告迹象**时才允许回撤这条记录。判错的方向永远是「多记一次」，不是「少记一次」。
         */
        val adEvidence: Boolean = true,
    )

    /** 每个 App 最近一次"新窗口出现"的时间与前台 Activity 类名 */
    private val lastStateChangeAt = HashMap<String, Long>()

    /**
     * 桌面包名集合。
     *
     * 桌面也算「一次真正的切 App」：从桌面点图标进来时，只有前一个出现的是桌面，
     * 才能判成「刚切到这个 App」，开屏窗口才打得开。
     */
    private var homePkgs: Set<String> = emptySet()

    /**
     * 能被当成「用户切到了这个 App」的包名缓存。
     *
     * 输入法、系统权限弹窗、某些系统浮层都会发 WINDOW_STATE_CHANGED，且包名是它自己的。
     * 不筛掉的话：在聊天页弹出键盘 → lastShownPkg 变成输入法 → 键盘收起后 App 的下一
     * 个事件又被当成「刚切进来」→ 开屏窗口（词表扫描）在聊天页重开 4 秒，那正是误点的来源。
     * 判据用「有没有启动图标」：输入法和弹窗都没有，真 App 都有。
     */
    private val realAppCache = HashMap<String, Boolean>()

    private fun isRealApp(pkg: String): Boolean = realAppCache.getOrPut(pkg) {
        runCatching { packageManager.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)
    }

    /**
     * 系统级开屏广告容器：没有启动图标、不属于桌面，但确实承载真实开屏广告。
     *
     * 0.17.48 实测：猎豹清理大师冷启动后，前台根节点的包名不是它自己，而是
     * `com.miui.systemAdSolution`——小米 HyperOS 把开屏交给了系统广告方案渲染。
     * dump 出来的树里是快手极速版的推广位，带 `view_skip_button`（文本「跳过 | 3」）
     * 和 `view_skip_button_nearby_top_right`，都是可点击的。
     *
     * 这个包名两条原有判据都不满足（没有 LAUNCHER，也不在 homePkgs），于是 WSC 分支
     * 整段被跳过：`lastStateChangeAt` 从不写入 → `isSplashNow` 恒为 false →
     * `scanAndAct` 在「两个扫描器都空且不在开屏窗口」处静默 return。
     * 表现为 `hot-close ... scans=0`：不是跳错，是根本没看见。
     *
     * 只收有实机节点的包名，不按厂商猜。加了之后仍然走同一套判据——热窗口只有 4 秒、
     * 要求真的从别的包切过来、命中后照样核验，不会因为这个豁免放宽任何一道闸。
     */
    private val SYSTEM_AD_GATEWAYS = setOf(
        "com.miui.systemAdSolution",
    )

    /** 这个包名能不能代表「用户此刻在前台一个真正的应用里」 */
    private fun countsAsForeground(pkg: String): Boolean =
        pkg in homePkgs || pkg in SYSTEM_AD_GATEWAYS || isRealApp(pkg)

    /**
     * 本轮开屏已经点掉的时间点。
     *
     * 开屏都是立刻出现，点掉就该立即收手：不等满 [SPLASH_WINDOW_MS]，主界面零暴露。
     * 下一次真的切到这个 App 时 `lastStateChangeAt` 会刷新成更大的值，窗口自动重开，
     * 所以这里不需要清理。
     */
    private val splashClosedAt = HashMap<String, Long>()

    /**
     * 上一次点击成功的时间与当时所在的 Activity，用于「同包续窗」。
     *
     * 0.17.53 实测：360 清理大师是两段开屏——`SplashActivity` 之后紧跟
     * `SplashRecommendActivity`，同一个包里。第一段点掉后 `splashClosedAt` 立刻生效，
     * 第二段出现时窗口已经关了，而 `freshSwitch` 要求「上一个包里不是自己」，
     * 同包换页拿不到新窗口，于是整段不被处理。用户看到的就是「跳过之后马上又是广告」。
     *
     * 这两个值只用来回答一个问题：**这次换页是不是发生在刚点完 6 秒之内、且换了 Activity**。
     * 是就续窗一次，然后立刻作废，不会变成连续点击。
     */
    private val lastHitAt = HashMap<String, Long>()
    private val lastHitAct = HashMap<String, String>()

    /** 本轮已经续过窗的包。真切换到这个 App 时清掉，见 [onAppSwitched]。 */
    private val retriedAfterHit = HashMap<String, Boolean>()

    /**
     * 「上一条是不是过渡页记录」（design.md 第 18 条）。
     *
     * **只在 [Io] 单线程里读写**，主线程不碰——主线程只把 `adEvidence` 随 PendingSkip 传下来。
     * 判定逻辑本身在 [TransitionDemoter]，这里只负责按包名持久化。
     */
    private val demoter = TransitionDemoter(TRANSITION_DEMOTE_MS)

    /**
     * 续窗落到了哪个页面（design.md 第 21 条）。
     *
     * 主线程读写；判定与计数在 [PlainActTracker]。命中时 [PlainActTracker.forget] 作废。
     */
    private val retryAct = HashMap<String, String>()
    private val plainTracker = PlainActTracker(PLAIN_ACT_HITS)

    /** 本轮热窗口里点掉了几次（续窗轮结束时用来区分「扫了没命中」和「点完收手」） */
    private var hotHitCount = 0

    /** 每个 App 上一次记录的开屏窗口状态：只在翻转时打日志，避免每个事件刷屏 */
    private val splashState = HashMap<String, Boolean>()

    /**
     * 每个 App 上次由「内容变化兜底」打开开屏窗的时刻。
     *
     * 兜底路径靠「事件包名 != 上一次出现在屏幕上的包」判切 App，而内容变化事件比
     * 窗口切换事件来得早、来得密：只要 lastShownPkg 因为任何原因滞后一拍，App 内
     * 换页（点搜索框、弹面板）就会被当成「又切进来了」，开屏窗重开 4 秒——词表就在
     * 非开屏页面上跑了一遍。这里加一个分钟级闹钟，同样的漏只补一次。
     */
    private val splashFallbackAt = HashMap<String, Long>()
    private val lastActivityClass = HashMap<String, String>()

    /** 本页面已点过的控件矩形（分桶后的键），换页面清空。见 [clickNodeOnce] */
    private val clickedRects = HashSet<String>()

    private val mainExecutor = Executor { mainHandler.post(it) }

    private val snapshotReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_TAKE_SNAPSHOT) return
            // 广播接收器里抛出的异常没有上层接盘，直接带走整个进程 ——
            // 而进程一死，无障碍服务跟着掉线，用户看到的就是「跳着跳着不跳了」。
            // 抓快照要读截图、要跨进程遍历节点，都是可能抛的地方，必须兜住
            try {
                takeSnapshot()
            } catch (t: Throwable) {
                traceW("snapshot-fail", t)
                bubbleToast("抓取失败：${t.javaClass.simpleName}")
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 落盘日志要在任何 trace 之前就绪，否则服务起来头几条记录只进内存，
        // 而内存记录恰恰是最容易被 dump 踢掉的那一份
        EngineTrace.init(this)
        // 连接留痕：装了新包之后服务是否真的起来了，只能靠这一行判断
        val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        traceI("service-connected $ver")
        clicker = ClickPerformer(this)
        connectedAt = SystemClock.elapsedRealtime()
        // 记住 xml 里的合并窗口，热窗口结束后按这个值还原。读不到就按 300 算
        runCatching { serviceInfo?.notificationTimeout }.getOrNull()
            ?.takeIf { it > 0 }
            ?.let { baseNotifTimeout = it }
        homePkgs = findHomePackages()
        blacklist = PACKAGE_BLACKLIST + homePkgs
        KeepAlive.scheduleJob(this)
        StatsStore.logEvent(this, getString(R.string.ka_log_connected))
        KeepAlive.markConnected(this)
        postWorkNotification()
        inst = this
        // 服务重启后（换包、重新绑定）先补一次前台包名，不然要等到下一次切页事件才有值。
        // 目标 App 禁了无障碍读不到窗口时，降级抓取就靠这个值认 App。
        // lastShownPkg 也必须一起补：它决定下一个 WSC 算不算「刚切进来」。
        // 桌面不能拿来当"上一个 App"：服务重绑那一瞬 rootInActiveWindow 读到的常是
        // 桌面窗口，补成桌面之后，微博的下一个 WSC 就成了"从桌面切过来"，
        // 开屏窗口直接在用户正在看的「我的」页上打开（0.17.33 实测踩到，见 CHANGELOG）。
        runCatching { rootInActiveWindow?.packageName?.toString() }
            .getOrNull()
            ?.takeIf { it != packageName && it != SYSTEM_UI_PKG && it !in homePkgs }
            ?.let {
                lastFgPkg = it
                if (lastShownPkg == null) lastShownPkg = it
            }
        if (com.noteatoranges.skipad.util.CaptureBubble.isEnabled(this)) {
            com.noteatoranges.skipad.util.CaptureBubble.apply(this)
            com.noteatoranges.skipad.util.CaptureBubble.onClick = { bubbleClicked() }
            com.noteatoranges.skipad.util.CaptureBubble.onLongClick = { bubbleLongClicked() }
        }
        if (!snapshotRegistered) {
            registerReceiver(
                snapshotReceiver, IntentFilter(ACTION_TAKE_SNAPSHOT),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            snapshotRegistered = true
        }
    }

    /**
     * 桌面包名：**只取默认桌面**，不能取所有声明 CATEGORY_HOME 的应用。
     *
     * AOSP 的 `com.android.settings.FallbackHome` 也声明了 CATEGORY_HOME（系统无桌面时的
     * 兜底桌面），用 queryIntentActivities 会把整个「设置」拉进黑名单——设置里所有规则
     * 从此一条都不生效，而且完全静默。0.17.x 排查链路断点就是踩在这里。
     */
    private fun findHomePackages(): Set<String> = try {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val def = packageManager.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        val p = def?.activityInfo?.packageName
        if (p.isNullOrBlank()) emptySet() else setOf(p)
    } catch (e: Exception) {
        emptySet()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        traceI("service-unbind")
        val appContext = applicationContext
        Thread {
            SystemClock.sleep(UNBIND_RECOVER_DELAY_MS)
            KeepAlive.checkAndRecover(appContext)
        }.start()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 热窗口没走完就被销毁（换包、手动关开关）：把合并窗口还原，别把 50ms 留给下一次
        runCatching { exitHotWindow(SystemClock.elapsedRealtime()) }
        // 记账攒在内存里，服务被杀就丢了：退出前补一次落盘
        runCatching { RuleStore.flushHits(this) }
        com.noteatoranges.skipad.util.CaptureBubble.hide(this)
        inst = null
        if (snapshotRegistered) {
            runCatching { unregisterReceiver(snapshotReceiver) }
            snapshotRegistered = false
        }
    }

    /** 悬浮钮点击：隐藏圆钮 → 广播抓取（服务同应用接收）→ 弹回 */
    fun bubbleClicked() {
        // 点击这一刻前台还是目标 App，先把包名抓下来：
        // 有些应用（货拉拉企业版）禁了无障碍遍历——窗口树读不到、连事件也不发，
        // 但 rootInActiveWindow 的包名往往还在，这是降级截图唯一的包名来源
        runCatching { rootInActiveWindow?.packageName?.toString() }
            .getOrNull()
            ?.takeIf { it != packageName && it != SYSTEM_UI_PKG }
            ?.let { lastFgPkg = it }
        com.noteatoranges.skipad.util.CaptureBubble.hide(this)
        // 250ms 在部分 ROM 上不够：圆钮移除后焦点/窗口列表还在变，取到的树是空的
        mainHandler.postDelayed({
            try {
                sendBroadcast(Intent(ACTION_TAKE_SNAPSHOT).setPackage(packageName))
            } catch (t: Throwable) {
                traceW("snapshot-broadcast-fail", t)
            }
        }, BUBBLE_HIDE_DELAY_MS)
        mainHandler.postDelayed({
            // 悬浮窗可能被系统拒绝（权限被收回、窗口数超限），抛出来会带走进程
            try {
                if (com.noteatoranges.skipad.util.CaptureBubble.isEnabled(this)) {
                    com.noteatoranges.skipad.util.CaptureBubble.apply(this)
                    com.noteatoranges.skipad.util.CaptureBubble.onClick = { bubbleClicked() }
                    com.noteatoranges.skipad.util.CaptureBubble.onLongClick = { bubbleLongClicked() }
                }
            } catch (t: Throwable) {
                traceW("bubble-restore-fail", t)
            }
        }, 1500)
    }

    /**
     * 长按悬浮钮：立刻按坐标规则点一次。
     *
     * 这是"自动识别不到的应用内广告"的人工兜底——用户看着广告长按一下就能点掉，
     * 不依赖任何启发式判断，因此零误判。冷启动开屏仍由 [schedulePointTaps] 自动触发。
     */
    fun bubbleLongClicked() {
        // 优先取「不是自己」的窗口：悬浮钮本身也是一个窗口，rootInActiveWindow 有可能
        // 指向它自己的 overlay。顺便和主扫描链路共用 activeWindowRoot 的兜底，
        // 部分 ROM 上 rootInActiveWindow 恒为 null，只用它等于长按完全没反应
        val root = activeWindowRoot(lastFgPkg) ?: runCatching { rootInActiveWindow }.getOrNull()
        val pkg = root?.packageName?.toString() ?: lastFgPkg
        if (root == null || pkg == null) {
            bubbleToast("读不到当前页面")
            return
        }
        val act = lastActivityClass[pkg] ?: ""
        val rules = RuleStore.pointRules(this, pkg, act)
        if (rules.isEmpty()) {
            val label = com.noteatoranges.skipad.util.AppLabels.get(this, pkg).label
            bubbleToast("$label 没有坐标规则")
            return
        }
        var ok = 0
        rules.forEach { r ->
            val point = resolvePoint(root, r) ?: run {
                bubbleToast("规则 ${r.id.take(6)} 的锚点控件不在当前页面")
                return@forEach
            }
            val fired = when (r.action) {
                SkipRule.ACTION_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
                SkipRule.ACTION_NONE -> true
                else -> clicker?.clickPoint(point.first, point.second) == true
            }
            if (fired) ok++
            traceD("manual-point ${r.id} ${r.actionLabel} $pkg" +
                    " (${(point.first / resources.displayMetrics.widthPixels * 100).toInt()}%," +
                    "${(point.second / resources.displayMetrics.heightPixels * 100).toInt()}%) ok=$fired",
            )
        }
        bubbleToast("已执行 ${rules.size} 条坐标规则（成功 $ok）")
    }

    // ———————— 日志：logcat + 进程内痕迹 ————————
    //
    // 每个决策点都要留两处痕：logcat 给连着电脑的时候看，EngineTrace 给
    // 「手机上出问题、没电脑」的时候看。少了后者，日志缓冲被 ROM 关掉的机型
    // （adb logcat 读出 0 字节）上就完全无法回答「刚才为什么不跳」。

    private fun traceV(msg: String) {
        EngineTrace.add(msg)
        Log.v(TAG, msg)
    }

    private fun traceD(msg: String) {
        EngineTrace.add(msg)
        Log.d(TAG, msg)
    }

    // ———————— 规则记账 ————————

    /** 记账已排队，避免一次跳转连开多个延迟任务 */
    private var hitFlushScheduled = false

    /**
     * 记一次「这条规则真的点掉了东西」。
     *
     * 只记用户规则：内置规则是引擎自己的一部分，用户无法也不需要去维护它，
     * 给它记账只会把「已 90 天未命中」的噪音堆进列表。
     *
     * 证据记的是命中节点当时的形态（类名 + 尺寸 + 原文），用来事后区分
     * 「点对了关闭钮」和「点到了一段含关键字的正文」——后者在记账里长一个样，
     * 只有看形状才分得出来。
     */
    private fun recordRuleHit(rule: SkipRule, node: AccessibilityNodeInfo) {
        if (!rule.id.startsWith("user:")) return
        // 复用本轮的节点属性缓存：这里读 text/class/bounds 是一次跨进程往返，
        // 扫描时已经读过一遍，再读一遍纯属浪费
        val f = runCatching { factsCache.of(node) }.getOrNull() ?: return
        val label = f.label ?: ""
        val cls = f.cls.substringAfterLast('.')
        val evidence = "$cls ${f.rect.width()}x${f.rect.height()} 「${label.take(24)}」"
        RuleStore.noteHit(rule.id.removePrefix("user:"), evidence)
        if (hitFlushScheduled) return
        hitFlushScheduled = true
        // 延迟合并：一次跳转会连来好几个事件，每条都写盘等于每次开屏都序列化一遍规则表
        mainHandler.postDelayed({
            hitFlushScheduled = false
            runCatching { RuleStore.flushHits(this@SkipAccessibilityService) }
        }, HIT_FLUSH_DELAY_MS)
    }

    /**
     * 未命中原因落痕。
     *
     * 以前只在 verbose 下记 `scan ... reject=...`，而 verbose 默认关着——
     * 于是「规则为什么没生效」这个问题在真机上永远没有答案。
     * 现在只要有明确拒绝理由就记一条，同包 3 秒最多一条，防止信息流里刷屏。
     */
    private fun traceMiss(pkg: String, dbg: String?) {
        if (dbg == null) return
        if (!dbg.contains("reject=") && !dbg.contains("EXCLUDE")) return
        val now = SystemClock.elapsedRealtime()
        if (now - (lastMissAt[pkg] ?: 0L) < MISS_TRACE_GAP_MS) return
        lastMissAt[pkg] = now
        traceD("miss $pkg $dbg")
    }

    private fun traceI(msg: String) {
        EngineTrace.add(msg)
        Log.i(TAG, msg)
    }

    private fun traceW(msg: String, t: Throwable? = null) {
        EngineTrace.add(if (t == null) msg else "$msg :: ${t.javaClass.simpleName}: ${t.message}")
        Log.w(TAG, msg, t)
    }

    /** 1dp 对应的像素，锚点偏移用 */
    private fun dp(v: Int): Float = v * resources.displayMetrics.density

    private fun dpF(v: Float): Float = v * resources.displayMetrics.density

    /**
     * 真实屏幕像素（含状态栏 / 导航栏）。
     *
     * dispatchGesture 的坐标和全屏截图用的都是这套空间；`displayMetrics` 给的是
     * 应用可用区域（1220x2453，少一条导航栏）。dp 点是按「屏幕中心」存的，
     * 用 displayMetrics 算中心会让所有坐标规则系统性上飘 129px（≈47dp）。
     */
    private fun realScreenPx(): Pair<Int, Int> = try {
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    } catch (e: Exception) {
        resources.displayMetrics.widthPixels to resources.displayMetrics.heightPixels
    }

    /**
     * 解析坐标规则的落点。
     *
     * 有锚点时按锚点控件的实际矩形算（锚点中心 + dx，锚点底边 + dy），这样换机型、
     * 分屏、弹窗内容变高变矮都不影响——固定屏幕比例做不到这点；
     * 锚点找不到（页面变了）才退回屏幕比例，两者都没有就放弃本次。
     */
    private fun resolvePoint(root: AccessibilityNodeInfo?, r: com.noteatoranges.skipad.db.SkipAppRule): Pair<Float, Float>? {
        // 自由取点：存的是「相对屏幕中心的 dp」，换分辨率/密度都能换算回正确位置
        if (r.hasDpPoint) {
            val (sw, sh) = realScreenPx()
            return (sw / 2f + dpF(r.dpX ?: 0f)) to (sh / 2f + dpF(r.dpY ?: 0f))
        }
        if (r.hasAnchor) {
            val anchor = findAnchorRect(root, r)
            if (anchor != null) {
                return (anchor.exactCenterX() + dp(r.dxDp ?: 0)) to
                    (anchor.bottom.toFloat() + dp(r.dyDp ?: 0))
            }
            traceD("anchor-miss ${r.id} vid=${r.anchorVid} text=${r.anchorText}")
            if (r.xRatio == null || r.yRatio == null) return null
        }
        val xr = r.xRatio ?: return null
        val yr = r.yRatio ?: return null
        // 比例也是相对真实屏幕存的（生成时用的同一套尺寸），不能混用 displayMetrics
        val (rw, rh) = realScreenPx()
        return rw * xr to rh * yr
    }

    /** 在树里找锚点控件，多个命中时取面积最小的那个（最具体的那个才是按钮本体） */
    private fun findAnchorRect(root: AccessibilityNodeInfo?, r: com.noteatoranges.skipad.db.SkipAppRule): Rect? {
        root ?: return null
        val vid = r.anchorVid?.takeIf { it.isNotBlank() }?.lowercase(java.util.Locale.ROOT)
        val txt = r.anchorText?.takeIf { it.isNotBlank() }
        if (vid == null && txt == null) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var best: Rect? = null
        var bestArea = Int.MAX_VALUE
        var n = 0
        while (queue.isNotEmpty() && n < ANCHOR_SCAN_NODES) {
            val node = queue.removeFirst()
            n++
            val f = factsCache.of(node)
            val vidHit = vid != null && f.vid?.lowercase(java.util.Locale.ROOT)?.contains(vid) == true
            val textHit = txt != null &&
                (f.text?.contains(txt, ignoreCase = true) == true ||
                    f.desc?.contains(txt, ignoreCase = true) == true)
            if (vidHit || textHit) {
                val rect = f.rect
                val area = rect.width() * rect.height()
                if (area in 1 until bestArea) {
                    best = Rect(rect)
                    bestArea = area
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return best
    }

    /**
     * 规则闸门：冷却与次数上限。
     *
     * 误点的代价远大于漏跳，所以判断放在动作之前，通过才记账。
     */
    private fun gate(key: String, cooldownMs: Long, maxCount: Int, now: Long): Boolean {
        val st = ruleState.getOrPut(key) { RuleRun() }
        if (maxCount > 0 && st.count >= maxCount) {
            traceV("gate-max $key ${st.count}/$maxCount")
            return false
        }
        if (cooldownMs > 0 && st.lastAt > 0 && now - st.lastAt < cooldownMs) {
            traceV("gate-cd $key ${now - st.lastAt}ms/${cooldownMs}ms")
            return false
        }
        st.lastAt = now
        st.count++
        return true
    }

    /** 节点的去重键：按矩形分 8px 桶。坐标抖动 1~2px 不会产生新键 */
    private fun rectKeyOf(node: AccessibilityNodeInfo): String {
        val r = Rect()
        node.getBoundsInScreen(r)
        return "${r.left / RECT_BUCKET_PX}|${r.top / RECT_BUCKET_PX}|" +
            "${r.right / RECT_BUCKET_PX}|${r.bottom / RECT_BUCKET_PX}"
    }

    /**
     * 同一个控件位置在本页面里只点一次。
     *
     * [gate] 的冷却按「规则」记账，可两条不同规则（内置 + 用户自建、开屏通道 + 弹窗通道）
     * 命中同一个控件时会各点一次；第二下落在已经跳转后的页面上，就是实打实的误点。
     * 这里按实际矩形再兜一层。矩形分桶是因为无障碍的 bounds 在相邻两帧间会有 1~2px 抖动，
     * 精确相等去不掉重复。
     *
     * 只管按节点点击；坐标规则的重试是故意连点，不走这里。
     */
    private fun clickNodeOnce(node: AccessibilityNodeInfo): Boolean {
        val key = rectKeyOf(node)
        if (key in clickedRects) {
            traceV("click-dup $key")
            return false
        }
        val ok = clicker?.click(node) == true
        // 只在真正点出去之后才记：点失败（节点失效等）还应该允许下次再试
        if (ok) clickedRects.add(key)
        return ok
    }

    /**
     * 真的切到某个 App：新的一轮开始。
     *
     * [onPageChanged] 按 Activity 判换页，单 Activity 应用（开屏和主界面同一个类名）
     * 第二次冷启动时判不出来，点击记录会一直留着。这里按「切 App」再清一次。
     * 只归零次数、不动冷却时间——切出去看一眼通知栏再回来，不该立刻又获得一次点击机会。
     */
    private fun onAppSwitched(pkg: String) {
        clickedRects.clear()
        resetRetryState(pkg)
        val prefix = "$pkg|"
        ruleState.keys.filter { it.startsWith(prefix) }.forEach { ruleState[it]?.count = 0 }
    }

    /**
     * 新的一轮开始：续窗凭据与「已续过」标记全部作废。
     *
     * **两个入口都要调**。0.17.53 只在 [onAppSwitched]（WSC 的 freshSwitch 分支）里清，
     * 而 360 清理大师冷启动走的是 [handleEvent] 里的 `wcc-switch` 兜底路径，不经过那里。
     * 实测后果：上一轮的 `retried=true` 残留到下一轮，日志里是
     * `retry-check new=SplashActivity lastHit= age=-1 retried=true`，
     * 这一轮即使出现第二段广告也永远不会续窗。
     */
    private fun resetRetryState(pkg: String) {
        retriedAfterHit.remove(pkg)
        lastHitAt.remove(pkg)
        lastHitAct.remove(pkg)
        retryAct.remove(pkg)
    }

    /**
     * 点击成功后，若同一个包里换了 Activity 且发生在 [RETRY_AFTER_HIT_MS] 内，续开一次开屏窗口。
     *
     * 0.17.53 实测的形态：360 清理大师 `SplashActivity` 点掉之后紧跟
     * `SplashRecommendActivity`，同一个包。第一段点击时 `splashClosedAt` 立刻生效，
     * 第二段出现时窗口已关；而 `freshSwitch` 要求「上一屏不是自己」，同包换页拿不到新窗口，
     * 于是第二段整个不在射程内——用户看到的正是「跳过之后马上又是广告」。
     *
     * 只续一次：续完立刻把凭据删掉。广告页之间来回跳不会变成连点。
     * 续窗后照常走面积/位置/文本长度/核验全部闸门，主界面上没有「跳过」字样时
     * 扫满四秒也只是 no-match。
     */
    private fun maybeReopenAfterHit(pkg: String, newAct: String) {
        // 0.17.57 诊断：0.17.56 在真实 360 上点击成功、第二段也出现了，却没有续窗，
        // 日志里一条都没有。三个提前 return 的条件必须能看见是哪一个挡的。
        // 只在这个包确实点过（有 lastHitAt 或已续过）时才打，否则每次 WSC 都会刷一行。
        val hitAt = lastHitAt[pkg]
        if (hitAt == null && retriedAfterHit[pkg] != true) return
        val nowCheck = SystemClock.elapsedRealtime()
        traceV(
            "retry-check $pkg new=${newAct.substringAfterLast('.')}" +
                " lastHit=${lastHitAct[pkg].orEmpty().substringAfterLast('.')}" +
                " age=${hitAt?.let { nowCheck - it } ?: -1} retried=${retriedAfterHit[pkg]}"
        )
        // 一轮里只允许续一次。不能只靠「删凭据」——每次点击成功都会重新写 lastHitAt，
        // 于是「点第 2 段 → 跳到第 3 段 → 又满足 6 秒内且换了类名」会继续续下去。
        // 受控 App 三连跳实测确认过这个漏洞：不拦的话每一段都会续。
        if (retriedAfterHit[pkg] == true) return
        if (hitAt == null) return
        val now = nowCheck
        if (now - hitAt > RETRY_AFTER_HIT_MS) {
            lastHitAt.remove(pkg)
            lastHitAct.remove(pkg)
            return
        }
        // 同一个页面里刷新内容不算换页，交给正常的 onPageChanged 处理
        if (newAct.isNotEmpty() && newAct == lastHitAct[pkg].orEmpty()) return
        lastHitAt.remove(pkg)
        lastHitAct.remove(pkg)
        if (pkg in blacklist) return
        // design.md 第 21 条：主界面白扫。某个页面连着两次续上来都一次没命中，就不再为它
        // 开续窗。门槛取「连续两次」而不是一次，是因为第二段广告实测 493–1400ms 才出现，
        // 偶发一次没填充不该被判成空窗；命中过就立刻作废（见 scanAndAct 命中分支）
        if (plainTracker.isPlain(pkg, newAct)) {
            traceV("retry-skip-plain $pkg act=${newAct.substringAfterLast('.')}")
            return
        }
        retriedAfterHit[pkg] = true
        retryAct[pkg] = newAct
        splashClosedAt.remove(pkg)
        lastStateChangeAt[pkg] = now
        splashState[pkg] = false          // 让下面的状态翻转日志能打出 open
        // 清掉本包所有规则的冷却与计数。
        //
        // `onPageChanged` 是故意保留冷却的（同页连点会重复打同一个按钮），
        // 但续窗面对的是**另一个页面**，冷却留着等于续了个空窗：
        // 受控 App 实测第 1 段点击后 `splash-common-text` 还在冷却，第 2 段整个
        // 停在 `cooldown ... 502ms` 里一次都没命中，直到冷却结束才点到第 3 段。
        val prefix = "$pkg|"
        ruleState.keys.filter { it.startsWith(prefix) }.forEach {
            ruleState[it]?.lastAt = 0L
            ruleState[it]?.count = 0
        }
        // 还有一层冷却不在这张表里：scanAndAct 开头的「同 App 冷却」用的是
        // lastSkipPkg + lastSkipAt（COOLDOWN_MS = 4000ms）。只清 ruleState 的话，
        // 续窗一开就立刻撞上它，日志里是 `cooldown <pkg> 501ms`，整个第二段照样点不动。
        //
        // 只解绑包名、不动 lastSkipAt：后者还被 [landedOnAdPage] 用来判断
        // 「点击后 3 秒内有没有出现下载类 CTA」，清成 0 会让那段判据直接失效。
        lastSkipPkg = null
        enterHotWindow(pkg)
        traceV("retry-window $pkg act=${newAct.substringAfterLast('.')} after=${now - hitAt}ms")
    }

    /** 换 App / 换页面：清节点属性缓存与规则计数，并强制重建扫描器 */
    private fun onPageChanged(pkg: String, activity: String) {
        if (stateActivity[pkg] == activity) return
        stateActivity[pkg] = activity
        factsCache.clear()
        val prefix = "$pkg|"
        // 换页面只把次数上限归零，**冷却时间必须留着**：点击后页面往往会跳到下一屏，
        // 那里若还有同一个词（App 名、标题），清掉冷却就会紧接着点第二下
        ruleState.keys.filter { it.startsWith(prefix) }.forEach { ruleState[it]?.count = 0 }
        userScannerKey = null
        // 同页面重复 WSC 会在上面 early return，这里清空只在真的换页面时发生
        clickedRects.clear()
    }

    private fun bubbleToast(msg: String) {
        mainHandler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onInterrupt() {}

    /**
     * 单次事件里的任何异常都不许把服务打死。
     *
     * 服务一崩，系统就把它记进 `crashed services` 并不再重新绑定，除非用户手动关开无障碍
     * 开关；期间通知还挂在栏里，点它毫无反应（0.17.12 在淘宝页就是这个现象）。漏跳一个
     * 广告无所谓，服务死掉是整条链路停摆。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        try {
            // 排障用：部分 ROM / 应用会发出 packageName 为空的无障碍事件。这类事件在
            // handleEvent 里两个分支都进不去（都要求 ep != null），表现是「这个 App
            // 完全不跳」，而且日志里一条都没有——分不清是事件没来，还是来了没包名。
            // 只在 pkg 为空时记一行，一秒最多一次。
            if (event.packageName == null) {
                val nowNoPkg = SystemClock.elapsedRealtime()
                if (nowNoPkg - lastNoPkgTrace > 1000) {
                    lastNoPkgTrace = nowNoPkg
                    traceV("ev-nopkg type=${event.eventType} cls=${event.className}")
                }
            }
            handleEvent(event)
        } catch (t: Throwable) {
            traceW("event-fail type=${event.eventType} pkg=${event.packageName}", t)
        }
    }

    private fun handleEvent(event: AccessibilityEvent) {
        val type = event.eventType

        // —— 坐标点按调度（必须最先做）：冷启动切换瞬间 rootInActiveWindow 往往还是
        //    旧窗口/桌面，root 包名判定会误入黑名单分支；这里用 event.packageName（准）。
        //    此路径只写 map，零开销，不做时间节流（切换事件常在 4ms 内连发）——
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val ep = event.packageName?.toString()
            // countsAsForeground：输入法 / 系统权限弹窗的窗口事件不算「切到某个 App」
            if (ep != null && ep != packageName && countsAsForeground(ep)) {
                if (lastStateChangeAt.size > 50) lastStateChangeAt.clear()
                val prevShown = lastShownPkg
                // 预热期内不认任何「切 App」。
                //
                // 只拦 enterHotWindow 是不够的：这条路径会先写 lastStateChangeAt，
                // 而 isSplashNow 只看那个时间戳。热窗口被挡下了，开屏窗口（词表扫描）
                // 照常开着 4 秒。0.17.33 实测：关开无障碍后停在微博「我的」页，
                // 服务重连 3 秒后词表在这张正常页面上命中了一次「跳过」并点了下去。
                // 预热期内只更新 lastShownPkg、不开任何窗口，20 秒后判据才可信。
                val warmingUp = SystemClock.elapsedRealtime() - connectedAt < SERVICE_WARMUP_MS
                // 「切到这个 App」：前一个出现在屏幕上的是**已知的**别的包（含桌面）才算。
                val freshSwitch = prevShown != null && prevShown != ep && !warmingUp
                lastShownPkg = ep
                traceV("wsc $ep prev=$prevShown fresh=$freshSwitch cls=${event.className}")
                // 开屏窗口只从「真的切到这个 App 的那一刻」起算：开屏页跳主界面会再来一个
                // WSC，若每次都刷新，主界面前 6 秒也会被当成开屏——那正是误点的来源
                if (freshSwitch) {
                    lastStateChangeAt[ep] = SystemClock.elapsedRealtime()
                    // 真的切到这个 App = 新的一轮：清掉「本页已点过」的矩形记录。
                    // 不清的话，微博、淘宝这类单 Activity 应用第二次冷启动时开屏页
                    // 还是同一个 Activity，onPageChanged 会提前返回，记录一直留着，
                    // 那个跳过按钮会被当成重复点击直接放过——服务不重启就永远点不动
                    onAppSwitched(ep)
                    // 桌面/系统界面不进热窗口：它们没有开屏广告，但右上角全是图标，
                    // 进了热窗口就会被主动扫描当"跳过按钮"点掉——实测点中了桌面上的大小姐图标
                    if (ep !in blacklist) enterHotWindow(ep)
                }
                // className 不一定就是 Activity：弹浮层 / 列表刷新时 WSC 事件报的是
                // android.widget.PopupWindow、androidx...RecyclerView 这类系统控件类名。
                // 直接记下来会让「页面限定」的规则永远失配（activityMatches 按类名后缀比）
                val cn = event.className?.toString()
                val realCn = cn?.takeIf { it.isNotBlank() && !isSystemUiClass(it) }
                // 0.17.54：换页判断用**原始** className，不用过滤后的 realCn。
                // 受控 App 实测：第二个 Activity 的 WSC 只报 android.widget.FrameLayout，
                // 被 isSystemUiClass 当成系统控件滤掉，续窗整整晚了一段，落在第三段才触发。
                // 真实 360 报的是真类名，两条路都要能走通。
                if (!freshSwitch) maybeReopenAfterHit(ep, cn.orEmpty())
                val act = realCn ?: lastActivityClass[ep].orEmpty()
                realCn?.let { lastActivityClass[ep] = it }
                // 换页：清属性缓存、清规则计数、下次事件重建扫描器
                onPageChanged(ep, act)
                if (freshSwitch && ep !in blacklist) {
                    // 用户 point 规则优先
                    RuleStore.pointRules(this, ep, act).forEach { r -> schedulePointTaps(ep, r) }
                }
                lastFgPkg = ep
            }
        }

        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        // 非切页事件也顺手记住前台包名：目标 App 禁了无障碍时，窗口 root 全为 null，
        // 抓取只能靠这个值判断"用户在哪个 App 里"，光靠 WSC 在服务重启后会丢。
        // 顺带补一个「切 App」兜底：部分应用（计算器这类轻量页）启动时不发 WSC，
        // 只看 WSC 的话它的开屏窗口永远打不开
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val ep = event.packageName?.toString()
            if (ep != null && ep != packageName && ep != SYSTEM_UI_PKG && countsAsForeground(ep)) {
                lastFgPkg = ep
                // prev 必须是已知的：服务刚连接时 lastShownPkg 还是 null，这时屏幕上
                // 可能已经停在某个 App 的任意页面（实测是微博「我的」页）。若把这种情况
                // 当成"刚切进来"，热窗口会在一张正常页面上打开，右上角启发式就点错了东西
                val hadPrev = lastShownPkg != null
                if (hadPrev && ep != lastShownPkg && ep !in blacklist) {
                    val now = SystemClock.elapsedRealtime()
                    // 预热期内同样不认。这条路径会把 lastStateChangeAt 写死，即使
                    // enterHotWindow 因为预热返回了，开屏窗口（词表扫描）还是会开
                    val warmingUp = now - connectedAt < SERVICE_WARMUP_MS
                    // 同一 App 一分钟内只补一次：内容变化事件比窗口事件密集得多，
                    // 不卡额度就会把「App 内换页」当成「刚切进来」，开屏窗反复重开
                    val last = splashFallbackAt[ep] ?: 0L
                    if (!warmingUp && now - last > SPLASH_FALLBACK_GAP_MS) {
                        if (splashFallbackAt.size > 50) splashFallbackAt.clear()
                        splashFallbackAt[ep] = now
                        traceV("wcc-switch $ep prev=$lastShownPkg")
                        lastShownPkg = ep
                        lastStateChangeAt[ep] = now
                        // 这条兜底路径同样代表「新的一轮」，必须和 WSC 的 freshSwitch
                        // 分支清一样的东西。0.17.58 只补了续窗三个状态，漏了规则计数——
                        // 后果是 0.17.60 跑批发现的：小米广告容器第一次成功点掉用掉
                        // maxCount=2 之后，count 永远停在 2，此后每一次容器广告都在
                        // gate-max 被挡在点击之前，日志里是「命中十几次、一次没点」。
                        // 换成 onAppSwitched 而不是只加一行，是为了让两个入口永远同步：
                        // 以后往「新一轮」里加状态，改这一处就够了
                        onAppSwitched(ep)
                        enterHotWindow(ep)
                        // 坐标规则也要排一次：不走窗口切换事件的应用（计算器这类）
                        // 只靠 WSC 分支排的话，它们的坐标规则一次都不会执行
                        RuleStore.pointRules(this, ep, lastActivityClass[ep].orEmpty())
                            .forEach { r -> schedulePointTaps(ep, r) }
                    }
                }
            }
        }

        val now = SystemClock.elapsedRealtime()
        // 热窗口内把节流压到 80/120ms：开屏只有 4 秒，300ms 的合并窗口等于白等三次
        val hot = isHot(now)
        if (now - lastHandleAt < (if (hot) HOT_THROTTLE_MS else THROTTLE_MS)) return
        lastHandleAt = now

        // 内容变化事件在信息流里一秒能来几十个，每次都要全树走一遍无障碍节点，
        // 是耗电主因。命中只取决于控件是否还在，下一次切页/内容变化都会补上
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (now - lastContentAt < (if (hot) HOT_CONTENT_THROTTLE_MS else CONTENT_THROTTLE_MS)) return
            lastContentAt = now
        }

        // rootInActiveWindow 在部分 ROM 上会是 null（窗口列表却可读），抓快照走的就是
        // windows 那条路。这里补一个同源的兜底，否则整条规则链路会静默不工作
        val rootRaw = rootInActiveWindow
        val root = rootRaw ?: activeWindowRoot(event.packageName?.toString())
        if (root == null) {
            // 0.17.50：这条原本是 `?: return`，一声不响地走掉。排查「某个 App 完全不跳」
            // 时它是第一个嫌疑人，但日志里一个字都没有——只能靠手动 uiautomator dump 反推。
            // 只在热窗口期内记，正常页面拿不到 root 是常态，不该刷屏
            val ep = event.packageName?.toString()
            if (ep != null && isHot(now)) traceMiss(ep, "reject=no-root")
            return
        }
        val pkg = root.packageName?.toString() ?: event.packageName?.toString() ?: return
        if (pkg in blacklist || pkg == packageName) return

        // 开屏窗口：只从「真的切到这个 App 的那一刻」起算 SPLASH_WINDOW_MS；
        // 本轮已经点掉过一次就立即收手（splashClosedAt），不等满窗口
        val splash = isSplashNow(pkg, now)
        // 开屏窗口的开合各记一次（只在翻转时打）：排查「为什么没触发」时，
        // 第一件事就是看这一刻窗口到底是开的还是关的
        if (splashState[pkg] != splash) {
            if (splashState.size > 50) splashState.clear()
            splashState[pkg] = splash
            traceV("splash-window $pkg ${if (splash) "open" else "closed"}")
        }

        scanAndAct(pkg, root, now, splash, windowKeyFor(pkg, event, root))
    }

    /** 此刻是否处在「刚切到这个 App」的开屏窗口里 */
    private fun isSplashNow(pkg: String, now: Long): Boolean {
        val sinceSwitch = lastStateChangeAt[pkg] ?: return false
        return now - sinceSwitch <= SPLASH_WINDOW_MS && sinceSwitch > (splashClosedAt[pkg] ?: 0L)
    }

    /**
     * 扫描并动作：事件路径与主动扫描路径共用这一段。
     *
     * 抽出来是为了让「主动扫描」不必伪造 AccessibilityEvent —— 开屏按钮出现时
     * 常常一个内容变化事件都没有，纯事件驱动的链路整段是瞎的。
     *
     * @return 是否真的点出去了
     */
    private fun scanAndAct(
        pkg: String,
        root: AccessibilityNodeInfo,
        now: Long,
        splash: Boolean,
        winKey: String,
    ): Boolean {
        // 双保险：事件路径在 handleEvent 里已挡过一次，主动扫描路径也必须挡。
        // 桌面、系统界面、自己都不该被任何规则碰
        if (pkg in blacklist || pkg == packageName) return false
        // 扫描总预算：事件路径与主动路径共用同一个计数。开屏 4 秒里两条路径叠起来
        // 最多扫 40 次全树（每次 15~25ms），再多就是拿主线程换那点运气
        if (splash && isHot(now)) {
            if (hotScanCount >= HOT_MAX_SCANS) return false
            hotScanCount++
        }
        // 刷新用户规则扫描器（规则版本 / 包名 / 页面任一变化就重建）
        val ver = RuleStore.version(this)
        val act = lastActivityClass[pkg].orEmpty()
        val key = "$ver|$pkg|$act"
        if (key != userScannerKey) {
            userScannerKey = key
            val nodeRules = RuleStore.nodeRulesAsScannerRules(this, pkg, act)
            userScanner = if (nodeRules.isEmpty()) null else NodeScanner(nodeRules, factsCache).also {
                it.verbose = Log.isLoggable(TAG, Log.VERBOSE)
            }
            userScannerPkg = pkg
        }

        // 结构路径规则最精确（用户从快照里点选的那个控件本身），先试一次；
        // 命中就不必再全树扫描。页面/冷却/次数闸在 tryPathRule 内部按规则判断
        if (tryPathRule(root, pkg, act, now)) return true

        // 开屏窗口内：用户规则优先，没命中再走词表（内置默认词 + 手输命中/排除词）；
        // 窗口外：只认用户规则，两样都没有就一条都不扫
        val userSc = userScanner?.takeIf { userScannerPkg == pkg }
        val kwSc = if (splash) keywordScannerFor(pkg) else null
        // 开屏窗口内即使两个扫描器都为空也要往下走：还有右上角启发式这一条
        if (userSc == null && kwSc == null && !splash) {
            // 0.17.50：热窗口开着、却因为「这个包没有规则、也不在开屏窗口」而放过时留一行。
            // 0.17.48 之前的系统广告容器漏跳就是这个形态：hot-open 打的是宿主，
            // 真正承载广告的却是另一个包名，它没规则也不在开屏窗口 → 静默放过，
            // 日志只剩 `scans=0`。记下包名，一眼就能看出「热窗口里混进了谁」。
            if (isHot(now)) traceMiss(pkg, "reject=no-rule-in-hot")
            return false
        }

        // 屏宽高必须用整屏尺寸（含状态栏/导航栏），不能用 displayMetrics：
        // 节点 rect 是屏幕坐标，而 displayMetrics 给的是应用可用区（少一条导航栏，
        // 1220×2453 vs 整屏 1220×2712）。基准混着用会把「中央区」的下边界抬高 10%，
        // 右上角判定的 22% 也同步缩紧；用户规则的 zone 又是按整屏比例存的（见 zoneOf）。
        val (w, h) = realScreenPx()

        // 同 App 冷却：刚点过的包在冷却期内不再重复匹配。
        // 原来比的是 winKey（含 event.className），而内容变化事件的 className 常是
        // 具体控件类，跳到新页面后键必然变化，冷却等于没设。改成按包名记——
        // 「刚点掉一个广告，4 秒内不在同一个 App 里再动手」才是这条闸的本意。
        if (lastSkipPkg == pkg && now - lastSkipAt < COOLDOWN_MS) {
            traceV("cooldown $pkg ${now - lastSkipAt}ms")
            return false
        }

        // 这个页面刚被判过「疑似点进广告」：静默一段时间。
        // 比调参数可靠——它不是猜出来的风险，是上一次真点错了的结果。
        // 静默期内每个事件都会走到这里，日志按包名一秒一次封顶：0.17.43 修好静默之后
        // 实测 9 秒打了 31 行同样的记录，EngineTrace 每条都 flush，纯属写放大。
        if (isBadPage(pkg, now)) {
            val lastTrace = lastBadPageTrace[pkg] ?: 0L
            if (now - lastTrace > 1000) {
                lastBadPageTrace[pkg] = now
                traceV("bad-page $pkg 静默中")
            }
            return false
        }

        val t0 = SystemClock.elapsedRealtime()
        // 命中可能来自主窗口，也可能来自同包的另一个窗口（广告 SDK 独立窗口）。
        // 「这一页像不像广告」必须在命中的那棵树上算，否则独立窗口里的按钮
        // 会被拿主窗口（往往只有 1 个节点）去判证据，永远判成「没有广告迹象」
        var hitRoot = root
        var hit = userSc?.findSkipTarget(root, w, h)
        var dbg = userSc?.lastDebug
        if (hit == null && kwSc != null) {
            hit = kwSc.findSkipTarget(root, w, h)
            dbg = kwSc.lastDebug
        }

        // 同包的其它窗口（0.17.37）：广告 SDK 把关闭钮放进独立窗口 / Dialog / 悬浮窗时，
        // 主窗口那棵树里根本没有这个控件，扫多少次都是 no-match。核验阶段早就遍历 windows
        // 了（findUserTargetInAnyWindow），扫描阶段此前没有——这是明确的漏跳路径。
        //
        // 三条限制，都为了「宁可少跳不可乱点」：
        // 1. 只在开屏窗口内做。窗口外只认用户规则，扩大扫描面等于扩大误点面；
        // 2. 只认同一个包名的窗口。跨包扫描就是 0.17.30 那次跨应用误点的来源；
        // 3. 次数计入热窗口总预算（HOT_MAX_SCANS），且额外窗口最多 2 个。
        // 右上角启发式不在这里跑：它是几何猜测，浮层窗口的尺寸不代表开屏页的布局。
        if (hit == null && splash) {
            var extra = 0
            val wins = runCatching { windows?.toList() ?: emptyList() }.getOrDefault(emptyList())
            for (w0 in wins) {
                if (extra >= MAX_EXTRA_WINDOWS) break
                val r0 = runCatching { w0.root }.getOrNull() ?: continue
                if (sameRoot(r0, root)) continue
                if (runCatching { r0.packageName?.toString() }.getOrNull() != pkg) continue
                if (isHot(now)) {
                    if (hotScanCount >= HOT_MAX_SCANS) {
                        traceV("hot-budget $pkg 额外窗口未扫")
                        break
                    }
                    hotScanCount++
                }
                extra++
                traceV("win-scan $pkg #${w0.id}")
                val h1 = userSc?.findSkipTarget(r0, w, h)
                if (h1 != null) {
                    hit = h1
                    hitRoot = r0
                    dbg = userSc?.lastDebug
                } else {
                    val h2 = kwSc?.findSkipTarget(r0, w, h)
                    if (h2 != null) {
                        hit = h2
                        hitRoot = r0
                        dbg = kwSc?.lastDebug
                    }
                }
                if (hit != null) {
                    traceD("win-hit $pkg #${w0.id}")
                    break
                }
            }
        }

        // 右上角启发式：0.17.62 停用，代码保留（CornerSkipFinder 与 CORNER_RULE 都没删）。
        //
        // 停用依据不是一次误点，是全量账：
        // - 0.17.60/0.17.61 两次跑批共 24 次真实冷启动，它只触发过 2 次，都是高德地图
        //   的「图层入口」（1072,288-1204,408，高德右上角的真实功能按钮），成功跳过真广告 0 次；
        // - 更早的 CHANGELOG 里它是常客：微博「我的」页编辑按钮、通知直落功能页、桌面网格图标，
        //   每一次都是出事之后补一道闸（2.5 秒时间闸、可滚动整页放弃、不补刀）。
        //
        // 它的定位本来是「广告有跳过按钮但既没文字也没 skip 类 id」的唯一出路，
        // 但这类开屏在 24 次样本里一次都没出现过。按「宁可少跳不可乱点」，
        // 零收益、持续误点的功能不该默认开着。要恢复就取消下面的注释，并先拿到
        // 「几何命中且确实跳掉了广告」的真机证据。
        //
        // if (hit == null && splash && inCornerWindow(now) && EnginePrefs.builtinWordsEnabled(this)) {
        //     val corner = runCatching { CornerSkipFinder.find(root, w, h, factsCache) }.getOrNull()
        //     if (corner != null) {
        //         hit = corner to CORNER_RULE
        //         dbg = "corner-hit「${runCatching {
        //             (corner.text ?: corner.contentDescription ?: corner.className)?.toString()
        //         }.getOrNull()?.take(12)}」"
        //     }
        // }
        // 扫描耗时：决定要不要给「每 App 事件次数」加预算时必须看这个数，
        // 不能凭感觉（上限是 TRAVERSE_TIMEOUT_MS=400ms）
        val cost = SystemClock.elapsedRealtime() - t0
        if (hit == null) {
            traceV("scan $pkg ${cost}ms: $dbg")
            traceMiss(pkg, dbg)
            return false
        }
        // 命中节点的文本可能在定位之后、动作之前失效（页面正在跳）。读失败退回规则 id，
        // 不因为一行记账把整次动作丢掉
        val detail = runCatching {
            hit.first.text?.toString()
                ?: hit.first.contentDescription?.toString()
                ?: hit.first.viewIdResourceName
        }.getOrNull() ?: hit.second.id
        traceD("match $pkg ${cost}ms: $dbg")

        val rule = hit.second
        // 冷却 / 次数上限：拦在这里，误点的代价远大于漏跳
        if (!gate("$pkg|${rule.id}", rule.cooldownMs, rule.maxCount, now)) return false
        if (rule.action == SkipRule.ACTION_NONE) {
            traceD("match-only $pkg 「$detail」 规则=${rule.id}")
            return false
        }
        // 这个位置本轮已经点过了：直接放过。放在 dumpTree 之前——否则每个后续事件
        // 都会重写一份诊断树，并且走到 action-fail 把自己的日志污染成「点击失败」
        val hitRectKey = rectKeyOf(hit.first)
        if (hitRectKey in clickedRects) {
            traceV("match-dup $pkg 规则=${rule.id}")
            return false
        }
        dumpTree(root, pkg, "match")
        val fired = when (rule.action) {
            SkipRule.ACTION_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            else -> clickNodeOnce(hit.first)
        }
        if (!fired) {
            traceD("action-fail $pkg ${rule.action} 规则=${rule.id}")
            return false
        }
        // 点了就收手：开屏窗口立刻关，不等 800ms 核验回来。
        // 广告点掉后通常 300~600ms 就露出主界面，这期间若还开着窗，主界面的内容变化
        // 事件会继续走词表扫描。关窗是唯一不依赖任何推断的收手方式；
        // 核验（verifySkip）不看这个时间戳，补刀照常进行。
        splashClosedAt[pkg] = now
        // 这个页面确实点得到东西：之前若被学成「空窗页」，立刻作废。
        // 学习判据只允许「连续两次空手而归」才算数，命中一次就推翻它
        // 计数必须赶在下面的 exitHotWindow 之前：那里要靠它区分
        // 「续窗扫满没命中」和「续窗点着了才退出」
        hotHitCount++
        plainTracker.forget(pkg, act)
        // 0.17.53：记下「刚点过」的凭据，供 [maybeReopenAfterHit] 判断同包换页。
        // 只有点击成功才写——没点出去就不该有续窗资格
        lastHitAt[pkg] = now
        lastHitAct[pkg] = lastActivityClass[pkg].orEmpty()
        // 热窗口的主动扫描也立刻停，不再往主界面上扫
        if (hotPkg == pkg) {
            val elapsed: Long = if (hotOpenedAt > 0) now - hotOpenedAt else -1L
            if (elapsed >= 0) traceD("hot-hit $pkg ${elapsed}ms 规则=${rule.id}")
            exitHotWindow(now)
        }
        lastSkipWindow = winKey
        lastSkipPkg = pkg
        lastSkipAt = now
        recordRuleHit(rule, hit.first)
        // 右上角启发式是几何猜的：没文本、没 id，核验时能复用的只有同一套几何判据。
        // 第一下没点掉时，「还在」既可能是广告没响应，也可能是页面已换、右上角另有
        // 别的小按钮——这时再补一次裸坐标点击，风险大于收益。所以 corner 一律不补刀。
        val retry = if (rule.id == CORNER_RULE.id) 0 else MAX_RETRY
        // 广告证据必须在点击这一刻采：点完页面就换了，事后再去新页面上找「广告」字样
        // 只会找到主界面。命中本来就少，多一次整树遍历可以忽略
        val adEv = hasAdEvidence(hitRoot, hit.first)
        if (adEv) traceV("ad-evidence $pkg 「$detail」 规则=${rule.id}")
        else traceV("no-ad-evidence $pkg 「$detail」 规则=${rule.id}")
        pending = PendingSkip(
            winKey, pkg, detail, 0, retry,
            ruleId = rule.id, act = act, rectKey = hitRectKey, adEvidence = adEv,
        )
        val token = pending
        mainHandler.postDelayed({ if (pending === token) verifySkip() }, VERIFY_DELAY_MS)
        return true
    }

    // —— 热窗口 ——

    /** 现在是否在热窗口内 */
    private fun isHot(now: Long): Boolean = hotPkg != null && now <= hotUntil

    /** 是否还在「允许右上角启发式」的那 2.5 秒里（比整个热窗口更短、更保守） */
    private fun inCornerWindow(now: Long): Boolean =
        hotOpenedAt > 0 && now - hotOpenedAt <= HOT_CORNER_WINDOW_MS

    /**
     * 进入热窗口：把系统事件合并窗口压到 50ms，并起主动扫描。
     *
     * 只在「真的切到某个 App」时调用。重复切同一个 App 会重开窗口，
     * 但主动扫描任务每次都先停旧的，不会叠出两条链。
     */
    private fun enterHotWindow(pkg: String) {
        val now = SystemClock.elapsedRealtime()
        // 服务刚连上时屏幕上停的可能是一张正常页面，不是开屏页：这段预热期一律不开热窗口
        if (now - connectedAt < SERVICE_WARMUP_MS) {
            traceV("hot-skip $pkg warmup=${now - connectedAt}ms")
            // 没开成窗就不该留下「续窗落点」，否则下一轮正常退出时会拿这个陈旧值去学习
            retryAct.remove(pkg)
            return
        }
        // 先停旧链：重复切 App 时若不停，旧任务还会在下一拍醒来，和新链叠成两条
        hotScanTask?.let { mainHandler.removeCallbacks(it) }
        hotScanTask = null
        hotPkg = pkg
        hotUntil = now + SPLASH_WINDOW_MS
        hotOpenedAt = now
        hotScanCount = 0
        hotHitCount = 0
        setNotifTimeout(HOT_NOTIF_TIMEOUT)
        scheduleActiveScan()
        // 记下开窗时的 Activity：这是判断「开屏页 vs 主界面」最直接的信号，
        // 但现在还不够证据做过硬闸（微博冷启动和主页同为 MainTabActivity，一刀切会误杀），
        // 所以先只记录，等真机日志攒够再定阈值
        traceV("hot-open $pkg act=${lastActivityClass[pkg].orEmpty().substringAfterLast('.')}")
    }

    /** 退出热窗口：还原事件合并窗口，停掉主动扫描 */
    private fun exitHotWindow(now: Long) {
        if (hotPkg == null) return
        val pkg = hotPkg!!
        hotPkg = null
        hotUntil = 0L
        hotOpenedAt = 0L
        hotScanTask?.let { mainHandler.removeCallbacks(it) }
        hotScanTask = null
        setNotifTimeout(baseNotifTimeout)
        // 只对「续窗轮」记账：正常冷启动的开屏窗口扫满没命中很正常（广告没填充），
        // 学到它头上就会把整类 App 的续窗能力废掉。
        // 还要求这个落点看起来像 Activity：部分 App 的窗口事件只给控件类名，
        // `lastActivityClass` 会是 `android.widget.FrameLayout`——拿它当键，
        // 等于把该 App 的所有页面共用一个「空窗」标记，一次误学就全废
        val actName = retryAct.remove(pkg).orEmpty()
        if (actName.isNotEmpty() && hotHitCount == 0) {
            val learned = plainTracker.learn(pkg, actName)
            val n = plainTracker.countOf(pkg, actName)
            if (learned) {
                traceV("plain-act learn $pkg act=${actName.substringAfterLast('.')} hits=$n")
            } else if (n > 0) {
                traceV("plain-act count $pkg act=${actName.substringAfterLast('.')} hits=$n")
            }
        }
        traceV("hot-close $pkg scans=$hotScanCount hits=$hotHitCount")
    }

    /** 改系统事件合并窗口；服务未连接或系统拒绝时静默跳过 */
    private fun setNotifTimeout(ms: Long) {
        try {
            val info = serviceInfo ?: return
            if (info.notificationTimeout == ms) return
            info.notificationTimeout = ms
            setServiceInfo(info)
        } catch (t: Throwable) {
            traceW("notif-timeout-fail $ms", t)
        }
    }

    /** 排下一次主动扫描；窗口过期、次数用完、或已经切走就停 */
    private fun scheduleActiveScan() {
        if (hotPkg == null) return
        val task = Runnable {
            hotScanTask = null
            val now = SystemClock.elapsedRealtime()
            val pkg = hotPkg ?: return@Runnable
            if (now > hotUntil) {
                exitHotWindow(now)
                return@Runnable
            }
            if (hotScanCount >= HOT_MAX_SCANS) {
                traceV("hot-budget $pkg ${hotScanCount}")
                exitHotWindow(now)
                return@Runnable
            }
            // 计数统一在 scanAndAct 里做：事件路径也在消耗同一份预算
            try {
                activeScan(pkg, now)
            } catch (t: Throwable) {
                traceW("hot-scan-fail", t)
            }
            scheduleActiveScan()
        }
        hotScanTask = task
        mainHandler.postDelayed(task, HOT_SCAN_INTERVAL_MS)
    }

    /**
     * 主动扫描一次：不等事件，自己取当前窗口的 root。
     *
     * 只有三条前置条件同时成立才扫：还在热窗口、前台仍是这个包、窗口 root 读得到。
     * 任何一条不成立就安静返回——这里每秒跑八次，不能有副作用。
     */
    private fun activeScan(pkg: String, now: Long) {
        // 桌面/系统界面一律不扫：它们的窗口里全是图标，任何启发式都会误判
        if (pkg in blacklist || pkg == packageName) return
        if (!isHot(now)) return
        if (lastFgPkg != null && lastFgPkg != pkg) return
        if (!isSplashNow(pkg, now)) return
        val root = currentRootOf(pkg) ?: return
        if (root.packageName?.toString() != pkg) return
        scanAndAct(pkg, root, now, true, "$pkg/${root.className ?: ""}")
    }

    private fun windowKeyFor(pkg: String, event: AccessibilityEvent, root: AccessibilityNodeInfo): String =
        "$pkg/${event.className ?: root.className ?: ""}"

    /**
     * 核验：在**同一个 App 的**窗口里再找一遍，目标消失才算跳过成功，否则手势补刀。
     *
     * 以前这里遍历所有窗口（含浮层、输入法），同一份规则套到别的窗口上，命中就补刀，
     * 那是实打实的跨应用误点。规则按 App 建，核验也只认那个 App。
     */
    private fun verifySkip() {
        // 这个方法由 postDelayed 反复调度：异常没人接盘就是整个进程倒下，
        // 而进程一死无障碍服务跟着掉线。补刀手势、跨窗口读节点都在这条路上
        try {
            verifySkipOnce()
        } catch (t: Throwable) {
            traceW("verify-fail", t)
            pending = null
        }
    }

    private fun verifySkipOnce() {
        val p = pending ?: return

        val found = when {
            p.path != null -> findByPathAnyWindow(p.pkg, p.path, 0)?.let { Triple(it, p.pkg, "path") }
            // 右上角启发式的节点不在任何扫描器的规则集里（CORNER_RULE 是虚拟规则），
            // 走 findUserTargetInAnyWindow 永远返回 null，等于「点完就记账」——
            // 点错了也照记成功。这里用同一个启发式复核：还在就补刀
            p.ruleId == CORNER_RULE.id -> currentRootOf(p.pkg)?.let { r ->
                val (cw, ch) = realScreenPx()
                CornerSkipFinder.find(r, cw, ch, factsCache)?.let { Triple(it, p.pkg, "corner") }
            }
            // 内置规则与手输关键字（下表外的两族）都不在 userScanner 里：拿
            // findUserTargetInAnyWindow 去查必然返回 null，于是「点完就记账」——
            // 点没点掉都算成功。0.17.37 之前统计里那条「设置 1 次」就是这么来的：
            // 设置页里出现「跳过」被当成开屏按钮，点了一下页面没变，照样记了一次。
            // 这里改用同一个内置扫描器复核，还在就不记账、走补刀。
            isBuiltinRuleId(p.ruleId) -> {
                // 页面已经换掉 = 点击生效了。新页面上同样的字不是「目标还在」，
                // 拿它去补刀就是往正文里点一下
                val cur = lastActivityClass[p.pkg].orEmpty()
                if (p.act.isNotEmpty() && cur.isNotEmpty() && cur != p.act) null
                else findBuiltinTarget(p.pkg, p.ruleId, p.rectKey)
            }
            else -> findUserTargetInAnyWindow(p.pkg, p.ruleId)
        }
        if (found == null) {
            val appCtx = applicationContext
            val now = SystemClock.elapsedRealtime()
            // 目标不在了，但「不在了」有两种：真的跳过成功，或者点进了广告落地页/应用商店。
            // 后者以前同样记成「已跳过」，统计因此虚高——0.17.33 那次「我的」页误点就是
            // 这样进的榜单。判据只看包名：跳到商店/浏览器时前台已经换成别人的包。
            val fg = foregroundPackage()
            // 系统广告容器（0.17.48）：开屏渲染在容器自己的包名里，点掉之后前台回到宿主 App。
            // 这里的「跨包」正是成功的样子，不是点进广告——真按 off-app 处理的话，
            // 统计会记成「疑似点进广告」，容器页还会被拉黑 10 分钟，下一次开屏直接不动作。
            val gatewaySucceeded = p.pkg in SYSTEM_AD_GATEWAYS && fg != null && fg != p.pkg
            if (fg != null && fg != p.pkg && !gatewaySucceeded) {
                traceD("off-app ${p.pkg} → $fg，不计入跳过")
                markBadPage(p.pkg, now)
                Io.run("stats-insert") {
                    StatsStore.logEvent(appCtx, "疑似点进广告：${p.pkg} → $fg")
                }
                // 早退：下面那行 verified 是给「真的跳过了」准备的，这里不能也打一遍
                splashClosedAt[p.pkg] = now
                pending = null
                return
            }
            if (landedOnAdPage(p, now)) {
                // 同包落地页（0.17.40）：目标没了、包名也没变，但新页面上是「立即下载」
                // 这类 CTA —— 那不是跳过了广告，是被带进了广告。以前这里会直接记账，
                // 统计虚高、落地页也不进黑名单。
                // 不能靠 Activity 名判断换页：实测部分 App 的 WSC 事件不带类名，
                // `lastActivityClass` 一直是空的（受控 App 的日志就是 `act=` 空）。
                traceD("landing ${p.pkg} 新页面出现下载类 CTA，疑似点进广告，不记账")
                markBadPage(p.pkg, now)
                Io.run("stats-insert") {
                    StatsStore.logEvent(appCtx, "疑似点进广告：${p.pkg}（同包落地页）")
                }
                splashClosedAt[p.pkg] = now
                pending = null
                return
            }
            // 容器命中时记到宿主名下：否则统计里会出现 com.miui.systemAdSolution
            // 这个用户完全看不懂的名字，而真正被跳过的是宿主 App 的开屏
            val statsPkg = if (gatewaySucceeded) (fg ?: p.pkg) else p.pkg
            // 过渡页回撤（0.17.60，design.md 第 18 条）：
            // 清理大师冷启动时，`splash-common-text` 先点掉启动页自带的「跳过」并记了一次
            // `verified`，86ms 后真正的 `SplashAdActivity` 才出现、又被点掉一次。前一次点的
            // 不是广告，统计里却算了一次「已跳过广告」。
            //
            // 判据：同一 App 两秒内连着 verified 两次，且**前一次点的时候页面上没有任何
            // 广告迹象**（无 SDK 资源名前缀、无「广告」字样、无倒计时数字）。
            // 不靠 Activity 名——很多 App 的窗口事件不带类名，这条判据对它们直接失效。
            // 有一点广告迹象就不撤：宁可多记一次，也不把真广告从统计里抹掉。
            val detail = p.detail
            val adEv = p.adEvidence
            Io.run("stats-insert") {
                val db = StatsDb.get(appCtx)
                val prevId = demoter.demoteTarget(p.pkg, now)
                if (prevId > 0L) {
                    val removed = runCatching { db.deleteById(prevId) }.getOrDefault(false)
                    traceD("demote-page-skip ${p.pkg} 上一条无广告迹象 removed=$removed")
                    StatsStore.logEvent(appCtx, "过渡页跳过：${p.pkg}")
                }
                val id = db.insert(statsPkg, detail)
                demoter.record(p.pkg, id, now, !adEv)
            }
            splashClosedAt[p.pkg] = now
            traceD("verified ${p.pkg} 「$detail」 (attempts=${p.attempts})")
            pending = null
            return
        }
        if (p.attempts >= p.maxRetry) {
            // 带上节点形态：下次再出现「点了不生效」，这几项决定该往哪儿查——
            // clickable=false 说明只剩坐标点击可用，bounds 出屏说明中心点本来就没打中，
            // cls 是自定义 View 说明点的是容器而不是按钮本体
            val shape = runCatching {
                val r = Rect()
                found.first.getBoundsInScreen(r)
                "cls=${found.first.className} clickable=${found.first.isClickable} " +
                    "bounds=(${r.left},${r.top},${r.right},${r.bottom})"
            }.getOrNull() ?: "cls=?"
            traceD("giveup ${p.pkg} 「${p.detail}」目标仍在(${found.second}, $shape), 已补刀${p.attempts}次不记账")
            dumpTree(rootInActiveWindow, p.pkg, "giveup")
            pending = null
            return
        }
        p.attempts++
        val rect = Rect()
        found.first.getBoundsInScreen(rect)
        if (!rect.isEmpty) {
            val ok = clicker?.clickPoint(rect.exactCenterX(), rect.exactCenterY()) ?: false
            traceD("retry#${p.attempts} ${p.pkg} gesture ok=$ok win=${found.second}")
        }
        mainHandler.postDelayed({ if (pending === p) verifySkip() }, VERIFY_DELAY_MS)
    }

    /**
     * 当前前台包名：活动窗口优先，读不到退回最近一次窗口事件记下的包。
     *
     * 核验时用它区分「真跳过」和「点进广告落地页」——跳到应用商店/浏览器时，
     * 活动窗口已经换成了别人的包，而 lastFgPkg 在 WSC 里同步更新，两条路都能取到。
     */
    private fun foregroundPackage(): String? {
        val r = runCatching { rootInActiveWindow }.getOrNull()
        val p = r?.packageName?.toString()
        if (p != null && p != SYSTEM_UI_PKG) return p
        return lastFgPkg?.takeIf { it != SYSTEM_UI_PKG }
    }

    /**
     * 误点页静默的 key。
     *
     * 首选 `包名/Activity`——同一个 App 里别的页面不该被连坐。但部分 App 的窗口事件
     * 不带类名（`lastActivityClass` 为空，受控 App 里 `hot-open ... act=` 后面就是空），
     * 以前这种情形直接 return，等于「静默 10 分钟」对这些 App 从来没生效过。
     * 拿不到页面名时退到包名级 key：代价是十分钟内整个 App 不再自动跳，
     * 换来的是「点错一次之后不会接着在该页面上点」。
     */
    private fun badPageKey(pkg: String): String {
        val act = lastActivityClass[pkg].orEmpty()
        return if (act.isEmpty()) "$pkg/*" else "$pkg/$act"
    }

    /**
     * 当前页面（或这个 App）是否处在「刚疑似点错、暂时别动」的静默期。
     *
     * 两个 key 都查：包名级覆盖「读不到 Activity」时打下的标记，页面级覆盖正常标记。
     * 只查其一的话，标记时与查询时的 key 可能对不上，静默就漏了。
     */
    private fun isBadPage(pkg: String, now: Long): Boolean {
        var hit = false
        val act = lastActivityClass[pkg].orEmpty()
        val keys = if (act.isEmpty()) listOf("$pkg/*") else listOf("$pkg/*", "$pkg/$act")
        for (k in keys) {
            val until = badPageUntil[k] ?: continue
            if (until <= now) badPageUntil.remove(k) else hit = true
        }
        return hit
    }

    private fun markBadPage(pkg: String, now: Long) {
        if (badPageUntil.size > 100) badPageUntil.clear()
        badPageUntil[badPageKey(pkg)] = now + BAD_PAGE_MS
    }

    /** 某个包名的可用窗口根：活动窗口优先，取不到再走窗口列表兜底 */
    private fun currentRootOf(pkg: String): AccessibilityNodeInfo? {
        val r = runCatching { rootInActiveWindow }.getOrNull()
        if (r != null && r.packageName?.toString() == pkg) return r
        return activeWindowRoot(pkg)
    }

    /**
     * 判断两次取到的是不是同一个窗口的根。
     *
     * 为什么不用 [AccessibilityNodeInfo.equals]：跨版本语义不完全一致（有的比
     * connectionId + windowId，有的还要比 sourceNodeId），拿不准就不依赖。
     * 几何 + 类名 + 子节点数三者相同，在实践中已经足够——误判成同一个只是少扫一次，
     * 误判成不同也只是多扫一次树，两种代价都可接受，不像重复点击那样不可逆。
     */
    private fun sameRoot(a: AccessibilityNodeInfo, b: AccessibilityNodeInfo): Boolean {
        if (a === b) return true
        val ra = Rect()
        val rb = Rect()
        runCatching { a.getBoundsInScreen(ra) }
        runCatching { b.getBoundsInScreen(rb) }
        return ra == rb && a.className == b.className && a.childCount == b.childCount
    }

    /**
     * 活动窗口的 root，作为 [rootInActiveWindow] 的兜底。
     *
     * 部分 ROM 上 rootInActiveWindow 返回 null，但 getWindows() 里的窗口 root 是可读的
     * （抓快照走的就是这条路）。没有这个兜底，整条规则链路会安静地不工作。
     */
    private fun activeWindowRoot(preferredPkg: String?): AccessibilityNodeInfo? {
        val wins = runCatching { windows?.toList() ?: emptyList() }.getOrDefault(emptyList())
        if (wins.isEmpty()) return null
        val ordered = wins.sortedByDescending { it.isActive }
        // 先按事件里的包名精确匹配，再退为「任意不是自己的窗口」：窗口 root 的
        // packageName 在部分 ROM 上读不出来，硬要用它判包名会把唯一可用的窗口丢掉
        for (w in ordered) {
            val r = runCatching { w.root }.getOrNull() ?: continue
            val p = runCatching { r.packageName?.toString() }.getOrNull()
            if (p == null) continue
            if (p == packageName || p in blacklist) continue
            if (preferredPkg == null || p == preferredPkg) return r
        }
        for (w in ordered) {
            val r = runCatching { w.root }.getOrNull() ?: continue
            val p = runCatching { r.packageName?.toString() }.getOrNull()
            if (p == packageName || (p != null && p in blacklist)) continue
            return r
        }
        return null
    }

    /**
     * 开屏窗口用的词表扫描器：内置默认词（可开关）+ 该 App 的命中词，带排除词。
     *
     * 缓存键含词表版本、内置开关、包名；用户改词或拨开关，下一次事件就重建，不用重启服务。
     * **只在开屏窗口内调用**——这是它不会在聊天页、信息流里动手的唯一保证。
     */
    private fun keywordScannerFor(pkg: String): NodeScanner? {
        val kwVer = KeywordStore.version(this)
        val builtinOn = EnginePrefs.builtinWordsEnabled(this)
        val key = "$kwVer|$builtinOn|$pkg"
        if (key != kwKey) {
            kwKey = key
            val (hits, excludes) = KeywordStore.forApp(this, pkg)
            val rules = ArrayList<SkipRule>()
            if (builtinOn) rules.addAll(BuiltinRules.defaults())
            // 只有命中词才有意义；只写了排除词时没有可命中的东西，整组不建。
            // allowCenter：用户手输的词免位置闸——他要点的就是这个，开屏按钮也常在中央。
            // cooldownMs：点掉开屏后往往会进到下一个页面，那里若也有同一个词（如 App 名），
            // 不设冷却就会连着点第二下，所以给 3 秒
            if (hits.isNotEmpty()) {
                rules.add(
                    SkipRule(id = "kw:$pkg", patterns = hits, allowCenter = true, cooldownMs = 3000L),
                )
            }
            kwScanner = if (rules.isEmpty()) null else NodeScanner(rules, factsCache, excludes).also {
                it.verbose = Log.isLoggable(TAG, Log.VERBOSE)
            }
            kwPkg = pkg
        }
        return kwScanner?.takeIf { kwPkg == pkg }
    }

    /**
     * 只在属于 [pkg] 的窗口里找规则目标。
     *
     * 没有该 App 的节点规则（点击来自结构路径或坐标规则）时直接返回 null，
     * 不拿别的规则去凑——凑出来的那个控件没人要，补刀点下去就是误点。
     */
    private fun findUserTargetInAnyWindow(pkg: String, ruleId: String? = null): Triple<AccessibilityNodeInfo, String, String>? {
        val scanner = userScanner?.takeIf { userScannerPkg == pkg } ?: return null
        val (w, h) = realScreenPx()
        // getWindows() 是平台类型，服务刚起来时可能为 null
        val wins = runCatching { windows?.toList() ?: emptyList() }.getOrDefault(emptyList())
        for (window in wins) {
            val root = window.root ?: continue
            if (root.packageName?.toString() != pkg) continue
            val hit = scanner.findSkipTarget(root, w, h) ?: continue
            // 只认触发本次动作的那条规则：点完跳到新页面后，扫描器已换成新页面的规则，
            // 拿新规则去「核验」会命中一个新目标然后补刀——那是实打实的跨页误点
            if (ruleId != null && hit.second.id != ruleId) continue
            return Triple(hit.first, pkg, "win#${window.id}")
        }
        return null
    }

    /**
     * 是不是内置词表 / 手输关键字这一类规则。
     *
     * 这两族都不在 [userScanner] 里（内置词表在 [kwScanner]，手输词也在同一个扫描器里，
     * 规则 id 形如 `kw:<pkg>`），所以核验时不能用 findUserTargetInAnyWindow。
     */
    private fun isBuiltinRuleId(ruleId: String): Boolean =
        ruleId.startsWith("kw:") || ruleId in BuiltinRules.ids

    /**
     * 核验内置规则 / 手输关键字命中：用同一个扫描器在同一批窗口里再找一遍。
     *
     * 返回非 null 表示「目标还在」，调用方会补刀或放弃；返回 null 才算真的跳掉了。
     * 注意这里必须按 [ruleId] 过滤：点完页面可能已经换掉，扫描器会按新页面重建规则，
     * 拿新规则的结果去判「还在」会命中一个不相干的新目标，然后补刀——跨页误点。
     */
    private fun findBuiltinTarget(
        pkg: String,
        ruleId: String,
        rectKey: String,
    ): Triple<AccessibilityNodeInfo, String, String>? {
        // 内置词表被用户关掉时无从复核，退回旧行为（按已跳过处理）
        if (!EnginePrefs.builtinWordsEnabled(this)) return null
        val scanner = keywordScannerFor(pkg) ?: return null
        val (w, h) = realScreenPx()
        val roots = ArrayList<AccessibilityNodeInfo>(3)
        currentRootOf(pkg)?.let { roots.add(it) }
        val wins = runCatching { windows?.toList() ?: emptyList() }.getOrDefault(emptyList())
        for (window in wins) {
            val r = runCatching { window.root }.getOrNull() ?: continue
            if (runCatching { r.packageName?.toString() }.getOrNull() != pkg) continue
            if (roots.any { sameRoot(it, r) }) continue
            roots.add(r)
        }
        for (r in roots) {
            val hit = runCatching { scanner.findSkipTarget(r, w, h) }.getOrNull() ?: continue
            if (hit.second.id != ruleId) continue
            // 同一条规则命中了另一个位置：那不是「按钮没点掉」，是页面上别处出现了同样的字
            if (rectKey.isNotEmpty() && rectKeyOf(hit.first) != rectKey) {
                traceV("verify-other $pkg 规则=$ruleId 位置不同")
                continue
            }
            return Triple(hit.first, pkg, "builtin")
        }
        return null
    }

    /**
     * 点完之后是不是被带进了同包的广告落地页。
     *
     * 判据三件事同时成立：
     * 1. 目标已经消失（调用方已在 `found == null` 分支里，这里不再重复判）；
     * 2. 前台仍是同一个包——跨包由 `off-app` 那条判据负责，这里只管它管不到的情形；
     * 3. 点击后 3 秒内，且页面上出现下载/安装类 CTA。
     *
     * 为什么不用 Activity 名判「换页」：实测部分 App 的 WSC 事件不带类名，
     * `lastActivityClass` 一直是空的（受控 App 的 `hot-open` 日志里 `act=` 就是空），
     * 靠它判断换页等于这条判据永不生效。
     *
     * 只认「立即下载」这类广告落地页专属说法，不收「打开」「查看」「购买」——
     * 那些在正常首页上到处都是，收进来会让真跳过也不记账。
     */
    private fun landedOnAdPage(p: PendingSkip, now: Long): Boolean {
        // 只在点击后 3 秒内看：再往后用户可能自己点了别的地方，
        // 那时页面上的东西不该算在这次点击头上
        val since = if (lastSkipAt > 0) now - lastSkipAt else Long.MAX_VALUE
        if (since > LANDING_CHECK_MS) return false
        if (foregroundPackage() != p.pkg) return false
        val root = currentRootOf(p.pkg) ?: return false
        return containsAdCta(root, 0)
    }

    /**
     * 这一页有没有广告迹象——**只服务统计口径，不参与点击**。
     *
     * 三条任一成立就算「像广告」，判定故意偏宽：判宽了顶多多记一次，
     * 判窄了会把真广告当成过渡页从统计里撤掉。
     *
     * 1. 命中节点本身或往上三层的资源名带广告 SDK 前缀；
     * 2. 树里出现「广告 / 推广 / Sponsored」这类字样；
     * 3. 树里出现 1–3 位纯数字（倒计时）。
     *
     * 实测依据（0.17.52 那次清理大师）：点在启动页自带的「跳过」上，页面里既没有
     * SDK 资源名，也没有广告字样，几毫秒后真正的 `SplashAdActivity` 才出现。
     */
    private fun hasAdEvidence(root: AccessibilityNodeInfo?, hit: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = hit
        var depth = 0
        while (cur != null && depth < 4) {
            val vid = runCatching { cur?.viewIdResourceName }.getOrNull()
            if (vid != null && hasAdPrefix(vid)) return true
            cur = runCatching { cur?.parent }.getOrNull()
            depth++
        }
        if (root == null) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < AD_EVIDENCE_MAX_NODES) {
            val n = queue.removeFirst()
            visited++
            val t = runCatching { n.text?.toString() }.getOrNull()?.trim()
            if (!t.isNullOrEmpty() && t.length <= 6) {
                if (AD_WORDS.any { w -> t.contains(w, ignoreCase = true) }) return true
                if (t.length <= 3 && t.all { it.isDigit() }) return true
            }
            val vid = runCatching { n.viewIdResourceName }.getOrNull()
            if (vid != null && hasAdPrefix(vid)) return true
            val c = runCatching { n.childCount }.getOrDefault(0)
            for (i in 0 until minOf(c, 20)) {
                runCatching { n.getChild(i) }.getOrNull()?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun hasAdPrefix(vid: String): Boolean {
        val v = vid.lowercase(java.util.Locale.ROOT)
        return BuiltinRules.AD_EVIDENCE_PREFIXES.any { v.contains(it) }
    }

    private fun containsAdCta(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 30) return false
        val t = runCatching { node.text?.toString() }.getOrNull()
        if (!t.isNullOrEmpty() && AD_LANDING_CTA.any { t.contains(it) }) return true
        val n = runCatching { node.childCount }.getOrDefault(0)
        for (i in 0 until n) {
            val c = runCatching { node.getChild(i) }.getOrNull() ?: continue
            if (containsAdCta(c, depth + 1)) return true
        }
        return false
    }

    /**
     * 系统控件类名不是 Activity。
     *
     * WSC 事件的 className 在弹浮层 / 列表刷新时会报 android.widget.PopupWindow、
     * androidx.recyclerview.widget.RecyclerView 这类类名。把它当成当前页面记下来，
     * 页面限定的规则就再也匹配不上了。用前缀判断而不是反射 Class.forName——
     * 跨进程加载不到对方的类，反射只会抛异常还慢。
     */
    private fun isSystemUiClass(cn: String): Boolean =
        cn.startsWith("android.") || cn.startsWith("androidx.") ||
            cn.startsWith("com.android.internal.")

    // ———————— 坐标点按 ————————

    private fun schedulePointTaps(pkg: String, r: com.noteatoranges.skipad.db.SkipAppRule) {
        // 开屏窗口里 root 常常还是旧窗口，落点必须在真正要点的时刻算，不能提前算好。
        //
        // 点 4 次而不是 1 次：广告出现时界面还没就绪，第一下常落在入场动画或骨架屏上，
        // 落空之后再没有第二次机会。参考 Android-Touch-Helper 的做法
        // （300ms 起、每 500ms 一次、共 6 次），这里取 0/600/1200/1800 四拍。
        // 闸门只在第一拍记一次账，后三拍是同一轮重试，不该被冷却挡掉。
        var gateOpened = false
        var aborted = false
        POINT_RETRY_OFFSETS.forEachIndexed { i, off ->
            mainHandler.postDelayed({
                // postDelayed 的 runnable 抛异常没有人接：Handler 直接把进程带走。
                // 坐标规则要读跨进程节点、要投递手势，都是会抛的地方
                try {
                    if (aborted) return@postDelayed
                    // 必须和主扫描链路用同一套 root 兜底：部分 ROM 上 rootInActiveWindow 恒为
                    // null，只认它的话坐标规则在那些机器上一次都不会触发（0.17.4 修的是同一个坑）
                    val root = currentRootOf(pkg) ?: return@postDelayed
                    val now = SystemClock.elapsedRealtime()
                    // 盲点时间盒：窗口出现超过 8 秒还在按坐标点，说明早就不是开屏了，
                    // 这时候点下去是拿用户的操作界面赌运气。只作用于坐标规则——
                    // 按文字/控件匹配的规则有自己的形状闸，不需要这层
                    val born = lastStateChangeAt[pkg]
                    if (born != null && now - born > POINT_MAX_WINDOW_AGE_MS) {
                        traceD("point-skip ${r.id} $pkg 窗口已 ${now - born}ms，超过盲点时间盒")
                        aborted = true
                        return@postDelayed
                    }
                    if (i == 0) {
                        if (!gate("$pkg|${r.id}", r.cooldownMs, r.maxCount, now)) {
                            aborted = true
                            return@postDelayed
                        }
                        gateOpened = true
                    } else if (!gateOpened) {
                        return@postDelayed
                    }
                    val point = resolvePoint(root, r) ?: return@postDelayed
                    val fired = when (r.action) {
                        SkipRule.ACTION_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
                        SkipRule.ACTION_NONE -> {
                            traceD("point-match-only ${r.id} $pkg")
                            true
                        }
                        else -> clicker?.clickPoint(point.first, point.second) ?: false
                    }
                    traceD("point#${i + 1} ${r.id} ${r.actionLabel} $pkg ok=$fired" +
                            " (${(point.first / resources.displayMetrics.widthPixels * 100).toInt()}%," +
                            "${(point.second / resources.displayMetrics.heightPixels * 100).toInt()}%)",
                    )
                } catch (t: Throwable) {
                    aborted = true
                    traceW("point-fail ${r.id} $pkg", t)
                }
            }, r.delayMs + off)
        }
    }

    // ———————— 快照 ————————

    private fun postWorkNotification() {
        val pi = android.app.PendingIntent.getBroadcast(
            this, 0,
            Intent(ACTION_TAKE_SNAPSHOT).setPackage(packageName),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, SNAP_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_keepalive)
            .setContentTitle(getString(R.string.snap_notif_title))
            .setContentText(getString(R.string.snap_notif_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            // 整条通知都能点：以前只有右侧那个 action 能点，很多人压根看不到它
            .setContentIntent(pi)
            .addAction(R.drawable.ic_stat_keepalive, getString(R.string.snap_action), pi)
            .build()
        try {
            val nm = androidx.core.app.NotificationManagerCompat.from(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nmSys = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                nmSys.createNotificationChannel(
                    android.app.NotificationChannel(
                        SNAP_CHANNEL, getString(R.string.snap_channel),
                        android.app.NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply {
                        setShowBadge(false)
                        setSound(null, null)
                    }
                )
                // 旧渠道建成了 IMPORTANCE_LOW：折叠时连 action 按钮都不显示，
                // 抓快照这条唯一不装悬浮窗的入口等于被藏起来。渠道重要性创建后改不了，
                // 只能换新 id，旧渠道直接删掉
                runCatching { nmSys.deleteNotificationChannel("snapshot") }
            }
            nm.notify(SNAP_NOTIF_ID, n)
        } catch (e: SecurityException) {
        }
    }

    /** 抓快照：截图 + 控件树 JSON 落盘（GKD 同款），供点选生成规则 */
    /** 抓快照：从通知栏触发时前台是通知栏自身——先收起通知栏再截屏抓树（GKD 用悬浮窗同理） */
    private fun takeSnapshot() {
        val shadeUp = rootInActiveWindow?.packageName?.toString() == "com.android.systemui"
        if (shadeUp) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            mainHandler.postDelayed({ captureNow() }, 600)
        } else {
            captureNow()
        }
    }

    /**
     * 候选窗口，按「别的 App 优先 → 面积大的优先」排序。
     *
     * 只挑一个活动窗口会踩坑：货拉拉首页上有个空白 tooltip 弹窗，
     * `rootInActiveWindow` 正好指向它，抓下来不到 3 个节点就报「没抓到内容」。
     * 主窗口面积大、节点多，就算排在后面也会被合并进来。
     */
    private fun captureCandidates(): List<Pair<Int, AccessibilityNodeInfo>> {
        class Cand(val id: Int, val node: AccessibilityNodeInfo, val area: Int, val self: Boolean)

        val cands = ArrayList<Cand>(8)
        val seen = HashSet<Int>()

        fun add(id: Int, node: AccessibilityNodeInfo?, area: Int) {
            if (node == null) return
            val p = node.packageName?.toString()
            if (p == SYSTEM_UI_PKG) return
            if (!seen.add(id)) return
            // 包名读不到（PopupWindow 常见）也收，交给调用方用 lastFgPkg 补
            cands.add(Cand(id, node, area, p == packageName))
        }

        val wins = runCatching { windows?.toList() }.getOrNull() ?: emptyList()
        for (win in wins) {
            val r = runCatching { win.root }.getOrNull() ?: continue
            val rect = Rect()
            runCatching { win.getBoundsInScreen(rect) }
            add(win.id, r, rect.width() * rect.height())
        }
        if (cands.isEmpty()) {
            add(0, runCatching { rootInActiveWindow }.getOrNull(), 0)
        }

        return cands
            .sortedWith(compareBy<Cand> { it.self }.thenByDescending { it.area })
            .map { it.id to it.node }
    }

    /**
     * 抓不到窗口时把现场写进外部目录。
     * release 包读不到 logcat（部分 ROM 直接关掉），这里是唯一能事后查的证据。
     */
    private fun dumpSnapFailure(attempt: Int) {
        runCatching {
            val base = getExternalFilesDir(null) ?: filesDir
            val dir = File(base, "diag")
            dir.mkdirs()
            val sb = StringBuilder()
            sb.append("time=").append(System.currentTimeMillis()).append('\n')
            sb.append("attempt=").append(attempt).append('\n')
            val act = runCatching { rootInActiveWindow }.getOrNull()
            sb.append("activeRoot pkg=").append(act?.packageName)
                .append(" cls=").append(act?.className).append('\n')
            val wins: List<AccessibilityWindowInfo> =
                runCatching { windows?.toList() }.getOrNull() ?: emptyList()
            sb.append("windowCount=").append(wins.size).append('\n')
            for ((i, w) in wins.withIndex()) {
                val wr = runCatching { w.root }.getOrNull()
                // AccessibilityWindowInfo 只有 getBoundsInScreen(Rect)，没有 getBounds()
                val rect = Rect()
                runCatching { w.getBoundsInScreen(rect) }
                sb.append("win#").append(i)
                    .append(" id=").append(w.id)
                    .append(" type=").append(w.type)
                    .append(" active=").append(w.isActive)
                    .append(" pkg=").append(wr?.packageName)
                    .append(" rootNull=").append(wr == null)
                    .append(" bounds=").append(rect)
                    .append('\n')
            }
            File(dir, "snapfail_${System.currentTimeMillis()}.txt").writeText(sb.toString())
        }
    }

    /**
     * 只存截图的降级路径。
     *
     * 有些应用（货拉拉企业版）在主界面把无障碍遍历整个关掉了，节点树根本读不到；
     * 但截图仍可截，用户长按截图取点就能建坐标规则——坐标规则不依赖控件树。
     */
    private fun captureScreenshotOnly(attempt: Int, pkg: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            dumpSnapFailure(attempt)
            Toast.makeText(this, R.string.snap_too_few, Toast.LENGTH_SHORT).show()
            return
        }
        // pkg 必须用调用方算好的那个：这里如果重新读 lastFgPkg，命不中就会存成 unknown，
        // 而规则里的 app 字段就是它——unknown 的规则永远不会生效
        val act = lastActivityClass[pkg] ?: ""
        val appCtx = applicationContext
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor,
            object : TakeScreenshotCallback() {
                override fun onResult(bitmap: Bitmap?, srcW: Int, srcH: Int) {
                    if (bitmap == null) {
                        Toast.makeText(appCtx, R.string.snap_shot_failed, Toast.LENGTH_SHORT).show()
                        return
                    }
                    val shotW = if (srcW > 0) srcW else resources.displayMetrics.widthPixels
                    val shotH = if (srcH > 0) srcH else resources.displayMetrics.heightPixels
                    Io.run("snapshot-save") {
                        SnapshotStore.save(appCtx, pkg, act, emptyList(), bitmap, shotW, shotH)
                        bitmap.recycle()
                        StatsStore.logEvent(appCtx, "快照（仅截图）：$pkg")
                    }
                    Toast.makeText(appCtx, R.string.snap_shot_only, Toast.LENGTH_LONG).show()
                }
            })
    }

    private fun captureNow(attempt: Int = 0) {
        // 抓快照会在多处被 postDelayed 调度（收起通知栏后、重试等待后），
        // 那些回调里抛异常同样会带走进程。统一在这里兜住
        try {
            captureNowUnsafe(attempt)
        } catch (t: Throwable) {
            traceW("capture-fail attempt=$attempt", t)
            bubbleToast("抓取失败：${t.javaClass.simpleName}")
        }
    }

    private fun captureNowUnsafe(attempt: Int = 0) {
        // 再试一次拿前台包名：hide 之后活动窗口就是目标 App
        runCatching { rootInActiveWindow?.packageName?.toString() }
            .getOrNull()
            ?.takeIf { it != packageName && it != SYSTEM_UI_PKG }
            ?.let { lastFgPkg = it }
        val candidates = captureCandidates()
        if (candidates.isEmpty()) {
            // 点悬浮钮会先把圆钮藏起来，这一瞬间窗口在重排，
            // rootInActiveWindow 与 window.getRoot() 都可能返回 null。
            // 以前这里直接报错，HyperOS + Android 17 上这就是「抓不到」的主因。
            if (attempt < CAPTURE_MAX_RETRY) {
                mainHandler.postDelayed({ captureNow(attempt + 1) }, CAPTURE_RETRY_DELAY_MS)
                return
            }
            // 一个窗口都拿不到（应用禁了无障碍，连自己的悬浮钮也刚被移除）：
            // 只要知道前台是哪个 App，就还能只存截图
            val fg = lastFgPkg
            traceI("capture-empty attempts=$attempt fg=$fg")
            captureScreenshotOnly(attempt, fg ?: UNKNOWN_PKG)
            return
        }
        // 包名优先取「不是自己」的窗口；取不到再用最后记录的前台包；
        // 两者都没有（应用把无障碍整个屏蔽了，连事件都不发）时存成 unknown，
        // 让用户在快照详情页手动指定目标应用——不能在这里直接报错把路堵死
        val otherPkg = candidates.firstOrNull {
            it.second.packageName?.toString() != packageName
        }?.second?.packageName?.toString()
        val pkg = otherPkg ?: lastFgPkg ?: UNKNOWN_PKG
        traceI("capture attempt=$attempt candidates=${candidates.size} pkg=$pkg otherPkg=$otherPkg")
        val act = lastActivityClass[pkg] ?: ""
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels

        // 同一个 App 可能有多个窗口（主窗口 + 浮层 / 对话框），全都抓：
        // 应用内弹窗的关闭钮经常就在独立浮层窗口里，只抓活动窗口会漏掉它。
        // 只收本 App 的窗口（包名读不到的也算），避免把输入法、别的 App 的悬浮窗混进来。
        val windowRoots = ArrayList<Pair<Int, AccessibilityNodeInfo>>(4)
        for ((id, r) in candidates) {
            val p = r.packageName?.toString()
            if (p != null && p != pkg) continue
            windowRoots.add(id to r)
            if (windowRoots.size > SNAPSHOT_MAX_WINDOWS) break
        }
        if (windowRoots.isEmpty()) {
            // 目标 App 的窗口一个都拿不到（它自己把无障碍遍历禁了）：只存截图，
            // 不能拿自己的悬浮钮根充数——那会把大小姐的界面当成目标 App 存进去
            captureScreenshotOnly(attempt, pkg)
            return
        }

        val nodes = ArrayList<SnapshotStore.Node>(SNAPSHOT_MAX_NODES)
        var idx = 0
        for ((winId, wroot) in windowRoots) {
            if (nodes.size >= SNAPSHOT_MAX_NODES) break
            val queue = ArrayDeque<Pair<AccessibilityNodeInfo, String>>()
            queue.add(wroot to "")
            var perWin = 0
            while (queue.isNotEmpty() && nodes.size < SNAPSHOT_MAX_NODES && perWin < SNAPSHOT_MAX_PER_WINDOW) {
                val (node, path) = queue.removeFirst()
                perWin++
                idx++
                val r = Rect()
                node.getBoundsInScreen(r)
                // 负宽高（滚动/动画中间态、WebView 未布局）在快照里没有意义，
                // 还会让用户选中一个点不到的节点，直接不收
                if (r.width() <= 0 || r.height() <= 0) {
                    for (i in 0 until node.childCount) {
                        val child = node.getChild(i) ?: continue
                        queue.add(child to if (path.isEmpty()) "$i" else "$path/$i")
                    }
                    continue
                }
                nodes.add(
                    SnapshotStore.Node(
                        idx, node.className?.toString() ?: "",
                        node.text?.toString() ?: "", node.contentDescription?.toString() ?: "",
                        node.viewIdResourceName ?: "", node.isClickable,
                        r.left, r.top, r.right, r.bottom,
                        path, winId,
                    )
                )
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    queue.add(child to if (path.isEmpty()) "$i" else "$path/$i")
                }
            }
        }
        // 系统界面、加载中的空白页只能抓到一两个节点，存下来是垃圾。
        // 也可能是这一帧只有个空壳弹窗——给重试机会，主窗口的树稍后才就绪
        traceI("capture nodes=${nodes.size} pkg=$pkg windows=${windowRoots.size}")
        if (nodes.size < 3) {
            if (attempt < CAPTURE_MAX_RETRY) {
                mainHandler.postDelayed({ captureNow(attempt + 1) }, CAPTURE_RETRY_DELAY_MS)
                return
            }
            // 还拿不到就是应用自己禁了无障碍遍历（货拉拉企业版主界面就是，
            // 系统级 uiautomator 一样读不到）。降级：只存截图，用户仍能长按取点建坐标规则
            captureScreenshotOnly(attempt, pkg)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor,
                object : TakeScreenshotCallback() {
                    override fun onResult(bitmap: Bitmap?, srcW: Int, srcH: Int) {
                        val appCtx = applicationContext
                        val nodeCount = nodes.size
                        // 宽高用截图自己的尺寸，不是 displayMetrics：
                        // displayMetrics 给的是「应用可用高度」(1220x2453)，不含导航栏；
                        // 截图是整屏 (1220x2712)。拿 2453 当纵向基准，越靠下的控件
                        // 偏差越大（旧快照的框「越往下越偏下」就是这么来的）。
                        val shotW = if (srcW > 0) srcW else w
                        val shotH = if (srcH > 0) srcH else h
                        Io.run("snapshot-save") {
                            val dir = SnapshotStore.save(appCtx, pkg, act, nodes, bitmap, shotW, shotH)
                            // 全屏图 1220×2712 一张 13MB，compress 完就没人用了，
                            // 不主动回收会一直压在 native heap，抓几次就积几十 MB
                            bitmap?.recycle()
                            StatsStore.logEvent(appCtx, "快照已保存：$pkg（$nodeCount 节点）")
                            traceD("snapshot saved ${dir.path} nodes=$nodeCount ${shotW}x$shotH")
                        }
                        Toast.makeText(appCtx, R.string.snap_saved, Toast.LENGTH_SHORT).show()
                    }
                })
        } else {
            val appCtx = applicationContext
            val nodeCount = nodes.size
            val (realW, realH) = realScreenPx()
            Io.run("snapshot-save") {
                SnapshotStore.save(appCtx, pkg, act, nodes, null, realW, realH)
                StatsStore.logEvent(appCtx, "快照已保存：$pkg（$nodeCount 节点，无截图）")
            }
            Toast.makeText(appCtx, R.string.snap_saved, Toast.LENGTH_SHORT).show()
        }
    }

    /** takeScreenshot 回调适配（API 30+），HardwareBuffer 转 Bitmap */
    private inner abstract class TakeScreenshotCallback :
        AccessibilityService.TakeScreenshotCallback {
        override fun onFailure(errorCode: Int) {
            traceW("takeScreenshot failed: $errorCode")
            // 截图失败原来只写日志：用户点了没反应，也不知道为什么
            com.noteatoranges.skipad.util.CaptureBubble.apply(this@SkipAccessibilityService)
            Toast.makeText(
                this@SkipAccessibilityService,
                R.string.snap_shot_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }

        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
            var shotW = 0
            var shotH = 0
            var bitmap: Bitmap? = null
            try {
                // 尺寸要在 close() 之前读：HardwareBuffer 关了之后再取宽高就不保证有效
                shotW = screenshot.hardwareBuffer.width
                shotH = screenshot.hardwareBuffer.height
                val hw = android.graphics.Bitmap.wrapHardwareBuffer(
                    screenshot.hardwareBuffer, screenshot.colorSpace
                )
                bitmap = hw?.copy(Bitmap.Config.ARGB_8888, false)
                // 硬件位图占的是图形内存，copy 完就没用了
                hw?.recycle()
            } catch (t: Throwable) {
                traceW("screenshot-decode-fail", t)
            } finally {
                // 无论解码成不成功都必须关：一张全屏 HardwareBuffer 是十几 MB 的 native 内存，
                // 上面任何一步抛异常时不关就是实打实的泄漏
                runCatching { screenshot.hardwareBuffer.close() }
            }
            try {
                onResult(bitmap, shotW, shotH)
            } catch (t: Throwable) {
                // 回调里还有存盘、转码、Toast 一整套流程，各自都可能抛
                traceW("screenshot-result-fail", t)
            }
        }

        abstract fun onResult(bitmap: Bitmap?, srcW: Int, srcH: Int)
    }

    // ———————— ————————

    private fun dumpTree(root: AccessibilityNodeInfo?, pkg: String, tag: String) {
        root ?: return
        try {
            val dir = File(filesDir, "diag")
            val sb = StringBuilder()
            sb.append("== $tag $pkg ${System.currentTimeMillis()} ==\n")
            for ((i, win) in windows.withIndex()) {
                sb.append("win#$i id=${win.id} type=${win.type} pkg=${win.root?.packageName} active=${win.isActive} focused=${win.isFocused}\n")
            }
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var n = 0
            while (queue.isNotEmpty() && n < 300) {
                val node = queue.removeFirst()
                n++
                val r = Rect()
                node.getBoundsInScreen(r)
                sb.append("[$n] ${node.className} txt=${node.text?.toString()?.take(30)} desc=${node.contentDescription?.toString()?.take(30)} " +
                    "id=${node.viewIdResourceName} click=${node.isClickable} bounds=$r\n")
                for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
            }
            val text = sb.toString()
            val ts = System.currentTimeMillis()
            // 落盘下沉到单线程 IO：构建控件树仍在主线程（要读 AccessibilityNodeInfo），
            // 但写文件不再阻塞无障碍回调
            Io.run("diag-dump") {
                dir.mkdirs()
                // 按修改时间留最新 20 份：文件名前缀有 match_/popup_/giveup_ 三种，
                // 按名字排序会把三类混在一起，可能整类被删而另一类全留着
                dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(20)?.forEach { it.delete() }
                File(dir, "${tag}_$ts.txt").writeText(text)
            }
        } catch (e: Exception) {
            traceW("dumpTree failed", e)
        }
    }

    // ———————— 结构路径规则（纯图标按钮的精确落点）————————

    /** 按结构路径在「本 App 的任一窗口」里找节点：纯图标按钮没有文字/ID 时的主路径 */
    private fun findByPathAnyWindow(pkg: String, path: String, winId: Int = 0): AccessibilityNodeInfo? {
        val roots = ArrayList<Pair<Int, AccessibilityNodeInfo>>(4)
        runCatching {
            windows?.forEach { w ->
                val r = w.root ?: return@forEach
                if (r.packageName?.toString() == pkg) roots.add(w.id to r)
            }
        }
        runCatching { rootInActiveWindow }
            .getOrNull()
            ?.let { if (it.packageName?.toString() == pkg) roots.add(-1 to it) }
        // 优先抓取时那个窗口：同一个 App 的主窗口和浮层窗口路径前缀往往一样，
        // 在错的窗口上解出来会点到另一个控件
        val ordered = if (winId != 0) roots.sortedByDescending { it.first == winId } else roots
        for ((_, r) in ordered) findByPath(r, path)?.let { return it }
        return null
    }

    /**
     * 沿子索引路径下钻，末端节点要有有效矩形才算命中。
     *
     * **必须先判 childCount**：AccessibilityNodeInfo.getChild(i) 在 i 越界时不会返回 null，
     * 而是在系统内部 `LongArray.get` 抛 ArrayIndexOutOfBoundsException，直接把整个无障碍
     * 服务打死（系统随后把它记进 crashed services，不再重新绑定）。0.17.12 在淘宝页反复
     * 崩溃、通知点不动就是这个越界，堆栈在 ArrayUtils.checkBounds。
     */
    private fun findByPath(root: AccessibilityNodeInfo, path: String): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo = root
        for (seg in path.split('/')) {
            val i = seg.toIntOrNull() ?: return null
            val count = runCatching { cur.childCount }.getOrDefault(0)
            if (i < 0 || i >= count) return null
            cur = runCatching { cur.getChild(i) }.getOrNull() ?: return null
        }
        val r = Rect()
        cur.getBoundsInScreen(r)
        return if (r.width() > 0 && r.height() > 0) cur else null
    }

    /**
     * 结构路径规则：先于文字/ID 规则尝试。
     * 纯图标关闭钮（ImageButton，无 text/desc/viewId）关键字和扫描器都拿它没办法，
     * 但快照里记下的「窗口根 → 子索引」路径能精确落回同一个控件。
     */
    private fun tryPathRule(root: AccessibilityNodeInfo, pkg: String, act: String, now: Long): Boolean {
        val rules = RuleStore.pathRules(this, pkg, act)
        if (rules.isEmpty()) return false
        for (r in rules) {
            val node = findByPathAnyWindow(pkg, r.nodePath ?: continue, r.nodeWinId) ?: continue
            // 类名/尺寸校验：页面改版后同一条路径可能指向别的控件，宁可不错
            if (!nodeMatches(r, node)) {
                traceV("path-skip ${r.id} 控件已变（${node.className}）")
                continue
            }
            if (!gate("$pkg|${r.id}", r.cooldownMs, r.maxCount, now)) continue
            if (r.action == SkipRule.ACTION_NONE) {
                traceD("path-match-only ${r.id} path=${r.nodePath} $pkg")
                continue
            }
            val fired = when (r.action) {
                SkipRule.ACTION_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
                else -> clickNodeOnce(node)
            }
            traceD("path-rule ${r.id} path=${r.nodePath} $pkg ok=$fired")
            if (!fired) continue
            val key = "$pkg#path/${r.nodePath}"
            lastSkipWindow = key
            lastSkipPkg = pkg
            lastSkipAt = now
            pending = PendingSkip(
                key, pkg, "路径·${r.nodePath}", 0, 1,
                path = r.nodePath, ruleId = r.id,
            )
            val token = pending
            mainHandler.postDelayed({ if (pending === token) verifySkip() }, VERIFY_DELAY_MS)
            return true
        }
        return false
    }

    /** 路径解析出来的节点是不是当初记录的那个：类名一致 + 尺寸偏差 ≤40% */
    private fun nodeMatches(r: com.noteatoranges.skipad.db.SkipAppRule, node: AccessibilityNodeInfo): Boolean {
        val want = r.nodeCls
        if (!want.isNullOrBlank()) {
            val got = node.className?.toString() ?: ""
            if (got != want) return false
        }
        val wDp = r.nodeWDp
        val hDp = r.nodeHDp
        if (wDp != null && hDp != null && wDp > 0 && hDp > 0) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val d = resources.displayMetrics.density
            val cw = rect.width() / d
            val ch = rect.height() / d
            if (Math.abs(cw - wDp) > wDp * 0.4f || Math.abs(ch - hDp) > hDp * 0.4f) return false
        }
        return true
    }

    companion object {
        const val TAG = "DaxiaojieEngine"

        /** 供悬浮窗开关等外部调用拿到运行中的服务实例（服务存活期非空） */
        val instance: SkipAccessibilityService?
            get() = inst

        @Volatile
        private var inst: SkipAccessibilityService? = null
        /** 事件节流：信息流一次滑动会发几十个内容变化事件，150ms 太密 */
        const val THROTTLE_MS = 300L
        const val COOLDOWN_MS = 4000L

        /** 内容变化事件的最小间隔：开屏既然立刻出现，600ms 能更早命中；单次扫描实测 4–41ms */
        const val CONTENT_THROTTLE_MS = 600L

        /** 找锚点控件的遍历上限：锚点通常就在弹窗里，不用扫全树 */
        const val ANCHOR_SCAN_NODES = 600

        /** 快照总节点上限（改多窗口后从 400 提到 1500，避免大页面被截断） */
        const val SNAPSHOT_MAX_NODES = 1500

        /** 单个窗口的节点上限：防止一个超大窗口把额度吃光，浮层窗口就没机会了 */
        const val SNAPSHOT_MAX_PER_WINDOW = 800

        /** 一次快照最多合并几个窗口（同一 App 的主窗口 + 浮层） */
        const val SNAPSHOT_MAX_WINDOWS = 3

        const val UNBIND_RECOVER_DELAY_MS = 1500L
        const val VERIFY_DELAY_MS = 800L
        const val MAX_RETRY = 2

        /**
         * 落地页判定的时间窗：只在点击后 3 秒内看新页面像不像广告落地页。
         *
         * 再往后用户可能自己点了别的地方，那时页面上的东西不该算在这次点击头上。
         */
        const val LANDING_CHECK_MS = 3000L

        /**
         * 广告落地页的 CTA 说法。
         *
         * 只收「落地页专属」的表达。故意不含「打开」「查看」「购买」「领取」——
         * 这几个在正常首页、商品页、消息页上遍地都是，收进来会让真跳过也不记账。
         */
        val AD_LANDING_CTA = listOf(
            "立即下载", "马上下载", "一键安装", "立即安装", "下载安装", "安装应用", "免费下载",
        )

        /** 记账落盘延迟：一次跳转的多个事件合并成一次写盘 */
        const val HIT_FLUSH_DELAY_MS = 5000L

        /** 未命中原因的最小记录间隔：信息流里一秒能扫十几次，不节流会把日志刷满 */
        const val MISS_TRACE_GAP_MS = 3000L

        /**
         * 开屏窗口：App 从别的 App 切到前台后的这段时间，只在这段时间里允许词表（内置 + 手输）。
         *
         * 用户实测开屏都是"立刻出现"，所以收到 4 秒：冷启动首帧通常 0.5–2 秒，留够余量；
         * 再长就会覆盖到开屏页跳过去之后的主界面，那正是误点开始的地方。
         */
        const val SPLASH_WINDOW_MS = 4000L

        /**
         * 同包续窗的有效期：点击成功后多久之内，还能因为「同包换页」再开一次窗。
         *
         * 360 清理大师实测 `SplashActivity` → `SplashRecommendActivity` 间隔约 490ms。
         * 取 6 秒是给慢速冷启动留余量，不是窗口长度——续窗之后仍然只有
         * [SPLASH_WINDOW_MS] 的扫描时间，而且整个过程只允许发生一次。
         */
        const val RETRY_AFTER_HIT_MS = 6000L

        /**
         * 过渡页回撤的判定窗：两条 verified 之间不超过这个时长，才认为后一条说明
         * 「前一条点的是过渡页」。
         *
         * 依据是实测间隔——清理大师启动页 → `SplashAdActivity` 86ms，360 两段之间
         * 493–1400ms。取 2 秒，比最长实测再留 0.6 秒余量；再长就会把「用户真看了
         * 两个广告」也串起来，把真广告撤掉。
         */
        const val TRANSITION_DEMOTE_MS = 2000L

        /**
         * 「空窗页」学习门槛：某个 Activity 连续这么多次作为续窗落点、整轮零命中，
         * 才不再为它开续窗（design.md 第 21 条）。
         *
         * 一次不够：第二段广告可能因为没填充而暂时扫不到，一次就学等于把偶发当规律。
         */
        const val PLAIN_ACT_HITS = 2

        /** 广告证据遍历的节点上限：开屏页再大也就几百个节点，这里是防呆 */
        const val AD_EVIDENCE_MAX_NODES = 400

        /**
         * 广告证据里的「广告」字样。
         *
         * 只用于统计口径（判这一页像不像广告），不参与点击，所以可以放宽。
         * 不收单字母缩写 "AD"：它会命中 BAD / BADGE 之类的正文词。
         */
        val AD_WORDS = listOf("广告", "推广", "sponsored", "advertisement")

        // —— 开屏热窗口（与 SPLASH_WINDOW_MS 完全对齐，不引入新的时间边界）——
        // 系统事件合并窗口：平时 300ms 省电，热窗口内压到 50ms 抢时间。
        // 50 不是 0：0 会让信息流类应用把每个内容变化都立刻投递，热窗口内只有几秒，
        // 但仍有应用会在开屏页里刷进度条，留 50ms 已经足够快又不至于打满主线程
        const val HOT_NOTIF_TIMEOUT = 50L

        /** 热窗口内的事件节流：平时 300ms，这里 80ms */
        const val HOT_THROTTLE_MS = 80L

        /** 热窗口内的内容变化节流：平时 600ms，这里 120ms */
        const val HOT_CONTENT_THROTTLE_MS = 120L

        /** 主动扫描节拍：不依赖事件，每 120ms 自己取一次 root 扫 */
        const val HOT_SCAN_INTERVAL_MS = 120L

        /**
         * 热窗口内的主动扫描次数上限。
         *
         * 4 秒 / 120ms ≈ 33 次，留点余量取 40。有上限是因为极少数 App 在开屏页
         * 狂发事件，事件路径 + 主动路径叠起来会把主线程占满——而漏几个广告
         * 远好过让用户觉得手机卡了。
         */
        const val HOT_MAX_SCANS = 40

        /**
         * 主窗口没命中时，最多再看几个同包的其它窗口（0.17.37）。
         *
         * 取 2 是因为真实开屏最多「主窗口 + 一个关闭钮浮层」两层；再多说明这不是开屏页，
         * 继续扫只会拿主线程换运气。每个窗口都是一次全树遍历（15~400ms），
         * 且和主扫描共用 [HOT_MAX_SCANS] 预算。
         */
        const val MAX_EXTRA_WINDOWS = 2

        /**
         * 右上角启发式的时间闸：只在切前台后 2.5 秒内启用。
         *
         * 广告的跳过按钮在这之前就该出现；而主界面的右上角按钮（发布、保存、分享）
         * 通常在广告倒计时结束之后才出现。实测过一次反例：桌面被当成开屏页，
         * 右上角的大小姐图标被点中——桌面已经在黑名单外被挡，这道时间闸是第二层。
         */
        const val HOT_CORNER_WINDOW_MS = 2500L

        /**
         * 服务连接后的预热期：这段时间内不开热窗口。
         *
         * 服务重启（换包、手动关开、系统回收后复活）时，屏幕上停的往往是用户正在看的
         * 某个页面，而不是开屏页。这段时间还不清楚"用户是从哪切过来的"，
         * 一上来就按开屏处理等于纯赌博。
         */
        const val SERVICE_WARMUP_MS = 20_000L

        /**
         * 疑似误点后的页面静默期。
         *
         * 核验发现点击后前台换了 App（跳进商店/浏览器）就判为疑似误点，该页面
         * 在这段时间内不再自动动手。十分钟是拍的值：够跨过一次冷启动，又不至于
         * 因为一次偶发判定就把这个 App 永久禁掉。
         */
        const val BAD_PAGE_MS = 10 * 60_000L

        const val SNAP_CHANNEL = "snapshot_v2"
        const val SNAP_NOTIF_ID = 2001
        const val ACTION_TAKE_SNAPSHOT = "com.noteatoranges.skipad.TAKE_SNAPSHOT"

        /** 系统界面窗口：抓快照时要跳过 */
        const val SYSTEM_UI_PKG = "com.android.systemui"

        /** 抓取时认不出目标应用（应用屏蔽了无障碍）时占位，用户可在这之后手动指定 */
        const val UNKNOWN_PKG = "unknown"

        /** 点悬浮钮后等多久再抓：圆钮移除后窗口在重排，马上抓会取到空树 */
        const val BUBBLE_HIDE_DELAY_MS = 350L

        /** 抓不到窗口时的重试次数与间隔（总等待 ≈ 3×320ms，用户基本感觉不到） */
        const val CAPTURE_MAX_RETRY = 3
        const val CAPTURE_RETRY_DELAY_MS = 320L

        /** 坐标规则的重试节拍（相对 delayMs）：一次落空不等于失败，再补三拍 */
        val POINT_RETRY_OFFSETS = longArrayOf(0L, 600L, 1200L, 1800L)

        /** 盲点时间盒：窗口出现超过这个时长就不再按坐标点（正常开屏早该过去了） */
        const val POINT_MAX_WINDOW_AGE_MS = 8000L

        /**
         * 「内容变化兜底」打开开屏窗的最小间隔。
         *
         * 这条路径只用来接住「启动时不发窗口切换事件」的应用（计算器这类）。
         * 一分钟一次足够覆盖冷启动，又能挡住 App 内换页把开屏窗反复重开。
         */
        const val SPLASH_FALLBACK_GAP_MS = 60_000L

        /** 节点矩形去重的分桶宽度（px）：相邻两帧的 bounds 会抖 1~2px，精确相等去不掉重复 */
        const val RECT_BUCKET_PX = 8

        /**
         * 右上角启发式命中的"虚拟规则"。
         *
         * 它不是一条用户规则，只用来借 [gate] 的冷却与次数上限：每个 App 每次切前台
         * 最多点一次右上角小控件。maxCount=1 配合 [onAppSwitched] 的清零，
         * 保证同一次开屏里不会连点两下。
         */
        val CORNER_RULE = SkipRule(id = "corner-skip", cooldownMs = 3000L, maxCount = 1)

        /** 系统包与噪声大户：直接跳过节点遍历（李跳跳同款"先粗筛再遍历"思路） */
        val PACKAGE_BLACKLIST = setOf(
            "com.android.systemui",
            "com.android.launcher3",
            "com.miui.home",
            "com.huawei.android.launcher",
            "com.sec.android.app.launcher",
            "com.google.android.apps.nexuslauncher",
            // 系统设置与权限安装器（0.17.38）：统计里出现过一条「设置 1 次」，说明
            // com.android.settings 在开屏窗口内出现过「跳过」字样并被点了。系统页
            // 没有广告，被当开销毁的是用户自己的设置项；权限弹窗更危险——那里点错
            // 就是替用户点了「允许」。整族拉黑，宁可不认这些窗口。
            "com.android.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.lbe.security.miui",           // MIUI 权限弹窗宿主
        )
    }

    @Volatile
    private var blacklist: Set<String> = PACKAGE_BLACKLIST
}
