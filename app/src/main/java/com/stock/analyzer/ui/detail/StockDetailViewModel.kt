package com.stock.analyzer.ui.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stock.analyzer.analysis.StockAnalyzer
import com.stock.analyzer.data.model.*
import com.stock.analyzer.data.repository.StockRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StockDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = StockRepository(application)
    private val analyzer = StockAnalyzer()

    enum class ChartPeriod(val label: String, val apiPeriod: String, val count: Int) {
        TIMELINE("分时", "daily", 1),
        DAILY("日线", "daily", 60),
        FIVE_DAY("5日", "5min", 240),
        WEEKLY("周线", "weekly", 52),
        MONTHLY("月线", "monthly", 24)
    }

    private val _stock = MutableStateFlow<Stock?>(null)
    val stock: StateFlow<Stock?> = _stock.asStateFlow()

    private val _klineData = MutableStateFlow<List<KLineData>>(emptyList())
    val klineData: StateFlow<List<KLineData>> = _klineData.asStateFlow()

    private val _analysis = MutableStateFlow<AnalysisResult?>(null)
    val analysis: StateFlow<AnalysisResult?> = _analysis.asStateFlow()

    private val _period = MutableStateFlow(ChartPeriod.DAILY)
    val period: StateFlow<ChartPeriod> = _period.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun loadStock(code: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            try {
                // 获取实时行情
                val quoteResult = repository.getStockQuote(code)
                quoteResult.onSuccess { quote ->
                    _stock.value = quote
                }.onFailure { e ->
                    _error.value = "获取行情失败: ${e.message}"
                }

                // 获取K线数据
                loadKlineData(code, _period.value)
            } catch (e: Exception) {
                _error.value = "加载失败: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun setPeriod(newPeriod: ChartPeriod) {
        _period.value = newPeriod
        val code = _stock.value?.code ?: return
        viewModelScope.launch {
            _isLoading.value = true
            loadKlineData(code, newPeriod)
            _isLoading.value = false
        }
    }

    private suspend fun loadKlineData(code: String, period: ChartPeriod) {
        if (period == ChartPeriod.TIMELINE) {
            // 分时数据用日线代替（简化处理）
            val result = repository.getKLineData(code, "daily", 1)
            result.onSuccess { data -> _klineData.value = data }
        } else {
            val result = repository.getKLineData(code, period.apiPeriod, period.count)
            result.onSuccess { data -> _klineData.value = data }.onFailure { e ->
                _error.value = "获取K线数据失败: ${e.message}"
            }
        }
        loadAnalysis(code)
    }

    /**
     * 综合分析固定用 130 根日线计算 —— 图表可以切周线/月线/分时，
     * 但评分口径必须和回测一致（MA60 + MA20 十日斜率 + ATR14），
     * 否则翻一个标签页分数就变了。
     */
    private suspend fun loadAnalysis(code: String) {
        repository.getKLineData(code, "daily", StockAnalyzer.BARS_FULL)
            .onSuccess { data -> if (data.isNotEmpty()) _analysis.value = analyzer.analyze(data) }
    }

    fun refresh() {
        val code = _stock.value?.code ?: return
        loadStock(code)
    }
}
