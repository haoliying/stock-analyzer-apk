package com.stock.analyzer.data.repository

import android.content.Context
import com.stock.analyzer.data.model.AssetClass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「当前看的是哪一类资产」这份状态。进程级一份，三个页面共用。
 *
 * 为什么要单独一个对象，而不是让每个页面自己记：切换必须同时影响
 * 今日候选、我的持仓、行情三页，而且要能改 [AppSettings] 里那一份持久化值。
 * 挂在页面上就会出现"这页切到贵金属、那页还是 A 股"的分裂状态。
 *
 * 为什么不塞进 MarketEngine：引擎只管 A 股那一轮，贵金属有自己那条链路
 * （[com.stock.analyzer.analysis.NonStockEngine]），两边都不该知道对方的内部状态。
 * 这一类以后加大宗商品、外汇也只是 AssetClass 多一个条目。
 */
object AssetClassSelection {

    private val _current = MutableStateFlow(AssetClass.A_SHARE)
    val current: StateFlow<AssetClass> = _current.asStateFlow()

    /** Application.onCreate 里调一次，把上次选的那一类读回来 */
    fun init(context: Context) {
        _current.value = try {
            AppSettings.get(context.applicationContext).assetClass
        } catch (e: Exception) {
            AssetClass.A_SHARE
        }
    }

    fun select(context: Context, assetClass: AssetClass) {
        if (_current.value == assetClass) return
        _current.value = assetClass
        try {
            AppSettings.get(context.applicationContext).assetClass = assetClass
        } catch (e: Exception) {
            // 存不回去只影响下次冷启动默认落在哪一类，本次切换照常生效
        }
    }
}
