package com.stock.analyzer.ui.nonstock

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.stock.analyzer.analysis.NonStockEngine
import com.stock.analyzer.data.model.NonStockQuote
import kotlinx.coroutines.flow.StateFlow

/**
 * 非股票（贵金属 / 大宗商品 / 外汇）列表页的视图模型 —— 和
 * [com.stock.analyzer.ui.market.MarketViewModel] 一样，只是进程级引擎 [NonStockEngine]
 * 的转发层，自己不发请求、不存状态。
 *
 * 这样切页、解锁、来回切换大类都不会打断刷新，也不会重复起一轮请求。
 */
class NonStockViewModel(application: Application) : AndroidViewModel(application) {

    val quotes: StateFlow<Map<String, NonStockQuote>> = NonStockEngine.quotes
    val fetchedAt: StateFlow<Long?> = NonStockEngine.fetchedAt
    val status: StateFlow<NonStockEngine.Status> = NonStockEngine.status
    val error: StateFlow<String?> = NonStockEngine.error

    init {
        NonStockEngine.start(application)
        NonStockEngine.requestIfStale()
    }

    fun refresh() = NonStockEngine.refreshNow()
}

/** v4.8.0~v4.9.0 的类名，泛化后保留别名 */
typealias MetalsViewModel = NonStockViewModel
