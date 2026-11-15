package com.stock.analyzer.ui.market

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.stock.analyzer.analysis.MarketEngine
import com.stock.analyzer.data.model.StockRecommendation
import kotlinx.coroutines.flow.StateFlow

/**
 * 「今日候选」页的视图模型。
 *
 * v4.7.0 起它不再自己跑分析：整轮全市场扫描搬进了进程级 [MarketEngine]，
 * 这里只做两件事 —— 把引擎的状态流交给界面、把界面上的按钮转成引擎的调用。
 *
 * 为什么要搬：以前是 `init { refresh() }` 跟着页面创建跑，于是切走再切回来、
 * 锁屏解锁回来，都要从第 1 只重新数一遍。现在进出页面不打断引擎，
 * 界面显示的一直是引擎的当前状态。
 */
class MarketViewModel(application: Application) : AndroidViewModel(application) {

    init {
        MarketEngine.start(application)
        // 只有结果确实旧了才催一轮；引擎正在跑的时候什么都不做
        MarketEngine.requestPassIfStale()
    }

    val recommendations: StateFlow<List<StockRecommendation>> = MarketEngine.recommendations
    val progress: StateFlow<String> = MarketEngine.progress
    val error: StateFlow<String?> = MarketEngine.error
    val lastUpdateTime: StateFlow<Long?> = MarketEngine.lastUpdateTime
    val poolInfo: StateFlow<String> = MarketEngine.poolInfo
    val status: StateFlow<MarketEngine.Status> = MarketEngine.status

    /** 刷新键：正在跑的那一轮不会被打回第 1 只，只是跑完立刻接新一轮 */
    fun refresh() = MarketEngine.refreshNow()
}
