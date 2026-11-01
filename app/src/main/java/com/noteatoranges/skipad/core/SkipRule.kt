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
     * 内置通用词专用：关键字必须落在文本的**开头或结尾**才算命中。
     *
     * 内置词是两三个字的通用词（跳过 / skip），纯包含匹配会把「广告跳过项目」
     * 「怎么跳过这一步」这类会话标题、正文列表项一起点掉——TeleAgent 那次就是
     * 侧边栏会话标题含「跳过」被当成开屏按钮点了。
     *
     * 而真实开屏按钮的文案，关键字几乎总在首尾（「跳过」「跳过广告」
     * 「5 秒后跳过」「跳过广告，进入APP」），首尾约束不误杀任何一种。
     * 用户自己写的词不走这条：他写「放弃福利」就是明确要包含匹配。
     */
    val strictText: Boolean = false,
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
    /**
     * 命中节点附近必须出现的文案（自身、父节点或兄弟节点任一命中即可）。
     *
     * 专治「倒计时数字被当成按钮」：开屏页右上角常年挂着一个孤零零的「5」，
     * 单看文本它就是个数字，正则 `^\d+s?$` 一抓一大把——正文里的楼层号、价格、
     * 消息数都能中。真实结构是「数字 + 紧邻的跳过文案」，两个节点互为兄弟或父子。
     * 空列表 = 不做这项校验（用户规则一律为空，不进这条路径）。
     */
    val neighborHints: List<String> = emptyList(),
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
 *
 * 0.17.29 补了两处实测出来的缺口（依据是 2026-11-01 的对照实验：关掉内置词表后
 * 五个 App 冷启动零命中，说明开屏成败全压在这一组上，漏一类就是整类漏掉）：
 * - 广告 SDK 的关闭按钮大多不带 skip 字样（csj_btn_close / topon_btn_close / ksad_*），
 *   原来只认 "skip"，等于把聚合 SDK 的开屏全放过；
 * - 倒计时数字单看文本没有判别力，必须和旁边的跳过文案成对出现。
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
        // 主流广告 SDK 的关闭钮命名集合。这些都是各家 SDK 的固定前缀，
        // 跨版本改名概率远低于业务代码；命中走 viewId 通道，免文本与位置闸
        SkipRule(
            id = "ad-sdk-viewid",
            viewIds = listOf(
                "tt_splash_skip",   // 穿山甲开屏
                "csj_btn_close",    // 穿山甲通用关闭
                "csj_ad_logo",      // 穿山甲广告角标（可点，点了也算关）
                "topon_",           // TopOn 聚合
                "ksad_skip",        // 快手联盟
                "gdt_",             // 优量汇
                "hiad_",            // 华为广告
            ),
            cooldownMs = BUILTIN_COOLDOWN_MS,
            maxCount = BUILTIN_MAX_COUNT,
        ),
        SkipRule(
            id = "splash-common-text",
            // zh-CN/zh-TW/ja/ko/en 常见"跳过"文案；contains 匹配天然覆盖"跳过 3""Skip 5s"
            // 韩语是 건너뛰기（ttwi-），此前误写成 건너떄기，韩语界面永远匹配不到
            patterns = listOf("跳过", "跳過", "スキップ", "건너뛰기", "skip"),
            // 但这些是两三个字的通用词，包含匹配会把会话标题、列表正文一起点掉
            // （实测：「广告跳过项目」被当成开屏按钮）。要求关键字落在首尾，
            // 真实按钮文案都满足，误伤面收窄到零
            strictText = true,
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
        // 纯倒计时数字单独成一条，不能并进上面那条：上面那两条正则能匹配任意位置的
        // 「skip 5s」，一旦共用一个 zone，按钮挪到屏幕下方就全废了。这条只管数字，
        // 才敢上位置 + 邻近文案两道约束
        SkipRule(
            id = "splash-countdown-num",
            regex = true,
            patterns = listOf("^\\s*\\d{1,2}\\s*(s|S|秒)?\\s*$"),
            // 倒计时按钮固定在顶部两角，给一个宽裕的带：横向右侧 40% 起、纵向顶部 45% 内
            zone = RectF(0.4f, 0f, 1f, 0.45f),
            // 数字本身不是按钮，旁边必须有跳过/关闭字样才算
            neighborHints = listOf("跳过", "跳過", "关闭", "關閉", "skip", "スキップ"),
            cooldownMs = BUILTIN_COOLDOWN_MS,
            maxCount = BUILTIN_MAX_COUNT,
        ),
    )

    /** 内置词两次动作的最小间隔：同一窗口内不再连点 */
    private const val BUILTIN_COOLDOWN_MS = 1200L

    /** 内置词单页面动作次数上限：一个开屏窗冪最多点两下，多出来的定位不准 */
    private const val BUILTIN_MAX_COUNT = 2
}
