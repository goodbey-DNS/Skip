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

    /** 快照广播只注册一次：onServiceConnected 可能被系统重复回调，重复注册会导致一次点击抓多张 */
    private var snapshotRegistered = false

    private var lastSkipWindow: String? = null
    private var lastSkipAt = 0L
    private var lastFgPkg: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pending: PendingSkip? = null

    private data class PendingSkip(
        val windowKey: String,
        val pkg: String,
        val detail: String,
        var attempts: Int,
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
        builtinScanner = NodeScanner(BuiltinRules.defaults())
        clicker = ClickPerformer(this)
        blacklist = PACKAGE_BLACKLIST + findHomePackages()
        KeepAlive.scheduleJob(this)
        StatsStore.logEvent(this, getString(R.string.ka_log_connected))
        postWorkNotification()
        inst = this
        if (com.noteatoranges.skipad.util.CaptureBubble.isEnabled(this)) {
            com.noteatoranges.skipad.util.CaptureBubble.apply(this)
            com.noteatoranges.skipad.util.CaptureBubble.onClick = { bubbleClicked() }
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
    fun bubbleClicked() {
        com.noteatoranges.skipad.util.CaptureBubble.hide(this)
        mainHandler.postDelayed({
            sendBroadcast(Intent(ACTION_TAKE_SNAPSHOT).setPackage(packageName))
        }, 250)
        mainHandler.postDelayed({
            if (com.noteatoranges.skipad.util.CaptureBubble.isEnabled(this)) {
                com.noteatoranges.skipad.util.CaptureBubble.apply(this)
                com.noteatoranges.skipad.util.CaptureBubble.onClick = { bubbleClicked() }
            }
        }, 1500)
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
                event.className?.toString()?.let { lastActivityClass[ep] = it }
                if (freshSwitch && ep !in blacklist) {
                    // 用户 point 规则优先
                    RuleStore.pointRules(this).filter { it.app == ep }.forEach { r ->
                        schedulePointTaps(ep, r.xRatio ?: 0.93f, r.yRatio ?: 0.065f, r.delayMs, "rule:${r.id}")
                    }
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

        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: event.packageName?.toString() ?: return
        if (pkg in blacklist || pkg == packageName) return

        // 启动窗口闸：只在"新窗口出现后 SPLASH_WINDOW_MS 内"扫描。
        // 无状态变化记录（服务在该 App 已在前台时才连上/重连）时按"现在"起算，
        // 否则 now - 0 恒大于闸门，等于这类场景永远不扫描。
        val stateAt = lastStateChangeAt[pkg] ?: now
        if (now - stateAt > SPLASH_WINDOW_MS) return

        // 同窗口冷却：刚点过的窗口在冷却期内不再重复匹配（COOLDOWN_MS 此前只定义未使用）
        if (lastSkipWindow != null && now - lastSkipAt < COOLDOWN_MS &&
            lastSkipWindow == windowKeyFor(pkg, event, root)
        ) {
            return
        }

        // 刷新用户规则扫描器（版本变化才重建）
        val ver = RuleStore.version(this)
        if (ver != userRulesVersionSeen) {
            userRulesVersionSeen = ver
            val nodeRules = RuleStore.nodeRulesAsScannerRules(this)
            userScanner = if (nodeRules.isEmpty()) null else NodeScanner(nodeRules)
        }

        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        // 用户规则优先
        val hit = userScanner?.findSkipTarget(root, w, h)
            ?: builtinScanner?.findSkipTarget(root, w, h)
        if (hit == null) {
            Log.v(TAG, "scan $pkg: ${userScanner?.lastDebug ?: builtinScanner?.lastDebug}")
            return
        }
        val detail = hit.first.text?.toString()
            ?: hit.first.contentDescription?.toString()
            ?: hit.first.viewIdResourceName
            ?: hit.second.id
        Log.d(TAG, "match $pkg: ${userScanner?.lastDebug ?: builtinScanner?.lastDebug}")
        dumpTree(root, pkg, "match")

        if (clicker?.click(hit.first) == true) {
            lastSkipWindow = windowKeyFor(pkg, event, root)
            lastSkipAt = now
            pending = PendingSkip(lastSkipWindow ?: "", pkg, detail, 0)
            val token = pending
            mainHandler.postDelayed({ if (pending === token) verifySkip() }, VERIFY_DELAY_MS)
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
        if (p.attempts >= MAX_RETRY) {
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
            val hit = userScanner?.findSkipTarget(root, w, h)
                ?: builtinScanner?.findSkipTarget(root, w, h)
            if (hit != null) return Triple(hit.first, wp, "win#${window.id} ${window.type}")
        }
        return null
    }

    // ———————— 坐标点按 ————————

    private fun schedulePointTaps(pkg: String, xRatio: Float, yRatio: Float, delayMs: Long, tag: String) {
        listOf(delayMs, delayMs + 1000).forEach { d ->
            mainHandler.postDelayed({
                val root = rootInActiveWindow ?: return@postDelayed
                if (root.packageName?.toString() != pkg) return@postDelayed
                val ok = clicker?.clickPoint(
                    resources.displayMetrics.widthPixels * xRatio,
                    resources.displayMetrics.heightPixels * yRatio,
                ) ?: false
                Log.d(TAG, "point $tag $pkg ok=$ok (${(xRatio * 100).toInt()}%,${(yRatio * 100).toInt()}%)")
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor,
                object : TakeScreenshotCallback() {
                    override fun onResult(bitmap: Bitmap?) {
                        val appCtx = applicationContext
                        val nodeCount = nodes.size
                        Io.run("snapshot-save") {
                            val dir = SnapshotStore.save(appCtx, pkg, act, nodes, bitmap)
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
        const val THROTTLE_MS = 150L
        const val COOLDOWN_MS = 4000L
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
