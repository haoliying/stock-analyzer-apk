package com.stock.analyzer.analysis

import android.content.Context
import com.google.gson.Gson
import com.stock.analyzer.data.model.AssetClass
import com.stock.analyzer.data.model.MetalInstrument
import com.stock.analyzer.data.model.MetalQuote
import com.stock.analyzer.data.repository.AppSettings
import com.stock.analyzer.data.repository.AssetClassSelection
import com.stock.analyzer.data.repository.MetalsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 进程级贵金属报价引擎。和 [MarketEngine] 同构，但**不算分、不出建议**：
 * 这一类只做行情、K 线、单位、涨跌幅。
 *
 * 为什么没有评分：同一套 130 根日线口径的分数在贵金属上跑的回测没过
 * （仓库 `backtest/metals/README.md`，iter1~iter8）。要点是
 * 择时每笔超额收益 13 条序列 0 条为正、置信区间整段在零下，
 * 低分档的绝对收益反而是正的，ATR 惩罚项在黄金上几乎不触发 ——
 * 分数量的不是同一件事，所以界面上干脆不给。
 * 给一个"验证过的东西都没有"的数字，比什么都不给糟糕得多。
 *
 * 抓取节奏：一次批量请求就拿到全部品种（`list=hf_XAU,...,nf_AG0`），
 * 所以这一路的成本比 A 股那 330 次日线低两个数量级，节奏可以密一些：
 *   · 屏幕上正看着贵金属 → [POLL_ON_PAGE]
 *   · 切回 A 股那一类 → 退到 [POLL_AWAY]，只当后台留个新鲜价（持仓里可能有贵金属）
 *   · 境外境内都收市（周日、周六早 6 点后）→ [GAP_OFF_SESSION] 醒来看一眼表，不发请求
 * 失败按 [GAP_ERROR] 起退避，最多 10 分钟。
 *
 * 明确不做：没有前台服务、没有常驻通知，和 MarketEngine 一样不假装能对抗厂商后台管控。
 */
object MetalsEngine {

    private const val POLL_ON_PAGE = 30_000L
    private const val POLL_AWAY = 10 * 60_000L
    private const val GAP_OFF_SESSION = 30 * 60_000L
    private const val GAP_ERROR = 60_000L
    private const val IDLE_POLL_BG = 60_000L

    private const val SNAPSHOT_NAME = "metals_snapshot.json"
    private const val SNAPSHOT_VERSION = 1

    /** 界面那行"报价新鲜度"要说的东西 */
    data class Status(
        val fetching: Boolean = false,
        val nextRunAt: Long = 0L,
        val offSession: Boolean = false,
        val continuous: Boolean = false,
        val foreground: Boolean = false
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gson = Gson()
    private val shanghai: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    private lateinit var appContext: Context
    private lateinit var repository: MetalsRepository

    @Volatile
    private var started = false

    @Volatile
    private var foreground = false

    @Volatile
    private var uiLocked = false

    @Volatile
    private var nextRunAt = 0L

    @Volatile
    private var wantImmediate = false

    @Volatile
    private var fetchingNow = false

    private var failStreak = 0

    private val wake = Channel<Unit>(1)

    private val _quotes = MutableStateFlow<Map<String, MetalQuote>>(emptyMap())
    val quotes: StateFlow<Map<String, MetalQuote>> = _quotes.asStateFlow()

    private val _fetchedAt = MutableStateFlow<Long?>(null)
    val fetchedAt: StateFlow<Long?> = _fetchedAt.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    // ================================================================== 生命周期

    /** 幂等：Application 调一次，各 ViewModel 再调也不会重复起循环 */
    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            appContext = context.applicationContext
            repository = MetalsRepository(appContext)
        }
        restoreSnapshot()
        scope.launch { loop() }
    }

    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (value) requestIfStale()
        signalWake()
        publishStatus()
    }

    fun setUiLocked(value: Boolean) {
        if (uiLocked == value) return
        uiLocked = value
        if (!value) requestIfStale()
        signalWake()
        publishStatus()
    }

    /** 顶部刷新键：立刻发一次批量请求（这一路只有一条请求，不需要"不打断进度"那套） */
    fun refreshNow() {
        wantImmediate = true
        nextRunAt = 0L
        signalWake()
        publishStatus()
    }

    /** 进页面时催一下：报价太旧（或从来没有）才真的发请求 */
    fun requestIfStale() {
        val stamp = _fetchedAt.value
        if (stamp == null || System.currentTimeMillis() - stamp > POLL_ON_PAGE * 2) refreshNow()
    }

    /**
     * 持仓页只想要个价，不想自己发请求时用。
     * 本机还没有任何报价（冷启动 + 引擎还没跑到第一轮）才当场取一次，
     * 否则只把过期判断交回循环 —— 持仓页不该和行情页抢着发请求。
     */
    suspend fun quotesNow(): Map<String, MetalQuote> {
        if (_quotes.value.isEmpty()) {
            val got = withContext(Dispatchers.IO) { repository.quotes(MetalInstrument.ALL) }
            if (!got.isNullOrEmpty()) merge(got, System.currentTimeMillis())
        } else {
            requestIfStale()
        }
        return _quotes.value
    }

    // ================================================================== 主循环

    private suspend fun loop() {
        while (scope.isActive) {
            if (!shouldRun()) {
                sleepUntil(System.currentTimeMillis() + IDLE_POLL_BG)
                continue
            }
            val now = System.currentTimeMillis()
            if (now < nextRunAt) {
                sleepUntil(nextRunAt)
                continue
            }
            if (!sessionLikely(now) && !wantImmediate) {
                nextRunAt = now + minOf(GAP_OFF_SESSION, msToNextSession(now))
                publishStatus()
                sleepUntil(nextRunAt)
                continue
            }
            try {
                runOnce()
            } catch (e: Exception) {
                _error.value = "贵金属行情异常: ${e.message}"
                failStreak++
                nextRunAt = System.currentTimeMillis() + GAP_ERROR
                fetchingNow = false
                publishStatus()
            }
        }
    }

    /** 和 A 股那一轮同一个开关：关掉就只在界面可见且已解锁时刷新 */
    private fun shouldRun(): Boolean = (try {
        AppSettings.get(appContext).backgroundAnalysis
    } catch (e: Exception) {
        false
    }) || (foreground && !uiLocked)

    private suspend fun runOnce() {
        fetchingNow = true
        publishStatus()
        val got = try {
            withContext(Dispatchers.IO) { repository.quotes(MetalInstrument.ALL) }
        } catch (e: Exception) {
            null
        }
        fetchingNow = false
        val now = System.currentTimeMillis()
        if (got.isNullOrEmpty()) {
            _error.value = "贵金属报价没取到（接口没返回，或者此刻没网），" +
                "界面仍是 ${formatTime(_fetchedAt.value ?: 0L)} 取到的价格"
            failStreak++
            nextRunAt = now + errGap(now)
        } else {
            merge(got, now)
            failStreak = 0
            _error.value = null
            nextRunAt = now + pollGap()
            saveSnapshot()
        }
        wantImmediate = false
        publishStatus()
    }

    /**
     * 合入新报价。某个品种这一次没回来时**保留上一次的数值**，
     * 因为界面每行都显示数据源自己那条时间戳，旧价格一眼就能看出来是旧的；
     * 直接从列表里消失反而会被当成"这个品种下架了"。
     */
    private fun merge(got: Map<String, MetalQuote>, at: Long) {
        val m = LinkedHashMap(_quotes.value)
        m.putAll(got)
        _quotes.value = m
        _fetchedAt.value = at
    }

    /**
     * 屏幕上正看着贵金属 → [POLL_ON_PAGE]；切回别的大类 → [POLL_AWAY]，
     * 因为持仓里可能还留着贵金属，价格还得往下走，只是不必那么勤。
     */
    private fun pollGap(): Long =
        if (AssetClassSelection.current.value == AssetClass.PRECIOUS_METALS) POLL_ON_PAGE else POLL_AWAY

    private fun errGap(now: Long): Long =
        if (sessionLikely(now)) GAP_ERROR
        else minOf(GAP_OFF_SESSION, GAP_ERROR * minOf(failStreak, 10).coerceAtLeast(2))

    /**
     * 此刻有没有"新价格"可取。贵金属是几个市场拼起来的：
     * 境外现货/期货几乎全天（周一早到周六凌晨），境内日盘 + 21:00~02:30 夜盘。
     * 所以工作日一律算"可能有新价格"，周六早上六点以后和整个周日算收市 ——
     * 那几天 Sina 返回的就是最后一笔，没必要每分钟去问一次。
     *
     * 只认星期和钟点，不认识境外的节假日（圣诞、元旦前后会白跑几轮）。
     */
    internal fun sessionLikely(now: Long): Boolean {
        val c = calendarAt(now)
        return when (c.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SATURDAY -> c.get(Calendar.HOUR_OF_DAY) < 6
            Calendar.SUNDAY -> false
            else -> true
        }
    }

    /** 睡到 until，可被 wake 提前叫醒（按刷新、回前台、解锁、切到大类） */
    private suspend fun sleepUntil(until: Long) {
        val remaining = until - System.currentTimeMillis()
        if (remaining <= 0L) return
        withTimeoutOrNull(remaining) { wake.receive() }
    }

    private fun signalWake() {
        wake.trySend(Unit)
    }

    /** 离下一个"可能有新价格"的钟点还有多久：每 30 分钟走一步，最多看 8 天 */
    internal fun msToNextSession(now: Long): Long {
        val c = calendarAt(now)
        var steps = 0
        while (steps < 8 * 48) {
            c.add(Calendar.MINUTE, 30)
            steps++
            if (sessionLikely(c.timeInMillis)) return c.timeInMillis - now
        }
        return GAP_OFF_SESSION
    }

    // ================================================================== 落盘 / 读回

    private class Snapshot(
        val version: Int = SNAPSHOT_VERSION,
        val savedAt: Long = 0L,
        val quotes: Map<String, MetalQuote> = emptyMap()
    )

    private fun snapshotFile(): File = File(appContext.filesDir, SNAPSHOT_NAME)

    /**
     * 冷启动先把上一次取到的报价放回去，界面上标的是"拉取于 …"，
     * 所以离线打开也能看到价格 —— 但那个时间戳会明说它是几点的数据，不会冒充实时。
     */
    private fun restoreSnapshot() {
        val snap = try {
            val f = snapshotFile()
            if (!f.exists()) null else gson.fromJson(f.readText(), Snapshot::class.java)
        } catch (e: Exception) {
            null
        } ?: return
        if (snap.savedAt <= 0L || snap.quotes.isEmpty() || snap.version != SNAPSHOT_VERSION) return
        _quotes.value = snap.quotes
        _fetchedAt.value = snap.savedAt
    }

    private suspend fun saveSnapshot() = withContext(Dispatchers.IO) {
        try {
            val stamp = _fetchedAt.value ?: System.currentTimeMillis()
            snapshotFile().writeText(
                gson.toJson(Snapshot(savedAt = stamp, quotes = _quotes.value))
            )
        } catch (e: Exception) {
            // 快照只是离线体验优化，写失败不影响刷新
        }
    }

    private fun publishStatus() {
        val continuous = try {
            AppSettings.get(appContext).backgroundAnalysis
        } catch (e: Exception) {
            false
        }
        _status.value = Status(
            fetching = fetchingNow,
            nextRunAt = nextRunAt,
            offSession = !sessionLikely(System.currentTimeMillis()),
            continuous = continuous,
            foreground = foreground
        )
    }

    // ================================================================== 时间工具

    private fun calendarAt(millis: Long): Calendar =
        Calendar.getInstance(shanghai).apply { timeInMillis = millis }

    private fun formatTime(millis: Long): String =
        if (millis <= 0L) "（还没有取到过）"
        else SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(millis)

    /** 状态行文案，三页共用这一句，别各写一套口径。参数由界面从 StateFlow 里取，保证会跟着刷新 */
    fun statusLine(s: Status, stamp: Long?): String {
        return when {
            s.fetching -> "贵金属：正在取批量报价（一次请求取回全部品种）"
            stamp == null -> "贵金属：还没取到报价" + (if (s.offSession) "，此刻境外与境内都收市" else "")
            s.offSession -> "贵金属：非交易时段，价格是 ${formatTime(stamp)} 取到的最后一笔，不发请求"
            else -> "贵金属：报价取于 ${formatTime(stamp)}" +
                (if (s.nextRunAt > stamp) "，下一次 ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(s.nextRunAt)}" else "") +
                (if (!s.continuous && !s.foreground) "（「后台持续分析」已关，界面不可见时不刷新）" else "")
        }
    }
}
