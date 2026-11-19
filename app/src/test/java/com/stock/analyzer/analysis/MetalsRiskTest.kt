package com.stock.analyzer.analysis

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 贵金属「风险参考」的口径对齐测试。
 *
 * 夹具 `metals_risk_parity.json` 由 `backtest/metals/export_risk_parity.py` 生成：
 * 输入是 backtest/metals/metals_data 里**上一轮回测用的那一批新浪日线**（与界面上这 6 个
 * 品种同一个接口、同一份历史），期望值是同一份脚本里逐行照着 [MetalsRisk] 写的 Python 算出来的。
 *
 * 它把关三件事，缺一件这个功能就不该上线：
 *   1) Kotlin 与回测脚本算的是同一个算法（波动、仓位轨迹、偏离、分位）；
 *   2) 界面上显示的 target 必须是 iter12 预注册跑过的那三档之一；
 *   3) 界面印出来的那个字符串必须等于 iter12 §[3] 打印的读数 —— 前两条防的是逻辑漂移，
 *      这一条拿回测输出的**原文**卡着，改参数的人必须同时改判据才能让它过。
 */
class MetalsRiskTest {

    private class GaugeFx {
        var vol: Double = Double.NaN
        var weight: Double = Double.NaN
        var target: Double = 0.0
        var bars: Int = 0
        var changes: Int = 0
        var ties: Int = -1
        var changes_per_year: Double = Double.NaN
        var tail: List<Double> = emptyList()
    }

    private class ExpectFx {
        var vol: Double = Double.NaN
        var bars_total: Int = 0
        var last_date: String = ""
        var gauge: Map<String, GaugeFx> = emptyMap()
    }

    private class SeriesFx {
        var source: String = ""
        var dates: List<String> = emptyList()
        var closes: List<Double> = emptyList()
    }

    private class DeviationFx {
        var z: Double = Double.NaN
        var percentile: Double = Double.NaN
        var as_of: String = ""
        var n_z: Int = 0
        var n_pairs: Int = 0
    }

    private class Fixture {
        var series: Map<String, SeriesFx> = emptyMap()
        var expect: Map<String, ExpectFx> = emptyMap()
        var deviations: Map<String, DeviationFx> = emptyMap()
        var iter12_table: Map<String, List<Double>> = emptyMap()
    }

    private fun load(): Fixture {
        val stream = javaClass.classLoader!!.getResourceAsStream("metals_risk_parity.json")
        assertTrue("缺少测试夹具 metals_risk_parity.json（先跑 backtest/metals/export_risk_parity.py）",
            stream != null)
        return Gson().fromJson(stream.reader().readText(), Fixture::class.java)
    }

    /** 尾部 400 根逐根对：只比最后一个数的话，回放顺序写反了也照样能撞上 */
    @Test
    fun `weight path matches the backtest mirror day by day`() {
        val fx = load()
        val diffs = ArrayList<String>()
        var compared = 0
        for ((code, s) in fx.series) {
            val exp = fx.expect[code] ?: continue
            for ((key, g) in exp.gauge) {
                val path = MetalsRisk.weightPath(s.closes, key.toDouble())
                if (path == null) { diffs.add("$code @$key: kotlin 判历史不足，回测却有数"); continue }
                val from = path.size - g.tail.size
                for (j in g.tail.indices) {
                    compared++
                    val got = path[from + j]
                    val want = g.tail[j]
                    if (got != want) diffs.add("$code @$key 第 ${from + j} 根 kotlin=$got backtest=$want")
                }
            }
        }
        assertTrue("没比对到任何一根", compared > 1000)
        assertTrue("仓位轨迹与回测镜像不一致：\n" + diffs.take(20).joinToString("\n"), diffs.isEmpty())
    }

    @Test
    fun `gauge numbers match the backtest mirror`() {
        val fx = load()
        val bad = ArrayList<String>()
        for ((code, s) in fx.series) {
            val exp = fx.expect[code] ?: continue
            for ((key, g) in exp.gauge) {
                val got = MetalsRisk.gauge(s.closes, key.toDouble())
                if (got == null) { bad.add("$code @$key: kotlin 返回 null"); continue }
                if (abs(got.vol - g.vol) > 1e-12) bad.add("$code @$key 波动 kotlin=${got.vol} backtest=${g.vol}")
                if (abs(got.weight - g.weight) > 1e-12)
                    bad.add("$code @$key 仓位 kotlin=${got.weight} backtest=${g.weight}")
                if (got.bars != g.bars) bad.add("$code @$key 可用根 kotlin=${got.bars} backtest=${g.bars}")
                if (abs(got.changesPerYear - g.changes_per_year) > 1e-9)
                    bad.add("$code @$key 次/年 kotlin=${got.changesPerYear} backtest=${g.changes_per_year}")
            }
        }
        assertTrue("风险刻度与回测镜像不一致：\n" + bad.joinToString("\n"), bad.isEmpty())
        // 界面那一档必须是 iter12 预注册跑过的三档之一
        assertTrue("界面档位不在预注册清单里", listOf(0.10, 0.15, 0.20).contains(MetalsRisk.TARGET_VOL))
    }

    /**
     * 界面印出来的字符串 = iter12 §[3] 表里的数。
     * 上一条测试的期望值是脚本跟着 Kotlin 一起生成的，改口径时会一起变；
     * 这一条卡的是回测输出的原文，动参数就必须同时动判据。
     */
    @Test
    fun `displayed readings equal the ones iter12 printed`() {
        val fx = load()
        val bad = ArrayList<String>()
        var checked = 0
        for ((code, s) in fx.series) {
            val table = fx.iter12_table[s.source] ?: continue
            val vol = MetalsRisk.volAnnualized(s.closes)
            if (vol == null) { bad.add("$code: 波动算不出来"); continue }
            checked++
            val volText = String.format("%.1f%%", table[0])
            if (String.format("%.1f%%", vol * 100) != volText)
                bad.add("$code 波动 界面=${"%.1f".format(vol * 100)}% iter12=$volText")
            listOf("0.10", "0.15", "0.20").forEachIndexed { i, key ->
                val g = MetalsRisk.gauge(s.closes, key.toDouble())
                if (g == null) { bad.add("$code @$key: null"); return@forEachIndexed }
                checked++
                val want = String.format("%.0f%%", table[i + 1])
                if (g.weightPercentText != want) bad.add("$code @$key 界面=${g.weightPercentText} iter12=$want")
            }
        }
        assertEquals("该核的读数条数不对（6 条序列 × 1 波动 + 3 档仓位）", 24, checked)
        assertTrue("界面显示的数不是回测里那个：\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `deviation readings match the backtest mirror`() {
        val fx = load()
        val series = fx.series
        val lookup: (String) -> Pair<List<String>, List<Double>>? = { code ->
            series[code]?.let { it.dates to it.closes }
        }
        val plan = mapOf(
            "ratio_XAU_XAG" to MetalsRisk.ReadingSpec("金银比（金/银）",
                MetalsRisk.ReadingKind.RATIO, "hf_XAU", "hf_XAG"),
            "ratio_XPT_XPD" to MetalsRisk.ReadingSpec("铂钯比（铂/钯）",
                MetalsRisk.ReadingKind.RATIO, "hf_XPT", "hf_XPD"),
            "premium_AU0_XAU" to MetalsRisk.ReadingSpec("沪金 vs 伦敦金",
                MetalsRisk.ReadingKind.PREMIUM, "nf_AU0", "hf_XAU"),
            "premium_AG0_XAG" to MetalsRisk.ReadingSpec("沪银 vs 伦敦银",
                MetalsRisk.ReadingKind.PREMIUM, "nf_AG0", "hf_XAG")
        )
        for ((key, spec) in plan) {
            val exp = fx.deviations[key] ?: error("夹具里没有 $key 这条读数")
            val got = MetalsRisk.deviation(spec, lookup)
            assertTrue("$key: kotlin 返回 null（回测却有数）", got != null)
            assertEquals("$key z", exp.z, got!!.z, 1e-9)
            assertEquals("$key 分位", exp.percentile, got.percentile, 1e-9)
            assertEquals("$key 截至日", exp.as_of, got.asOf)
        }
        // 这四个读数必须就是界面上那四对的算法：比值用 inner join，境内外用「≤ 当日最后一根外盘」
        val xau = series["hf_XAU"]!!
        val xag = series["hf_XAG"]!!
        val viaRatio = MetalsRisk.ratio("金银比（金/银）", xau.dates, xau.closes, xag.dates, xag.closes)!!
        assertEquals("ratio() 与配对表算出来的不是同一个数", plan["ratio_XAU_XAG"]!!.label, viaRatio.label)
        assertEquals(0.0, abs(viaRatio.z - fx.deviations["ratio_XAU_XAG"]!!.z), 1e-12)
    }

    /** 配对表必须是回测里跑过的那几对；换一对就等于显示一个没验证过的数 */
    @Test
    fun `reading plan only uses pairs the backtest ran`() {
        val known = setOf(
            Triple(MetalsRisk.ReadingKind.RATIO, "hf_XAU", "hf_XAG"),
            Triple(MetalsRisk.ReadingKind.RATIO, "hf_XPT", "hf_XPD"),
            Triple(MetalsRisk.ReadingKind.PREMIUM, "nf_AU0", "hf_XAU"),
            Triple(MetalsRisk.ReadingKind.PREMIUM, "nf_AG0", "hf_XAG")
        )
        val codes = listOf("hf_XAU", "hf_XAG", "hf_XPT", "hf_XPD", "nf_AU0", "nf_AG0")
        for (code in codes) {
            val specs = MetalsRisk.readingsFor(code)
            assertTrue("$code 没有读数", specs.isNotEmpty())
            for (s in specs) {
                assertTrue("$code 的配对 ${s.label} 不在回测跑过的那四对里",
                    known.contains(Triple(s.kind, s.numCode, s.denCode)))
            }
        }
        assertTrue("不认识的代码不该给读数", MetalsRisk.readingsFor("sh600000").isEmpty())
        // 同一个读数不管从哪一页进去都必须是同一个方向（金银比永远是金/银）
        assertEquals(MetalsRisk.readingsFor("hf_XAU")[0].numCode, MetalsRisk.readingsFor("hf_XAG")[0].numCode)
    }

    @Test
    fun `weight stays inside the pre-registered band`() {
        val fx = load()
        var seen = 0
        for ((_, s) in fx.series) {
            for (t in listOf(0.10, 0.15, 0.20)) {
                val path = MetalsRisk.weightPath(s.closes, t) ?: continue
                for (p in path) {
                    if (!p.isFinite()) continue
                    seen++
                    // 下限 25%、上限满仓、永远落在 5% 的格子上
                    assertTrue("仓位越界：$p", p >= MetalsRisk.FLOOR - 1e-12 && p <= 1.0 + 1e-12)
                    val k = p / MetalsRisk.STEP
                    assertTrue("仓位不在 5% 的格子上：$p", abs(k - Math.rint(k)) < 1e-9)
                }
            }
        }
        assertTrue("没检查到足够的仓位点", seen > 10000)
    }

    @Test
    fun `history too short gives no number instead of a made one up`() {
        val short = (1..129).map { 100.0 + it % 3 }
        assertTrue(MetalsRisk.gauge(short) == null)
        assertTrue(MetalsRisk.weightPath(short) == null)
        // 130 根够了，但波动窗口要 21 个收益 —— 前 21 根不给仓位
        val ok = (1..200).map { 100.0 + (it % 7) * 0.5 }
        val path = MetalsRisk.weightPath(ok)
        assertTrue(path != null)
        assertTrue("前 21 根不该有仓位", path!!.take(MetalsRisk.VOL_WINDOW).all { !it.isFinite() })
        assertTrue("最后一段该有仓位", path.last().isFinite())
        // 价格里出现 0 或负数：整条判为不可用，不拿能算的那截凑一个数
        assertTrue(MetalsRisk.gauge(ok.toMutableList().apply { this[50] = 0.0 }) == null)
        assertTrue(MetalsRisk.volAnnualized(ok.toMutableList().apply { this[50] = -1.0 }) == null)
        assertTrue(MetalsRisk.volAnnualized(List(MetalsRisk.VOL_WINDOW) { 100.0 }) == null)
        // 一条完全不动的序列：标准差 0，不给波动也不给仓位
        val flatline = List(400) { 100.0 }
        assertTrue(MetalsRisk.volAnnualized(flatline) == null)
        assertTrue(MetalsRisk.gauge(flatline) == null)
    }

    /**
     * 取整规则单独钉一下。
     *
     * 先说清楚它的分量：`export_risk_parity.py` 数过，这 6 条序列 23400 根日线里
     * target/σ 一次都没正好落在档位中间（平局 0 次），所以这条规则**不改变任何已显示的数**。
     * 写死它、并且用 0.125 这种浮点上真是 .5 的输入测它，只是为了两份实现（Python 的
     * `round()` 与 Kotlin 的 `Math.rint`）将来不各自漂移。
     */
    @Test
    fun `step rounding ties to the even grid like python round`() {
        assertEquals(0.10, MetalsRisk.stepOf(0.125), 1e-12)     // 2.5 档 → 取偶 2 档
        assertEquals(0.20, MetalsRisk.stepOf(0.225), 1e-12)     // 4.5 档 → 取偶 4 档
        assertEquals(0.30, MetalsRisk.stepOf(0.325), 1e-12)     // 6.5 档 → 取偶 6 档
        assertEquals(0.40, MetalsRisk.stepOf(0.375), 1e-12)     // 7.5 档 → 取偶 8 档
        // 非平局就是普通的就近
        assertEquals(0.35, MetalsRisk.stepOf(0.34), 1e-12)
        assertEquals(0.30, MetalsRisk.stepOf(0.31), 1e-12)
        assertEquals(1.00, MetalsRisk.stepOf(1.0), 1e-12)
        assertEquals(0.00, MetalsRisk.stepOf(0.01), 1e-12)
    }

    /**
     * 界面文案的底线：这一类不给带方向的判断（iter12 预注册那条产品口径 ——
     * C1/C3/C7 不过而 C2 过，这个数只能作为风险刻度显示，文案不得出现"该买/该卖/会涨/会跌"）。
     */
    @Test
    fun `metals copy never gives a direction`() {
        val banned = listOf("会涨", "会跌", "该买", "该卖", "建议买入", "建议卖出", "目标价", "抄底")
        val texts = com.stock.analyzer.ui.nonstock.NonStockCopy.run {
            noAdvice(com.stock.analyzer.data.model.AssetClass.PRECIOUS_METALS) +
                readingRules(com.stock.analyzer.data.model.AssetClass.PRECIOUS_METALS) +
                riskCardNote(com.stock.analyzer.data.model.AssetClass.PRECIOUS_METALS)
        }
        assertTrue("文案条数不对（noAdvice + readingRules + riskCardNote）", texts.size >= 13)
        for ((i, line) in texts.withIndex()) {
            for (word in banned) {
                assertTrue("贵金属文案第 ${i + 1} 条出现了「$word」：$line", !line.contains(word))
            }
        }
    }
}
