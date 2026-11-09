package com.stock.analyzer.analysis

import com.stock.analyzer.data.model.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 技术分析类 - 提供各类技术指标计算和交易建议
 */
class StockAnalyzer {

    /**
     * 对K线数据进行全面技术分析
     */
    fun analyze(klineData: List<KLineData>): AnalysisResult {
        if (klineData.isEmpty()) {
            return AnalysisResult(
                ma5 = null, ma10 = null, ma20 = null, rsi = null,
                macd = null, kdj = null,
                recommendation = Recommendation.WATCH,
                signals = emptyList(), score = 0
            )
        }

        val closes = klineData.map { it.close }
        val ma5 = calculateMA(closes, 5)
        val ma10 = calculateMA(closes, 10)
        val ma20 = calculateMA(closes, 20)
        val rsi = calculateRSI(closes, 14)
        val macd = calculateMACD(closes)
        val kdj = calculateKDJ(klineData)

        val signals = mutableListOf<Signal>()
        var score = 50 // 基础分

        // MA分析
        val lastClose = closes.last()
        if (ma5 != null && ma10 != null) {
            if (ma5 > ma10) {
                signals.add(Signal(SignalType.TREND_UP, "MA5上穿MA10，短期趋势向好", SignalStrength.MEDIUM))
                score += 10
            } else {
                signals.add(Signal(SignalType.TREND_DOWN, "MA5下穿MA10，短期趋势走弱", SignalStrength.MEDIUM))
                score -= 10
            }
        }
        if (ma5 != null && ma20 != null) {
            if (ma5 > ma20) {
                signals.add(Signal(SignalType.TREND_UP, "MA5在MA20上方，中期趋势向上", SignalStrength.MEDIUM))
                score += 5
            } else {
                signals.add(Signal(SignalType.TREND_DOWN, "MA5在MA20下方，中期趋势向下", SignalStrength.MEDIUM))
                score -= 5
            }
        }
        if (lastClose > (ma5 ?: lastClose)) {
            score += 5
        } else {
            score -= 5
        }

        // RSI分析
        if (rsi != null) {
            when {
                rsi > 80 -> {
                    signals.add(Signal(SignalType.OVERBOUGHT, "RSI=${String.format("%.1f", rsi)}，严重超买", SignalStrength.STRONG))
                    score -= 15
                }
                rsi > 70 -> {
                    signals.add(Signal(SignalType.OVERBOUGHT, "RSI=${String.format("%.1f", rsi)}，超买区域", SignalStrength.MEDIUM))
                    score -= 10
                }
                rsi < 20 -> {
                    signals.add(Signal(SignalType.OVERSOLD, "RSI=${String.format("%.1f", rsi)}，严重超卖", SignalStrength.STRONG))
                    score += 15
                }
                rsi < 30 -> {
                    signals.add(Signal(SignalType.OVERSOLD, "RSI=${String.format("%.1f", rsi)}，超卖区域", SignalStrength.MEDIUM))
                    score += 10
                }
                rsi in 40.0..60.0 -> {
                    score += 0 // 中性
                }
            }
        }

        // MACD分析
        if (macd != null) {
            if (macd.dif > macd.dea) {
                if (macd.macd > 0) {
                    signals.add(Signal(SignalType.GOLDEN_CROSS, "MACD金叉，买入信号", SignalStrength.STRONG))
                    score += 15
                } else {
                    signals.add(Signal(SignalType.GOLDEN_CROSS, "MACD dif>dea，趋势转强", SignalStrength.MEDIUM))
                    score += 8
                }
            } else {
                if (macd.macd < 0) {
                    signals.add(Signal(SignalType.DEATH_CROSS, "MACD死叉，卖出信号", SignalStrength.STRONG))
                    score -= 15
                } else {
                    signals.add(Signal(SignalType.DEATH_CROSS, "MACD dif<dea，趋势转弱", SignalStrength.MEDIUM))
                    score -= 8
                }
            }
        }

        // KDJ分析
        if (kdj != null) {
            if (kdj.k > kdj.d && kdj.j > kdj.k) {
                signals.add(Signal(SignalType.GOLDEN_CROSS, "KDJ金叉，短线看涨", SignalStrength.MEDIUM))
                score += 8
            } else if (kdj.k < kdj.d && kdj.j < kdj.k) {
                signals.add(Signal(SignalType.DEATH_CROSS, "KDJ死叉，短线看跌", SignalStrength.MEDIUM))
                score -= 8
            }
            if (kdj.j > 100) {
                signals.add(Signal(SignalType.OVERBOUGHT, "KDJ J值=${String.format("%.1f", kdj.j)}超买", SignalStrength.WEAK))
                score -= 5
            }
            if (kdj.j < 0) {
                signals.add(Signal(SignalType.OVERSOLD, "KDJ J值=${String.format("%.1f", kdj.j)}超卖", SignalStrength.WEAK))
                score += 5
            }
        }

        // 突破分析
        if (klineData.size >= 20) {
            val recent20High = klineData.takeLast(20).maxOf { it.high }
            val recent20Low = klineData.takeLast(20).minOf { it.low }
            if (lastClose >= recent20High) {
                signals.add(Signal(SignalType.BREAKOUT, "突破20日新高", SignalStrength.STRONG))
                score += 10
            }
            if (lastClose <= recent20Low) {
                signals.add(Signal(SignalType.BREAKDOWN, "跌破20日新低", SignalStrength.STRONG))
                score -= 10
            }
        }

        score = score.coerceIn(0, 100)

        val recommendation = when {
            score >= 75 -> Recommendation.BUY
            score >= 55 -> Recommendation.HOLD
            score >= 40 -> Recommendation.WATCH
            else -> Recommendation.SELL
        }

        return AnalysisResult(
            ma5 = ma5, ma10 = ma10, ma20 = ma20,
            rsi = rsi, macd = macd, kdj = kdj,
            recommendation = recommendation,
            signals = signals,
            score = score
        )
    }

    /**
     * 计算移动平均线
     */
    fun calculateMA(data: List<Double>, period: Int): Double? {
        if (data.size < period) return null
        val last = data.takeLast(period)
        return last.average()
    }

    /**
     * 计算RSI指标
     */
    fun calculateRSI(prices: List<Double>, period: Int = 14): Double? {
        if (prices.size < period + 1) return null

        var gains = 0.0
        var losses = 0.0

        for (i in (prices.size - period) until prices.size) {
            val change = prices[i] - prices[i - 1]
            if (change > 0) gains += change
            else losses += abs(change)
        }

        val avgGain = gains / period
        val avgLoss = losses / period

        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    /**
     * 计算MACD指标
     */
    fun calculateMACD(prices: List<Double>): MACDResult? {
        if (prices.size < 26) return null

        val ema12 = calculateEMA(prices, 12)
        val ema26 = calculateEMA(prices, 26)

        if (ema12 == null || ema26 == null) return null

        val dif = ema12 - ema26

        // 计算DEA (DIF的9日EMA)
        val difList = mutableListOf<Double>()
        for (i in 9 until prices.size) {
            val e12 = calculateEMA(prices.take(i + 1), 12)
            val e26 = calculateEMA(prices.take(i + 1), 26)
            if (e12 != null && e26 != null) {
                difList.add(e12 - e26)
            }
        }

        val dea = if (difList.isNotEmpty()) {
            calculateEMA(difList, min(9, difList.size)) ?: dif
        } else dif

        val macd = (dif - dea) * 2

        return MACDResult(dif = dif, dea = dea, macd = macd)
    }

    /**
     * 计算KDJ指标
     */
    fun calculateKDJ(klineData: List<KLineData>, period: Int = 9): KDJResult? {
        if (klineData.size < period) return null

        val recent = klineData.takeLast(period)
        val highestHigh = recent.maxOf { it.high }
        val lowestLow = recent.minOf { it.low }
        val lastClose = klineData.last().close

        val rsv = if (highestHigh != lowestLow) {
            ((lastClose - lowestLow) / (highestHigh - lowestLow)) * 100
        } else 50.0

        // 简化计算
        val k = rsv * 1.0 / 3 + 50.0 * 2.0 / 3
        val d = k * 1.0 / 3 + 50.0 * 2.0 / 3
        val j = 3 * k - 2 * d

        return KDJResult(k = k, d = d, j = j)
    }

    /**
     * 计算EMA
     */
    private fun calculateEMA(data: List<Double>, period: Int): Double? {
        if (data.size < period) return null
        val multiplier = 2.0 / (period + 1)
        var ema = data.take(period).average()
        for (i in period until data.size) {
            ema = (data[i] - ema) * multiplier + ema
        }
        return ema
    }

    /**
     * 生成持仓建议
     */
    fun generatePositionAdvice(stock: PortfolioStock, klineData: List<KLineData>): PositionAdvice {
        val analysis = analyze(klineData)
        val currentPrice = stock.currentPrice
        val buyPrice = stock.buyPrice

        val advice = when {
            analysis.score >= 75 -> AdviceType.STRONG_BUY
            analysis.score >= 60 -> AdviceType.BUY
            analysis.score >= 40 -> AdviceType.HOLD
            analysis.score >= 25 -> AdviceType.SELL
            else -> AdviceType.STRONG_SELL
        }

        // 加仓区间：基于支撑位（近期低点）
        val support = klineData.takeLast(20).minOfOrNull { it.low } ?: currentPrice * 0.95
        val addRange = PriceRange(low = support * 0.98, high = support * 1.02)

        // 减仓区间：基于阻力位（近期高点）
        val resistance = klineData.takeLast(20).maxOfOrNull { it.high } ?: currentPrice * 1.05
        val reduceRange = PriceRange(low = resistance * 0.98, high = resistance * 1.02)

        val reason = buildString {
            append("评分${analysis.score}分")
            analysis.rsi?.let { append("，RSI=${String.format("%.1f", it)}") }
            analysis.macd?.let { append("，MACD=${String.format("%.3f", it.macd)}") }
            if (analysis.signals.isNotEmpty()) {
                append("，")
                append(analysis.signals.first().message)
            }
        }

        return PositionAdvice(
            code = stock.code,
            name = stock.name,
            currentPrice = currentPrice,
            buyPrice = buyPrice,
            quantity = stock.quantity,
            advice = advice,
            addPriceRange = addRange,
            reducePriceRange = reduceRange,
            reason = reason
        )
    }

    /**
     * 生成推荐评分
     */
    fun generateRecommendation(stock: Stock, klineData: List<KLineData>): StockRecommendation? {
        if (klineData.size < 30) return null

        val analysis = analyze(klineData)
        val lastClose = stock.price

        val buyRange = if (analysis.recommendation == Recommendation.BUY || analysis.recommendation == Recommendation.HOLD) {
            val support = klineData.takeLast(20).minOf { it.low }
            PriceRange(low = support * 0.98, high = support * 1.02)
        } else null

        val sellRange = if (analysis.recommendation == Recommendation.SELL) {
            val resistance = klineData.takeLast(20).maxOf { it.high }
            PriceRange(low = resistance * 0.98, high = resistance * 1.02)
        } else null

        return StockRecommendation(
            rank = 0,
            stock = stock,
            analysis = analysis,
            buyPriceRange = buyRange,
            sellPriceRange = sellRange
        )
    }
}
