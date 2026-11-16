package com.stock.analyzer.ui.metals

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.stock.analyzer.analysis.MetalsEngine
import com.stock.analyzer.data.model.MetalQuote
import kotlinx.coroutines.flow.StateFlow

/**
 * 贵金属列表页的视图模型 —— 和 [com.stock.analyzer.ui.market.MarketViewModel] 一样，
 * 只是进程级引擎 [MetalsEngine] 的转发层，自己不发请求、不存状态。
 *
 * 这样切页、解锁、来回切换大类都不会打断刷新，也不会重复起一轮请求。
 */
class MetalsViewModel(application: Application) : AndroidViewModel(application) {

    val quotes: StateFlow<Map<String, MetalQuote>> = MetalsEngine.quotes
    val fetchedAt: StateFlow<Long?> = MetalsEngine.fetchedAt
    val status: StateFlow<MetalsEngine.Status> = MetalsEngine.status
    val error: StateFlow<String?> = MetalsEngine.error

    init {
        MetalsEngine.start(application)
        MetalsEngine.requestIfStale()
    }

    fun refresh() = MetalsEngine.refreshNow()
}
