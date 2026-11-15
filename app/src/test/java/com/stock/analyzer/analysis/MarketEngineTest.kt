package com.stock.analyzer.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * v4.7.0 把全市场分析从 ViewModel 搬进了进程级 [MarketEngine]。
 *
 * 这类重构最怕两件事，都在这里钉住：
 *   1) 候选集合被顺手改掉 —— 分位池是"当日全样本前 50%"，样本换了分数口径就废了；
 *   2) 时段判断写错 —— 周末还在盘中节奏猛跑，或者开盘了还在睡。
 * 引擎本身要 Context 才能跑起来，这里只测这些不依赖网络的纯函数。
 */
class MarketEngineTest {

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply {
            clear()
            set(y, mo - 1, d, h, mi, 0)
        }.timeInMillis

    /** 2026-11-13 是周五，14 周六，15 周日，16 周一（用真实日历核对过） */
    private val friday = 2026 to arrayOf(11, 13)

    @Test
    fun `候选集合与上一版完全一致，只是顺序变了`() {
        val candidates = MarketEngine.candidates
        val universe = MarketEngine.universe()

        assertEquals("名单里有重复代码", candidates.size, candidates.toSet().size)
        assertEquals("抓取集合必须就是那份候选名单，一只不多一只不少",
            candidates.toSet(), universe.toSet())
        assertEquals(330, candidates.size)

        // 行情页那 30 只排在最前：它们本来就是候选名单的子集，重排不改变集合
        assertTrue("quickCodes 必须是候选名单的子集",
            candidates.containsAll(MarketEngine.quickCodes))
        assertEquals(MarketEngine.quickCodes, universe.take(MarketEngine.quickCodes.size))
    }

    @Test
    fun `盘中时段判断`() {
        val (y, md) = friday
        fun at(h: Int, m: Int, dayOffset: Int = 0) = millis(y, md[0], md[1] + dayOffset, h, m)

        assertFalse("9:15 之前不该跑", MarketEngine.inTradingSession(at(9, 14)))
        assertTrue(MarketEngine.inTradingSession(at(9, 15)))
        assertTrue("上午收盘那一刻仍算盘中（收盘数据要落地）", MarketEngine.inTradingSession(at(11, 30)))
        assertFalse("午休不发请求", MarketEngine.inTradingSession(at(11, 31)))
        assertTrue(MarketEngine.inTradingSession(at(13, 0)))
        assertTrue(MarketEngine.inTradingSession(at(15, 5)))
        assertFalse("15:06 起算收盘", MarketEngine.inTradingSession(at(15, 6)))

        assertFalse("周六不跑", MarketEngine.inTradingSession(at(10, 0, dayOffset = 1)))
        assertFalse("周日不跑", MarketEngine.inTradingSession(at(10, 0, dayOffset = 2)))
        assertTrue("下周一照跑", MarketEngine.inTradingSession(at(10, 0, dayOffset = 3)))
    }

    @Test
    fun `收盘后要睡到下一个时段开始`() {
        val (y, md) = friday
        fun at(h: Int, m: Int, dayOffset: Int = 0) = millis(y, md[0], md[1] + dayOffset, h, m)

        // 午休：离 13:00 还有 60 分钟
        assertEquals(60 * 60_000L, MarketEngine.msToNextSession(at(12, 0)))
        // 周五 15:30 → 周一 9:15：周末两天都要跳过（周六 9:15 不算时段）
        val toMonday = MarketEngine.msToNextSession(at(15, 30))
        // 2026-11-16 是周一：周五 15:30 → 周一 9:15 = 2 天零 17 小时 45 分
        val expected = millis(2026, 11, 16, 9, 15) - at(15, 30)
        assertEquals("周五收盘后应该睡到周一 9:15", expected, toMonday)
        assertEquals("上午收盘那一刻，下一站是 13:00", 90 * 60_000L, MarketEngine.msToNextSession(at(11, 30)))
        assertFalse("周六上午不算盘中", MarketEngine.inTradingSession(at(9, 30, dayOffset = 1)))
        // 开盘前：离明早 9:15
        assertEquals((9 * 60 + 15 - 6 * 60) * 60_000L, MarketEngine.msToNextSession(at(6, 0)))
    }

    /** 引擎没 start() 之前，任何状态流都该是可读的默认值 —— 页面在 Application 之前就会订阅 */
    @Test
    fun `未启动时状态流可用`() {
        val s = MarketEngine.status.value
        assertFalse(s.analyzing)
        assertFalse(s.paused)
        assertEquals(0, s.total)
        assertTrue(MarketEngine.insights.value.isEmpty())
    }
}
