package com.stock.analyzer.analysis

import com.stock.analyzer.data.model.AdviceType
import com.stock.analyzer.data.model.AnalysisResult
import com.stock.analyzer.data.model.KDJResult
import com.stock.analyzer.data.model.KLineData
import com.stock.analyzer.data.model.MACDResult
import com.stock.analyzer.data.model.PriceRange
import com.stock.analyzer.data.model.PortfolioStock
import com.stock.analyzer.data.model.PositionAdvice
import com.stock.analyzer.data.model.Recommendation
import com.stock.analyzer.data.model.Signal
import com.stock.analyzer.data.model.SignalStrength
import com.stock.analyzer.data.model.SignalType
import com.stock.analyzer.data.model.Stock
import com.stock.analyzer.data.model.StockRecommendation
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * 技术分析引擎 v4.3.0
 *
 * ## 评分模型从哪来
 * 不是拍脑袋调出来的，而是在 288 只 A 股（当前市值 > 200 亿）的真实日线上回测出来的：
 * 数据 2024-06 ~ 2026-11（约 2.4 年、560 根K线），信号日 2024-12-25 ~ 2026-11-13 共 456 个
 * 交易日，样本外为 2026-04-23 之后（116 个交易日）。协议写死在 backtest/strategy.py：
 *   信号只用第 t 天收盘前的数据 → 第 t+1 天开盘买入（开盘即涨停的买不到，剔除）
 *   → 持有 20 个交易日 → 扣 0.12% 成本 → 与"同一天全样本平均收益"相减得超额收益
 *   → 按【信号日期】等权统计（每个交易日算一票，避免某天集中爆发把结果带偏）
 *   → 样本外 = 2026-04-23 之后；参数只在样本内定，样本外只验证一次。
 *
 * ## 进入评分的只有三项（其余都在回测里被否掉了）
 *   score = 50
 *         + 趋势结构：现价 / MA20 / MA60 的排列方向，按 MA20 的 10 日斜率分档 ±6 / ±14 / ±22
 *         + 短期排列：现价 > MA10 > MA20 → +4，否则 −2
 *         − 波动惩罚：max(0, ATR%每日 − 2.0%) × 200
 *   结果钳制在 0..100。
 *
 * ## 实测（v4.3.0 定稿规则，20 日持有；基准有两个，别混）
 *   基准A = 288 只等权·每日买入持有 = +38.9%；基准B = 同一篮子、每 20 日再平衡 = +62.6%±10.5%。
 *   这 23.7 个百分点是"再平衡"本身赚的，不是算法赚的 —— 拿换仓组合去比 A 是不公平的（iter27）。
 *   定稿 = 当日分数前 50% 池 · 分位等距 30 只 · 等权 · 持满 20 个交易日 · 默认不强制止损：
 *     日均超额 +0.669%（Newey-West t_HAC +1.51，块自助 p 0.068），样本外（2026-04-23 后）+1.198%；
 *     20 个换仓相位累计 +86.1%±19.6%，19/20 个相位跑赢基准B、20/20 跑赢基准A，平均最大回撤 −11.1%；
 *     分月 23 个：65% 为正，中位 +0.51%，最差 −2.75%（2026-07），最好 +5.25%（2026-05）；
 *     模拟真实用户（20 种抽法 × 20 个相位 = 400 条曲线）：超基准B 中位 +22.4pp、p10 −4.0pp、
 *     跑赢概率 85%；累计中位 +89.0%、p10 +48.1%、最差 +22.9%；回撤中位 −10.9%、最差 −27.5%；
 *     单期（20 个交易日）胜率 67%，中位 +2.67%，1.9% 的期次亏超 10%，最差一期 −20.8%（iter36）。
 *   安慰剂（同规模 30 只，iter36 第 2 节）：池内最高分 30 只 −0.430%（只有 4/20 相位跑赢基准）、
 *     全市场随机 30 只 +0.005%、后 50% 随机 30 只 −0.966%（t_HAC −1.92，0/20 相位）
 *     —— 超额来自分数排序，但"只买最高分"是反指；分层取样 vs 排行榜的配对差 +1.100%，
 *     t_HAC +1.91、块自助 p 0.035，这是整套规则里唯一勉强过 5% 显著性的结论。
 *   与 v4.2.0（同样前 50% 池、只取 20 只）配对：日均差 −0.078%、t_HAC −0.61、p 0.750；
 *     与 v4.1.0（≥68 全部等权）配对：日均差 +0.181%、t_HAC +0.36、p 0.337。
 *     诚实的说法：三只数、两种池口径在**收益**上都无法区分，改的是稳定性和可执行性（见下）。
 *   单只层面（不变）：加仓档 score≥68 样本外 20 日超额 +2.86%，胜率 51.0%，亏超 10% 17.0%，
 *     亏超 20% 4.5%（旧算法同期：均值 −1.27%，胜率 43.0%，亏超 10% 24.2%，亏超 20% 9.4%）；
 *     减仓档 score≤42 超额 −1.11%，方向正确。
 *   名单构成（历史 456 个信号日、30 个槽位）：加仓档 38.8%、持有档 53.7%、减仓档 7.5%、
 *     强烈减仓 0.1%；名单里最低分的中位数 44.2 —— 所以"没到 68 分"出现在列表里是设计如此。
 *   还要提醒的一点：288 只是按"当前市值 > 200 亿"挑的，自带幸存者偏差；真实样本外只有 21~23 个月、
 *     约 N/H≈23 个独立观测，所有参数都落在噪声带里，上面的区间估计比点估计更可信。
 *
 * ## 为什么是 30 只（iter33 / iter34 / iter35，全部在同一批信号日、同一协议下）
 *   · 收益上"多少只"根本不是变量：K = 10/15/20/25/30/40/50/60/80/120/满池(133) 的日均超额
 *     全在 +0.47%~+0.77% 之间，配对检验 |t_HAC| ≤ 0.61、p ≥ 0.44，怎么都不显著。
 *   · 稳的是分散带来的尾部改善（400 条曲线的下沿）：超基准B 的 p10 从 K=20 的 −11.2pp
 *     抬到 K=30 的 −4.0pp、K=60 的 −1.1pp；回撤最差 −34.8% → −27.5% → −25.0%；跑赢概率 78% → 85% → 88%。
 *   · 上界不是统计而是下单：A 股 100 股一手（科创板 200 股），30 只名单里最贵的那一只中位 334 元、
 *     p90 1399 元 —— 严格等权 30 只的本金门槛中位约 159 万，散户不可能满足；5 万本金实际只能
 *     覆盖约 41% 的槽位（有效约 11 只），10 万约 62%（17 只），20 万约 78%（22 只）。
 *     名单照给 30 只是因为它定义"该买哪一类"，钱不够时按同分位带往下替代，而不是去买前几名。
 *   · 5 元最低佣金：K=30 时单只 3.3% 仓位，本金 10 万 → 0.15%/边（与回测 0.12% 基本一致），
 *     5 万 → 0.30%/边（两年绝对收益多损耗约 8pp），3 万 → 0.50%/边（约 17pp）—— 小资金必须减只数。
 *   · 再往上不划算：K=40 相对 K=30 只把 p10 从 −4.0pp 抬到 −2.9pp（噪声内），最差回撤反而更深
 *     （−30.8% vs −27.5%），而 5 万本金的手续费从 0.30% 涨到 0.40%/边、每 20 日下单笔数从 49 涨到 61。
 *   · 30 只 = 在"尾部确实更稳"和"小资金还下得动"之间取的那个点，不是收益最优（收益上没有最优点）。
 *
 * ## 明确不做的事（都测过，都是负结果或不可复现的收益）
 *   · RSI/MACD/KDJ 加减分：旧版本靠这些打分，实测分档与未来收益几乎无关甚至倒挂。
 *   · "跌破 20 日低点加仓"：旧版加仓区间就是这么算的，本质是给下跌趋势摊平。
 *   · 超买超卖反转、乖离率惩罚、20 日动量加成：平均收益更高但月度胜率掉到 43%~50%、
 *     下跌月更差 —— 靠拥挤动量赚平均、亏在尾部，不上线。
 *   · ATR>4.5% 一刀切拒绝：整体超额 −0.48%，所以只用连续惩罚，不做硬门槛。
 *   · 不出"强烈买入"档：score≥76 那一档实测为负（高分 = 低波动 + 已涨一段的拥挤股）。
 *   · 不按评分排序出前几名：详见 [selectDailyCandidates]。
 *   · 入池不加绝对分数门槛：在 30 只定稿规则上配对检验，日均超额差 ≥31 −0.145%、≥43 −0.259%、
 *     ≥55 −0.168%、≥68 −0.181%，最差单期 −16.6% / −22.2% / −31.9% / −18.9%（iter37）；
 *     都不显著，但点估计全为负，而且门槛越高尾部越差 —— 剔掉池底几只等于把取样挤回分数头部。
 *   · 不做择时：跌破大盘 40 日均线空仓，超基准B 从 +27.9pp 掉到 +15.6pp，半仓同样更差（iter27）。
 *   · 不默认机械止损：30 只口径下，2×ATR 把平均最大回撤 −11.1%→−5.6%、最差单期 −16.6%→−6.7%、
 *     亏超 10% 的期次 1.4%→0.0%，代价是日均超额 +0.669%→−0.157%、累计 +86.1%→+58.8%。
 *     止损价照旧显示 —— 它买的是尾部安全，不是收益，这个取舍留给用户（iter36 第 4 节）。
 *
 * 诚实的结论：这套模型能稳定跑赢"同一篮子大盘每 20 日再平衡"的概率约八成半（400 条曲线 85%），
 * 回撤下沿比 20 只版本明显改善，但它不是稳赚，也测不出"稳赢 10%"。
 * A 股短期方向本身不可预测，必须配合仓位上限。
 */
class StockAnalyzer {

    companion object {
        /** 波动惩罚起点：日均真实波幅 2.0% 以下不扣分 */
        const val ATR_KNEE = 0.020
        const val ATR_PEN = 200.0
        const val SLOPE_STRONG = 0.015

        const val BUY_SCORE = 68          // 加仓线
        const val SELL_SCORE = 42         // 减仓线
        const val STRONG_SELL_SCORE = 30  // 强烈减仓线

        /** MA60 及其 10 日斜率都能算出来的最少 K 线数 */
        const val BARS_MIN = 71
        /** 与回测口径完全一致所需的最少 K 线数（Wilder 递推充分收敛） */
        const val BARS_FULL = 130

        /**
         * 每日候选列表按评分分位等距取样的只数（等权，单只 ≈ 总资产 3.3%）。
         * 只数不是越多越好也不是越少越好，依据见 [selectDailyCandidates] 与 backtest/iter33~35.py。
         */
        const val DAILY_PICKS = 30
        /** 分散池 = 当日可评分股票按分数排名前 50%（分数在当日中位数以上），不设绝对门槛 */
        const val POOL_FRACTION = 0.50
    }

    // ------------------------------------------------------------------ 评分

    /** 回测打分函数的忠实移植。返回 (分数, 是否有足够数据判断)。 */
    private fun score(f: Feat, i: Int): Pair<Int, Boolean> {
        val ma10 = f.ma10[i]
        val ma20 = f.ma20[i]
        val ma60 = f.ma60[i]
        val slope = f.ma20Slope[i]
        if (nan(ma10) || nan(ma20) || nan(ma60) || nan(slope)) return 0 to false
        val c = f.close[i]
        var s = 50.0
        val up = c > ma20 && ma20 > ma60
        val dn = c < ma20 && ma20 < ma60
        when {
            up -> s += if (slope > SLOPE_STRONG) 22.0 else if (slope > 0.0) 14.0 else 6.0
            dn -> s -= if (slope < -SLOPE_STRONG) 22.0 else if (slope < 0.0) 14.0 else 6.0
            else -> s -= 3.0
        }
        s += if (c > ma10 && ma10 > ma20) 4.0 else -2.0
        if (!nan(f.atrPct[i])) s -= max(0.0, f.atrPct[i] - ATR_KNEE) * ATR_PEN
        return round(min(100.0, max(0.0, s))).toInt() to true
    }

    private fun nan(v: Double) = v.isNaN()

    private fun opt(v: Double): Double? = if (v.isNaN()) null else v

    // ------------------------------------------------------------------ 分析

    /**
     * 综合分析：分数只由趋势结构 + 波动决定，RSI/MACD/KDJ/ADX/量能只作为提示信号。
     */
    fun analyze(klineData: List<KLineData>): AnalysisResult {
        if (klineData.isEmpty()) {
            return AnalysisResult(
                ma5 = null, ma10 = null, ma20 = null, rsi = null,
                macd = null, kdj = null,
                recommendation = Recommendation.WATCH,
                signals = emptyList(), score = 0,
                trendState = "无数据",
                riskNote = "没有K线数据，无法判断趋势，不给加减仓建议",
                confidence = "数据不足",
                dataSufficient = false
            )
        }

        val f = Feat.build(klineData)
        val i = f.n - 1
        val (sc, ok) = score(f, i)
        val price = f.close[i]

        val ma10 = opt(f.ma10[i])
        val ma20 = opt(f.ma20[i])
        val ma60 = opt(f.ma60[i])
        val atr = opt(f.atr[i])
        val atrPct = opt(f.atrPct[i])

        val trendState = when {
            ma20 == null || ma60 == null -> "数据不足"
            price > ma20 && ma20 > ma60 -> "多头排列"
            price < ma20 && ma20 < ma60 -> "空头排列"
            else -> "均线纠缠"
        }

        val stop = stopLoss(price, atr, ma20)

        return AnalysisResult(
            ma5 = opt(f.ma5[i]), ma10 = ma10, ma20 = ma20,
            rsi = opt(f.rsi[i]),
            macd = MACDResult(dif = f.macd.dif[i], dea = f.macd.dea[i], macd = f.macd.hist[i]),
            kdj = KDJResult(k = f.kdj.k[i], d = f.kdj.d[i], j = f.kdj.j[i]),
            recommendation = when {
                !ok -> Recommendation.WATCH
                sc >= BUY_SCORE -> Recommendation.BUY
                sc > SELL_SCORE -> Recommendation.HOLD
                else -> Recommendation.SELL
            },
            signals = buildSignals(f, i, sc, ok, trendState),
            score = sc,
            ma60 = ma60,
            atr = atr,
            atrPercent = atrPct,
            adx = opt(f.adx.adx[i]),
            trendState = trendState,
            stopLoss = stop,
            riskNote = riskNote(price, atrPct, ma20, stop, ok, f.n),
            confidence = confidence(sc, ok),
            dataSufficient = ok
        )
    }

    // ------------------------------------------------------------------ 信号（只展示，不参与评分）

    private fun buildSignals(
        f: Feat, i: Int, sc: Int, ok: Boolean, trendState: String
    ): List<Signal> {
        val out = ArrayList<Signal>()
        val c = f.close[i]
        val ma10 = f.ma10[i]
        val ma20 = f.ma20[i]
        val ma60 = f.ma60[i]
        val slope = f.ma20Slope[i]

        if (!ok) {
            out.add(
                Signal(
                    SignalType.TREND_DOWN,
                    "K线仅 ${f.n} 根，算不出 MA60 及其斜率（需 ≥$BARS_MIN 根），暂不评分",
                    SignalStrength.STRONG
                )
            )
        }

        if (!nan(ma20) && !nan(ma60) && !nan(slope)) {
            val pct = String.format("%.1f", slope * 100)
            when (trendState) {
                "多头排列" -> out.add(
                    Signal(SignalType.TREND_UP, "现价>MA20>MA60，MA20 十日 $pct% —— 趋势结构成立", SignalStrength.STRONG)
                )
                "空头排列" -> out.add(
                    Signal(SignalType.TREND_DOWN, "现价<MA20<MA60，MA20 十日 $pct% —— 空头排列，反弹不追", SignalStrength.STRONG)
                )
                else -> out.add(
                    Signal(SignalType.TREND_DOWN, "均线纠缠（现价与 MA20/MA60 不同向），趋势信号易失效", SignalStrength.MEDIUM)
                )
            }
            if (!nan(ma10)) {
                out.add(
                    if (c > ma10 && ma10 > ma20)
                        Signal(SignalType.TREND_UP, "短期多头：现价>MA10>MA20", SignalStrength.MEDIUM)
                    else
                        Signal(SignalType.TREND_DOWN, "短期未形成现价>MA10>MA20", SignalStrength.MEDIUM)
                )
            }
        }

        val ap = f.atrPct[i]
        if (!nan(ap)) {
            val v = String.format("%.1f", ap * 100)
            when {
                ap > 0.045 -> out.add(Signal(SignalType.OVERBOUGHT, "日均真实波幅 $v%，极端波动：涨跌都会被放大", SignalStrength.STRONG))
                ap > 0.035 -> out.add(Signal(SignalType.OVERBOUGHT, "日均真实波幅 $v%，波动偏高，止损需放宽、仓位需下调", SignalStrength.MEDIUM))
                ap >= ATR_KNEE -> out.add(Signal(SignalType.OVERBOUGHT, "日均真实波幅 $v%，波动中等", SignalStrength.WEAK))
                else -> out.add(Signal(SignalType.TREND_UP, "日均真实波幅 $v%，波动温和", SignalStrength.WEAK))
            }
        }

        // MACD：用对齐后的整条序列判断真正的穿越事件，而不是只比最后一天的大小
        if (i >= 3) {
            val dif = f.macd.dif
            val dea = f.macd.dea
            var golden = false
            var dead = false
            for (j in (i - 2)..i) {
                if (nan(dif[j - 1]) || nan(dea[j - 1])) continue
                if (dif[j - 1] <= dea[j - 1] && dif[j] > dea[j]) golden = true
                if (dif[j - 1] >= dea[j - 1] && dif[j] < dea[j]) dead = true
            }
            when {
                golden -> out.add(Signal(SignalType.GOLDEN_CROSS, "MACD 三日内金叉（DIF 上穿 DEA）", SignalStrength.MEDIUM))
                dead -> out.add(Signal(SignalType.DEATH_CROSS, "MACD 三日内死叉（DIF 下穿 DEA）", SignalStrength.MEDIUM))
                dif[i] > dea[i] -> out.add(Signal(SignalType.GOLDEN_CROSS, "MACD 多头运行，但无新增金叉", SignalStrength.WEAK))
                else -> out.add(Signal(SignalType.DEATH_CROSS, "MACD 空头运行", SignalStrength.WEAK))
            }
        }

        val r = f.rsi[i]
        if (!nan(r)) {
            val v = String.format("%.1f", r)
            when {
                r >= 70 -> out.add(Signal(SignalType.OVERBOUGHT, "RSI $v 超买（仅提示，不参与评分）", SignalStrength.MEDIUM))
                r <= 30 -> out.add(Signal(SignalType.OVERSOLD, "RSI $v 超卖（仅提示，超卖不等于买点）", SignalStrength.MEDIUM))
                else -> out.add(Signal(SignalType.OVERSOLD, "RSI $v 中性（仅提示，不参与评分）", SignalStrength.WEAK))
            }
        }

        val kd = f.kdj
        if (i >= 1 && !nan(kd.k[i]) && !nan(kd.k[i - 1])) {
            if (kd.k[i] > kd.d[i] && kd.k[i - 1] <= kd.d[i - 1])
                out.add(Signal(SignalType.GOLDEN_CROSS, "KDJ 金叉（短线提示，可能与中线趋势背离）", SignalStrength.WEAK))
            else if (kd.k[i] < kd.d[i] && kd.k[i - 1] >= kd.d[i - 1])
                out.add(Signal(SignalType.DEATH_CROSS, "KDJ 死叉（短线提示）", SignalStrength.WEAK))
        }

        val ax = f.adx.adx[i]
        if (!nan(ax)) {
            val v = String.format("%.1f", ax)
            if (ax >= 25) out.add(Signal(SignalType.TREND_UP, "ADX $v，趋势强度足够", SignalStrength.MEDIUM))
            else out.add(Signal(SignalType.TREND_DOWN, "ADX $v，趋势强度偏弱，均线信号容易反复打脸", SignalStrength.WEAK))
        }

        if (i >= 1) {
            val vr = f.volRatio[i]
            if (!nan(vr) && vr >= 1.5) {
                val v = String.format("%.2f", vr)
                if (c >= f.close[i - 1])
                    out.add(Signal(SignalType.BREAKOUT, "成交量为前 5 日均量 $v 倍，放量上涨", SignalStrength.WEAK))
                else
                    out.add(Signal(SignalType.BREAKDOWN, "成交量为前 5 日均量 $v 倍，放量下跌", SignalStrength.MEDIUM))
            }
        }

        if (i >= 20) {
            var hi = Double.NEGATIVE_INFINITY
            var lo = Double.POSITIVE_INFINITY
            for (t in (i - 20) until i) {
                if (f.high[t] > hi) hi = f.high[t]
                if (f.low[t] < lo) lo = f.low[t]
            }
            if (c >= hi) out.add(Signal(SignalType.BREAKOUT, "创 20 日新高（不加分：回测里新高股并未更好）", SignalStrength.WEAK))
            if (c <= lo) out.add(Signal(SignalType.BREAKDOWN, "创 20 日新低（不回补：回测里抄底是负超额）", SignalStrength.WEAK))
        }

        if (ok && sc in (SELL_SCORE + 1) until BUY_SCORE) {
            out.add(Signal(SignalType.TREND_DOWN, "评分 $sc 未达加仓线 $BUY_SCORE：只持有，不新增", SignalStrength.MEDIUM))
        }
        return out
    }

    // ------------------------------------------------------------------ 风险文案

    private fun riskNote(
        price: Double, atrPct: Double?, ma20: Double?, stop: Double?, ok: Boolean, bars: Int
    ): String {
        if (!ok) return "K线仅 $bars 根（需 ≥$BARS_MIN），本轮不给加减仓建议，仅可继续持有观察。"
        return buildString {
            if (atrPct != null) {
                val pct = String.format("%.1f", atrPct * 100)
                append("日均真实波幅 $pct%")
                append(
                    when {
                        atrPct > 0.045 -> "（极端）：单日就能吃掉本金近 5%，仓位应减半 "
                        atrPct > 0.035 -> "（偏高）：单笔金额相应下调 "
                        atrPct >= ATR_KNEE -> "（中等） "
                        else -> "（温和） "
                    }
                )
                append("；")
            }
            if (stop != null && price > 0) {
                val pct = String.format("%.1f", (1 - stop / price) * 100)
                append("止损参考 $stop（约 −$pct%）")
                if (ma20 != null && ma20 < price) append("，或收盘跌破 MA20 ${String.format("%.2f", ma20)}，两者先到哪个走哪个")
                append("；照这个价机械止损的历史代价：名单最大回撤 −11%→−6%、最差一期 −17%→−7%，" +
                    "同期累计 +86%→+59% —— 止损买的是尾部安全，不是收益；")
            }
            append("仓位上限：单笔最大亏损控制在本金 2% 以内，可买股数 ≈ 本金×2% ÷（现价−止损）" +
                "—— 按名单 30 只等权（单只约 3.3%）时这条通常自动满足，重仓一两只时才要照式子减")
        }
    }

    private fun confidence(sc: Int, ok: Boolean): String {
        if (!ok) return "数据不足，回测中无此情形"
        return when {
            sc >= BUY_SCORE ->
                "回测参考：该档样本外 20 日超额 +2.86%，胜率约 51%，亏超 10% 占 17%，亏超 20% 占 4.5%（历史数据，不承诺未来）"
            sc > SELL_SCORE ->
                "回测参考：该档未形成稳定超额，历史上以持有为主、不新增仓位"
            else ->
                "回测参考：该档 20 日平均跑输基准 −1.1%，属于历史上确实更容易亏的一段"
        }
    }

    // ------------------------------------------------------------------ 价格区间

    /**
     * 加仓区间 = 回踩均线带，而不是"跌到 20 日最低点去摊平"（后者是旧版最大的坑）。
     * 多头排列时取 [较低均线, 较高均线]，且上限不超过现价 +1%（不追高）；
     * 均线远在下方（已大幅超涨）时兜到现价下方 8% 以内；
     * 均线全在上方（趋势已坏）时退化成"现价附近，暂不加仓"的窄带。
     */
    fun addPriceRange(price: Double, ma10: Double?, ma20: Double?): PriceRange {
        if (price <= 0) return PriceRange(0.0, 0.0)
        val a = ma10 ?: ma20 ?: return PriceRange(r2(price * 0.97), r2(price * 0.99))
        val b = ma20 ?: ma10 ?: return PriceRange(r2(price * 0.97), r2(price * 0.99))
        val hi = min(max(a, b), price * 1.01)
        val lo = max(min(a, b), price * 0.92)
        return if (lo < hi) PriceRange(r2(lo), r2(hi)) else PriceRange(r2(price * 0.99), r2(price * 1.01))
    }

    /**
     * 减仓区间：趋势尚可时是"涨到 +1×ATR ~ +2.5×ATR 分批兑现"的目标带；
     * 趋势已坏（score≤42）时是"现价到反弹位就减"的离场带。两个区间永远都显示。
     */
    fun reducePriceRange(price: Double, atr: Double?, sc: Int, ma10: Double?, ma20: Double?): PriceRange {
        if (price <= 0) return PriceRange(0.0, 0.0)
        if (sc > SELL_SCORE) {
            val a = atr ?: price * 0.03
            return PriceRange(r2(price + a), r2(price + 2.5 * a))
        }
        val above = listOfNotNull(ma10, ma20).filter { it > price }.minOrNull()
        val hi = max(price * 1.03, above ?: price * 1.05)
        return PriceRange(r2(price), r2(hi))
    }

    /**
     * 止损价：现价下方 2×ATR，但不低于 MA20 下方 0.5%（趋势失效位），
     * 并强制落在 [−20%, −2%]：太宽等于没止损，太窄会被日常波动扫掉。
     */
    fun stopLoss(price: Double, atr: Double?, ma20: Double?): Double? {
        if (price <= 0) return null
        val a = atr ?: price * 0.03
        var sl = price - 2 * a
        if (ma20 != null && ma20 < price) sl = max(sl, ma20 * 0.995)
        return r2(sl.coerceIn(price * 0.80, price * 0.98))
    }

    // ------------------------------------------------------------------ 持仓建议

    /**
     * 持仓建议：加仓/减仓区间永远给出（数据不足时给保守兜底并说明），另附止损与风险提示。
     * 注意最高档只到"建议加仓"—— 回测里 ≥76 分那一档是负超额，不发"强烈加仓"。
     */
    fun generatePositionAdvice(stock: PortfolioStock, klineData: List<KLineData>): PositionAdvice {
        val analysis = analyze(klineData)
        val price = if (stock.currentPrice > 0) stock.currentPrice else (klineData.lastOrNull()?.close ?: 0.0)

        val advice = when {
            !analysis.dataSufficient -> AdviceType.HOLD
            analysis.score >= BUY_SCORE -> AdviceType.BUY
            analysis.score > SELL_SCORE -> AdviceType.HOLD
            analysis.score > STRONG_SELL_SCORE -> AdviceType.SELL
            else -> AdviceType.STRONG_SELL
        }

        val reason = buildString {
            append("评分 ${analysis.score}")
            analysis.trendState?.let { append("｜$it") }
            analysis.atrPercent?.let { append("｜日波幅 ${String.format("%.1f", it * 100)}%") }
            if (!analysis.dataSufficient) append("｜K线不足，仅持有")
        }

        return PositionAdvice(
            code = stock.code,
            name = stock.name,
            currentPrice = price,
            buyPrice = stock.buyPrice,
            quantity = stock.quantity,
            advice = advice,
            addPriceRange = addPriceRange(price, analysis.ma10, analysis.ma20),
            reducePriceRange = reducePriceRange(price, analysis.atr, analysis.score, analysis.ma10, analysis.ma20),
            reason = reason,
            stopLoss = analysis.stopLoss,
            riskNote = analysis.riskNote
        )
    }

    // ------------------------------------------------------------------ 每日候选池

    /**
     * 每日列表怎么挑 —— 回测给出的最重要一条产品决策。
     *
     * backtest/iter36.py（30 只口径）在同一批信号日上实测：
     *   · 池内"分数最高的 30 只"（排行榜）：日均超额 −0.430%，20 个换仓相位只有 4 个跑赢基准；
     *   · 池内"按分位等距取 30 只"（本函数）：+0.669%，t_HAC +1.51，19/20 个相位跑赢基准；
     *   · 随机抽 30 只（全市场、20 个种子平均）：+0.005% —— 安慰剂归零，说明上面的差值确实来自评分；
     *   · 从分数后半段随机抽 30 只：−0.966%（t_HAC −1.92，0/20 相位），分数低的那一半是真的在拖累。
     * 排行榜 vs 分层的配对差 +1.100%、块自助 p 0.035，是这套规则里唯一过 5% 显著性的一条。
     * 原因：最高分那一档是"低波动 + 已经涨过一段"的拥挤交易，只买头部等于把仓位押在同一类
     * 股票上，回调时一起回撤。所以这个列表不是排行榜，而是覆盖分散池的分层取样，
     * 应当等权对待，不要只买第一名。
     *
     * 只数为什么是 30（[DAILY_PICKS]）：K 从 15 到满池(约 133 只)收益统计上无法区分，
     * 但 400 条模拟资金曲线的下沿随只数改善（超基准 p10：20 只 −11.2pp → 30 只 −4.0pp）；
     * 而 A 股一手 100 股 + 5 元最低佣金决定了小资金跟不动太多只（backtest/iter35.py）。
     * 钱不够时请按相同间隔从名单里隔 2~3 只挑 1 只（缩到 12~15 只），**不要**改成只买前几名。
     */
    fun selectDailyCandidates(all: List<StockRecommendation>, picks: Int = DAILY_PICKS): List<StockRecommendation> {
        val pool = dailyPool(all)
        if (pool.size <= picks) return pool
        return (0 until picks).map { i -> pool[i * (pool.size - 1) / picks] }
    }

    /**
     * 当日分散池 = 所有"K线足够、能算出分数"的股票按分数排名前 [POOL_FRACTION]（即分数在当日
     * 中位数以上），**不设绝对分数门槛**。backtest/iter30.py（门槛代价）与 iter31 / iter37.py
     * （池宽、只数、门槛在 30 只口径下的复核）：
     *   · 绝对门槛一律是负贡献（配对检验：≥31 −0.145%、≥43 −0.259%、≥55 −0.168%、≥68 −0.181%，
     *     都是 20 日口径的日均超额损失；都不显著但点估计全为负），所以去掉它；
     *   · 池宽 30%~55% 的差别全在噪声带里（+0.51%~+1.01%，t_HAC 0.8~1.8），45% 是样本内峰值但它的
     *     样本外（+1.104%）反而不如 30~40%（+1.35%）—— 不追峰；放宽到 60% 明显变差
     *     （+0.323%、12/20 个换仓相位跑赢基准）。取 50% 是为了不挑数：它就是"当日中位数"。
     */
    fun dailyPool(all: List<StockRecommendation>): List<StockRecommendation> {
        val ranked = all.filter { it.analysis.dataSufficient }.sortedByDescending { it.analysis.score }
        if (ranked.isEmpty()) return ranked
        val take = minOf(ranked.size, Math.ceil(ranked.size * POOL_FRACTION).toInt())
        return ranked.take(take)
    }

    /** 今日分散池规模，供 UI 说明"共 N 只在分位池内，列表按分位取 [DAILY_PICKS] 只" */
    fun eligibleCount(all: List<StockRecommendation>): Int = dailyPool(all).size

    /** 单只股票的推荐条目；K 线不足直接不入池 */
    fun generateRecommendation(stock: Stock, klineData: List<KLineData>): StockRecommendation? {
        if (klineData.size < BARS_MIN) return null
        val analysis = analyze(klineData)
        val price = if (stock.price > 0) stock.price else klineData.last().close
        return StockRecommendation(
            rank = 0,
            stock = stock,
            analysis = analysis,
            buyPriceRange = if (analysis.dataSufficient && analysis.score >= BUY_SCORE)
                addPriceRange(price, analysis.ma10, analysis.ma20) else null,
            sellPriceRange = reducePriceRange(price, analysis.atr, analysis.score, analysis.ma10, analysis.ma20)
        )
    }

    // ------------------------------------------------------------------ 指标序列

    /** 一只股票的整套指标序列（字段与回测 precompute 的特征一一对应） */
    private class Feat(
        val n: Int,
        val close: DoubleArray, val high: DoubleArray, val low: DoubleArray,
        val ma5: DoubleArray, val ma10: DoubleArray, val ma20: DoubleArray, val ma60: DoubleArray,
        val ma20Slope: DoubleArray,
        val atr: DoubleArray, val atrPct: DoubleArray,
        val rsi: DoubleArray,
        val macd: Indicators.Macd, val kdj: Indicators.Kdj, val adx: Indicators.Adx,
        val volRatio: DoubleArray
    ) {
        companion object {
            fun build(k: List<KLineData>): Feat {
                val n = k.size
                val close = DoubleArray(n) { k[it].close }
                val high = DoubleArray(n) { k[it].high }
                val low = DoubleArray(n) { k[it].low }
                val vol = DoubleArray(n) { k[it].volume.toDouble() }
                val ma20 = Indicators.sma(close, 20)
                val atr = Indicators.atr(high, low, close, 14)
                return Feat(
                    n, close, high, low,
                    Indicators.sma(close, 5), Indicators.sma(close, 10), ma20, Indicators.sma(close, 60),
                    Indicators.pctChange(ma20, 10),
                    atr, Indicators.atrPercent(atr, close),
                    Indicators.rsi(close, 14),
                    Indicators.macd(close), Indicators.kdj(high, low, close, 9),
                    Indicators.adx(high, low, close, 14),
                    Indicators.volumeRatio(vol, 5)
                )
            }
        }
    }

    private fun r2(v: Double): Double = round(v * 100) / 100
}
