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
import com.noteatoranges.skipad.db.RuleStore
import com.noteatoranges.skipad.db.KeywordStore
import com.noteatoranges.skipad.db.SnapshotStore
import com.noteatoranges.skipad.db.StatsDb
import com.noteatoranges.skipad.keepalive.KeepAlive
import com.noteatoranges.skipad.store.StatsStore
import com.noteatoranges.skipad.util.Io
import java.io.File
import java.util.concurrent.Executor

/**
 * 核心无障碍服务：窗口事件 → 规则匹配（用户规则优先，内置规则兜底）→ 模拟点击。
 *
 * - 点击后验证制：点击 800ms 后在**所有窗口**（含 overlay）重扫，目标还在就用真实手势补刀
 *   （最多 2 次），目标消失才记一次跳过；
 * - 用户规则（GKD 式）：快照点选生成的 node/point 规则优先于内置关键字；
 * - 坐标点按兜底：无可读文本的广告 App 冷启动后朝右上角标准位真点；
 * - 快照：常驻通知「抓取快照」按钮，截图+控件树落盘供点选生成规则；
 * - 诊断：TAG=SkipAdEngine；控件树落盘 filesDir/diag/。
 */
class SkipAccessibilityService : AccessibilityService() {

    private var builtinScanner: NodeScanner? = null
    private var userScanner: NodeScanner? = null
    private var userRulesVersionSeen = -1
    private var clicker: ClickPerformer? = null
    private var lastHandleAt = 0L

    /** 两个通道共享的节点属性缓存：同一次事件里弹窗通道直接复用开屏通道读过的属性 */
    private val factsCache = NodeFactsCache()

    /** 扫描器重建的键（规则版本 + 包名 + 页面）：换 App / 换页面都要重建，否则规则会跨页生效 */
    private var userScannerKey: String? = null

    /**
     * userScanner 是按哪个包构建的。
     *
     * 用户规则是 per-App 的，但 [findTargetInAnyWindow] 会遍历所有窗口（含浮层）。
     * 不看包名就拿同一份扫描器过一遍，等于把 A 应用的规则套到 B 应用的控件上——
     * 命中就点，是实打实的误点。
     */
    private var userScannerPkg: String? = null

    /**
     * 用户自建关键字缓存：**按包名**存，弹窗通道扫哪个窗口就取哪个包的那份；
     * 关键字版本号一变整份清掉（用户改词无需重启服务）。
     */
    private var kwVerSeen = -1
    private val kwCache = HashMap<String, Pair<List<String>, List<String>>>()

    private fun keywordsFor(pkg: String): Pair<List<String>, List<String>> {
        val ver = KeywordStore.version(this)
        if (ver != kwVerSeen) {
            kwVerSeen = ver
            kwCache.clear()
        }
        return kwCache.getOrPut(pkg) { KeywordStore.forApp(this, pkg) }
    }

    /** 每条规则的运行态：上次动作时间与累计次数（换页面清零） */
    private class RuleRun(var lastAt: Long = 0L, var count: Int = 0)
    private val ruleState = HashMap<String, RuleRun>()

    /** 每个 App 上次执行规则时的页面，用于判断“换页” */
    private val stateActivity = HashMap<String, String>()

    /** 快照广播只注册一次：onServiceConnected 可能被系统重复回调，重复注册会导致一次点击抓多张 */
    private var snapshotRegistered = false

    /** 窗口 id → (时间戳, 是否有广告标识)，避免每次扫描重走节点树 */
    private val adHintCache = HashMap<Int, Pair<Long, Boolean>>()

    private var lastSkipWindow: String? = null
    private var lastSkipAt = 0L
    private var lastFgPkg: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pending: PendingSkip? = null

    /** 弹窗通道节流：每次全树扫描有成本，未命中时也不低于这个间隔 */
    private var lastPopupScanAt = 0L

    /** 内容变化事件的独立节流时间戳 */
    private var lastContentAt = 0L

    /** 事件心跳日志的节流时间戳（确认无障碍事件真的在进来） */
    private var lastEventLogAt = 0L

    /** 弹窗点击冷却：按窗口记账，同窗口 4 秒内不重复点（原来是整 App 20 秒） */
    private val popupClickAt = HashMap<String, Long>()
    private val popupClickCount = HashMap<String, Int>()

    /** 点了三次也没关掉的窗口：静默 30 秒，别反复去点同一个顽固弹窗 */
    private val popupBlockedUntil = HashMap<String, Long>()

    /** 上一次弹窗动作时间：保证两次动作之间隔开一个验证周期 */
    private var lastPopupActionAt = 0L

    /** 手动执行坐标规则后的静默期：这段时间内弹窗通道不再动手，避免同一下点两遍 */
    private var popupSuppressUntil = 0L

    /** 弱证据（角落小图 / 图片弹窗）需连续两次命中同一控件才点，单次命中先记账 */
    private class WeakHit(val key: String, val rect: Rect, val at: Long)
    private var lastWeakHit: WeakHit? = null

    private data class PendingSkip(
        val windowKey: String,
        val pkg: String,
        val detail: String,
        var attempts: Int,
        /** 弹窗只补刀一次：弹窗消失后原坐标已变成广告本身，多点一下就是点广告 */
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
        builtinScanner = NodeScanner(BuiltinRules.defaults(), factsCache).also {
            it.verbose = Log.isLoggable(TAG, Log.VERBOSE)
        }
        clicker = ClickPerformer(this)
        blacklist = PACKAGE_BLACKLIST + findHomePackages()
        KeepAlive.scheduleJob(this)
        StatsStore.logEvent(this, getString(R.string.ka_log_connected))
        KeepAlive.markConnected(this)
        postWorkNotification()
        inst = this
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

    private fun findHomePackages(): Set<String> = try {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.queryIntentActivities(home, 0)
            .mapTo(mutableSetOf()) { it.activityInfo.packageName }
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
        // 手动点过之后，弹窗通道静默一段时间，避免同一下点两遍
        popupSuppressUntil = SystemClock.elapsedRealtime() + MANUAL_POINT_SUPPRESS_MS
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
        // 弹窗计数跟着页面走：同窗口的冷却记录留着没意义，窗口 id 会换
        popupClickAt.clear()
        popupClickCount.clear()
        popupBlockedUntil.clear()
        // 广告标识缓存是按窗口 id 记的，换页后旧 id 的结果没有意义
        adHintCache.clear()
        lastWeakHit = null
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
                val freshSwitch = lastFgPkg != ep && ep !in blacklist
                lastStateChangeAt[ep] = SystemClock.elapsedRealtime()
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
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastHandleAt < THROTTLE_MS) return
        lastHandleAt = now

        // 新增窗口（浮层弹窗最常见形态）单独走弹窗通道：这类窗口不一定改动
        // 活动窗口的 root，只靠 WSC/WCC 会漏。不动开屏扫描，避免重复点
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (event.windowChanges and AccessibilityEvent.WINDOWS_CHANGE_ADDED == 0) return
            tryPopupClose(event, now)
            return
        }

        // 内容变化事件在信息流里一秒能来几十个，每次都要全树走一遍无障碍节点，
        // 是耗电主因。开屏广告真正出现时伴随 WINDOW_STATE_CHANGED，内容变化慢一拍不影响
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (now - lastContentAt < CONTENT_THROTTLE_MS) return
            // 滑动信息流只产生 SCROLL / TEXT_CHANGED，弹窗浮层是整棵子树插进来。
            // 没带 SUBTREE 的变化基本不可能长出关闭按钮，直接跳过（0 表示未上报，不过滤）
            val types = event.contentChangeTypes
            if (types != 0 &&
                types and AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE == 0
            ) {
                return
            }
            lastContentAt = now
        }

        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: event.packageName?.toString() ?: return
        if (pkg in blacklist || pkg == packageName) return

        // 刷新用户规则扫描器（规则版本 / 包名 / 页面任一变化就重建）
        val ver = RuleStore.version(this)
        val act = lastActivityClass[pkg].orEmpty()
        val key = "$ver|$pkg|$act"
        if (key != userScannerKey) {
            userRulesVersionSeen = ver
            userScannerKey = key
            val nodeRules = RuleStore.nodeRulesAsScannerRules(this, pkg, act)
            userScanner = if (nodeRules.isEmpty()) null else NodeScanner(nodeRules, factsCache).also {
                it.verbose = Log.isLoggable(TAG, Log.VERBOSE)
            }
            userScannerPkg = pkg
        }

        // 关键字不再在事件入口统一加载：弹窗通道会扫多个窗口，
        // 每个窗口按自己的包名取词（见 keywordsFor），避免跨应用误伤

        // 结构路径规则最精确（用户从快照里点选的那个控件本身），先试一次；
        // 命中就不必再全树扫描。页面/冷却/次数闸在 tryPathRule 内部按规则判断
        if (tryPathRule(root, pkg, act, now)) return

        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels

        // 启动窗口闸：只在"新窗口出现后 SPLASH_WINDOW_MS 内"扫描开屏广告。
        // 无状态变化记录（服务在该 App 已在前台时才连上/重连）时按"现在"起算，
        // 否则 now - 0 恒大于闸门，等于这类场景永远不扫描。
        // 闸外不等于放弃：用户点菜单后的弹窗要靠下面的弹窗通道，它有自己的节流。
        val stateAt = lastStateChangeAt[pkg] ?: now
        if (now - stateAt > SPLASH_WINDOW_MS) {
            tryPopupClose(event, now)
            return
        }

        // 同窗口冷却：刚点过的窗口在冷却期内不再重复匹配（COOLDOWN_MS 此前只定义未使用）
        if (lastSkipWindow != null && now - lastSkipAt < COOLDOWN_MS &&
            lastSkipWindow == windowKeyFor(pkg, event, root)
        ) {
            return
        }

        // 用户规则优先；窗口有"广告"标识时免位置闸
        val adHint = adHintFor(-1, root)
        val t0 = SystemClock.elapsedRealtime()
        val hit = userScanner?.findSkipTarget(root, w, h, adHint)
            ?: builtinScanner?.findSkipTarget(root, w, h, adHint)
        // 扫描耗时：决定要不要给「每 App 事件次数」加预算时必须看这个数，
        // 不能凭感觉（上限是 TRAVERSE_TIMEOUT_MS=400ms）
        val cost = SystemClock.elapsedRealtime() - t0
        if (hit == null) {
            Log.v(TAG, "scan $pkg ${cost}ms: ${userScanner?.lastDebug ?: builtinScanner?.lastDebug}")
            // 开屏窗口期内也可能先浮出营销弹窗，主路径没命中就顺带试一次弹窗通道
            tryPopupClose(event, now)
            return
        }
        val detail = hit.first.text?.toString()
            ?: hit.first.contentDescription?.toString()
            ?: hit.first.viewIdResourceName
            ?: hit.second.id
        Log.d(TAG, "match $pkg ${cost}ms: ${userScanner?.lastDebug ?: builtinScanner?.lastDebug}")

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

    /**
     * 应用内弹窗通道：用户点菜单后浮出来的营销卡片。
     *
     * 不套启动窗口闸——弹窗出现时距 Activity 切换可能已过几分钟；改用两道自己的节流：
     * 扫描间隔（窗口切换 500ms / 新增窗口 400ms / 内容变化 1800ms），点击后按**窗口**
     * 冷却 4 秒、同一窗口最多 3 次——此前是整 App 20 秒冷却，一个 App 里连着两个弹窗，
     * 第二个基本会被放过。
     *
     * 扫描范围是所有可交互窗口（活动窗口优先，最多 3 个）：浮层弹窗常常是独立窗口，
     * 不在 rootInActiveWindow 里。点完同样进 800ms 验证：没消失就不记账，只补刀一次。
     */
    private fun tryPopupClose(event: AccessibilityEvent, now: Long, force: Boolean = false) {
        val interval = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> POPUP_SCAN_STATE_MS
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> POPUP_SCAN_WINDOW_MS
            else -> POPUP_SCAN_MS
        }
        if (!force && now - lastPopupScanAt < interval) return
        lastPopupScanAt = now
        val scanner = builtinScanner ?: run {
            Log.w(TAG, "popup: builtinScanner 未初始化（服务未连上）")
            return
        }
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        var scanned = 0
        for (win in popupWindowCandidates()) {
            val root = win.root ?: continue
            val pkg = root.packageName?.toString() ?: continue
            scanned++
            // 「像弹窗的窗口」：窗口自己没铺满屏幕（对话框/浮层），E2 靠这个放宽节点数限制
            val rootRect = Rect()
            root.getBoundsInScreen(rootRect)
            val dialogWindow = rootRect.width() > 0 &&
                (rootRect.width() < w * 0.98f || rootRect.height() < h * 0.98f)
            // 关键字严格按「这个窗口属于哪个包」取：弹窗通道会扫多个窗口，
            // 拿前台 App 的词去扫别的 App 的窗口就是跨应用误伤
            val (kh, ke) = keywordsFor(pkg)
            if (kh.isNotEmpty() || ke.isNotEmpty()) {
                Log.v(TAG, "kw $pkg#${win.id} hits=${kh.size} excludes=${ke.size}")
            }
            val hit = scanner.findPopupCloseTarget(root, w, h, dialogWindow, kh, ke)
            if (hit == null) {
                Log.v(TAG, "popup-none $pkg#${win.id}: ${scanner.lastPopupDebug}")
                continue
            }
            val key = "$pkg#${win.id}"
            // 弱证据要看到两次：弹窗刚插入时布局还没稳，单帧的判断不牢靠
            if (hit.tier >= 5) {
                val r = Rect()
                hit.node.getBoundsInScreen(r)
                val prev = lastWeakHit
                val same = prev != null && prev.key == key && prev.rect == r && now - prev.at < WEAK_CONFIRM_MS
                lastWeakHit = WeakHit(key, r, now)
                if (!same) {
                    Log.v(TAG, "popup-weak-wait $key: ${scanner.lastPopupDebug}")
                    // 没有后续事件也要有第二次查看的机会，否则弱证据永远等不到确认
                    mainHandler.postDelayed(
                        { tryPopupClose(event, SystemClock.elapsedRealtime(), true) },
                        WEAK_CONFIRM_DELAY_MS,
                    )
                    continue
                }
            } else {
                lastWeakHit = null
            }
            if (!gatePopup(key, now)) {
                Log.v(TAG, "popup-cool $key: ${scanner.lastPopupDebug}")
                continue
            }
            Log.d(TAG, "popup-match $key: ${scanner.lastPopupDebug}")
            // 同位置本轮已点过：跳过这个窗口，不要重写诊断树
            if (rectKeyOf(hit.node) in clickedRects) {
                Log.v(TAG, "popup-dup $pkg")
                continue
            }
            dumpTree(root, pkg, "popup")
            if (clickNodeOnce(hit.node)) {
                lastSkipWindow = key
                lastSkipAt = now
                pending = PendingSkip(key, pkg, "弹窗·${hit.why}", 0, 1)
                val token = pending
                mainHandler.postDelayed({ if (pending === token) verifySkip() }, VERIFY_DELAY_MS)
                return
            }
            Log.d(TAG, "popup-click-failed $pkg")
        }
        if (scanned == 0) Log.v(TAG, "popup: 没有可扫窗口")
    }

    /** 待扫窗口：活动窗口优先，跳过自己和黑名单，最多 [POPUP_SCAN_WINDOWS] 个 */
    private fun popupWindowCandidates(): List<AccessibilityWindowInfo> {
        val all = runCatching { windows?.toList() ?: emptyList() }.getOrDefault(emptyList())
        if (all.isEmpty()) return emptyList()
        val out = ArrayList<AccessibilityWindowInfo>(POPUP_SCAN_WINDOWS)
        for (win in all.sortedByDescending { it.isActive }) {
            val p = runCatching { win.root?.packageName?.toString() }.getOrNull() ?: continue
            if (p == packageName || p in blacklist) continue
            out.add(win)
            if (out.size >= POPUP_SCAN_WINDOWS) break
        }
        return out
    }

    /**
     * 弹窗点击闸门：按窗口冷却 + 同窗口次数上限 + 全局最小间隔。
     * 全局最小间隔是为了让上一次的 800ms 验证先跑完，别在验证期间插队点第二个。
     */
    private fun gatePopup(key: String, now: Long): Boolean {
        if (now < popupSuppressUntil) return false
        if (now < (popupBlockedUntil[key] ?: 0L)) return false
        if (now - lastPopupActionAt < POPUP_GLOBAL_MIN_MS) return false
        val last = popupClickAt[key] ?: 0L
        if (last > 0 && now - last < POPUP_WINDOW_COOLDOWN_MS) return false
        val n = popupClickCount[key] ?: 0
        if (n >= POPUP_MAX_PER_WINDOW) return false
        popupClickAt[key] = now
        popupClickCount[key] = n + 1
        lastPopupActionAt = now
        return true
    }

    private fun windowKeyFor(pkg: String, event: AccessibilityEvent, root: AccessibilityNodeInfo): String =
        "$pkg/${event.className ?: root.className ?: ""}"

    /** 核验：所有窗口（含 overlay）都找不到目标才算跳过成功，否则手势补刀 */
    private fun verifySkip() {
        val p = pending ?: return
        val found = findTargetInAnyWindow()
        if (found == null) {
            val appCtx = applicationContext
            Io.run("stats-insert") { StatsDb.get(appCtx).insert(p.pkg, p.detail) }
            Log.d(TAG, "verified ${p.pkg} 「${p.detail}」 (attempts=${p.attempts})")
            pending = null
            return
        }
        if (p.attempts >= p.maxRetry) {
            Log.d(TAG, "giveup ${p.pkg} 「${p.detail}」广告仍在(${found.second}), 已补刀${p.attempts}次不记账")
            dumpTree(rootInActiveWindow, p.pkg, "giveup")
            // 点不掉就暂时别碰这个窗口：反复尝试只会反复误点
            if (p.detail.startsWith("弹窗")) popupBlockedUntil[p.windowKey] = SystemClock.elapsedRealtime() + POPUP_STUBBORN_MS
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

    /** 在所有可交互窗口里找跳过目标（用户规则 + 内置规则，rootInActiveWindow 未必是广告层） */
    private fun findTargetInAnyWindow(): Triple<AccessibilityNodeInfo, String, String>? {
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        // getWindows() 是平台类型，服务刚起来时可能为 null
        val wins = runCatching { windows?.toList() ?: emptyList() }.getOrDefault(emptyList())
        for (window in wins) {
            val root = window.root ?: continue
            val wp = root.packageName?.toString() ?: continue
            if (wp == packageName || wp in blacklist) continue
            // 窗口里有"广告"标识时，跳过按钮不设位置闸（微博的跳过就在右下角）
            val adHint = adHintFor(window.id, root)
            // 用户规则只对本包生效：窗口属于别的 App（浮层、对话框）时退回内置规则
            val us = userScanner.takeIf { wp == userScannerPkg }
            val hit = us?.findSkipTarget(root, w, h, adHint)
                ?: builtinScanner?.findSkipTarget(root, w, h, adHint)
            if (hit != null) return Triple(hit.first, wp, "win#${window.id} ${window.type}")
            // 弹窗通道点下去之后也按同一套核验：弹窗还在说明没点掉，补刀一次
            val kw = keywordsFor(wp)
            val popup = builtinScanner?.findPopupCloseTarget(
                root, w, h, isDialogWindow(root, w, h), kw.first, kw.second,
            )
            if (popup != null) return Triple(popup.node, wp, "popup#${window.id}")
            // 纯图片/视频开屏：跳过按钮既无文本也无 skip 类 id，只在开屏类 Activity 上
            // 按"右上/右下角 + 小尺寸"兜底，避免在正常页面误点搜索、更多按钮
            val act = lastActivityClass[wp] ?: ""
            if (isSplashLike(act)) {
                val corner = builtinScanner?.findCornerButton(root, w, h)
                if (corner != null) {
                    Log.d(TAG, "corner-fallback $wp act=$act")
                    return Triple(corner, wp, "corner#${window.id}")
                }
            }
        }
        return null
    }

    /** 窗口是否没铺满屏幕（对话框/浮层）：弹窗通道用它放宽“节点稀疏”限制 */
    private fun isDialogWindow(root: AccessibilityNodeInfo, w: Int, h: Int): Boolean {
        val r = Rect()
        root.getBoundsInScreen(r)
        return r.width() > 0 && (r.width() < w * 0.98f || r.height() < h * 0.98f)
    }

    /** 广告标识判定带 1.5 秒缓存：同一次开屏会被反复扫描，别每次都重走 200 个节点 */
    private fun adHintFor(windowId: Int, root: AccessibilityNodeInfo): Boolean {
        val now = SystemClock.elapsedRealtime()
        adHintCache[windowId]?.let { (t, v) -> if (now - t < AD_HINT_TTL_MS) return v }
        val v = adSignature(root) == true
        adHintCache[windowId] = now to v
        return v
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

    /** 开屏类 Activity：决定能不能用"角落小按钮"兜底 */
    private fun isSplashLike(act: String): Boolean {
        if (act.isEmpty()) return false
        val low = act.lowercase(java.util.Locale.ROOT)
        return low.contains("splash") || low.contains("welcome") || low.contains("advert") ||
            low.contains("adactivity") || low.contains(".ad.")
    }

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

    private fun captureNow(attempt: Int = 0) {
        val candidates = captureCandidates()
        if (candidates.isEmpty()) {
            // 点悬浮钮会先把圆钮藏起来，这一瞬间窗口在重排，
            // rootInActiveWindow 与 window.getRoot() 都可能返回 null。
            // 以前这里直接报错，HyperOS + Android 17 上这就是「抓不到」的主因。
            if (attempt < CAPTURE_MAX_RETRY) {
                mainHandler.postDelayed({ captureNow(attempt + 1) }, CAPTURE_RETRY_DELAY_MS)
                return
            }
            dumpSnapFailure(attempt)
            Toast.makeText(this, R.string.snap_failed, Toast.LENGTH_SHORT).show()
            return
        }
        // 包名优先取窗口自己的；取不到就用最后记录的前台包（弹窗窗口常常没有 packageName）。
        // 以前这里 `?: return` 静默退出：点了没反应、不报错、不落盘，最难查的一种
        val pkg = candidates.first().second.packageName?.toString() ?: lastFgPkg
        if (pkg == null) {
            Toast.makeText(this, R.string.snap_no_pkg, Toast.LENGTH_SHORT).show()
            return
        }
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
            if (windowRoots.size > POPUP_SCAN_WINDOWS) break
        }
        if (windowRoots.isEmpty()) windowRoots.add(0 to candidates.first().second)

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
        if (nodes.size < 3) {
            if (attempt < CAPTURE_MAX_RETRY) {
                mainHandler.postDelayed({ captureNow(attempt + 1) }, CAPTURE_RETRY_DELAY_MS)
                return
            }
            Toast.makeText(this, R.string.snap_too_few, Toast.LENGTH_SHORT).show()
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

        /** 弹窗通道：未命中时的扫描节流（全树最多 500 节点，别跟着信息流滚动一直扫） */
        const val POPUP_SCAN_MS = 1800L

        /** 窗口切换时弹窗可能刚出现，用更短的间隔 */
        const val POPUP_SCAN_STATE_MS = 500L

        /** 新增窗口事件：弹窗刚挂上来，间隔再短一点 */
        const val POPUP_SCAN_WINDOW_MS = 400L

        /** 一次最多扫几个窗口（浮层弹窗常在独立窗口里，但没必要把整桌窗口扫一遍） */
        const val POPUP_SCAN_WINDOWS = 3

        /** 内容变化事件的最小间隔：滑动信息流时一秒能来几十个，没必要每个都扫 */
        const val CONTENT_THROTTLE_MS = 900L

        /** 找锚点控件的遍历上限：锚点通常就在弹窗里，不用扫全树 */
        const val ANCHOR_SCAN_NODES = 600

        /** 快照总节点上限（改多窗口后从 400 提到 1500，避免大页面被截断） */
        const val SNAPSHOT_MAX_NODES = 1500

        /** 单个窗口的节点上限：防止一个超大窗口把额度吃光，浮层窗口就没机会了 */
        const val SNAPSHOT_MAX_PER_WINDOW = 800

        /** 弹窗冷却改为按窗口：同窗口 4 秒内不重复点，最多 3 次（此前整 App 20 秒） */
        const val POPUP_WINDOW_COOLDOWN_MS = 4000L
        const val POPUP_MAX_PER_WINDOW = 3

        /** 两次弹窗动作的最小间隔：让上一次的 800ms 验证先跑完 */
        const val POPUP_GLOBAL_MIN_MS = 1200L

        /** 手动执行坐标规则后的静默期，避免自动通道在同一点上再点一次 */
        const val MANUAL_POINT_SUPPRESS_MS = 8000L

        /** 点了 3 次仍没关掉的窗口，静默这么久再试 */
        const val POPUP_STUBBORN_MS = 30000L

        /** 弱证据确认窗口：两次命中同一控件且间隔在这么内才算数 */
        const val WEAK_CONFIRM_MS = 2500L

        /** 弱证据首次命中后多久补一次扫描 */
        const val WEAK_CONFIRM_DELAY_MS = 450L
        const val UNBIND_RECOVER_DELAY_MS = 1500L
        const val VERIFY_DELAY_MS = 800L
        const val MAX_RETRY = 2
        const val SPLASH_WINDOW_MS = 12000L
        const val CORNER_X_RATIO = 0.93f
        const val CORNER_Y_RATIO = 0.065f
        const val SNAP_CHANNEL = "snapshot"
        const val SNAP_NOTIF_ID = 2001
        const val ACTION_TAKE_SNAPSHOT = "com.noteatoranges.skipad.TAKE_SNAPSHOT"

        /** 系统界面窗口：抓快照时要跳过 */
        const val SYSTEM_UI_PKG = "com.android.systemui"

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
        const val AD_HINT_TTL_MS = 1500L

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
