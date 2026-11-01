package com.noteatoranges.skipad.core

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
        // viewId 带 skip 的基本就是广告 SDK 的跳过按钮本体（穿山甲 tt_splash_skip_btn 等）
        SkipRule(id = "ad-skip-viewid", viewIds = listOf("skip")),
        SkipRule(
            id = "splash-common-text",
            // zh-CN/zh-TW/ja/ko/en 常见"跳过"文案；contains 匹配天然覆盖"跳过 3""Skip 5s"
            patterns = listOf("跳过", "跳過", "スキップ", "건너뛰기", "skip"),
        ),
        SkipRule(
            id = "splash-countdown-regex",
            regex = true,
            patterns = listOf(
                "(?i)skip[\\s\\d]*(s|sec|秒)?",
                "\\d+\\s*(s|S|秒)\\s*(之|後|后)?\\s*跳过",
            ),
        ),
    )
}
