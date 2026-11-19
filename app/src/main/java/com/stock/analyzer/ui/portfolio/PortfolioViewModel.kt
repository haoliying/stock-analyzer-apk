package com.stock.analyzer.ui.portfolio

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stock.analyzer.R
import com.stock.analyzer.analysis.NonStockEngine
import com.stock.analyzer.analysis.StockAnalyzer
import com.stock.analyzer.data.model.*
import com.stock.analyzer.data.repository.StockRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PortfolioViewModel(application: Application) : AndroidViewModel(application) {
    
    private val repository = StockRepository(application)
    private val analyzer = StockAnalyzer()
    private val context = application
    
    companion object {
        /**
         * 给股票代码补全 sh/sz/bj 前缀。
         *
         * 这里同时会把存错的旧代码改写回来：北交所 2024 年起改用 920xxx 段，
         * 老版本按"9 开头 = 沪市"把它存成 sh920xxx，行情和 K 线接口就都取不到，
         * 表现是"我的持仓里这只股票没有日线/月线、也没分析"。
         */
        fun normalizeStockCode(code: String): String {
            // 非股票资产代码（hf_XAU / nf_RB0 / fx_USDCNY）的大小写与前缀就是接口参数本身，
            // 转小写会让数据源查不到（hf_CL 会变成 hf_cl）—— 这一类一个字符都不动。
            // 判据用 fromCode（不是只认贵金属）：大宗商品与外汇同样不能动
            if (AssetClass.fromCode(code) != AssetClass.A_SHARE) return code.trim()
            val trimmed = code.trim().lowercase()
            val numCode = trimmed.replace(Regex("[^0-9]"), "")
            if (numCode.length != 6) return trimmed
            val prefix = when {
                numCode.startsWith("92") || numCode.startsWith("4") || numCode.startsWith("8") -> "bj"
                numCode.startsWith("6") || numCode.startsWith("9") || numCode.startsWith("5") -> "sh"
                else -> "sz"
            }
            return prefix + numCode
        }

        /** 去掉前缀，用于显示。非股票资产那一类前缀就是要看的（hf_ / nf_ / fx_ 表示哪个市场），原样返回 */
        fun displayCode(code: String): String {
            if (AssetClass.fromCode(code) != AssetClass.A_SHARE) return code
            return code.lowercase().removePrefix("sh").removePrefix("sz").removePrefix("bj")
        }
    }
    
    private val _portfolio = MutableStateFlow<List<PortfolioStock>>(emptyList())
    val portfolio: StateFlow<List<PortfolioStock>> = _portfolio.asStateFlow()
    
    private val _advices = MutableStateFlow<List<PositionAdvice>>(emptyList())
    val advices: StateFlow<List<PositionAdvice>> = _advices.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    private val _showAddDialog = MutableStateFlow(false)
    val showAddDialog: StateFlow<Boolean> = _showAddDialog.asStateFlow()
    
    private var previousAdvices = listOf<PositionAdvice>()
    
    init {
        createNotificationChannel()
        loadPortfolio()
        // 贵金属那一类的现价不在这里发请求：引擎那一轮（30 秒一批）本来就在跑，
        // 这里只订阅它的结果，报价一变就重新算一遍市值与盈亏
        viewModelScope.launch {
            NonStockEngine.quotes.collect { applyNonStockPrices(publish = true) }
        }
    }
    
    fun loadPortfolio() {
        viewModelScope.launch {
            val list = repository.loadPortfolio()
            // 修复已有持仓数据：补全缺少 sh/sz 前缀的股票代码
            val normalized = list.map { stock ->
                val normalizedCode = normalizeStockCode(stock.code)
                if (normalizedCode != stock.code) {
                    stock.copy(code = normalizedCode)
                } else stock
            }
            // 如果有修复，保存回去
            if (normalized != list) {
                repository.savePortfolio(normalized)
            }
            _portfolio.value = normalized
            if (normalized.isNotEmpty()) {
                refreshPrices()
            }
        }
    }
    
    /**
     * 刷新一遍持仓现价。
     *
     * 两类分开取：A 股走 [StockRepository]（一次批量行情 + 取不到价的补一次日线），
     * 非股票资产走 [NonStockEngine] 那一轮批量报价。以前是把所有代码一起丢给 A 股行情接口，
     * `hf_XAU` / `fx_USDCNY` 之类拿不到结果，于是又会被"补现价"那段拖去发一次注定失败的日线请求。
     */
    fun refreshPrices() {
        viewModelScope.launch {
            _isLoading.value = true
            val currentPortfolio = _portfolio.value

            val stockHolds = currentPortfolio.filter { !it.isNonStock }
            if (stockHolds.isNotEmpty()) {
                val codes = stockHolds.map { it.code }
                val quotesResult = repository.getRealtimeQuotes(codes)

                quotesResult.onSuccess { quotes ->
                    val updated = stockHolds.map { ps ->
                        val quote = quotes.find { it.code == ps.code }
                        if (quote != null) {
                            // 如果当前价格为0（休市），使用昨收价
                            val effectivePrice = if (quote.price > 0) quote.price else quote.close
                            ps.copy().apply { updateCurrentPrice(effectivePrice) }
                        } else {
                            ps
                        }
                    }

                    // 对于价格仍为0的股票，从K线数据获取最近收盘价
                    val finalList = updated.map { ps ->
                        if (ps.currentPrice <= 0) {
                            try {
                                val klineResult = repository.getKLineData(ps.code, "daily", 5)
                                if (klineResult.isSuccess) {
                                    val kline = klineResult.getOrDefault(emptyList())
                                    if (kline.isNotEmpty()) {
                                        val lastClose = kline.last().close
                                        ps.copy().apply { updateCurrentPrice(lastClose) }
                                    } else ps
                                } else ps
                            } catch (e: Exception) {
                                ps
                            }
                        } else ps
                    }

                    val merged = currentPortfolio.filter { it.isNonStock } + finalList
                    _portfolio.value = merged
                    repository.savePortfolio(merged)
                    generateAdvices(finalList)
                }.onFailure { e ->
                    _error.value = "更新价格失败: ${e.message}"
                    // 尝试从K线获取最近价格
                    fillPricesFromKline(stockHolds)
                }
            }
            // 贵金属那几行：先用手上的报价定一次价，同时催引擎取最新的一批
            applyNonStockPrices(publish = true)
            NonStockEngine.requestIfStale()
            _isLoading.value = false
        }
    }

    /**
     * 用引擎当前的批量报价给非股票持仓定价。
     *
     * 取不到价就**保持原值**，不会把现价写成 0 —— 写成 0 在盈亏那一栏就是
     * "-100%"，那是假消息。引擎那一轮回来之后这个函数会再跑一次。
     */
    private fun applyNonStockPrices(publish: Boolean): List<PortfolioStock> {
        val list = _portfolio.value
        val nonStockHolds = list.filter { it.isNonStock }
        if (nonStockHolds.isEmpty()) return list
        val quotes = NonStockEngine.quotes.value
        if (quotes.isEmpty()) return list
        var changed = false
        val updated = list.map { ps ->
            val price = if (ps.isNonStock) quotes[ps.code]?.price ?: 0.0 else 0.0
            if (price > 0 && price != ps.currentPrice) {
                changed = true
                ps.copy().apply { updateCurrentPrice(price) }
            } else ps
        }
        if (changed && publish) {
            _portfolio.value = updated
            repository.savePortfolio(updated)
        }
        return if (changed) updated else list
    }

    private suspend fun fillPricesFromKline(portfolio: List<PortfolioStock>) {
        // portfolio 传进来的是 A 股那一拨，贵金属持仓不在里面，价格另说
        val updated = portfolio.map { ps ->
            if (ps.currentPrice <= 0) {
                try {
                    val klineResult = repository.getKLineData(ps.code, "daily", 5)
                    if (klineResult.isSuccess) {
                        val kline = klineResult.getOrDefault(emptyList())
                        if (kline.isNotEmpty()) {
                            ps.copy().apply { updateCurrentPrice(kline.last().close) }
                        } else ps
                    } else ps
                } catch (e: Exception) { ps }
            } else ps
        }
        _portfolio.value = _portfolio.value.filter { it.isNonStock } + updated
        repository.savePortfolio(_portfolio.value)
        generateAdvices(updated)
    }
    
    /**
     * 逐只算加减仓建议。
     *
     * 贵金属那一类**不进这个循环**：同一套分数在它身上回测没过
     * （`backtest/metals/README.md`），给一个没验证过的建议比不给更糟。
     * 界面上那几条持仓卡因此只有成本与盈亏，没有区间和止损。
     */
    private fun generateAdvices(portfolio: List<PortfolioStock>) {
        viewModelScope.launch {
            val adviceList = mutableListOf<PositionAdvice>()
            for (stock in portfolio.filter { !it.isNonStock }) {
                // 130 根：MA60 与它的 10 日斜率必须有足够历史（与回测口径一致）
                val klineResult = try {
                    repository.getKLineData(stock.code, "daily", StockAnalyzer.BARS_FULL)
                } catch (e: Exception) {
                    Result.failure<List<KLineData>>(e)
                }
                val kline = klineResult.getOrElse { emptyList() }

                // 历史不足或取不到数据也照样出一条建议：引擎会标 dataSufficient=false 降级为"仅持有"，
                // 持仓卡片不会因为拿不到K线而消失（v4.0.1 的减仓区间消失就是这个门槛造成的，
                // v4.3.0 之前"有的股票没被分析"是同一个 skip 造成的）
                val advice = analyzer.generatePositionAdvice(stock, kline)
                val note = when {
                    kline.isEmpty() -> "｜没取到日线数据（${klineResult.exceptionOrNull()?.message
                        ?: "数据源未返回"}），无法评分；区间按现价保守估算"
                    else -> staleSuffix(kline.last().date)
                }
                adviceList.add(if (note == null) advice else advice.copy(reason = advice.reason + note))

                // 一只一条地发布：以前整个列表要等最后一只算完才出现，
                // 切回这一页就会看到卡片整片消失再一次性回来
                _advices.value = adviceList.toList()

                // 控制请求频率（delay 而不是 Thread.sleep：后者会卡住主线程）
                delay(250)
            }
            
            // 检查是否需要发送通知
            checkAndNotify(adviceList)
            previousAdvices = adviceList
            _advices.value = adviceList
        }
    }

    /** 数据是死的时候别装作在分析现价：明说最后交易日 */
    private fun staleSuffix(lastDate: String): String? {
        val lag = StockRepository.lagDays(lastDate) ?: return null
        return if (lag > StockRepository.STALE_LAG_DAYS)
            "｜最后交易日 $lastDate（$lag 天前），可能已停牌/退市，评分只反映历史"
        else null
    }
    
    private fun checkAndNotify(currentAdvices: List<PositionAdvice>) {
        for (advice in currentAdvices) {
            if (advice.advice == AdviceType.STRONG_BUY || 
                advice.advice == AdviceType.STRONG_SELL ||
                advice.advice == AdviceType.BUY ||
                advice.advice == AdviceType.SELL) {
                
                // 检查之前是否已经有相同的通知（避免重复）
                val prevAdvice = previousAdvices.find { it.code == advice.code }
                if (prevAdvice == null || prevAdvice.advice != advice.advice) {
                    sendNotification(advice)
                }
            }
        }
    }
    
    private fun sendNotification(advice: PositionAdvice) {
        val adviceText = when (advice.advice) {
            AdviceType.STRONG_BUY -> "强烈加仓"
            AdviceType.BUY -> "建议加仓"
            AdviceType.HOLD -> "持有观望"
            AdviceType.SELL -> "建议减仓"
            AdviceType.STRONG_SELL -> "强烈减仓"
        }
        
        val title = "【$adviceText】${advice.name}(${advice.code})"
        val content = "现价: ${String.format("%.2f", advice.currentPrice)} | ${advice.reason}"
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val notification = NotificationCompat.Builder(context, "stock_advice")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(advice.code.hashCode(), notification)
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "stock_advice",
                "股票操作提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "当持仓股票出现加仓或减仓信号时发送通知"
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    fun addStock(code: String, name: String, buyPrice: Double, quantity: Int) {
        viewModelScope.launch {
            val normalizedCode = normalizeStockCode(code)
            val stock = PortfolioStock(
                code = normalizedCode, name = name, buyPrice = buyPrice,
                quantity = quantity, buyDate = System.currentTimeMillis()
            )
            repository.addPortfolioStock(stock)
            _showAddDialog.value = false
            loadPortfolio()
        }
    }
    
    fun updateStock(stock: PortfolioStock) {
        viewModelScope.launch {
            repository.updatePortfolioStock(stock)
            loadPortfolio()
        }
    }
    
    fun removeStock(code: String) {
        viewModelScope.launch {
            repository.removePortfolioStock(code)
            loadPortfolio()
        }
    }
    
    fun showAddDialog() { _showAddDialog.value = true }
    fun hideAddDialog() { _showAddDialog.value = false }
    
    /**
     * 合计只统计 A 股那一类。
     *
     * 非股票资产不能和股票加在同一个数里：伦敦金是美元/盎司、沪银是元/千克、外汇是货币对，
     * 币种不同、数量单位不同，硬加出来的"总资产"是假的。
     * 非股票那几行的合计在界面上按币种分开列（[metalTotals]）。
     */
    fun getTotalValue(): Double = _portfolio.value.filter { !it.isNonStock }.sumOf { it.currentPrice * it.quantity }
    fun getTotalCost(): Double = _portfolio.value.filter { !it.isNonStock }.sumOf { it.buyPrice * it.quantity }
    fun getTotalProfit(): Double = getTotalValue() - getTotalCost()

    /** 非股票持仓按币种分开的合计：市值、成本、盈亏各自一套，不跨币种相加 */
    fun metalTotals(): List<MetalTotal> {
        val out = LinkedHashMap<String, MetalTotal>()
        for (ps in _portfolio.value.filter { it.isNonStock }) {
            val cur = ps.nonStockInstrument?.currency ?: "其他"
            val prev = out[cur] ?: MetalTotal(cur, 0.0, 0.0, 0)
            out[cur] = prev.copy(
                value = prev.value + ps.currentPrice * ps.quantity,
                cost = prev.cost + ps.buyPrice * ps.quantity,
                holds = prev.holds + 1
            )
        }
        return out.values.toList()
    }
    fun getTotalProfitPercent(): Double {
        val cost = getTotalCost()
        return if (cost > 0) (getTotalProfit() / cost) * 100 else 0.0
    }
}

/**
 * 非股票持仓的合计，按币种一条。
 *
 * 为什么不合成一个总数：伦敦金按美元/盎司计价、沪银按元/千克计价、外汇按货币对计价，
 * 加起来需要一个实时汇率源，而这一版没有核过任何免 Key 的汇率接口 ——
 * 拿一个没验证过的汇率把多种币种折成一个数，比分开显示更容易让人做错决定。
 */
data class MetalTotal(
    val currency: String,
    val value: Double,
    val cost: Double,
    val holds: Int
) {
    val profit: Double get() = value - cost
    val profitPercent: Double get() = if (cost > 0) profit / cost * 100 else 0.0
    val symbol: String get() = when (currency) {
        "USD" -> "$"; "CNY" -> "¥"; else -> "$currency "
    }
}
