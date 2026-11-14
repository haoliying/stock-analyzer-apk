package com.stock.analyzer.ui.list

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stock.analyzer.analysis.StockAnalyzer
import com.stock.analyzer.data.model.KLineData
import com.stock.analyzer.data.model.Stock
import com.stock.analyzer.data.repository.StockRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StockListViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = StockRepository(application)
    private val analyzer = StockAnalyzer()

    private val _stocks = MutableStateFlow<List<Stock>>(emptyList())
    val stocks: StateFlow<List<Stock>> = _stocks.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * 每只股票的评分状态，按代码索引。
     * 行情页以前只拉实时价、完全不做分析，所以用户问"为什么有的股票没被分析"——
     * 现在是逐只补齐日线并按今日候选同一口径打分，取不到数据的也会写明原因。
     */
    data class Insight(
        val score: Int,
        val tier: String,
        val note: String?       // 非 null = 这一行的结论不可用，原因写在这里
    )

    private val _insights = MutableStateFlow<Map<String, Insight>>(emptyMap())
    val insights: StateFlow<Map<String, Insight>> = _insights.asStateFlow()

    // 默认显示的股票列表
    private val defaultCodes = listOf(
        "sh600519", "sh600036", "sh600030", "sz000858", "sz000651",
        "sz000333", "sh601318", "sh600276", "sh600048", "sz000002",
        "sh601888", "sz300750", "sz002594", "sz300059", "sh600900",
        "sz000001", "sh601398", "sh601288", "sh601939", "sh601012",
        "sz000725", "sz300015", "sh600887", "sz000568", "sz002304",
        "sh603288", "sz002415", "sz300760", "sh601166", "sz002142"
    )

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            try {
                val result = repository.getRealtimeQuotes(defaultCodes)
                result.onSuccess { stockList ->
                    _stocks.value = stockList
                    analyzeAll(stockList)
                }.onFailure { e ->
                    _error.value = "加载失败: ${e.message}"
                }
            } catch (e: Exception) {
                _error.value = "加载失败: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** 逐只补日线并打分，算完一只就发布一次，列表边算边出结果 */
    private suspend fun analyzeAll(stockList: List<Stock>) {
        val acc = LinkedHashMap<String, Insight>()
        _insights.value = acc
        for (stock in stockList) {
            acc[stock.code] = analyzeOne(stock.code)
            _insights.value = LinkedHashMap(acc)
            delay(100)
        }
    }

    private suspend fun analyzeOne(code: String): Insight {
        val result = try {
            repository.getKLineData(code, "daily", StockAnalyzer.BARS_FULL)
        } catch (e: Exception) {
            Result.failure<List<KLineData>>(e)
        }
        val kline = result.getOrElse { emptyList() }
        return when {
            kline.isEmpty() -> Insight(
                score = 0,
                tier = "未分析",
                note = "没有日线数据：${result.exceptionOrNull()?.message ?: "数据源未返回"}（停牌、退市或代码已变更）"
            )
            kline.size < StockAnalyzer.BARS_MIN -> Insight(
                score = 0,
                tier = "数据不足",
                note = "日线仅 ${kline.size} 根，算不出 MA60 及斜率（需 ≥${StockAnalyzer.BARS_MIN} 根）"
            )
            else -> {
                val a = analyzer.analyze(kline)
                val lag = StockRepository.lagDays(kline.last().date)
                Insight(
                    score = a.score,
                    tier = tierOf(a.score, a.dataSufficient),
                    note = if (lag != null && lag > StockRepository.STALE_LAG_DAYS)
                        "最后交易日 ${kline.last().date}（$lag 天前），多半已停牌或退市"
                    else if (a.dataSufficient) null
                    else "K线仅 ${kline.size} 根，分数不完整"
                )
            }
        }
    }

    /** 档位口径与今日候选、持仓建议完全一致（同一个 score 函数、同一组阈值） */
    private fun tierOf(score: Int, sufficient: Boolean): String = when {
        !sufficient -> "数据不足"
        score >= StockAnalyzer.BUY_SCORE -> "达到加仓线"
        score > StockAnalyzer.SELL_SCORE -> "持有"
        score > StockAnalyzer.STRONG_SELL_SCORE -> "减仓"
        else -> "强烈减仓"
    }
}
