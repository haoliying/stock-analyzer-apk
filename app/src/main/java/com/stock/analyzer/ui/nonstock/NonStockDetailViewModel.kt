package com.stock.analyzer.ui.nonstock

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stock.analyzer.analysis.MetalsRisk
import com.stock.analyzer.analysis.NonStockEngine
import com.stock.analyzer.data.model.KLineData
import com.stock.analyzer.data.model.NonStockCatalog
import com.stock.analyzer.data.model.NonStockInstrument
import com.stock.analyzer.data.repository.NonStockRepository
import com.stock.analyzer.ui.detail.StockDetailViewModel.ChartPeriod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 非股票详情页的视图模型：一条品种 + 一份日线缓存 + 界面上那个周期标签 + 风险参考那几个数。
 *
 * 只支持日 / 周 / 月三个周期：**没有分时和 5 分钟**。
 * 分时那一路接口（东财 trends2、腾讯分钟线）都不覆盖期货与现汇，
 * 与其在界面上留一个点了报错的标签，不如根本不给。
 * 周线、月线不单独发请求，由 [NonStockRepository.aggregate] 从日线聚合，
 * 所以三个周期天然对得上同一份历史。
 *
 * 风险刻度与偏离读数（[MetalsRisk]）要在 Dispatchers.Default 上算：
 * 波动目标是有状态的全量回放，偏离度要跟整段历史比，两边都是几万到几十万次加法，
 * 放主线程会卡那一帧。
 *
 * 风险刻度（波动目标）是**通用算术**，三类非股票资产共用；相对读数（金银比等）
 * 是贵金属专属，[MetalsRisk.readingsFor] 对非贵金属代码返回空列表，界面上就少那几行，
 * 不拿别的品种的数顶。
 */
class NonStockDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = NonStockRepository(application)

    private val _instrument = MutableStateFlow<NonStockInstrument?>(null)
    val instrument: StateFlow<NonStockInstrument?> = _instrument.asStateFlow()

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

    /**
     * 风险刻度（21 日年化波动 + 按 15% 波动目标折算的仓位）。
     * null = 这一页的日线还不够算（不足 [MetalsRisk.MIN_BARS] 根），界面上就整行不出现。
     */
    private val _risk = MutableStateFlow<MetalsRisk.Gauge?>(null)
    val risk: StateFlow<MetalsRisk.Gauge?> = _risk.asStateFlow()

    /** 相对读数（金银比 / 铂钯比 / 境内外对照），全部是描述性的，不带方向 */
    private val _readings = MutableStateFlow<List<MetalsRisk.Deviation>>(emptyList())
    val readings: StateFlow<List<MetalsRisk.Deviation>> = _readings.asStateFlow()

    val quotes: StateFlow<Map<String, com.stock.analyzer.data.model.NonStockQuote>> =
        NonStockEngine.quotes

    private var code: String = ""

    init {
        NonStockEngine.start(application)
    }

    fun load(code: String, force: Boolean = false) {
        val inst = NonStockCatalog.of(code)
        _instrument.value = inst
        if (inst == null) {
            _error.value = "不认识这个非股票资产代码：$code"
            return
        }
        this.code = code
        NonStockEngine.requestIfStale()
        loadBars(force)
    }

    fun setPeriod(period: ChartPeriod) {
        if (_period.value == period) return
        _period.value = period
        // 日线已经在手上了，切周期只是换一种聚合方式，不再发请求
        _bars.value = NonStockRepository.aggregate(_daily.value, apiPeriod(period))
        _note.value = noteFor(_bars.value, period)
    }

    fun refresh() = load(code, force = true)

    private fun loadBars(force: Boolean) {
        val inst = _instrument.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            repository.bars(inst, NonStockRepository.PERIOD_DAILY, force)
                .onSuccess { daily ->
                    _daily.value = daily
                    _bars.value = NonStockRepository.aggregate(daily, apiPeriod(_period.value))
                    _note.value = noteFor(_bars.value, _period.value)
                    loadRiskRefs(inst, daily, force)
                }
                .onFailure { _error.value = it.message }
            _isLoading.value = false
        }
    }

    /**
     * 风险刻度与相对读数。
     *
     * 读数要把参照品种的那份日线也取回来（金看银、境内看境外），所以这里可能再发一次请求；
     * 拿不到就少显示一行，不拿上一个品种的数顶。
     * 全部计算都在 [MetalsRisk] 里，是纯算术，没有第二个数据源。
     */
    private fun loadRiskRefs(inst: NonStockInstrument, daily: List<KLineData>, force: Boolean) {
        viewModelScope.launch(Dispatchers.Default) {
            _risk.value = MetalsRisk.gauge(daily.map { it.close })
            val specs = MetalsRisk.readingsFor(inst.code)
            if (specs.isEmpty()) {
                _readings.value = emptyList()
                return@launch
            }
            val need = LinkedHashSet<String>()
            specs.forEach {
                need += it.numCode
                need += it.denCode
            }
            need -= inst.code
            val extra = HashMap<String, List<KLineData>>()
            for (c in need) {
                val other = NonStockCatalog.of(c) ?: continue
                // 参照那一页的缓存照常复用，只有本品种被强制刷新时才一起 force
                extra[c] = repository.bars(other, NonStockRepository.PERIOD_DAILY, force)
                    .getOrNull() ?: emptyList()
            }
            val byCode = HashMap<String, Pair<List<String>, List<Double>>>()
            (extra + mapOf(inst.code to daily)).forEach { (c, bars) ->
                if (bars.isNotEmpty()) byCode[c] = bars.map { it.date } to bars.map { it.close }
            }
            _readings.value = specs.mapNotNull {
                MetalsRisk.deviation(it) { code -> byCode[code] }
            }
        }
    }

    /** 只有日/周/月三个周期，[ChartPeriod] 里那两个分钟级在这里不会被选到 */
    private fun apiPeriod(period: ChartPeriod): String = when (period) {
        ChartPeriod.WEEKLY -> NonStockRepository.PERIOD_WEEKLY
        ChartPeriod.MONTHLY -> NonStockRepository.PERIOD_MONTHLY
        else -> NonStockRepository.PERIOD_DAILY
    }

    private fun noteFor(bars: List<KLineData>, period: ChartPeriod): String? {
        if (bars.isEmpty()) return "没有${period.label}数据"
        val last = NonStockRepository.parseDate(bars.last().date) ?: return null
        val gap = java.time.temporal.ChronoUnit.DAYS.between(last, java.time.LocalDate.now())
        return when {
            gap <= 4 -> null
            else -> "最后一根${period.label}是 ${bars.last().date}（距今 $gap 天）：" +
                "境外节假日、境内休市或这个合约已经换月都会这样，不是接口坏了"
        }
    }
}

/** v4.8.0~v4.9.0 的类名，泛化后保留别名 */
typealias MetalDetailViewModel = NonStockDetailViewModel
