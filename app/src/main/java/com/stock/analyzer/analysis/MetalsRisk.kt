package com.stock.analyzer.analysis

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 贵金属这一类的「风险刻度」：波动目标折算的仓位、以及两个相对偏离读数。
 *
 * 它**不是**A 股那套评分，也不会输出「加仓/减仓/持有」这种带方向的判断。
 * 依据在 `backtest/metals/README.md`（iter9~iter12，2026-11-17）：
 *
 *   · iter9：把 8 族 20 个贵金属原生择时口径（均线在场 / 双均线 / 时序动量 / 波动率目标 /
 *     唐奇安突破 / ATR 跟踪止损 / 趋势内回调 / 带宽阶梯）用**出厂那套 A 股公式之外的东西**
 *     跑了一遍，判据 C1..C7 预注册。结果 **20 个配置 0 个通过**：扣掉正确的一天滞后后，
 *     它们相对「一直持有」的日超额点估计全为负（−0.6 ~ −3.5bp），其中 6 个的 95%CI 整段在零下。
 *     （预注册表头那句「7 族 21 个配置」数错了：21 含基准 F0，族数是 8。）
 *   · iter10：外盘收盘→境内的 lead-lag 拆成三种配对后，重叠版 IC +0.53~+0.66
 *     （就是以前界面上那句 corr=0.66 的来源），干净版 IC −0.04~+0.02、涨−跌 −1.8bp。
 *     **那句话是窗口重叠造出来的，不是领先关系**，界面上已经改掉。
 *   · iter11：iter10 那条「溢价太贵就减半仓」当时标着"干净滞后"，其实还是 L=1 泄漏版
 *     （OOS 夏普 1.51~2.07、日超额 CI 整段在零上）。换成真正到账的 L=2 之后，
 *     6 组配对 × 2 个窗口共 12 个配置夏普掉到 0.08~0.68，CI **全部**整段在零下。
 *   · iter12：唯一在 IS 与 OOS 都稳定成立的东西是**波动率目标对回撤的作用**——
 *     判据 C2（OOS + IS 最大回撤都优于一直持有）**4/4 资产**，平均把 OOS 最大回撤做小
 *     15.0pp（target 20%）/ 23.4pp（15%）/ 30.9pp（10%）。
 *     但它对收益没有证据：日超额 95%CI 三档都含 0（15% 那档 −1.1bp [−3.9, +1.0]），
 *     C1 夏普只有 1/4 达标，**C5（0.30%/边下夏普仍 ≥ 基准 +0.10）只有 1/4**，
 *     银与铂的年化分别比一直持有少 3.0pp 与 1.4pp。
 *     （未取整的 iter9 F4 那一版，回撤优势在 0.30%/边下仍 4/4 —— 那是**另一版规则**的数，
 *     不能安在界面这个 F9 上，所以这里不引用它。）
 *     按预注册那条产品口径：**只当风险刻度显示，不当买卖依据**。
 *
 * 所以这里只放两样有证据的东西：
 *   1) 一个**风险刻度**（σ 与目标波动决定仓位，纯算术，不给方向）；
 *   2) 两个**相对读数**（金银比 / 铂钯比 / 境内外溢价 偏离自己 120 日常态多少个 σ，描述性）。
 * 界面文案不许出现「会涨/会跌/该买/该卖」。
 *
 * 数值口径与回测脚本逐条对齐，由 `MetalsRiskTest` 用 Python 生成的夹具把关：
 * 波动率是 21 日收盘收益的**总体**标准差（ddof=0）× √252；取整用 half-even
 * （`java.lang.Math.rint`，与 Python 内置 `round()` 同一条规则；`export_risk_parity.py`
 * 数过：这 6 条序列一次都没踩到 .5 平局，所以这条规则不改动任何一个显示的数，
 * 写死它只是为了让两份实现不在边界上分叉）；
 * 调仓频率的算法与 iter12 §[4] 一致（只数可用位之间发生变化的次数，除以「可用位数 / 252」年）。
 * 这三点任一改动都会让界面显示的数和回测里跑的不是同一个东西。
 */
object MetalsRisk {

    /** 与 iter9/iter12 相同：21 日窗口、252 交易日年化 */
    const val VOL_WINDOW = 21
    const val TRADING_DAYS = 252.0

    /** iter12 预声明的三个档位里界面用的那一个（10%/15%/20% 都跑过，15% 是中间档） */
    const val TARGET_VOL = 0.15
    const val STEP = 0.05
    const val BAND = 0.10
    const val FLOOR = 0.25

    /** 偏离度窗口：与 iter10/iter11 的 z(120) 同一条公式 */
    const val Z_WINDOW = 120

    /** 历史不足 130 根就不给数（和 A 股那套 MIN_HIST 同一个门槛，少一个都不硬算） */
    const val MIN_BARS = 130

    /**
     * 风险刻度。[weight] 是「按 [target] 年化波动目标折算的仓位」，0.25~1.0，
     * 已按 [STEP] 取整、按 [BAND] 过滤掉一天挪几个点的小动作。
     * [bars] 是能算出波动、因而参与了回放的那几根日线；[changesPerYear] 是这套规则
     * 在 [bars] 根里平均一年动几次。算法与 iter12 §[4] 同一条公式（数变化的次数 ÷ 年数），
     * 只是那段回测是在 2021 年以后的样本上量的（15% 档 8.5~16.3 次/年），这里量的是
     * 手机上这一整段历史，两个数不该被当成同一个数念。
     */
    data class Gauge(
        val vol: Double,
        val weight: Double,
        val target: Double,
        val bars: Int,
        val changesPerYear: Double
    ) {
        val volPercentText: String get() = String.format(Locale.US, "%.1f%%", vol * 100)
        val weightPercentText: String get() = String.format(Locale.US, "%.0f%%", weight * 100)

        val cadenceText: String
            get() = String.format(Locale.US, "按这套规则，%d 根日线里平均一年调 %.1f 次", bars, changesPerYear)
    }

    /** 相对偏离读数。[z] 是「偏离自己 [Z_WINDOW] 日常态多少个 σ」，正=更贵 */
    data class Deviation(
        val label: String,
        val z: Double,
        val percentile: Double,
        val asOf: String
    ) {
        val zText: String get() = String.format(Locale.US, "%+.2fσ", z)
        val percentileText: String get() = String.format(Locale.US, "%.1f%%", percentile)
    }

    /**
     * 读数的配对表：谁跟谁比、怎么比、叫什么。
     *
     * 这张表写死在这里而不是让界面自己拼，是因为它必须是回测里跑过的那几对
     * （iter10 §[5]、iter11 §[6] 用的是同一组）：[RATIO] 是同币种比值（同日 inner join），
     * [PREMIUM] 是境内外对照（境内日期为轴，外盘取「≤ 该日期」的最后一根）。
     * 换一对没跑过的组合显示出来，那个数就没有依据了。
     */
    enum class ReadingKind { RATIO, PREMIUM }

    data class ReadingSpec(
        val label: String,
        val kind: ReadingKind,
        val numCode: String,      // RATIO 的分子 / PREMIUM 的境内
        val denCode: String       // RATIO 的分母 / PREMIUM 的外盘参照
    )

    /**
     * 每个品种配哪些读数。金银比永远是「金/银」，不因为打开的是白银那一页就翻过来——
     * 翻过来数值符号相反，历史分位也要跟着重算，那是另一个读数。
     */
    fun readingsFor(code: String): List<ReadingSpec> = when (code) {
        "hf_XAU" -> listOf(
            ReadingSpec("金银比（金/银）", ReadingKind.RATIO, "hf_XAU", "hf_XAG"),
            ReadingSpec("沪金 vs 伦敦金", ReadingKind.PREMIUM, "nf_AU0", "hf_XAU")
        )
        "hf_XAG" -> listOf(
            ReadingSpec("金银比（金/银）", ReadingKind.RATIO, "hf_XAU", "hf_XAG"),
            ReadingSpec("沪银 vs 伦敦银", ReadingKind.PREMIUM, "nf_AG0", "hf_XAG")
        )
        "hf_XPT" -> listOf(ReadingSpec("铂钯比（铂/钯）", ReadingKind.RATIO, "hf_XPT", "hf_XPD"))
        "hf_XPD" -> listOf(ReadingSpec("铂钯比（铂/钯）", ReadingKind.RATIO, "hf_XPT", "hf_XPD"))
        "nf_AU0" -> listOf(ReadingSpec("沪金 vs 伦敦金", ReadingKind.PREMIUM, "nf_AU0", "hf_XAU"))
        "nf_AG0" -> listOf(ReadingSpec("沪银 vs 伦敦银", ReadingKind.PREMIUM, "nf_AG0", "hf_XAG"))
        else -> emptyList()
    }

    /** 按 [ReadingSpec] 取一个读数；两条序列里任何一条还没到手就返回 null，不硬凑 */
    fun deviation(
        spec: ReadingSpec,
        series: (String) -> Pair<List<String>, List<Double>>?
    ): Deviation? {
        val a = series(spec.numCode) ?: return null
        val b = series(spec.denCode) ?: return null
        return when (spec.kind) {
            ReadingKind.RATIO -> ratio(spec.label, a.first, a.second, b.first, b.second)
            ReadingKind.PREMIUM -> premium(spec.label, a.first, a.second, b.first, b.second)
        }
    }

    // ───────────────────────────── 波动与仓位 ─────────────────────────────

    /**
     * 21 日滚动年化波动（总体标准差 × √252），返回最后一个可用值。
     * 历史不够 [VOL_WINDOW] + 1 根时返回 null —— 不给没有依据的数。
     */
    fun volAnnualized(closes: List<Double>): Double? {
        if (closes.size < VOL_WINDOW + 1) return null
        val ret = returns(closes) ?: return null
        val from = ret.size - VOL_WINDOW
        var sum = 0.0
        for (i in from until ret.size) sum += ret[i]
        val mean = sum / VOL_WINDOW
        var sq = 0.0
        for (i in from until ret.size) {
            val d = ret[i] - mean
            sq += d * d
        }
        val sd = sqrt(sq / VOL_WINDOW)          // ddof=0：与 Python rolling.std(ddof=0) 一致
        return if (sd <= 0.0) null else sd * sqrt(TRADING_DAYS)
    }

    /** 日收盘收益序列；出现非正价格就整体判为不可用（贵金属不该有 0 价） */
    private fun returns(closes: List<Double>): DoubleArray? {
        val r = DoubleArray(closes.size) { Double.NaN }
        for (i in 1 until closes.size) {
            val p = closes[i - 1]
            val c = closes[i]
            if (!p.isFinite() || !c.isFinite() || p <= 0.0 || c <= 0.0) return null
            r[i] = c / p - 1.0
        }
        return r
    }

    /**
     * 波动目标仓位序列（与 iter12 的 `pos_f9` 逐日一致）。
     *
     * 必须**整段回放**而不是只看最后一天：取整 + 不动区间是有状态的，
     * 只用最后一天会算出另一个数，而那已经不是回测里验证过的那个东西了。
     * 返回 null 表示历史不足 [MIN_BARS]。
     */
    fun weightPath(closes: List<Double>, target: Double = TARGET_VOL): DoubleArray? {
        if (closes.size < MIN_BARS) return null
        val ret = returns(closes) ?: return null
        val n = closes.size
        val out = DoubleArray(n) { Double.NaN }
        var cur = 1.0
        var lastVol = Double.NaN
        // 与 pandas rolling(min_periods=WINDOW) 一样：前 WINDOW 个收益位没有波动值
        for (i in VOL_WINDOW until n) {
            var sum = 0.0
            for (k in (i - VOL_WINDOW + 1) until i + 1) sum += ret[k]
            val mean = sum / VOL_WINDOW
            var sq = 0.0
            for (k in (i - VOL_WINDOW + 1) until i + 1) {
                val d = ret[k] - mean
                sq += d * d
            }
            val v = sqrt(sq / VOL_WINDOW) * sqrt(TRADING_DAYS)
            if (!v.isFinite() || v <= 0.0) continue
            lastVol = v
            val step = stepOf(min(1.0, target / v))
            if (abs(step - cur) >= BAND) cur = step
            out[i] = max(cur, FLOOR)
        }
        if (!lastVol.isFinite()) return null
        return out
    }

    /**
     * 把 target/σ 折到 5% 一档的格子上。
     *
     * 取整规则写成单独一个函数，是因为它是 [STEP] 这条产品规则唯一落地的地方：
     * half-even（`Math.rint`，与 Python 内置 `round()` 同一条规则），换成四舍五入
     * 就会在 .5 平局上跟回测分叉。`export_risk_parity.py` 数过：这 6 条序列
     * 23400 根日线里一次都没踩到平局，所以这条规则不改动任何一个已显示的数，
     * 它的作用只是保证两份实现将来不会各自漂移。
     */
    internal fun stepOf(raw: Double): Double = java.lang.Math.rint(raw / STEP) * STEP

    /** 界面要的那一个数：最后一个可用的仓位 + 它的波动、以及这套规则在这一整段历史里多久动一次 */
    fun gauge(closes: List<Double>, target: Double = TARGET_VOL): Gauge? {
        val path = weightPath(closes, target) ?: return null
        val vol = volAnnualized(closes) ?: return null
        var valid = 0
        var changes = 0
        var last = Double.NaN
        var lastIdx = -1
        for (i in path.indices) {
            val p = path[i]
            if (!p.isFinite()) continue
            if (last.isFinite() && abs(p - last) > 1e-9) changes++
            last = p
            valid++
            lastIdx = i
        }
        if (lastIdx < 0 || valid < 2) return null
        return Gauge(vol = vol, weight = path[lastIdx], target = target, bars = valid,
            changesPerYear = changes / (valid / TRADING_DAYS))
    }

    // ───────────────────────────── 相对偏离 ─────────────────────────────

    /**
     * 境内外溢价：以**境内日期**为轴，外盘取「≤ 该日期」的最后一根收盘（外盘那天休市不会瞎补）。
     * spread = log(境内) − log(外盘)；z = (spread − MA_k) / std_k（总体标准差）。
     *
     * 两个市场的计价货币不同（元/克 vs 美元/盎司），所以这个数**不是**"溢价多少元"，
     * 只是"偏离自己 120 日常态多少个 σ"。文案里必须这么说。
     */
    fun premium(
        label: String,
        domDates: List<String>,
        domCloses: List<Double>,
        ovsDates: List<String>,
        ovsCloses: List<Double>,
        window: Int = Z_WINDOW
    ): Deviation? = deviation(label, pairByLookup(domDates, domCloses, ovsDates, ovsCloses), window)

    /** 同币种比值（金银比、铂钯比）：同日 inner join */
    fun ratio(
        label: String,
        numDates: List<String>,
        numCloses: List<Double>,
        denDates: List<String>,
        denCloses: List<Double>,
        window: Int = Z_WINDOW
    ): Deviation? = deviation(label, pairByInnerJoin(numDates, numCloses, denDates, denCloses), window)

    private fun deviation(label: String, pairs: List<Pair<String, Double>>, window: Int): Deviation? {
        if (pairs.size < window + MIN_BARS) return null
        val s = DoubleArray(pairs.size) { ln(pairs[it].second) }
        // 分位要跟**整段** z 序列比，只拿最近一段比会把常态自己当成历史
        val zs = ArrayList<Double>(s.size - window + 1)
        var lastIdx = -1
        for (i in window - 1 until s.size) {
            var sum = 0.0
            for (k in (i - window + 1) until i + 1) sum += s[k]
            val ma = sum / window
            var sq = 0.0
            for (k in (i - window + 1) until i + 1) {
                val d = s[k] - ma
                sq += d * d
            }
            val sd = sqrt(sq / window)
            if (sd <= 0.0) continue
            val z = (s[i] - ma) / sd
            if (!z.isFinite()) continue
            zs.add(z)
            lastIdx = i                      // 最后一个可用读数是哪一天，界面要按它标时间
        }
        if (lastIdx < 0) return null
        val cur = zs.last()
        val pct = zs.count { it <= cur } * 100.0 / zs.size
        return Deviation(label, cur, pct, pairs[lastIdx].first)
    }

    private fun pairByLookup(
        domDates: List<String>,
        domCloses: List<Double>,
        ovsDates: List<String>,
        ovsCloses: List<Double>
    ): List<Pair<String, Double>> {
        val out = ArrayList<Pair<String, Double>>(domDates.size)
        var j = -1                                    // 指向 ovs 里 ≤ 当前境内日期的最后一根
        for (i in domDates.indices) {
            while (j + 1 < ovsDates.size && ovsDates[j + 1] <= domDates[i]) j++
            if (j < 0) continue
            val a = domCloses[i]
            val b = ovsCloses[j]
            if (a.isFinite() && b.isFinite() && a > 0 && b > 0) out.add(domDates[i] to (a / b))
        }
        return out
    }

    private fun pairByInnerJoin(
        numDates: List<String>,
        numCloses: List<Double>,
        denDates: List<String>,
        denCloses: List<Double>
    ): List<Pair<String, Double>> {
        val den = HashMap<String, Double>(denDates.size)
        denDates.forEachIndexed { i, d -> den[d] = denCloses[i] }
        val out = ArrayList<Pair<String, Double>>(numDates.size)
        for (i in numDates.indices) {
            val b = den[numDates[i]] ?: continue
            val a = numCloses[i]
            if (a.isFinite() && b.isFinite() && a > 0 && b > 0) out.add(numDates[i] to (a / b))
        }
        return out
    }
}
