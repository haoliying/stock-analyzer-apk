package com.stock.analyzer.ui.market

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

class MarketViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = StockRepository(application)
    private val analyzer = StockAnalyzer()

    private val _recommendations = MutableStateFlow<List<StockRecommendation>>(emptyList())
    val recommendations: StateFlow<List<StockRecommendation>> = _recommendations.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _progress = MutableStateFlow("")
    val progress: StateFlow<String> = _progress.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _lastUpdateTime = MutableStateFlow<Long?>(null)
    val lastUpdateTime: StateFlow<Long?> = _lastUpdateTime.asStateFlow()

    // 今日分位池规模（进入前 50% 分位的股票数），供页面说明"列表是从中分层取样的"
    private val _poolInfo = MutableStateFlow("")
    val poolInfo: StateFlow<String> = _poolInfo.asStateFlow()

    // 候选股票列表（约200只热门股票）
    private val candidates = listOf(
        // 沪市主板
        "sh600000", "sh600009", "sh600016", "sh600019", "sh600028",
        "sh600029", "sh600030", "sh600031", "sh600036", "sh600048",
        "sh600050", "sh600089", "sh600104", "sh600111", "sh600115",
        "sh600150", "sh600176", "sh600183", "sh600188", "sh600196",
        "sh600219", "sh600276", "sh600298", "sh600309", "sh600316",
        "sh600332", "sh600346", "sh600352", "sh600362", "sh600383",
        "sh600392", "sh600398", "sh600406", "sh600426", "sh600436",
        "sh600438", "sh600460", "sh600487", "sh600489", "sh600498",
        "sh600516", "sh600519", "sh600521", "sh600536", "sh600547",
        "sh600549", "sh600570", "sh600583", "sh600585", "sh600588",
        "sh600606", "sh600655", "sh600660", "sh600674", "sh600688",
        "sh600690", "sh600703", "sh600741", "sh600745", "sh600760",
        "sh600763", "sh600795", "sh600809", "sh600837", "sh600845",
        "sh600848", "sh600862", "sh600867", "sh600886", "sh600887",
        "sh600893", "sh600900", "sh600905", "sh600918", "sh600919",
        "sh600941", "sh600958", "sh600977", "sh600989", "sh600999",
        "sh601006", "sh601009", "sh601012", "sh601021", "sh601066",
        "sh601088", "sh601100", "sh601108", "sh601111", "sh601117",
        "sh601138", "sh601155", "sh601162", "sh601166", "sh601169",
        "sh601186", "sh601211", "sh601225", "sh601228", "sh601229",
        "sh601231", "sh601236", "sh601238", "sh601288", "sh601298",
        "sh601318", "sh601319", "sh601328", "sh601336", "sh601360",
        "sh601377", "sh601390", "sh601398", "sh601600", "sh601601",
        "sh601607", "sh601618", "sh601628", "sh601633", "sh601658",
        "sh601668", "sh601669", "sh601688", "sh601698", "sh601727",
        "sh601766", "sh601788", "sh601800", "sh601808", "sh601816",
        "sh601818", "sh601828", "sh601838", "sh601857", "sh601877",
        "sh601878", "sh601881", "sh601888", "sh601898", "sh601899",
        "sh601901", "sh601919", "sh601933", "sh601939", "sh601958",
        "sh601985", "sh601988", "sh601989", "sh601992", "sh601995",
        "sh601998", "sh603000", "sh603019", "sh603156", "sh603185",
        "sh603195", "sh603259", "sh603260", "sh603288", "sh603290",
        "sh603501", "sh603517", "sh603568", "sh603613", "sh603659",
        "sh603799", "sh603806", "sh603833", "sh603882", "sh603899",
        "sh603986",
        // 科创板
        "sh688005", "sh688008", "sh688009", "sh688012", "sh688036",
        "sh688111", "sh688126", "sh688169", "sh688180", "sh688185",
        "sh688390", "sh688561", "sh688599",
        // 深市主板
        "sz000001", "sz000002", "sz000063", "sz000066", "sz000069",
        "sz000100", "sz000157", "sz000166", "sz000301", "sz000333",
        "sz000338", "sz000400", "sz000401", "sz000423", "sz000425",
        "sz000538", "sz000568", "sz000596", "sz000625", "sz000651",
        "sz000661", "sz000683", "sz000703", "sz000708", "sz000709",
        "sz000723", "sz000725", "sz000733", "sz000738", "sz000768",
        "sz000776", "sz000778", "sz000783", "sz000786", "sz000800",
        "sz000807", "sz000858", "sz000860", "sz000876", "sz000895",
        "sz000938", "sz000960", "sz000963", "sz000975", "sz000977",
        "sz000999", "sz002001", "sz002007", "sz002008", "sz002024",
        "sz002027", "sz002032", "sz002049", "sz002050", "sz002064",
        "sz002080", "sz002120", "sz002129", "sz002142", "sz002146",
        "sz002152", "sz002153", "sz002156", "sz002179", "sz002180",
        "sz002202", "sz002230", "sz002236", "sz002241", "sz002271",
        "sz002304", "sz002311", "sz002340", "sz002352", "sz002371",
        "sz002384", "sz002385", "sz002389", "sz002399", "sz002410",
        "sz002415", "sz002422", "sz002430", "sz002460", "sz002466",
        "sz002468", "sz002475", "sz002493", "sz002555", "sz002594",
        "sz002600", "sz002601", "sz002602", "sz002607", "sz002648",
        "sz002709", "sz002714", "sz002736", "sz002739", "sz002756",
        "sz002812", "sz002821", "sz002831", "sz002841", "sz002850",
        "sz002916", "sz002920", "sz002938", "sz002945", "sz002966",
        // 创业板
        "sz300003", "sz300009", "sz300014", "sz300015", "sz300033",
        "sz300059", "sz300122", "sz300124", "sz300142", "sz300144",
        "sz300146", "sz300223", "sz300274", "sz300285", "sz300308",
        "sz300316", "sz300347", "sz300394", "sz300408", "sz300413",
        "sz300433", "sz300450", "sz300496", "sz300498", "sz300601",
        "sz300628", "sz300661", "sz300750", "sz300751", "sz300760",
        "sz300782", "sz300832", "sz300866", "sz300896", "sz300979",
        "sz300999"
    )

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            _progress.value = "正在获取股票数据..."

            try {
                // 获取所有候选股票的实时行情
                _progress.value = "正在获取行情数据..."
                val quotesResult = repository.getRealtimeQuotes(candidates)
                val quotes = quotesResult.getOrElse {
                    _error.value = "获取行情失败: ${it.message}"
                    _isLoading.value = false
                    return@launch
                }

                _progress.value = "正在分析 ${quotes.size} 只股票..."
                val results = mutableListOf<StockRecommendation>()

                quotes.forEachIndexed { index, stock ->
                    _progress.value = "分析中 ${index + 1}/${quotes.size}: ${stock.name}"

                    try {
                        // 130 根日线：MA60 + 10日斜率 + Wilder 递推需要这段历史，
                        // 与回测（backtest/precompute.py）的口径一致
                        val klineResult = repository.getKLineData(stock.code, "daily", StockAnalyzer.BARS_FULL)
                        if (klineResult.isSuccess) {
                            val kline = klineResult.getOrDefault(emptyList())
                            val rec = analyzer.generateRecommendation(stock, kline)
                            if (rec != null) {
                                results.add(rec)
                            }
                        }
                    } catch (e: Exception) {
                        // 跳过分析失败的股票
                    }

                    // 控制请求频率
                    if (index < quotes.size - 1) {
                        Thread.sleep(200)
                    }
                }

                // 关键：不按评分排序取前几名。
                // iter36（30 只口径，同一批信号日）实测：池内"分数最高的 30 只"日均超额 −0.430%
                // （20 个换仓相位只有 4 个跑赢基准），"按分位等距取 30 只"+0.669%（t_HAC +1.51，
                // 19/20 相位跑赢），两者配对差 +1.100%、块自助 p 0.035 —— 这是全模型最硬的一条结论。
                // 入池不再用 ≥68 这个绝对门槛：门槛的代价见 StockAnalyzer 类注释（iter32 / iter37）。
                val eligible = analyzer.eligibleCount(results)
                val picks = analyzer.selectDailyCandidates(results)
                    .mapIndexed { i, rec -> rec.copy(rank = i + 1) }
                val nBuy = picks.count { it.analysis.recommendation == com.stock.analyzer.data.model.Recommendation.BUY }

                _recommendations.value = picks
                _poolInfo.value = "今日 $eligible 只进入前 ${(StockAnalyzer.POOL_FRACTION * 100).toInt()}% 分位池，" +
                    "等距取 ${picks.size} 只等权（不是排行榜）；" +
                    "其中 $nBuy 只在加仓线以上，其余为持有档——档位只回答\"手里这只该不该再加\"，" +
                    "回测赚的是整篮子的分位差"
                _lastUpdateTime.value = System.currentTimeMillis()
            } catch (e: Exception) {
                _error.value = "分析失败: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
