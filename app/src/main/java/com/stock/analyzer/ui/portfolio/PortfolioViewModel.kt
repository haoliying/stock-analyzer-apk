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
import com.stock.analyzer.analysis.StockAnalyzer
import com.stock.analyzer.data.model.*
import com.stock.analyzer.data.repository.StockRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PortfolioViewModel(application: Application) : AndroidViewModel(application) {
    
    private val repository = StockRepository(application)
    private val analyzer = StockAnalyzer()
    private val context = application
    
    companion object {
        /** 给股票代码补全 sh/sz 前缀 */
        fun normalizeStockCode(code: String): String {
            val trimmed = code.trim().lowercase()
            if (trimmed.startsWith("sh") || trimmed.startsWith("sz")) return trimmed
            // 纯数字代码，按规则补前缀
            val numCode = trimmed.replace(Regex("[^0-9]"), "")
            return when {
                numCode.startsWith("6") || numCode.startsWith("9") -> "sh$numCode"
                numCode.startsWith("0") || numCode.startsWith("3") || numCode.startsWith("2") -> "sz$numCode"
                numCode.startsWith("4") || numCode.startsWith("8") -> "bj$numCode" // 北交所
                else -> "sz$numCode"
            }
        }

        /** 去掉前缀，用于显示 */
        fun displayCode(code: String): String {
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
    
    fun refreshPrices() {
        viewModelScope.launch {
            _isLoading.value = true
            val currentPortfolio = _portfolio.value
            
            // 获取实时行情
            if (currentPortfolio.isNotEmpty()) {
                val codes = currentPortfolio.map { it.code }
                val quotesResult = repository.getRealtimeQuotes(codes)
                
                quotesResult.onSuccess { quotes ->
                    val updated = currentPortfolio.map { ps ->
                        val quote = quotes.find { it.code == ps.code }
                        if (quote != null) {
                            // 如果当前价格为0（休市），使用昨收价
                            val effectivePrice = if (quote.price > 0) quote.price else quote.close
                            ps.copy().apply { 
                                updateCurrentPrice(effectivePrice)
                            }
                        } else {
                            // 获取不到实时数据，尝试用K线最后一条的收盘价
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
                    
                    _portfolio.value = finalList
                    repository.savePortfolio(finalList)
                    generateAdvices(finalList)
                }.onFailure { e ->
                    _error.value = "更新价格失败: ${e.message}"
                    // 尝试从K线获取最近价格
                    fillPricesFromKline(currentPortfolio)
                }
            }
            _isLoading.value = false
        }
    }
    
    private suspend fun fillPricesFromKline(portfolio: List<PortfolioStock>) {
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
        _portfolio.value = updated
        repository.savePortfolio(updated)
        generateAdvices(updated)
    }
    
    private fun generateAdvices(portfolio: List<PortfolioStock>) {
        viewModelScope.launch {
            val adviceList = mutableListOf<PositionAdvice>()
            for (stock in portfolio) {
                try {
                    // 130 根：MA60 与它的 10 日斜率必须有足够历史（与回测口径一致）
                    val klineResult = repository.getKLineData(stock.code, "daily", StockAnalyzer.BARS_FULL)
                    if (klineResult.isSuccess) {
                        val kline = klineResult.getOrDefault(emptyList())
                        // 历史不足也照样给建议：引擎会标记 dataSufficient=false 并降级为"仅持有"，
                        // 持仓卡片不会因为拿不到K线而消失（v4.0.1 的减仓区间消失就是这个门槛造成的）
                        if (kline.isNotEmpty()) {
                            adviceList.add(analyzer.generatePositionAdvice(stock, kline))
                        }
                    }
                    Thread.sleep(300)
                } catch (e: Exception) { }
            }
            
            // 检查是否需要发送通知
            checkAndNotify(adviceList)
            previousAdvices = adviceList
            _advices.value = adviceList
        }
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
    
    fun getTotalValue(): Double = _portfolio.value.sumOf { it.currentPrice * it.quantity }
    fun getTotalCost(): Double = _portfolio.value.sumOf { it.buyPrice * it.quantity }
    fun getTotalProfit(): Double = getTotalValue() - getTotalCost()
    fun getTotalProfitPercent(): Double {
        val cost = getTotalCost()
        return if (cost > 0) (getTotalProfit() / cost) * 100 else 0.0
    }
}
