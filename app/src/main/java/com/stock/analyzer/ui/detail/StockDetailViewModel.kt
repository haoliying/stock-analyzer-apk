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

    /**
     * 进入详情页时请求的代码。行情接口失败时 `_stock` 是 null，
     * 但 K 线和评分照样要能取 —— 以前切周期靠 `_stock.value?.code`，
     * 拿不到行情就整个页面卡死在"重试"，日线/周线/月线点了没反应。
     */
    private var requestedCode: String = ""

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

    /** 图表区域的数据状态文案：null 表示正常。停牌/退市/新股历史不足都从这里说明 */
    private val _dataNote = MutableStateFlow<String?>(null)
    val dataNote: StateFlow<String?> = _dataNote.asStateFlow()

    fun loadStock(code: String) {
        requestedCode = code
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            try {
                // 获取实时行情
                val quoteResult = repository.getStockQuote(code)
                quoteResult.onSuccess { quote ->
                    _stock.value = quote
                }.onFailure { e ->
                    // 行情失败不再是整页错误：历史K线和评分还能照常给出
                    _error.value = "实时行情获取失败: ${e.message}"
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
        if (_period.value == newPeriod && _klineData.value.isNotEmpty()) return
        _period.value = newPeriod
        val code = requestedCode.ifBlank { _stock.value?.code ?: return }
        viewModelScope.launch {
            _isLoading.value = true
            loadKlineData(code, newPeriod)
            _isLoading.value = false
        }
    }

    private suspend fun loadKlineData(code: String, period: ChartPeriod) {
        if (period == ChartPeriod.TIMELINE) {
            // 分时走真正的当日分钟序列；取不到时退回最新一根日线，不再拿 1 根日线冒充分时
            val tl = repository.getTimelineData(code).getOrNull()
            if (!tl.isNullOrEmpty()) {
                _klineData.value = tl.map { KLineData(it.time, it.price, it.price, it.price, it.price, it.volume) }
                _dataNote.value = null
            } else {
                val d = repository.getKLineData(code, "daily", 1).getOrNull()
                _klineData.value = d ?: emptyList()
                _dataNote.value = if (d.isNullOrEmpty())
                    "没有当日分时数据（休市、停牌或当日无成交）"
                else
                    "没有取到当日分时数据，上面显示的是最新一个交易日"
            }
        } else {
            val result = repository.getKLineData(code, period.apiPeriod, period.count)
            val data = result.getOrElse { emptyList() }
            _klineData.value = data
            _dataNote.value = when {
                data.isEmpty() ->
                    "没有${period.label}数据：${result.exceptionOrNull()?.message ?: "数据源未返回"}"
                else -> staleNote(data.last().date, period)
            }
            if (data.isEmpty() && result.isFailure) {
                _error.value = "获取${period.label}数据失败: ${result.exceptionOrNull()?.message}"
            }
        }
        loadAnalysis(code)
    }

    /** 最后一根K线太久以前 = 停牌/退市/代码变更，明说，不假装是最新行情 */
    private fun staleNote(lastDate: String, period: ChartPeriod): String? {
        val lag = StockRepository.lagDays(lastDate) ?: return null
        val limit = if (period == ChartPeriod.MONTHLY) StockRepository.STALE_LAG_DAYS + 31 else StockRepository.STALE_LAG_DAYS
        return if (lag > limit) "最后一根${period.label}是 $lastDate（距今 $lag 天）：多半已停牌、退市或代码变更，下面的分数只是历史数据的结果"
        else null
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
        val code = requestedCode.ifBlank { _stock.value?.code ?: return }
        loadStock(code)
    }
}
