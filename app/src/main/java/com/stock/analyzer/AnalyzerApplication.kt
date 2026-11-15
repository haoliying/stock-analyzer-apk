package com.stock.analyzer

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.stock.analyzer.analysis.MarketEngine

/**
 * 进程启动点。
 *
 * 存在的唯一理由：把「持续分析」挂在进程上，而不是挂在某个页面（ViewModel）上。
 * v4.6.0 之前那一轮全市场分析写在 `MarketViewModel.init` 里，页面一销毁状态就没了，
 * 于是切页、解锁回来都从第 1 只重新数。
 */
class AnalyzerApplication : Application() {

    /**
     * 处于 STARTED 的 Activity 个数。用计数而不是"是否 resumed"：
     * 页面转场时两个 Activity 会短暂重叠，布尔值会闪一下"看不见"，把在跑的那轮误暂停掉。
     */
    private var visibleActivities = 0

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                visibleActivities++
                MarketEngine.setForeground(true)
            }

            override fun onActivityStopped(activity: Activity) {
                if (visibleActivities > 0) visibleActivities--
                MarketEngine.setForeground(visibleActivities > 0)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        MarketEngine.start(this)
    }
}
