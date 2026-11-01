package com.noteatoranges.skipad.core

import android.graphics.RectF

/**
 * 跳过规则模型：一条规则 = 一组匹配条件 + 一个动作。
 *
 * 0.17.0 起内置通用词表撤除，所有规则都来自用户从快照点选生成
 * （见 [com.noteatoranges.skipad.db.RuleStore]），并按 App 隔离生效。
 *
 * 防误触双约束是本模型的底线（任何匹配都必须同时过这两关）：
 * - [maxTextLength]：节点文本超长视为正文里提到关键字，不是按钮；
 * - [maxAreaRatio]：可点击祖先面积占屏比例过大视为大图上印的假按钮。
 */
data class SkipRule(
    val id: String,
    /** regex=false 时为关键字（忽略大小写包含匹配）；regex=true 时为正则 */
    val patterns: List<String> = emptyList(),
    val regex: Boolean = false,
    /** viewId 资源名包含匹配（忽略大小写，如 "skip" 命中 tt_splash_skip_btn）；命中免文本与位置闸 */
    val viewIds: List<String> = emptyList(),
    val maxTextLength: Int = 24,
    val maxAreaRatio: Float = 0.12f,
    /**
     * 是否允许命中屏幕中央的主体区。
     *
     * 内置通用词（“跳过”）必须保留位置闸：正文里出现“跳过”四个字不该被当成按钮。
     * 但用户手输的词是明确指定——“K50”“放弃福利”这些就是他要点的东西，
     * 而开屏页的按钮又常摆在中央，再卡位置闸就把用户写的话吹了。
     */
    val allowCenter: Boolean = false,
    /**
     * 用户点选时那个控件在屏幕上的位置（四边比例 0..1）。
     *
     * 有它就只认「这块位置附近」的命中，取代「屏幕正中一律算正文」的粗闸：
     * 中央的按钮能点，而页面上别处出现的同一串字点不动——用位置代替几何猜测。
     */
    val zone: RectF? = null,
    val enabled: Boolean = true,
    /** 命中后的动作：click（默认）/ back（返回键）/ none（只记录不动手） */
    val action: String = ACTION_CLICK,
    /** 同一条规则两次动作之间的最小间隔，0 = 不限 */
    val cooldownMs: Long = 0L,
    /** 同一条规则累计动作次数上限，0 = 不限（换页面时清零） */
    val maxCount: Int = 0,
) {
    companion object {
        const val ACTION_CLICK = "click"
        const val ACTION_BACK = "back"
        const val ACTION_NONE = "none"
    }
}

/**
 * 内置开屏规则：**只允许在开屏窗口期内使用**（见 SkipAccessibilityService.isSplashWindow）。
 *
 * 0.17.0 曾把这一组整块删掉，原因是它没有场景边界，在聊天页、信息流里也会跑。
 * 0.17.1 按「开屏 = App 刚切到前台的头几秒」这个硬边界收回来：窗口之外一条都不用，
 * 于是它的形态约束（位置闸/面积闸/长度闸/负向词）只需要对付开屏页那一种布局。
 */
object BuiltinRules {

    fun defaults(): List<SkipRule> = listOf(
        // viewId 带 skip 的基本就是广告 SDK 的跳过按钮本体（穿山甲 tt_splash_skip_btn 等）；
        // 冷却/次数上限：没有闸时一个窗口内会把页面上所有像跳过的控件都点一遍，
        // 第二下落在已经跳走的页面上就是一次没有意义的点击
        SkipRule(
            id = "ad-skip-viewid",
            viewIds = listOf("skip"),
            cooldownMs = BUILTIN_COOLDOWN_MS,
            maxCount = BUILTIN_MAX_COUNT,
        ),
        SkipRule(
            id = "splash-common-text",
            // zh-CN/zh-TW/ja/ko/en 常见"跳过"文案；contains 匹配天然覆盖"跳过 3""Skip 5s"
            patterns = listOf("跳过", "跳過", "スキップ", "건너떄기", "skip"),
            cooldownMs = BUILTIN_COOLDOWN_MS,
            maxCount = BUILTIN_MAX_COUNT,
        ),
        SkipRule(
            id = "splash-countdown-regex",
            regex = true,
            patterns = listOf(
                "(?i)skip[\\s\\d]*(s|sec|秒)?",
                "\\d+\\s*(s|S|秒)\\s*(之|后|後)?\\s*跳过",
            ),
            cooldownMs = BUILTIN_COOLDOWN_MS,
            maxCount = BUILTIN_MAX_COUNT,
        ),
    )

    /** 内置词两次动作的最小间隔：同一窗口内不再连点 */
    private const val BUILTIN_COOLDOWN_MS = 1200L

    /** 内置词单页面动作次数上限：一个开屏窗冪最多点两下，多出来的定位不准 */
    private const val BUILTIN_MAX_COUNT = 2
}
