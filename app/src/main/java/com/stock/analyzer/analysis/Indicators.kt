package com.stock.analyzer.analysis

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 技术指标计算库 —— 与回测脚本 backtest/indicators.py 逐条对应。
 *
 * 约定：所有函数返回与输入等长的序列，无法计算的位置为 [Double.NaN]。
 * EMA 一律用首值播种（等价 pandas ewm(adjust=False)），Wilder 类指标用
 * alpha = 1/period 的同一套递推，保证手机上的数值与回测一致。
 */
object Indicators {

    /** 简单移动平均 */
    fun sma(x: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(x.size) { Double.NaN }
        if (x.size < period || period <= 0) return out
        var sum = 0.0
        for (i in x.indices) {
            sum += x[i]
            if (i >= period) sum -= x[i - period]
            if (i >= period - 1) out[i] = sum / period
        }
        return out
    }

    /** 指数移动平均，span = period，首值播种 */
    fun ema(x: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(x.size) { Double.NaN }
        if (x.isEmpty()) return out
        val a = 2.0 / (period + 1)
        var prev = x[0]
        out[0] = prev
        for (i in 1 until x.size) {
            prev = (x[i] - prev) * a + prev
            out[i] = prev
        }
        return out
    }

    /** Wilder 平滑，alpha = 1/period，首值播种 */
    fun wilder(x: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(x.size) { Double.NaN }
        if (x.isEmpty()) return out
        val a = 1.0 / period
        var prev = x[0]
        out[0] = prev
        for (i in 1 until x.size) {
            prev = (x[i] - prev) * a + prev
            out[i] = prev
        }
        return out
    }

    /** (x[i] - x[i-lag]) / x[i-lag] */
    fun pctChange(x: DoubleArray, lag: Int): DoubleArray {
        val out = DoubleArray(x.size) { Double.NaN }
        for (i in lag until x.size) {
            val prev = x[i - lag]
            if (!prev.isNaN() && prev != 0.0) out[i] = (x[i] - prev) / prev
        }
        return out
    }

    /** RSI(14)，Wilder 递推版（不是简单均值，后者会明显高估超买超卖） */
    fun rsi(close: DoubleArray, period: Int = 14): DoubleArray {
        val m = close.size
        val out = DoubleArray(m) { Double.NaN }
        if (m <= period) return out
        val up = DoubleArray(m)
        val dn = DoubleArray(m)
        for (i in 1 until m) {
            val ch = close[i] - close[i - 1]
            up[i] = if (ch > 0) ch else 0.0
            dn[i] = if (ch < 0) -ch else 0.0
        }
        var ag = 0.0
        var al = 0.0
        for (i in 1..period) {
            ag += up[i]
            al += dn[i]
        }
        ag /= period
        al /= period
        out[period] = if (al == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + ag / al)
        for (i in period + 1 until m) {
            ag = (ag * (period - 1) + up[i]) / period
            al = (al * (period - 1) + dn[i]) / period
            out[i] = if (al == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + ag / al)
        }
        return out
    }

    class Macd(val dif: DoubleArray, val dea: DoubleArray, val hist: DoubleArray)

    /**
     * MACD(12,26,9)。DIF/DEA/柱 是同一条对齐的时间序列 ——
     * 老版本每次循环都对前缀重算 EMA，导致 DEA 与 DIF 错位，这里改为整条序列递推。
     */
    fun macd(close: DoubleArray, fast: Int = 12, slow: Int = 26, signal: Int = 9): Macd {
        val dif = DoubleArray(close.size) { Double.NaN }
        if (close.isEmpty()) return Macd(dif, DoubleArray(close.size) { Double.NaN }, DoubleArray(close.size) { Double.NaN })
        val e12 = ema(close, fast)
        val e26 = ema(close, slow)
        for (i in close.indices) dif[i] = e12[i] - e26[i]
        val dea = ema(dif, signal)
        val hist = DoubleArray(close.size) { (dif[it] - dea[it]) * 2.0 }
        return Macd(dif, dea, hist)
    }

    class Kdj(val k: DoubleArray, val d: DoubleArray, val j: DoubleArray)

    /** KDJ(9,3,3)，迭代平滑：K=(2K'+RSV)/3, D=(2D'+K)/3, J=3K-2D */
    fun kdj(high: DoubleArray, low: DoubleArray, close: DoubleArray, period: Int = 9): Kdj {
        val m = close.size
        val k = DoubleArray(m) { Double.NaN }
        val d = DoubleArray(m) { Double.NaN }
        val j = DoubleArray(m) { Double.NaN }
        var pk = 50.0
        var pd = 50.0
        for (i in period - 1 until m) {
            var hh = Double.NEGATIVE_INFINITY
            var ll = Double.POSITIVE_INFINITY
            for (t in i - period + 1..i) {
                if (high[t] > hh) hh = high[t]
                if (low[t] < ll) ll = low[t]
            }
            val rsv = if (hh == ll) 50.0 else (close[i] - ll) / (hh - ll) * 100.0
            pk = (2 * pk + rsv) / 3
            pd = (2 * pd + pk) / 3
            k[i] = pk
            d[i] = pd
            j[i] = 3 * pk - 2 * pd
        }
        return Kdj(k, d, j)
    }

    /** ATR(14)，Wilder 平滑 */
    fun atr(high: DoubleArray, low: DoubleArray, close: DoubleArray, period: Int = 14): DoubleArray {
        val m = close.size
        if (m == 0) return DoubleArray(0) { Double.NaN }
        val tr = DoubleArray(m)
        tr[0] = high[0] - low[0]
        for (i in 1 until m) {
            tr[i] = max(high[i] - low[i], max(abs(high[i] - close[i - 1]), abs(low[i] - close[i - 1])))
        }
        return wilder(tr, period)
    }

    /** ATR / 现价，即"日均真实波幅"，评分模型的波动惩罚就用它 */
    fun atrPercent(atr: DoubleArray, close: DoubleArray): DoubleArray {
        val out = DoubleArray(atr.size) { Double.NaN }
        for (i in atr.indices) {
            if (!atr[i].isNaN() && close[i] > 0) out[i] = atr[i] / close[i]
        }
        return out
    }

    class Adx(val adx: DoubleArray, val plusDi: DoubleArray, val minusDi: DoubleArray)

    /** ADX(14) 及 +DI / -DI（仅用于展示趋势强度，不参与评分） */
    fun adx(high: DoubleArray, low: DoubleArray, close: DoubleArray, period: Int = 14): Adx {
        val m = close.size
        val nan = DoubleArray(m) { Double.NaN }
        if (m < 2) return Adx(nan, nan, nan)
        val tr = DoubleArray(m)
        val plusDm = DoubleArray(m)
        val minusDm = DoubleArray(m)
        tr[0] = high[0] - low[0]
        for (i in 1 until m) {
            tr[i] = max(high[i] - low[i], max(abs(high[i] - close[i - 1]), abs(low[i] - close[i - 1])))
            val up = high[i] - high[i - 1]
            val dn = low[i - 1] - low[i]
            plusDm[i] = if (up > dn && up > 0) up else 0.0
            minusDm[i] = if (dn > up && dn > 0) dn else 0.0
        }
        val str = wilder(tr, period)
        val sp = wilder(plusDm, period)
        val sm = wilder(minusDm, period)
        val pdi = DoubleArray(m)
        val mdi = DoubleArray(m)
        val dx = DoubleArray(m)
        for (i in 0 until m) {
            pdi[i] = if (str[i] > 0) 100.0 * sp[i] / str[i] else 0.0
            mdi[i] = if (str[i] > 0) 100.0 * sm[i] / str[i] else 0.0
            val s = pdi[i] + mdi[i]
            dx[i] = if (s > 0) 100.0 * abs(pdi[i] - mdi[i]) / s else 0.0
        }
        return Adx(wilder(dx, period), pdi, mdi)
    }

    class Bollinger(val mid: DoubleArray, val upper: DoubleArray, val lower: DoubleArray, val pos: DoubleArray)

    /** 布林带(20, 2)，pos = 收盘价在带内的位置 0..1 */
    fun bollinger(close: DoubleArray, period: Int = 20, mult: Double = 2.0): Bollinger {
        val m = close.size
        val mid = sma(close, period)
        val upper = DoubleArray(m) { Double.NaN }
        val lower = DoubleArray(m) { Double.NaN }
        val pos = DoubleArray(m) { Double.NaN }
        if (m >= period) {
            for (i in period - 1 until m) {
                var sq = 0.0
                for (t in i - period + 1..i) sq += (close[t] - mid[i]) * (close[t] - mid[i])
                val sd = sqrtOrZero(sq / period)
                upper[i] = mid[i] + mult * sd
                lower[i] = mid[i] - mult * sd
                val w = upper[i] - lower[i]
                if (w > 0) pos[i] = (close[i] - lower[i]) / w
            }
        }
        return Bollinger(mid, upper, lower, pos)
    }

    private fun sqrtOrZero(v: Double): Double = if (v <= 0 || v.isNaN()) 0.0 else kotlin.math.sqrt(v)

    /** 当日成交量 / 前 period 日均量（不含当日） */
    fun volumeRatio(vol: DoubleArray, period: Int = 5): DoubleArray {
        val out = DoubleArray(vol.size) { Double.NaN }
        for (i in period until vol.size) {
            var s = 0.0
            for (t in i - period until i) s += vol[t]
            val avg = s / period
            if (avg > 0) out[i] = vol[i] / avg
        }
        return out
    }
}
