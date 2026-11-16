package com.stock.analyzer.data.repository

import android.content.Context
import com.stock.analyzer.data.model.AssetClass

/**
 * 应用级配置，存在本机 SharedPreferences 里，不进任何网络。
 *
 * 目前三项：应用锁开关、后台持续分析开关、当前资产大类（A股 / 贵金属）。
 * 应用锁默认关闭 —— 装上就锁会把人挡在外面，必须由用户自己在「设置」里明确打开。
 * 后台持续分析默认开启 —— 这一版要解决的就是"每次进来都从第 1 只重算"。
 */
class AppSettings private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 开启后：冷启动、或从后台回到前台，都需要生物识别解锁才能看到内容 */
    var appLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_APP_LOCK, false)
        set(value) {
            prefs.edit().putBoolean(KEY_APP_LOCK, value).apply()
        }

    /**
     * 开启后：全市场这一轮由进程自己按时间连续跑（切页、解锁、界面不可见都不停），
     * 关掉就只在界面可见且已解锁时跑；两种情况下被打断的一轮都是从断点续跑，不回到第 1 只。
     */
    var backgroundAnalysis: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND, true)
        set(value) {
            prefs.edit().putBoolean(KEY_BACKGROUND, value).apply()
        }

    /**
     * 当前选中的资产大类（今日候选 / 我的持仓 / 行情 三页共用这一个值）。
     *
     * 存在本机而不是只放内存：切换是用户的选择，进程被杀后重新打开还在贵金属那一类，
     * 比每次都跳回 A 股更符合"随时切换"的用法。默认 A 股 —— 这一类才是验证过的那套。
     */
    var assetClass: AssetClass
        get() = AssetClass.fromKey(
            prefs.getString(KEY_ASSET_CLASS, null)
        )
        set(value) {
            prefs.edit().putString(KEY_ASSET_CLASS, value.key).apply()
        }

    companion object {
        private const val NAME = "app_settings"
        private const val KEY_APP_LOCK = "app_lock_enabled"
        private const val KEY_BACKGROUND = "background_analysis"
        private const val KEY_ASSET_CLASS = "asset_class"

        @Volatile
        private var instance: AppSettings? = null

        /** 进程内单例：解锁逻辑和「设置」页读写的必须是同一份配置 */
        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(context).also { instance = it }
            }
    }
}
