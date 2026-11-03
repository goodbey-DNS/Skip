package com.noteatoranges.skipad.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断翻译的回归测试。
 *
 * 这些场景在真机上很难逐个造出来（放弃、点进落地页、误点拦截都得等广告出问题），
 * 但它们的输入就是一行日志文本 —— 直接喂字符串比等真机便宜得多。
 */
class DiagnosisTest {

    private val labelOf: (String) -> String = { pkg ->
        when (pkg) {
            "com.sina.weibo" -> "微博"
            "com.tencent.mm" -> "微信"
            "com.taobao.taobao" -> "淘宝"
            else -> pkg
        }
    }

    private fun one(line: String): Diagnosis.Event? =
        Diagnosis.explain(listOf(line), labelOf).firstOrNull()

    @Test
    fun `verified 第一次点掉`() {
        val e = one("10:20:30.123 verified com.sina.weibo 「跳过」 (attempts=0)")
        requireNotNull(e)
        assertEquals(Diagnosis.Tone.OK, e.tone)
        assertEquals(Diagnosis.Kind.SKIP, e.kind)
        assertEquals("微博 跳过成功", e.title)
        assertTrue(e.detail.contains("第一次点击就生效"))
        assertEquals("10:20:30", e.time)
    }

    @Test
    fun `verified 补刀后成功要报次数`() {
        val e = one("10:20:30.123 verified com.sina.weibo 「跳过」 (attempts=2)")
        requireNotNull(e)
        assertTrue(e.detail.contains("点了 3 次才生效"))
    }

    @Test
    fun `giveup 报总点击次数且不计入跳过`() {
        val e = one(
            "10:20:30.123 giveup com.sina.weibo 「跳过」目标仍在(win, cls=android.widget.TextView " +
                "clickable=false bounds=(0, 0, 10, 10)), 已补刀2次不记账"
        )
        requireNotNull(e)
        assertEquals(Diagnosis.Tone.WARN, e.tone)
        assertEquals(Diagnosis.Kind.SKIP, e.kind)
        assertEquals("微博 没跳过去，已放弃", e.title)
        assertTrue(e.detail.contains("一共 3 次"))
        assertTrue(e.detail.contains("不计入跳过"))
    }

    @Test
    fun `landing 与 off-app 都算没跳成`() {
        val landing = one("10:20:30.123 landing com.taobao.taobao 新页面出现下载类 CTA，疑似点进广告，不记账")
        requireNotNull(landing)
        assertEquals("淘宝 点进了广告落地页", landing.title)
        assertEquals(Diagnosis.Kind.SKIP, landing.kind)

        val offApp = one("10:20:30.123 off-app com.sina.weibo → com.tencent.mm，不计入跳过")
        requireNotNull(offApp)
        assertEquals("微博 点完跳到了别的应用", offApp.title)
        assertTrue(offApp.detail.contains("微信"))
    }

    @Test
    fun `userfree-skip 说明为什么不敢点`() {
        val e = one("10:20:30.123 userfree-skip com.sina.weibo 「跳过」 无广告迹象且页面像主界面，不点")
        requireNotNull(e)
        assertEquals("微博 看到「跳过」但没敢点", e.title)
        assertEquals(Diagnosis.Tone.WARN, e.tone)
    }

    @Test
    fun `miss 把拒绝理由翻成人话`() {
        val e = one("10:20:30.123 miss com.taobao.taobao 「跳过」在中央区域(cx=610,cy=800) reject=「跳过」在中央区域(cx=610,cy=800)")
        requireNotNull(e)
        assertEquals(Diagnosis.Kind.MISS, e.kind)
        assertTrue(e.title.contains("淘宝"))
        assertTrue(e.detail.contains("屏幕正中间"))
    }

    @Test
    fun `miss 的 no-root 与 EXCLUDE 各有说法`() {
        val noRoot = one("10:20:30.123 miss com.sina.weibo reject=no-root")
        requireNotNull(noRoot)
        assertTrue(noRoot.detail.contains("系统没给出"))

        val exclude = one("10:20:30.123 miss com.sina.weibo EXCLUDE「教程」整屏放弃")
        requireNotNull(exclude)
        assertTrue(exclude.detail.contains("排除的词"))
    }

    @Test
    fun `预热期放过说清楚还会不会处理`() {
        val e = one("10:20:30.123 hot-skip com.sina.weibo warmup=8000ms")
        requireNotNull(e)
        assertEquals("微博 刚重连，先放过", e.title)
        assertTrue(e.detail.contains("8 秒"))
        assertTrue(e.detail.contains("之后再遇到会正常处理"))
    }

    @Test
    fun `看了一圈没命中才出结论 命中过的不重复说`() {
        val miss = one("10:20:30.123 hot-close com.sina.weibo scans=6 hits=0")
        requireNotNull(miss)
        assertEquals("微博 看了一圈，没找到跳过按钮", miss.title)

        assertNull(one("10:20:30.123 hot-close com.sina.weibo scans=6 hits=2"))
        assertNull(one("10:20:30.123 hot-close com.sina.weibo scans=0 hits=0"))
    }

    @Test
    fun `抓取的三种结局`() {
        val ok = one("10:20:30.123 capture nodes=48 pkg=com.sina.weibo windows=2")
        requireNotNull(ok)
        assertEquals("抓取快照：找到 48 个控件", ok.title)
        assertTrue(ok.detail.contains("微博"))

        val saved = one("10:20:30.123 snapshot saved /data/user/0/x/files/snap/1 nodes=48 1220x2712")
        requireNotNull(saved)
        assertEquals("快照已保存", saved.title)

        val empty = one("10:20:30.123 capture-empty attempts=3 fg=com.sina.weibo")
        requireNotNull(empty)
        assertEquals("快照没抓到内容", empty.title)
        assertTrue(empty.detail.contains("3 次"))
    }

    @Test
    fun `服务事件单独归类 不算跳过`() {
        val conn = one("10:20:30.123 service-connected 0.17.75")
        requireNotNull(conn)
        assertEquals(Diagnosis.Kind.SERVICE, conn.kind)
        assertTrue(conn.detail.contains("0.17.75"))

        val unbind = one("10:20:30.123 service-unbind")
        requireNotNull(unbind)
        assertEquals(Diagnosis.Kind.SERVICE, unbind.kind)
        assertEquals(Diagnosis.Tone.WARN, unbind.tone)
    }

    @Test
    fun `噪声行不产生事件`() {
        val noise = listOf(
            "10:20:30.123 scan com.sina.weibo 18ms: 「跳过」在中央区域(cx=1,cy=2) reject=「跳过」",
            "10:20:30.123 wsc com.sina.weibo prev=com.miui.home fresh=true cls=com.sina.weibo.MainActivity",
            "10:20:30.123 gate-max com.sina.weibo|user:12 1/1",
            "这一行根本没有时间戳",
            "",
        )
        assertTrue(Diagnosis.explain(noise, labelOf).isEmpty())
    }

    @Test
    fun `落盘时间戳也能解析`() {
        val e = one("11-04 10:20:30.123 verified com.sina.weibo 「跳过」 (attempts=0)")
        requireNotNull(e)
        assertEquals("10:20:30", e.time)
    }

    @Test
    fun `跳过成功后紧接着的过程行不再重复出现`() {
        val events = Diagnosis.explain(
            listOf(
                "10:20:30.123 verified com.sina.weibo 「跳过」 (attempts=0)",
                // 点掉之后引擎会再开一次窗口、扫几遍没命中：这条不能和上面同时展示
                "10:20:33.123 hot-close com.sina.weibo scans=3 hits=0",
                // 一分钟后又是一次独立开屏，应该保留
                "10:21:30.123 hot-close com.sina.weibo scans=3 hits=0",
            ),
            labelOf,
        )
        assertEquals(1, events.count { it.kind == Diagnosis.Kind.SKIP })
        assertEquals(1, events.count { it.kind == Diagnosis.Kind.MISS })
    }

    @Test
    fun `统计只算跳过结果 不把服务连接算成功`() {
        val events = Diagnosis.explain(
            listOf(
                "10:20:30.123 service-connected 0.17.75",
                "10:20:31.123 service-connected 0.17.75",
                "10:20:32.123 verified com.sina.weibo 「跳过」 (attempts=0)",
                "10:20:33.123 giveup com.taobao.taobao 「跳过」目标仍在(x, cls=y), 已补刀0次不记账",
            ),
            labelOf,
        )
        val t = Diagnosis.tally(events)
        assertEquals(1, t.ok)
        assertEquals(1, t.warn)

        val last = Diagnosis.lastSkip(events)
        requireNotNull(last)
        assertEquals("微博 跳过成功", last.title)

        val service = Diagnosis.lastService(events)
        requireNotNull(service)
        assertEquals(Diagnosis.Tone.OK, service.tone)
    }
}
