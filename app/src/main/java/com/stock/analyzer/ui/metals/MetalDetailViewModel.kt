package com.stock.analyzer.ui.metals

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stock.analyzer.analysis.MetalsEngine
import com.stock.analyzer.data.model.KLineData
import com.stock.analyzer.data.model.MetalInstrument
import com.stock.analyzer.data.repository.MetalsRepository
import com.stock.analyzer.ui.detail.StockDetailViewModel.ChartPeriod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 贵金属详情页的视图模型：一条品种 + 一份日线缓存 + 界面上那个周期标签。
 *
 * 只支持日 / 周 / 月三个周期：**没有分时和 5 分钟**。
 * 分时那一路接口（东财 trends2、腾讯分钟线）都不覆盖境外现货期货，
 * 与其在界面上留一个点了报错的标签，不如根本不给。
 * 周线、月线不单独发请求，由 [MetalsRepository.aggregate] 从日线聚合，
 * 所以三个周期天然对得上同一份历史。
 */
class MetalDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MetalsRepository(application)

    private val _instrument = MutableStateFlow<MetalInstrument?>(null)
    val instrument: StateFlow<MetalInstrument?> = _instrument.asStateFlow()

    private val _period = MutableStateFlow(ChartPeriod.DAILY)
    val period: StateFlow<ChartPeriod> = _period.asStateFlow()

    private val _bars = MutableStateFlow<List<KLineData>>(emptyList())
    val bars: StateFlow<List<KLineData>> = _bars.asStateFlow()

    private val _daily = MutableStateFlow<List<KLineData>>(emptyList())
    val daily: StateFlow<List<KLineData>> = _daily.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 数据本身没问题、但界面上必须提醒的一句（周末/隔日/历史到底）*/
    private val _note = MutableStateFlow<String?>(null)
    val note: StateFlow<String?> = _note.asStateFlow()

    val quotes: StateFlow<Map<String, com.stock.analyzer.data.model.MetalQuote>> = MetalsEngine.quotes

    private var code: String = ""

    init {
        MetalsEngine.start(application)
    }

    fun load(code: String, force: Boolean = false) {
        val inst = MetalInstrument.of(code)
        _instrument.value = inst
        if (inst == null) {
            _error.value = "不认识这个贵金属代码：$code"
            return
        }
        this.code = code
        MetalsEngine.requestIfStale()
        loadBars(force)
    }

    fun setPeriod(period: ChartPeriod) {
        if (_period.value == period) return
        _period.value = period
        // 日线已经在手上了，切周期只是换一种聚合方式，不再发请求
        _bars.value = MetalsRepository.aggregate(_daily.value, apiPeriod(period))
        _note.value = noteFor(_bars.value, period)
    }

    fun refresh() = load(code, force = true)

    private fun loadBars(force: Boolean) {
        val inst = _instrument.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            repository.bars(inst, MetalsRepository.PERIOD_DAILY, force)
                .onSuccess { daily ->
                    _daily.value = daily
                    _bars.value = MetalsRepository.aggregate(daily, apiPeriod(_period.value))
                    _note.value = noteFor(_bars.value, _period.value)
                }
                .onFailure { _error.value = it.message }
            _isLoading.value = false
        }
    }

    /** 只有日/周/月三个周期，[ChartPeriod] 里那两个分钟级在这里不会被选到 */
    private fun apiPeriod(period: ChartPeriod): String = when (period) {
        ChartPeriod.WEEKLY -> MetalsRepository.PERIOD_WEEKLY
        ChartPeriod.MONTHLY -> MetalsRepository.PERIOD_MONTHLY
        else -> MetalsRepository.PERIOD_DAILY
    }

    private fun noteFor(bars: List<KLineData>, period: ChartPeriod): String? {
        if (bars.isEmpty()) return "没有${period.label}数据"
        val last = MetalsRepository.parseDate(bars.last().date) ?: return null
        val gap = java.time.temporal.ChronoUnit.DAYS.between(last, java.time.LocalDate.now())
        return when {
            gap <= 4 -> null
            else -> "最后一根${period.label}是 ${bars.last().date}（距今 $gap 天）：" +
                "境外节假日、境内休市或这个合约已经换月都会这样，不是接口坏了"
        }
    }
}
