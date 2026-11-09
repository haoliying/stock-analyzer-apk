package com.stock.analyzer.data.model

import com.google.gson.annotations.SerializedName

/**
 * 股票基本信息
 */
data class Stock(
    @SerializedName("code") val code: String,
    @SerializedName("name") val name: String,
    @SerializedName("price") val price: Double,
    @SerializedName("change") val change: Double,
    @SerializedName("changePercent") val changePercent: Double,
    @SerializedName("volume") val volume: Long,
    @SerializedName("turnover") val turnover: Double,
    @SerializedName("high") val high: Double,
    @SerializedName("low") val low: Double,
    @SerializedName("open") val open: Double,
    @SerializedName("close") val close: Double,
    @SerializedName("timestamp") val timestamp: Long
) {
    val isRise: Boolean get() = change >= 0
}

/**
 * K线数据
 */
data class KLineData(
    val date: String,
    val open: Double,
    val close: Double,
    val high: Double,
    val low: Double,
    val volume: Long
)

/**
 * 分时数据
 */
data class TimelineData(
    val time: String,
    val price: Double,
    val avgPrice: Double,
    val volume: Long,
    val preClose: Double
)

/**
 * 股票基本信息（用于搜索）
 */
data class StockInfo(
    val code: String,
    val name: String,
    val pinyin: String  // 拼音首字母
)

/**
 * 持仓股票
 */
data class PortfolioStock(
    val code: String,           // 股票代码
    val name: String,           // 股票名称
    val buyPrice: Double,       // 购买价格
    val quantity: Int,          // 持有数量（股）
    val buyDate: Long,          // 购买日期时间戳
    var currentPrice: Double = 0.0,  // 当前价格
    var profit: Double = 0.0,        // 盈亏金额
    var profitPercent: Double = 0.0  // 盈亏百分比
) {
    fun updateCurrentPrice(price: Double) {
        currentPrice = price
        profit = (price - buyPrice) * quantity
        profitPercent = if (buyPrice > 0) ((price - buyPrice) / buyPrice) * 100 else 0.0
    }
}

/**
 * 持仓建议
 */
data class PositionAdvice(
    val code: String,
    val name: String,
    val currentPrice: Double,
    val buyPrice: Double,
    val quantity: Int,
    val advice: AdviceType,           // 建议类型
    val addPriceRange: PriceRange,   // 加仓价格区间
    val reducePriceRange: PriceRange, // 减仓价格区间
    val reason: String                // 建议原因
)

enum class AdviceType {
    STRONG_BUY,    // 强烈加仓
    BUY,           // 建议加仓
    HOLD,          // 持有观望
    SELL,          // 建议减仓
    STRONG_SELL    // 强烈减仓
}

data class PriceRange(
    val low: Double,
    val high: Double
)

/**
 * 股票历史记录
 */
data class StockHistory(
    val code: String,
    val name: String,
    val klineData: List<KLineData>
)

/**
 * 技术分析结果
 */
data class AnalysisResult(
    val ma5: Double?,
    val ma10: Double?,
    val ma20: Double?,
    val rsi: Double?,
    val macd: MACDResult?,
    val kdj: KDJResult?,
    val recommendation: Recommendation,
    val signals: List<Signal>,
    val score: Int = 0  // 综合评分
)

/**
 * MACD指标
 */
data class MACDResult(
    val dif: Double,
    val dea: Double,
    val macd: Double
)

/**
 * KDJ指标
 */
data class KDJResult(
    val k: Double,
    val d: Double,
    val j: Double
)

/**
 * 操作建议
 */
enum class Recommendation {
    BUY,
    SELL,
    HOLD,
    WATCH
}

/**
 * 交易信号
 */
data class Signal(
    val type: SignalType,
    val message: String,
    val strength: SignalStrength
)

enum class SignalType {
    GOLDEN_CROSS,
    DEATH_CROSS,
    OVERBOUGHT,
    OVERSOLD,
    TREND_UP,
    TREND_DOWN,
    BREAKOUT,
    BREAKDOWN
}

enum class SignalStrength {
    STRONG,
    MEDIUM,
    WEAK
}

/**
 * 股票推荐
 */
data class StockRecommendation(
    val rank: Int,
    val stock: Stock,
    val analysis: AnalysisResult,
    val buyPriceRange: PriceRange?,
    val sellPriceRange: PriceRange?
)
