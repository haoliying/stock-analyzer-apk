package com.stock.analyzer.data.repository

import android.content.Context

/**
 * 应用级配置，存在本机 SharedPreferences 里，不进任何网络。
 *
 * 目前只有一项：应用锁开关。默认关闭 —— 装上就锁会把人挡在外面，
 * 必须由用户自己在「设置」里明确打开。
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

    companion object {
        private const val NAME = "app_settings"
        private const val KEY_APP_LOCK = "app_lock_enabled"

        @Volatile
        private var instance: AppSettings? = null

        /** 进程内单例：解锁逻辑和「设置」页读写的必须是同一份配置 */
        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(context).also { instance = it }
            }
    }
}
