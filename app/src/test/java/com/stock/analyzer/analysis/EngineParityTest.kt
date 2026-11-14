package com.stock.analyzer.analysis

import com.stock.analyzer.data.model.AdviceType
import com.stock.analyzer.data.model.KLineData
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 与回测脚本的口径对齐测试。
 *
 * engine_parity.json 由 backtest/export_parity.py 生成：真实A股日线 + Python 回测引擎
 * （backtest/final_validate.py 的 new_fn，即上线的那套评分）逐bar算出的期望分数。
 * 只要 Kotlin 引擎与回测有一根K线的判断不一致，这个测试就会红。
 */
class EngineParityTest {

    private class Bar {
        var d: String = ""
        var o: Double = 0.0
        var c: Double = 0.0
        var h: Double = 0.0
        var l: Double = 0.0
        var v: Long = 0
    }

    private class StockFx {
        var code: String = ""
        var bars: List<Bar> = emptyList()
        var expected: List<List<Double>> = emptyList()
    }

    private class Fixture {
        var stocks: List<StockFx> = emptyList()
    }

    private fun load(): Fixture {
        val stream = javaClass.classLoader!!.getResourceAsStream("engine_parity.json")
        assertTrue("缺少测试夹具 engine_parity.json（先跑 backtest/export_parity.py）", stream != null)
        return Gson().fromJson(stream.reader().readText(), Fixture::class.java)
    }

    private fun toK(bars: List<Bar>) = bars.map { KLineData(it.d, it.o, it.c, it.h, it.l, it.v) }

    @Test
    fun `kotlin score matches backtest bar by bar`() {
        val fx = load()
        val analyzer = StockAnalyzer()
        var checked = 0
        var compared = 0
        val diffs = ArrayList<String>()

        for (s in fx.stocks) {
            val k = toK(s.bars)
            for (t in k.indices) {
                val res = analyzer.analyze(k.subList(0, t + 1))
                val exp = s.expected[t]
                val expOk = exp[1] == 1.0
                checked++
                if (res.dataSufficient != expOk) {
                    diffs.add("${s.code} ${s.bars[t].d}: 可判断性 kotlin=${res.dataSufficient} backtest=$expOk")
                    continue
                }
                if (!expOk) continue
                compared++
                val expScore = Math.round(exp[0]).toInt()
                if (res.score != expScore) {
                    diffs.add("${s.code} ${s.bars[t].d}: kotlin=${res.score} backtest=$expScore")
                }
            }
        }
        println("parity: bars=$checked, scored=$compared, mismatches=${diffs.size}")
        diffs.take(20).forEach { println("  MISMATCH $it") }
        assertTrue("Kotlin 引擎与回测不一致：${diffs.take(5)}", diffs.isEmpty())
        assertTrue("对比样本太少", compared > 2000)
    }

    @Test
    fun `advice thresholds and price ranges are consistent`() {
        val fx = load()
        val analyzer = StockAnalyzer()
        for (s in fx.stocks) {
            val k = toK(s.bars)
            if (k.size < StockAnalyzer.BARS_MIN) continue
            val last = k.last()
            val stock = com.stock.analyzer.data.model.PortfolioStock(
                code = s.code, name = s.code, buyPrice = last.close, quantity = 100,
                buyDate = System.currentTimeMillis(), currentPrice = last.close
            )
            val a = analyzer.analyze(k)
            val advice = analyzer.generatePositionAdvice(stock, k)

            // 分档与回测的阈值必须一致，且最高只到 BUY（≥76 分那一档回测为负，不发强烈买入）
            if (a.dataSufficient) {
                when {
                    a.score >= StockAnalyzer.BUY_SCORE -> assertEquals(AdviceType.BUY, advice.advice)
                    a.score > StockAnalyzer.SELL_SCORE -> assertEquals(AdviceType.HOLD, advice.advice)
                    a.score > StockAnalyzer.STRONG_SELL_SCORE -> assertEquals(AdviceType.SELL, advice.advice)
                    else -> assertEquals(AdviceType.STRONG_SELL, advice.advice)
                }
                assertTrue("不该发出强烈加仓", advice.advice != AdviceType.STRONG_BUY)
            } else {
                assertEquals(AdviceType.HOLD, advice.advice)
            }

            // 两个区间永远可见且有序
            assertTrue(advice.addPriceRange.low > 0)
            assertTrue(advice.addPriceRange.low < advice.addPriceRange.high)
            assertTrue(advice.reducePriceRange.low <= advice.reducePriceRange.high)

            // 止损必须低于现价、且不超过 20%
            advice.stopLoss?.let {
                assertTrue("stop=$it price=${last.close}", it < last.close && it >= last.close * 0.80)
            }
            assertTrue(advice.riskNote!!.isNotEmpty())
        }
    }

    /** 每日列表 = 前 50% 分位池 + 分位等距取样，不是评分排行榜，也没有绝对门槛 */
    @Test
    fun `daily list is stratified across the relative top-50 pool`() {
        val analyzer = StockAnalyzer()
        val mk: (Int) -> com.stock.analyzer.data.model.StockRecommendation = { score ->
            val st = com.stock.analyzer.data.model.Stock(
                code = "c$score", name = "s$score", price = 10.0, change = 0.0, changePercent = 0.0,
                volume = 0, turnover = 0.0, high = 10.0, low = 10.0, open = 10.0, close = 10.0,
                timestamp = 0L
            )
            com.stock.analyzer.data.model.StockRecommendation(
                rank = 0, stock = st,
                analysis = com.stock.analyzer.data.model.AnalysisResult(
                    null, null, null, null, null, null,
                    com.stock.analyzer.data.model.Recommendation.BUY, emptyList(), score,
                    dataSufficient = true
                ),
                buyPriceRange = null, sellPriceRange = null
            )
        }
        val all = (0 until 100).map { mk(100 - it) }         // 100 只，分数 100..1 递减
        // 池 = ceil(100 × 0.50) = 50 只，即 100..51；低于中位的那一半靠"排名"出局，不靠绝对门槛
        assertEquals(50, analyzer.dailyPool(all).size)
        assertEquals(50, analyzer.eligibleCount(all))
        assertEquals(51, analyzer.dailyPool(all).last().analysis.score)

        val scores = analyzer.selectDailyCandidates(all, 30).map { it.analysis.score }
        assertEquals(30, scores.size)
        assertEquals("默认只数就是上线的 30 只", StockAnalyzer.DAILY_PICKS, scores.size)
        assertEquals("首名仍是池内最高分", 100, scores.first())
        // 池内 50 只按 pool[i*(q-1)/30] 取样 → index 0,1,3,4,...,47 → 最低 53 分（池底 51、52 不在名单里）
        assertEquals("取样应单调递减且不重复", scores.size, scores.toSet().size)
        assertTrue("取样应覆盖池子大部: $scores", scores.first() - scores.last() >= 40)
        assertTrue("末名应来自池子中后段，证明不是 top-N: $scores", scores.last() < 70)
        assertEquals(53, scores.last())

        // 奇数只：ceil(41 × 0.50) = 21
        assertEquals(21, analyzer.dailyPool((0 until 41).map { mk(100 - it) }).size)
        // 全场都在加仓线以下也必须给出名单（旧规则此时会空）
        val weak = (20..29).map { mk(it) }                 // 10 只，分数全部 < 68
        assertEquals(5, analyzer.dailyPool(weak).size)
        assertEquals(5, analyzer.selectDailyCandidates(weak, 30).size)
        // 池子不足 picks 只时全给
        assertTrue(analyzer.selectDailyCandidates((0 until 8).map { mk(90 - it) }, 30).size == 4)
        // K 线不足的不入池
        val shortBar = com.stock.analyzer.data.model.AnalysisResult(
            null, null, null, null, null, null,
            com.stock.analyzer.data.model.Recommendation.BUY, emptyList(), 99, dataSufficient = false
        )
        val withBad = all.take(10) + all.first().copy(analysis = shortBar)
        assertEquals(5, analyzer.dailyPool(withBad).size)
    }
}
