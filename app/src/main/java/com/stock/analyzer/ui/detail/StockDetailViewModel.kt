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

    /**
     * @param count      首次进入这个周期时取多少根
     * @param stepBars   竖线拖到最左边时，一次往前多取多少根（动态加载）
     * @param maxBars    这个周期最多保留多少根，避免无限往历史上拉
     * @param windowBars 默认一屏画多少根（放大缩小按钮改的是界面状态，不动这里）
     * @param minWindow  一屏最少多少根（放到最大）
     * @param maxWindow  一屏最多多少根（缩到最小、看得最远）
     */
    enum class ChartPeriod(
        val label: String,
        val apiPeriod: String,
        val count: Int,
        val stepBars: Int,
        val maxBars: Int,
        val windowBars: Int,
        val minWindow: Int,
        val maxWindow: Int,
        val isMinute: Boolean = false
    ) {
        TIMELINE("分时", "daily", 1, 0, 0, 60, 20, 240, true),
        DAILY("日线", "daily", 60, 60, 400, 30, 10, 200),
        FIVE_DAY("5日", "5min", 240, 240, 720, 60, 20, 240, true),
        WEEKLY("周线", "weekly", 52, 52, 260, 30, 10, 160),
        MONTHLY("月线", "monthly", 24, 24, 240, 30, 10, 120)
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

    /** 竖线拖到左边界时往前追加历史，这两个状态给图表显示"加载中/到底了" */
    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _historyAtEnd = MutableStateFlow(false)
    val historyAtEnd: StateFlow<Boolean> = _historyAtEnd.asStateFlow()

    /** 当前周期已经取到多少根；追加历史时以它为基准往上加 */
    private var historyCount: Int = 0

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
                _klineData.value = tl.map {
                    KLineData(
                        date = it.time,
                        open = if (it.open > 0) it.open else it.price,
                        close = it.price,
                        high = if (it.high > 0) it.high else it.price,
                        low = if (it.low > 0) it.low else it.price,
                        volume = it.volume,
                        avgPrice = it.avgPrice,
                        preClose = it.preClose,
                        amount = it.amount
                    )
                }
                _dataNote.value = null
                // 分时只有当天这一段，往左再拖也没有更早的分钟数据
                historyCount = _klineData.value.size
                _historyAtEnd.value = true
            } else {
                val d = repository.getKLineData(code, "daily", 1).getOrNull()
                _klineData.value = d ?: emptyList()
                historyCount = _klineData.value.size
                _historyAtEnd.value = true
                _dataNote.value = if (d.isNullOrEmpty())
                    "没有当日分时数据（休市、停牌或当日无成交）"
                else
                    "没有取到当日分时数据，上面显示的是最新一个交易日"
            }
        } else {
            val result = repository.getKLineData(code, period.apiPeriod, period.count)
            val data = result.getOrElse { emptyList() }
            _klineData.value = data
            historyCount = data.size
            // 给了的根数还不够一口的量 = 这只股票到这个周期就这么多历史（新股、刚上市）
            _historyAtEnd.value = data.isEmpty() || data.size < period.count
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

    /**
     * 竖线拖到最左边时往前追加一段历史。返回的列表变长后由界面无跳动地对齐，
     * 分时不调用（当天以下没有分钟数据）。
     */
    fun loadOlderBars() {
        val period = _period.value
        if (period == ChartPeriod.TIMELINE) return
        loadHistory(period, historyCount + period.stepBars)
    }

    /** 一屏要显示的根数超过了已加载的根数（往外缩小）时，按需要补数据 */
    fun ensureBars(minBars: Int) {
        val period = _period.value
        if (historyCount >= minBars) return
        loadHistory(period, maxOf(historyCount + period.stepBars, minBars))
    }

    private fun loadHistory(period: ChartPeriod, want: Int) {
        val code = requestedCode.ifBlank { _stock.value?.code ?: return }
        if (_isLoadingMore.value) return

        val target = minOf(maxOf(want, historyCount + 1), period.maxBars)
        if (target <= historyCount) {
            _historyAtEnd.value = true
            return
        }

        viewModelScope.launch {
            _isLoadingMore.value = true
            val result = try {
                repository.getKLineData(code, period.apiPeriod, target)
            } catch (e: Exception) {
                Result.failure<List<KLineData>>(e)
            }
            val data = result.getOrNull().orEmpty()
            if (data.size > _klineData.value.size) {
                _klineData.value = data
                historyCount = data.size
            }
            // 接口给不出更多了（已到上市首日）或者到了本周期上限
            if (data.size < target || historyCount >= period.maxBars) _historyAtEnd.value = true
            _isLoadingMore.value = false
        }
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
