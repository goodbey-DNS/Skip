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
