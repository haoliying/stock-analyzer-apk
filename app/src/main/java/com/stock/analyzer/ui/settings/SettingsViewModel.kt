package com.stock.analyzer.ui.settings

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import com.stock.analyzer.analysis.StockAnalyzer
import com.stock.analyzer.data.repository.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 版本卡上的信息，全部从安装包里读，不在界面上写死版本号 */
data class VersionInfo(
    val versionName: String,
    val versionCode: String,
    val packageName: String,
    val debuggable: Boolean
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = AppSettings.get(app)

    private val _appLockEnabled = MutableStateFlow(settings.appLockEnabled)
    val appLockEnabled: StateFlow<Boolean> = _appLockEnabled.asStateFlow()

    private val _availability = MutableStateFlow(AppLock.check(app))
    val availability: StateFlow<AppLock.Availability> = _availability.asStateFlow()

    /** 开关操作的结果：开不了的原因，或开启/关闭后的说明。一直显示到下一次操作 */
    private val _lockNotice = MutableStateFlow<String?>(null)
    val lockNotice: StateFlow<String?> = _lockNotice.asStateFlow()

    val version: VersionInfo = readVersion(app)

    /** 评分口径这行直接从引擎常量里拼，改了常量界面就跟着变，不会说谎 */
    val engineLine: String = "评分线：加仓 ${StockAnalyzer.BUY_SCORE}／减仓 ${StockAnalyzer.SELL_SCORE}／强烈减仓 ${StockAnalyzer.STRONG_SELL_SCORE}；" +
        "满窗 ${StockAnalyzer.BARS_FULL} 根日线，不足 ${StockAnalyzer.BARS_MIN} 根不评分；" +
        "波动惩罚起点 ${String.format("%.1f", StockAnalyzer.ATR_KNEE * 100)}%；" +
        "每日 ${StockAnalyzer.DAILY_PICKS} 只 = 当日分数前 ${(StockAnalyzer.POOL_FRACTION * 100).toInt()}% 组成的分散池里等距取样"

    fun setAppLock(on: Boolean) {
        if (on) {
            // 生物识别不可用还硬开，下次启动就把人锁在门外了；先刷新一次再决定
            val status = AppLock.check(getApplication())
            _availability.value = status
            if (!status.usable) {
                _lockNotice.value = "没能开启应用锁：" + status.status
                return
            }
        }
        settings.appLockEnabled = on
        _appLockEnabled.value = on
        _lockNotice.value = if (on) "已开启。切出应用再回来就会要求解锁；现在正在看的这一屏不受影响。" else "已关闭。"
    }

    fun refreshAvailability() {
        _availability.value = AppLock.check(getApplication())
    }

    private fun readVersion(app: Application): VersionInfo = try {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toString()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toString()
        }
        VersionInfo(
            versionName = info.versionName ?: "未知",
            versionCode = code,
            packageName = app.packageName,
            debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        )
    } catch (e: Exception) {
        VersionInfo("读不到", "读不到", app.packageName, false)
    }
}
