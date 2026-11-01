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
import com.noteatoranges.skipad.core.NodeFactsCache
import com.noteatoranges.skipad.core.NodeScanner
import com.noteatoranges.skipad.core.SkipRule
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
 * - 诊断：TAG=SkipAdEngine；控件树落盘 filesDir/diag/。
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

    /** 快照广播只注册一次：onServiceConnected 可能被系统重复回调，重复注册会导致一次点击抓多张 */
    private var snapshotRegistered = false

    /** 上次执行动作的窗口键与时间：同窗口冷却期内不重复匹配 */
    private var lastSkipWindow: String? = null
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

    private data class PendingSkip(
        val windowKey: String,
        val pkg: String,
        val detail: String,
        var attempts: Int,
        /** 补刀上限：目标没消失才补，弹窗/路径类只补一次 */
        val maxRetry: Int = MAX_RETRY,
    )

    /** 每个 App 最近一次"新窗口出现"的时间与前台 Activity 类名 */
    private val lastStateChangeAt = HashMap<String, Long>()
    private val lastActivityClass = HashMap<String, String>()

    /** 本页面已点过的控件矩形（分桶后的键），换页面清空。见 [clickNodeOnce] */
    private val clickedRects = HashSet<String>()

    private val mainExecutor = Executor { mainHandler.post(it) }

    private val snapshotReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_TAKE_SNAPSHOT) takeSnapshot()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 连接留痕：装了新包之后服务是否真的起来了，只能靠这一行判断
        val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        Log.i(TAG, "service-connected $ver")
        clicker = ClickPerformer(this)
        blacklist = PACKAGE_BLACKLIST + findHomePackages()
        KeepAlive.scheduleJob(this)
        StatsStore.logEvent(this, getString(R.string.ka_log_connected))
        KeepAlive.markConnected(this)
        postWorkNotification()
        inst = this
        // 服务重启后（换包、重新绑定）先补一次前台包名，不然要等到下一次切页事件才有值。
        // 目标 App 禁了无障碍读不到窗口时，降级抓取就靠这个值认 App
        runCatching { rootInActiveWindow?.packageName?.toString() }
            .getOrNull()
            ?.takeIf { it != packageName && it != SYSTEM_UI_PKG }
            ?.let { lastFgPkg = it }
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
        Log.i(TAG, "service-unbind")
        val appContext = applicationContext
        Thread {
            SystemClock.sleep(UNBIND_RECOVER_DELAY_MS)
            KeepAlive.checkAndRecover(appContext)
        }.start()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
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
            sendBroadcast(Intent(ACTION_TAKE_SNAPSHOT).setPackage(packageName))
        }, BUBBLE_HIDE_DELAY_MS)
        mainHandler.postDelayed({
            if (com.noteatoranges.skipad.util.CaptureBubble.isEnabled(this)) {
                com.noteatoranges.skipad.util.CaptureBubble.apply(this)
                com.noteatoranges.skipad.util.CaptureBubble.onClick = { bubbleClicked() }
                com.noteatoranges.skipad.util.CaptureBubble.onLongClick = { bubbleLongClicked() }
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
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        if (pkg == null || root == null) {
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
            Log.d(
                TAG,
                "manual-point ${r.id} ${r.actionLabel} $pkg" +
                    " (${(point.first / resources.displayMetrics.widthPixels * 100).toInt()}%," +
                    "${(point.second / resources.displayMetrics.heightPixels * 100).toInt()}%) ok=$fired",
            )
        }
        bubbleToast("已执行 ${rules.size} 条坐标规则（成功 $ok）")
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
            Log.d(TAG, "anchor-miss ${r.id} vid=${r.anchorVid} text=${r.anchorText}")
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
            Log.v(TAG, "gate-max $key ${st.count}/$maxCount")
            return false
        }
        if (cooldownMs > 0 && st.lastAt > 0 && now - st.lastAt < cooldownMs) {
            Log.v(TAG, "gate-cd $key ${now - st.lastAt}ms/${cooldownMs}ms")
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
            Log.v(TAG, "click-dup $key")
            return false
        }
        val ok = clicker?.click(node) == true
        // 只在真正点出去之后才记：点失败（节点失效等）还应该允许下次再试
        if (ok) clickedRects.add(key)
        return ok
    }

    /** 换 App / 换页面：清节点属性缓存与规则计数，并强制重建扫描器 */
    private fun onPageChanged(pkg: String, activity: String) {
        if (stateActivity[pkg] == activity) return
        stateActivity[pkg] = activity
        factsCache.clear()
        val prefix = "$pkg|"
        ruleState.keys.filter { it.startsWith(prefix) }.forEach { ruleState.remove(it) }
        userScannerKey = null
        // 同页面重复 WSC 会在上面 early return，这里清空只在真的换页面时发生
        clickedRects.clear()
    }

    private fun bubbleToast(msg: String) {
        mainHandler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val type = event.eventType

        // —— 坐标点按调度（必须最先做）：冷启动切换瞬间 rootInActiveWindow 往往还是
        //    旧窗口/桌面，root 包名判定会误入黑名单分支；这里用 event.packageName（准）。
        //    此路径只写 map，零开销，不做时间节流（切换事件常在 4ms 内连发）——
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val ep = event.packageName?.toString()
            if (ep != null && ep != packageName) {
                if (lastStateChangeAt.size > 50) lastStateChangeAt.clear()
                val prevShown = lastShownPkg
                // 「切到这个 App」：前一个出现在屏幕上的是别的包（含桌面）就算
                val freshSwitch = prevShown != ep
                lastShownPkg = ep
                // 开屏窗口只从「真的切到这个 App 的那一刻」起算：开屏页跳主界面会再来一个
                // WSC，若每次都刷新，主界面前 6 秒也会被当成开屏——那正是误点的来源
                if (freshSwitch) lastStateChangeAt[ep] = SystemClock.elapsedRealtime()
                // className 不一定就是 Activity：弹浮层 / 列表刷新时 WSC 事件报的是
                // android.widget.PopupWindow、androidx...RecyclerView 这类系统控件类名。
                // 直接记下来会让「页面限定」的规则永远失配（activityMatches 按类名后缀比）
                val cn = event.className?.toString()
                val realCn = cn?.takeIf { it.isNotBlank() && !isSystemUiClass(it) }
                val act = realCn ?: lastActivityClass[ep].orEmpty()
                realCn?.let { lastActivityClass[ep] = it }
                // 换页：清属性缓存、清规则计数、下次事件重建扫描器
                onPageChanged(ep, act)
                if (freshSwitch && ep !in blacklist) {
                    // 用户 point 规则优先
                    RuleStore.pointRules(this, ep, act).forEach { r -> schedulePointTaps(ep, r) }
                    if (ep in CORNER_TAP_PKGS) scheduleCornerTaps(ep)
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
            if (ep != null && ep != packageName && ep != SYSTEM_UI_PKG) {
                lastFgPkg = ep
                if (ep != lastShownPkg && ep !in blacklist) {
                    lastShownPkg = ep
                    lastStateChangeAt[ep] = SystemClock.elapsedRealtime()
                }
            }
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastHandleAt < THROTTLE_MS) return
        lastHandleAt = now

        // 内容变化事件在信息流里一秒能来几十个，每次都要全树走一遍无障碍节点，
        // 是耗电主因。命中只取决于控件是否还在，下一次切页/内容变化都会补上
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (now - lastContentAt < CONTENT_THROTTLE_MS) return
            lastContentAt = now
        }

        // rootInActiveWindow 在部分 ROM 上会是 null（窗口列表却可读），抓快照走的就是
        // windows 那条路。这里补一个同源的兜底，否则整条规则链路会静默不工作
        val rootRaw = rootInActiveWindow
        val root = rootRaw ?: activeWindowRoot(event.packageName?.toString()) ?: return
        val pkg = root.packageName?.toString() ?: event.packageName?.toString() ?: return
        if (pkg in blacklist || pkg == packageName) return

        // 开屏窗口：只从「真的切到这个 App 的那一刻」起算 SPLASH_WINDOW_MS。
        // 窗口内才允许内置关键字；窗口外只认快照生成的用户规则
        val sinceSwitch = lastStateChangeAt[pkg]
        val splash = sinceSwitch != null && now - sinceSwitch <= SPLASH_WINDOW_MS

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
        if (tryPathRule(root, pkg, act, now)) return

        // 开屏窗口内：用户规则优先，没命中再走词表（内置默认词 + 手输命中/排除词）；
        // 窗口外：只认用户规则，两样都没有就一条都不扫
        val userSc = userScanner?.takeIf { userScannerPkg == pkg }
        val kwSc = if (splash) keywordScannerFor(pkg) else null
        if (userSc == null && kwSc == null) return

        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels

        // 同窗口冷却：刚点过的窗口在冷却期内不再重复匹配
        if (lastSkipWindow != null && now - lastSkipAt < COOLDOWN_MS &&
            lastSkipWindow == windowKeyFor(pkg, event, root)
        ) {
            return
        }

        val t0 = SystemClock.elapsedRealtime()
        var hit = userSc?.findSkipTarget(root, w, h)
        var dbg = userSc?.lastDebug
        if (hit == null && kwSc != null) {
            hit = kwSc.findSkipTarget(root, w, h)
            dbg = kwSc.lastDebug
        }
        // 扫描耗时：决定要不要给「每 App 事件次数」加预算时必须看这个数，
        // 不能凭感觉（上限是 TRAVERSE_TIMEOUT_MS=400ms）
        val cost = SystemClock.elapsedRealtime() - t0
        if (hit == null) {
            Log.v(TAG, "scan $pkg ${cost}ms: $dbg")
            return
        }
        val detail = hit.first.text?.toString()
            ?: hit.first.contentDescription?.toString()
            ?: hit.first.viewIdResourceName
            ?: hit.second.id
        Log.d(TAG, "match $pkg ${cost}ms: $dbg")

        val rule = hit.second
        // 冷却 / 次数上限：拦在这里，误点的代价远大于漏跳
        if (!gate("$pkg|${rule.id}", rule.cooldownMs, rule.maxCount, now)) return
        if (rule.action == SkipRule.ACTION_NONE) {
            Log.d(TAG, "match-only $pkg 「$detail」 规则=${rule.id}")
            return
        }
        // 这个位置本轮已经点过了：直接放过。放在 dumpTree 之前——否则每个后续事件
        // 都会重写一份诊断树，并且走到 action-fail 把自己的日志污染成「点击失败」
        if (rectKeyOf(hit.first) in clickedRects) {
            Log.v(TAG, "match-dup $pkg 规则=${rule.id}")
            return
        }
        dumpTree(root, pkg, "match")
        val fired = when (rule.action) {
            SkipRule.ACTION_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            else -> clickNodeOnce(hit.first)
        }
        if (!fired) {
            Log.d(TAG, "action-fail $pkg ${rule.action} 规则=${rule.id}")
            return
        }
        lastSkipWindow = windowKeyFor(pkg, event, root)
        lastSkipAt = now
        pending = PendingSkip(lastSkipWindow ?: "", pkg, detail, 0)
        val token = pending
        mainHandler.postDelayed({ if (pending === token) verifySkip() }, VERIFY_DELAY_MS)
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
        val p = pending ?: return
        val found = findUserTargetInAnyWindow(p.pkg)
        if (found == null) {
            val appCtx = applicationContext
            Io.run("stats-insert") { StatsDb.get(appCtx).insert(p.pkg, p.detail) }
            Log.d(TAG, "verified ${p.pkg} 「${p.detail}」 (attempts=${p.attempts})")
            pending = null
            return
        }
        if (p.attempts >= p.maxRetry) {
            Log.d(TAG, "giveup ${p.pkg} 「${p.detail}」目标仍在(${found.second}), 已补刀${p.attempts}次不记账")
            dumpTree(rootInActiveWindow, p.pkg, "giveup")
            pending = null
            return
        }
        p.attempts++
        val rect = Rect()
        found.first.getBoundsInScreen(rect)
        if (!rect.isEmpty) {
            val ok = clicker?.clickPoint(rect.exactCenterX(), rect.exactCenterY()) ?: false
            Log.d(TAG, "retry#${p.attempts} ${p.pkg} gesture ok=$ok win=${found.second}")
        }
        mainHandler.postDelayed({ if (pending === p) verifySkip() }, VERIFY_DELAY_MS)
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
            // 只有命中词才有意义；只写了排除词时没有可命中的东西，整组不建
            if (hits.isNotEmpty()) rules.add(SkipRule(id = "kw:$pkg", patterns = hits))
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
    private fun findUserTargetInAnyWindow(pkg: String): Triple<AccessibilityNodeInfo, String, String>? {
        val scanner = userScanner?.takeIf { userScannerPkg == pkg } ?: return null
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        // getWindows() 是平台类型，服务刚起来时可能为 null
        val wins = runCatching { windows?.toList() ?: emptyList() }.getOrDefault(emptyList())
        for (window in wins) {
            val root = window.root ?: continue
            if (root.packageName?.toString() != pkg) continue
            val hit = scanner.findSkipTarget(root, w, h) ?: continue
            return Triple(hit.first, pkg, "win#${window.id}")
        }
        return null
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
                if (aborted) return@postDelayed
                val root = rootInActiveWindow ?: return@postDelayed
                if (root.packageName?.toString() != pkg) return@postDelayed
                val now = SystemClock.elapsedRealtime()
                // 盲点时间盒：窗口出现超过 8 秒还在按坐标点，说明早就不是开屏了，
                // 这时候点下去是拿用户的操作界面赌运气。只作用于坐标规则——
                // 按文字/控件匹配的规则有自己的形状闸，不需要这层
                val born = lastStateChangeAt[pkg]
                if (born != null && now - born > POINT_MAX_WINDOW_AGE_MS) {
                    Log.d(TAG, "point-skip ${r.id} $pkg 窗口已 ${now - born}ms，超过盲点时间盒")
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
                        Log.d(TAG, "point-match-only ${r.id} $pkg")
                        true
                    }
                    else -> clicker?.clickPoint(point.first, point.second) ?: false
                }
                Log.d(
                    TAG,
                    "point#${i + 1} ${r.id} ${r.actionLabel} $pkg ok=$fired" +
                        " (${(point.first / resources.displayMetrics.widthPixels * 100).toInt()}%," +
                        "${(point.second / resources.displayMetrics.heightPixels * 100).toInt()}%)",
                )
            }, r.delayMs + off)
        }
    }

    private fun scheduleCornerTaps(pkg: String) {
        Log.d(TAG, "corner scheduled $pkg")
        CORNER_TAP_DELAYS.forEachIndexed { i, delay ->
            mainHandler.postDelayed({
                val root = rootInActiveWindow ?: return@postDelayed
                if (root.packageName?.toString() != pkg) return@postDelayed
                val actCls = lastActivityClass[pkg] ?: ""
                // 只认 ad / splash；原先还认 launch，LauncherActivity / SplashGuideActivity
                // 这类正常入口页也会被当成开屏而向后上角盲点三下。
                if (!actCls.contains("ad", true) && !actCls.contains("splash", true)) {
                    Log.d(TAG, "corner#${i + 1} $pkg skip: 非广告/开屏窗口(act=$actCls)")
                    return@postDelayed
                }
                // 再加一道实证闸：有可读内容但看不到广告 SDK 痕迹就不点
                // （纯 WebView / 游戏渲染读不到任何信息时返回 null，仍然放行）
                if (adSignature(root) == false) {
                    Log.d(TAG, "corner#${i + 1} $pkg skip: 窗口有内容但无广告特征(act=$actCls)")
                    return@postDelayed
                }
                val ok = clicker?.clickPoint(
                    resources.displayMetrics.widthPixels * CORNER_X_RATIO,
                    resources.displayMetrics.heightPixels * CORNER_Y_RATIO,
                ) ?: false
                Log.d(TAG, "corner#${i + 1} $pkg ok=$ok act=$actCls")
            }, delay)
        }
    }

    /**
     * 盲点前的实证闸，三态：
     * null = 窗口里读不到任何信息（纯 WebView / 游戏渲染），盲点是唯一手段，放行；
     * true = 有广告 SDK 痕迹，放行；
     * false = 有可读内容但不像广告，拒绝——这是防误点的关键。
     */
    private fun adSignature(root: AccessibilityNodeInfo): Boolean? {
        var sawContent = false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var n = 0
        while (queue.isNotEmpty() && n < AD_SIGNATURE_SCAN_NODES) {
            val node = queue.removeFirst()
            n++
            val vid = node.viewIdResourceName?.lowercase(java.util.Locale.ROOT) ?: ""
            val txt = node.text?.toString() ?: node.contentDescription?.toString()
            if (vid.isNotEmpty() || !txt.isNullOrBlank()) sawContent = true
            if (vid.isNotEmpty() && AD_VIEWID_HINTS.any { vid.contains(it) }) return true
            if (!txt.isNullOrBlank() && AD_TEXT_HINTS.any { txt.contains(it, ignoreCase = true) }) return true
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return if (sawContent) false else null
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
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.snap_action), pi)
            .build()
        try {
            val nm = androidx.core.app.NotificationManagerCompat.from(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                    .createNotificationChannel(
                        android.app.NotificationChannel(
                            SNAP_CHANNEL, getString(R.string.snap_channel), android.app.NotificationManager.IMPORTANCE_LOW
                        )
                    )
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
            Log.i(TAG, "capture-empty attempts=$attempt fg=$fg")
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
        Log.i(TAG, "capture attempt=$attempt candidates=${candidates.size} pkg=$pkg otherPkg=$otherPkg")
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
            // 不能拿自己的悬浮钮根充数——那会把 SkipAd 的界面当成目标 App 存进去
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
        Log.i(TAG, "capture nodes=${nodes.size} pkg=$pkg windows=${windowRoots.size}")
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
                            Log.d(TAG, "snapshot saved ${dir.path} nodes=$nodeCount ${shotW}x$shotH")
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
            Log.w("SkipAdEngine", "takeScreenshot failed: $errorCode")
            // 截图失败原来只写日志：用户点了没反应，也不知道为什么
            com.noteatoranges.skipad.util.CaptureBubble.apply(this@SkipAccessibilityService)
            Toast.makeText(
                this@SkipAccessibilityService,
                R.string.snap_shot_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }

        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
            // 尺寸要在 close() 之前读：HardwareBuffer 关了之后再取宽高就不保证有效
            val shotW = screenshot.hardwareBuffer.width
            val shotH = screenshot.hardwareBuffer.height
            val hw = android.graphics.Bitmap.wrapHardwareBuffer(
                screenshot.hardwareBuffer, screenshot.colorSpace
            )
            val bitmap = hw?.copy(Bitmap.Config.ARGB_8888, false)
            screenshot.hardwareBuffer.close()
            // 硬件位图占的是图形内存，copy 完就没用了
            hw?.recycle()
            onResult(bitmap, shotW, shotH)
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
            Log.w(TAG, "dumpTree failed", e)
        }
    }

    // ———————— 结构路径规则（纯图标按钮的精确落点）————————

    /** 按结构路径在「本 App 的任一窗口」里找节点：纯图标按钮没有文字/ID 时的主路径 */
    private fun findByPathAnyWindow(pkg: String, path: String): AccessibilityNodeInfo? {
        val roots = ArrayList<AccessibilityNodeInfo>(3)
        rootInActiveWindow?.let { if (it.packageName?.toString() == pkg) roots.add(it) }
        runCatching {
            windows?.forEach { w ->
                val r = w.root ?: return@forEach
                if (r.packageName?.toString() == pkg) roots.add(r)
            }
        }
        for (r in roots) findByPath(r, path)?.let { return it }
        return null
    }

    /** 沿子索引路径下钻，末端节点要有有效矩形才算命中 */
    private fun findByPath(root: AccessibilityNodeInfo, path: String): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo = root
        for (seg in path.split('/')) {
            val i = seg.toIntOrNull() ?: return null
            cur = cur.getChild(i) ?: return null
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
            val node = findByPathAnyWindow(pkg, r.nodePath ?: continue) ?: continue
            // 类名/尺寸校验：页面改版后同一条路径可能指向别的控件，宁可不错
            if (!nodeMatches(r, node)) {
                Log.v(TAG, "path-skip ${r.id} 控件已变（${node.className}）")
                continue
            }
            if (!gate("$pkg|${r.id}", r.cooldownMs, r.maxCount, now)) continue
            if (r.action == SkipRule.ACTION_NONE) {
                Log.d(TAG, "path-match-only ${r.id} path=${r.nodePath} $pkg")
                continue
            }
            val fired = when (r.action) {
                SkipRule.ACTION_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
                else -> clickNodeOnce(node)
            }
            Log.d(TAG, "path-rule ${r.id} path=${r.nodePath} $pkg ok=$fired")
            if (!fired) continue
            val key = "$pkg#path/${r.nodePath}"
            lastSkipWindow = key
            lastSkipAt = now
            pending = PendingSkip(key, pkg, "路径·${r.nodePath}", 0, 1)
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
        const val TAG = "SkipAdEngine"

        /** 供悬浮窗开关等外部调用拿到运行中的服务实例（服务存活期非空） */
        val instance: SkipAccessibilityService?
            get() = inst

        @Volatile
        private var inst: SkipAccessibilityService? = null
        /** 事件节流：信息流一次滑动会发几十个内容变化事件，150ms 太密 */
        const val THROTTLE_MS = 300L
        const val COOLDOWN_MS = 4000L

        /** 内容变化事件的最小间隔：滑动信息流时一秒能来几十个，没必要每个都扫 */
        const val CONTENT_THROTTLE_MS = 900L

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
         * 开屏窗口：App 从别的 App 切到前台后的这段时间，只在这段时间里允许内置关键字。
         * 用户实测开屏广告多是 2–5 秒，这里给到 6 秒；再多就会盖住开屏页跳过去之后的
         * 主界面，那正是误点开始的地方。
         */
        const val SPLASH_WINDOW_MS = 6000L
        const val CORNER_X_RATIO = 0.93f
        const val CORNER_Y_RATIO = 0.065f
        const val SNAP_CHANNEL = "snapshot"
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

        val CORNER_TAP_PKGS = setOf(
            "com.coolapk.market",
            "com.netease.mail",
            "com.ehai",
        )
        val CORNER_TAP_DELAYS = longArrayOf(900L, 1900L, 2900L)

        /** 广告 SDK 的 viewId 特征（穿山甲 / 快手 / 广点通 / 百度 / Sigmob / 小米） */
        val AD_VIEWID_HINTS = listOf(
            "tt_splash", "ksad", "gdt_", "baidu_ad", "sigmob", "miui_ad",
            "adview", "splash_ad", "ad_container",
        )
        val AD_TEXT_HINTS = listOf("广告", "Sponsored")
        const val AD_SIGNATURE_SCAN_NODES = 200

        /** 坐标规则的重试节拍（相对 delayMs）：一次落空不等于失败，再补三拍 */
        val POINT_RETRY_OFFSETS = longArrayOf(0L, 600L, 1200L, 1800L)

        /** 盲点时间盒：窗口出现超过这个时长就不再按坐标点（正常开屏早该过去了） */
        const val POINT_MAX_WINDOW_AGE_MS = 8000L

        /** 节点矩形去重的分桶宽度（px）：相邻两帧的 bounds 会抖 1~2px，精确相等去不掉重复 */
        const val RECT_BUCKET_PX = 8

        /** 系统包与噪声大户：直接跳过节点遍历（李跳跳同款"先粗筛再遍历"思路） */
        val PACKAGE_BLACKLIST = setOf(
            "com.android.systemui",
            "com.android.launcher3",
            "com.miui.home",
            "com.huawei.android.launcher",
            "com.sec.android.app.launcher",
            "com.google.android.apps.nexuslauncher",
        )
    }

    @Volatile
    private var blacklist: Set<String> = PACKAGE_BLACKLIST
}
