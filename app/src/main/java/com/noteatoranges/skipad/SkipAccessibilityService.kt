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

    /** 弹窗点击冷却：误判的代价是点掉一个正常控件，宁可慢也不连着点 */
    private var lastPopupClickAt = 0L

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

    private val mainExecutor = Executor { mainHandler.post(it) }

    private val snapshotReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_TAKE_SNAPSHOT) takeSnapshot()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 连接留痕：装了新包之后服务是否真的起来了，只能靠这一行判断
        Log.i(TAG, "service-connected 0.10.1")
        builtinScanner = NodeScanner(BuiltinRules.defaults(), factsCache).also {
            it.verbose = Log.isLoggable(TAG, Log.VERBOSE)
        }
        clicker = ClickPerformer(this)
        blacklist = PACKAGE_BLACKLIST + findHomePackages()
        KeepAlive.scheduleJob(this)
        StatsStore.logEvent(this, getString(R.string.ka_log_connected))
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

    /** 悬浮窗点击：隐藏圆钮 → 广播抓取（服务同应用接收）→ 弹回 */
    fun bubbleClicked() {        com.noteatoranges.skipad.util.CaptureBubble.hide(this)
        mainHandler.postDelayed({
            sendBroadcast(Intent(ACTION_TAKE_SNAPSHOT).setPackage(packageName))
        }, 250)
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
        // 手动点过之后，弹窗通道短时间内不要再来一次，避免同一下点两遍
        lastPopupClickAt = SystemClock.elapsedRealtime()
    }

    /** 1dp 对应的像素，锚点偏移用 */
    private fun dp(v: Int): Float = v * resources.displayMetrics.density

    /**
     * 解析坐标规则的落点。
     *
     * 有锚点时按锚点控件的实际矩形算（锚点中心 + dx，锚点底边 + dy），这样换机型、
     * 分屏、弹窗内容变高变矮都不影响——固定屏幕比例做不到这点；
     * 锚点找不到（页面变了）才退回屏幕比例，两者都没有就放弃本次。
     */
    private fun resolvePoint(root: AccessibilityNodeInfo?, r: com.noteatoranges.skipad.db.SkipAppRule): Pair<Float, Float>? {
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
        return resources.displayMetrics.widthPixels * xr to resources.displayMetrics.heightPixels * yr
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

    /** 换 App / 换页面：清节点属性缓存与规则计数，并强制重建扫描器 */
    private fun onPageChanged(pkg: String, activity: String) {
        if (stateActivity[pkg] == activity) return
        stateActivity[pkg] = activity
        factsCache.clear()
        val prefix = "$pkg|"
        ruleState.keys.filter { it.startsWith(prefix) }.forEach { ruleState.remove(it) }
        userScannerKey = null
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
                val act = event.className?.toString() ?: lastActivityClass[ep].orEmpty()
                event.className?.toString()?.let { lastActivityClass[ep] = it }
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

        val now = SystemClock.elapsedRealtime()
        if (now - lastHandleAt < THROTTLE_MS) return
        lastHandleAt = now

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
        }

        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels

        // 启动窗口闸：只在"新窗口出现后 SPLASH_WINDOW_MS 内"扫描开屏广告。
        // 无状态变化记录（服务在该 App 已在前台时才连上/重连）时按"现在"起算，
        // 否则 now - 0 恒大于闸门，等于这类场景永远不扫描。
        // 闸外不等于放弃：用户点菜单后的弹窗要靠下面的弹窗通道，它有自己的节流。
        val stateAt = lastStateChangeAt[pkg] ?: now
        if (now - stateAt > SPLASH_WINDOW_MS) {
            tryPopupClose(root, pkg, event, now, w, h)
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
        val hit = userScanner?.findSkipTarget(root, w, h, adHint)
            ?: builtinScanner?.findSkipTarget(root, w, h, adHint)
        if (hit == null) {
            Log.v(TAG, "scan $pkg: ${userScanner?.lastDebug ?: builtinScanner?.lastDebug}")
            // 开屏窗口期内也可能先浮出营销弹窗，主路径没命中就顺带试一次弹窗通道
            tryPopupClose(root, pkg, event, now, w, h)
            return
        }
        val detail = hit.first.text?.toString()
            ?: hit.first.contentDescription?.toString()
            ?: hit.first.viewIdResourceName
            ?: hit.second.id
        Log.d(TAG, "match $pkg: ${userScanner?.lastDebug ?: builtinScanner?.lastDebug}")
        dumpTree(root, pkg, "match")

        val rule = hit.second
        // 冷却 / 次数上限：拦在这里，误点的代价远大于漏跳
        if (!gate("$pkg|${rule.id}", rule.cooldownMs, rule.maxCount, now)) return
        if (rule.action == SkipRule.ACTION_NONE) {
            Log.d(TAG, "match-only $pkg 「$detail」 规则=${rule.id}")
            return
        }
        val fired = when (rule.action) {
            SkipRule.ACTION_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            else -> clicker?.click(hit.first) == true
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
     * 扫描间隔 [POPUP_SCAN_MS]（未命中也走），点击后 [POPUP_COOLDOWN_MS] 内不再点。
     * 点完同样进 800ms 验证：弹窗没消失就不记账，且只补刀一次。
     */
    private fun tryPopupClose(
        root: AccessibilityNodeInfo,
        pkg: String,
        event: AccessibilityEvent,
        now: Long,
        w: Int,
        h: Int,
    ) {
        // 窗口切换（新 Activity / Dialog）时弹窗可能刚出现，用更短的间隔；
        // 信息流的内容变化事件一秒几十个，没必要跟着全速扫
        val interval = if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            POPUP_SCAN_STATE_MS
        } else {
            POPUP_SCAN_MS
        }
        if (now - lastPopupScanAt < interval) return
        lastPopupScanAt = now
        val scanner = builtinScanner ?: run {
            Log.w(TAG, "popup: builtinScanner 未初始化（服务未连上）")
            return
        }
        val hit = scanner.findPopupCloseTarget(root, w, h)
        if (hit == null) {
            Log.v(TAG, "popup-none $pkg: ${scanner.lastPopupDebug}")
            return
        }
        if (now - lastPopupClickAt < POPUP_COOLDOWN_MS) {
            Log.v(TAG, "popup-cool $pkg: ${scanner.lastPopupDebug}")
            return
        }
        lastPopupClickAt = now
        val key = "popup:$pkg/${event.className ?: root.className ?: ""}"
        Log.d(TAG, "popup-match $pkg: ${scanner.lastPopupDebug}")
        dumpTree(root, pkg, "popup")
        if (clicker?.click(hit.node) == true) {
            lastSkipWindow = key
            lastSkipAt = now
            pending = PendingSkip(key, pkg, "弹窗·${hit.why}", 0, 1)
            val token = pending
            mainHandler.postDelayed({ if (pending === token) verifySkip() }, VERIFY_DELAY_MS)
        } else {
            Log.d(TAG, "popup-click-failed $pkg")
        }
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
        for (window in windows) {
            val root = window.root ?: continue
            val wp = root.packageName?.toString() ?: continue
            if (wp == packageName || wp in blacklist) continue
            // 窗口里有"广告"标识时，跳过按钮不设位置闸（微博的跳过就在右下角）
            val adHint = adHintFor(window.id, root)
            val hit = userScanner?.findSkipTarget(root, w, h, adHint)
                ?: builtinScanner?.findSkipTarget(root, w, h, adHint)
            if (hit != null) return Triple(hit.first, wp, "win#${window.id} ${window.type}")
            // 弹窗通道点下去之后也按同一套核验：弹窗还在说明没点掉，补刀一次
            val popup = builtinScanner?.findPopupCloseTarget(root, w, h)
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

    /** 广告标识判定带 1.5 秒缓存：同一次开屏会被反复扫描，别每次都重走 200 个节点 */
    private fun adHintFor(windowId: Int, root: AccessibilityNodeInfo): Boolean {
        val now = SystemClock.elapsedRealtime()
        adHintCache[windowId]?.let { (t, v) -> if (now - t < AD_HINT_TTL_MS) return v }
        val v = adSignature(root) == true
        adHintCache[windowId] = now to v
        return v
    }

    /** 开屏类 Activity：决定能不能用"角落小按钮"兜底 */
    private fun isSplashLike(act: String): Boolean {
        if (act.isEmpty()) return false
        val low = act.lowercase(java.util.Locale.ROOT)
        return low.contains("splash") || low.contains("welcome") || low.contains("advert") ||
            low.contains("adactivity") || low.contains(".ad.")
    }

    // ———————— 坐标点按 ————————

    private fun schedulePointTaps(pkg: String, r: com.noteatoranges.skipad.db.SkipAppRule) {
        // 开屏窗口里 root 常常还是旧窗口，落点必须在真正要点的时刻算，不能提前算好
        listOf(r.delayMs, r.delayMs + 1000).forEach { d ->
            mainHandler.postDelayed({
                val root = rootInActiveWindow ?: return@postDelayed
                if (root.packageName?.toString() != pkg) return@postDelayed
                val now = SystemClock.elapsedRealtime()
                if (!gate("$pkg|${r.id}", r.cooldownMs, r.maxCount, now)) return@postDelayed
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
                    "point ${r.id} ${r.actionLabel} $pkg ok=$fired" +
                        " (${(point.first / resources.displayMetrics.widthPixels * 100).toInt()}%," +
                        "${(point.second / resources.displayMetrics.heightPixels * 100).toInt()}%)",
                )
            }, d)
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

    private fun captureNow() {
        var root = rootInActiveWindow
        // 兜底：活动窗口还是系统层时，从窗口列表里找第一个应用窗口
        if (root == null || root.packageName?.toString() == "com.android.systemui") {
            root = windows.firstOrNull {
                it.root != null && it.root.packageName?.toString() !in setOf(
                    "com.android.systemui", packageName
                )
            }?.root ?: run {
                Toast.makeText(this, R.string.snap_failed, Toast.LENGTH_SHORT).show()
                return
            }
        }
        val pkg = root.packageName?.toString() ?: return
        val act = lastActivityClass[pkg] ?: ""
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        val nodes = ArrayList<SnapshotStore.Node>(400)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var idx = 0
        while (queue.isNotEmpty() && idx < 400) {
            val node = queue.removeFirst()
            idx++
            val r = Rect()
            node.getBoundsInScreen(r)
            nodes.add(
                SnapshotStore.Node(
                    idx, node.className?.toString() ?: "",
                    node.text?.toString() ?: "", node.contentDescription?.toString() ?: "",
                    node.viewIdResourceName ?: "", node.isClickable,
                    r.left, r.top, r.right, r.bottom,
                )
            )
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        // 系统界面、加载中的空白页只能抓到一两个节点，存下来是垃圾，直接提示重抓
        if (nodes.size < 3) {
            Toast.makeText(this, "这个页面没抓到内容，换个页面再试", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor,
                object : TakeScreenshotCallback() {
                    override fun onResult(bitmap: Bitmap?) {
                        val appCtx = applicationContext
                        val nodeCount = nodes.size
                        Io.run("snapshot-save") {
                            val dir = SnapshotStore.save(appCtx, pkg, act, nodes, bitmap)
                            // 全屏图 1220×2712 一张 13MB，compress 完就没人用了，
                            // 不主动回收会一直压在 native heap，抓几次就积几十 MB
                            bitmap?.recycle()
                            StatsStore.logEvent(appCtx, "快照已保存：$pkg（$nodeCount 节点）")
                            Log.d(TAG, "snapshot saved ${dir.path}")
                        }
                        Toast.makeText(appCtx, R.string.snap_saved, Toast.LENGTH_SHORT).show()
                    }
                })
        } else {
            val appCtx = applicationContext
            val nodeCount = nodes.size
            Io.run("snapshot-save") {
                SnapshotStore.save(appCtx, pkg, act, nodes, null)
                StatsStore.logEvent(appCtx, "快照已保存：$pkg（$nodeCount 节点，无截图）")
            }
            Toast.makeText(appCtx, R.string.snap_saved, Toast.LENGTH_SHORT).show()
        }
    }

    /** takeScreenshot 回调适配（API 30+），HardwareBuffer 转 Bitmap */
    private abstract class TakeScreenshotCallback :
        AccessibilityService.TakeScreenshotCallback {
        override fun onFailure(errorCode: Int) {
            Log.w("SkipAdEngine", "takeScreenshot failed: $errorCode")
        }

        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
            val hw = android.graphics.Bitmap.wrapHardwareBuffer(
                screenshot.hardwareBuffer, screenshot.colorSpace
            )
            val bitmap = hw?.copy(Bitmap.Config.ARGB_8888, false)
            screenshot.hardwareBuffer.close()
            // 硬件位图占的是图形内存，copy 完就没用了
            hw?.recycle()
            onResult(bitmap)
        }

        abstract fun onResult(bitmap: Bitmap?)
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
                dir.listFiles()?.sortedBy { it.name }?.dropLast(20)?.forEach { it.delete() }
                File(dir, "${tag}_$ts.txt").writeText(text)
            }
        } catch (e: Exception) {
            Log.w(TAG, "dumpTree failed", e)
        }
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

        /** 内容变化事件的最小间隔：滑动信息流时一秒能来几十个，没必要每个都扫 */
        const val CONTENT_THROTTLE_MS = 900L

        /** 找锚点控件的遍历上限：锚点通常就在弹窗里，不用扫全树 */
        const val ANCHOR_SCAN_NODES = 600

        /** 弹窗点击后的冷却：同一 App 20 秒内不再点弹窗关闭钮 */
        const val POPUP_COOLDOWN_MS = 20000L
        const val UNBIND_RECOVER_DELAY_MS = 1500L
        const val VERIFY_DELAY_MS = 800L
        const val MAX_RETRY = 2
        const val SPLASH_WINDOW_MS = 12000L
        const val CORNER_X_RATIO = 0.93f
        const val CORNER_Y_RATIO = 0.065f
        const val SNAP_CHANNEL = "snapshot"
        const val SNAP_NOTIF_ID = 2001
        const val ACTION_TAKE_SNAPSHOT = "com.noteatoranges.skipad.TAKE_SNAPSHOT"

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
