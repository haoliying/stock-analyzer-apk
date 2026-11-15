package com.stock.analyzer.ui.list

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stock.analyzer.analysis.MarketEngine
import com.stock.analyzer.data.model.Stock
import com.stock.analyzer.data.repository.StockRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 「行情」页的视图模型。
 *
 * 分数不再在这里逐只算（那是 v4.6.0 的做法：进页面就 for 循环 30 只，每只 sleep 100ms，
 * 切出去再回来又从零开始）。评分由进程级 [MarketEngine] 统一产出，
 * 这里只负责把这一屏的实时价拉一次 —— 一次批量请求，不带循环。
 */
class StockListViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = StockRepository(application)

    private val _stocks = MutableStateFlow<List<Stock>>(emptyList())
    val stocks: StateFlow<List<Stock>> = _stocks.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 每只股票的评分状态，按代码索引，口径与「今日候选」同一个 score 函数 */
    val insights: StateFlow<Map<String, MarketEngine.Insight>> = MarketEngine.insights

    /** 引擎跑到哪了，界面用来说"这几只的分是上一轮的" */
    val status: StateFlow<MarketEngine.Status> = MarketEngine.status

    private val defaultCodes = listOf(
        "sh600519", "sh600036", "sh600030", "sz000858", "sz000651",
        "sz000333", "sh601318", "sh600276", "sh600048", "sz000002",
        "sh601888", "sz300750", "sz002594", "sz300059", "sh600900",
        "sz000001", "sh601398", "sh601288", "sh601939", "sh601012",
        "sz000725", "sz300015", "sh600887", "sz000568", "sz002304",
        "sh603288", "sz002415", "sz300760", "sh601166", "sz002142"
    )

    init {
        MarketEngine.start(application)
        refresh()
    }

    /** 只刷这一屏的实时价。评分不在这里做，所以刷多少次都不会"从第 1 只重新分析" */
    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                repository.getRealtimeQuotes(defaultCodes)
                    .onSuccess { _stocks.value = it }
                    .onFailure { e -> _error.value = "加载失败: ${e.message}" }
            } catch (e: Exception) {
                _error.value = "加载失败: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
