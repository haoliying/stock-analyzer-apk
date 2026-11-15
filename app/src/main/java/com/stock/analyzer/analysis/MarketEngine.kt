package com.stock.analyzer.analysis

import android.content.Context
import com.google.gson.Gson
import com.stock.analyzer.data.model.KLineData
import com.stock.analyzer.data.model.Recommendation
import com.stock.analyzer.data.model.Stock
import com.stock.analyzer.data.model.StockRecommendation
import com.stock.analyzer.data.repository.AppSettings
import com.stock.analyzer.data.repository.StockRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
 * 进程级「持续分析」引擎（v4.7.0）。
 *
 * ## 为什么要把它从 ViewModel 里搬出来
 * v4.6.0 及以前，全市场这一轮是写在 `MarketViewModel.init { refresh() }` 里的：
 * ViewModel 跟着页面创建销毁，于是
 *   · 切到别的页再切回来 → 重新从第 1 只开始
 *   · 锁屏解锁回来 → 重新从第 1 只开始
 *   · 开着应用锁进应用 → 解锁后页面才组装，也是从第 1 只开始
 * 用户看到的就是"每次进来都在从 1 数"。引擎挂在进程上（`Application.onCreate()` 启动），
 * 页面只是订阅它的状态流：**进出页面、上锁解锁都不再打断或重启分析**。
 *
 * ## 续跑，而不是重来
 * 一轮跑到第几只（[cursor]）记在内存里。关掉「后台持续分析」或应用退到不可见时，
 * 本轮停在原地、已算好的分数保留；回到前台接着往下数，不清零、不重头。
 * 只有进程真被系统杀掉才可能重新起一轮 —— 那种情况也会先把上一轮的完整结果
 * 从 [SNAPSHOT_NAME] 读回界面（带"数据时间"标注），不会白屏空列表。
 *
 * ## 为什么候选名单要等一轮跑完才整体替换
 * 「今日候选」的口径是**当日全样本的分数分位**（前 50% 池里等距取 30 只，见
 * [StockAnalyzer.dailyPool]）。一轮跑到一半手上只有部分股票，"这批部分样本的前 50%"
 * 和"全天的前 50%"不是同一个统计量，拿它当候选名单是骗人。所以：
 *   · 候选名单 / 池子说明 / 更新时间 = 只在**整轮跑完**时一次性替换（口径与 v4.6.0 逐字一致）；
 *   · 逐只评分（行情页那一列）= 算完一只发布一只，它是个体系数，不存在跨样本问题。
 *
 * ## 抓取节奏
 * 每只股票一次日线请求 + [THROTTLE_MS] 间隔，和旧版一字不差（限流保护，别调小）。
 * 区别是这一轮现在按时间自己转：盘中跑完歇 [GAP_TRADING]；收盘后当天只补一轮；
 * 夜里/周末睡到下一个时段前再醒（醒来只是看一眼时间，不发请求）。
 *
 * ## 明确不做
 * 没有前台服务、没有常驻通知：进程被厂商后台管控杀掉就是杀掉了，不假装能对抗它。
 */
object MarketEngine {

    /** 行情页那一列的评分状态。原先是 `StockListViewModel.Insight`，现在由引擎统一产出 */
    data class Insight(val score: Int, val tier: String, val note: String?)

    /** 引擎运行状态，供界面写明"本轮跑到哪了、下一轮什么时候开始" */
    data class Status(
        val analyzing: Boolean = false,
        val paused: Boolean = false,
        val resumed: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val scored: Int = 0,
        val passStartedAt: Long = 0L,
        val nextRunAt: Long = 0L,
        val continuous: Boolean = false,
        val foreground: Boolean = false
    )

    // ---- 节奏常量 ----
    private const val THROTTLE_MS = 120L               // 与旧版一致：控制请求频率
    private const val IDLE_POLL_MS = 1_000L            // 空转轮询（界面可见时）：只看时间，不发请求
    private const val IDLE_POLL_BG = 15_000L           // 界面不可见时把检查粒度放粗，省电
    private const val GAP_TRADING = 20_000L            // 盘中：一轮跑完歇 20 秒
    private const val GAP_ERROR = 60_000L              // 取行情失败（多半没网）：一分钟后再试
    private const val GAP_CATCH_UP = 20_000L           // 当天还没跑完过一轮：尽快补
    private const val GAP_OFF_SESSION = 10 * 60_000L   // 收盘/周末：醒来看一眼时间的最长间隔
    private const val PUBLISH_EVERY = 5                // 逐只评分每算 5 只发布一次
    private const val STALE_TRADING = 3 * 60_000L      // 盘中结果超过 3 分钟算旧，进页面催一轮
    private const val STALE_CLOSED = 60 * 60_000L      // 收盘后 1 小时内不必催

    private const val SNAPSHOT_NAME = "market_snapshot.json"
    private const val SNAPSHOT_VERSION = 1

    private val analyzer = StockAnalyzer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gson = Gson()
    private val shanghai: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    private lateinit var appContext: Context
    private lateinit var repository: StockRepository

    @Volatile
    private var started = false

    // ---- 本轮进行中的状态（只在循环协程里改，进程内续跑用，不落盘）----
    private var quotes: List<Stock> = emptyList()
    private val scored = LinkedHashMap<String, StockRecommendation>()
    private val notes = LinkedHashMap<String, Insight>()
    private var cursor = 0
    private var noKline = 0
    private var shortHistory = 0
    private var passStartedAt = 0L
    private var passResumed = false
    private var pausedMidPass = false

    /** 本轮是否正在进行（含取行情快照那一段）。只在循环协程里改 */
    private var analyzingNow = false

    /**
     * 连续"这一轮没拿到任何数据"（接口不通、没网、日线全取不到）的次数。
     * 用来退避：数据源一直不响应时，不该整夜每分钟重放一遍 330 次日线请求。
     */
    private var failStreak = 0

    /** 上一轮跑完时评出了多少只分数 —— 两轮之间状态栏不至于显示 0 */
    private var lastScored = 0

    /** 等待中的循环随时可以被叫醒（用户按刷新、回到前台、解锁） */
    private val wake = Channel<Unit>(1)

    @Volatile
    private var nextRunAt = 0L

    @Volatile
    private var wantImmediate = false

    @Volatile
    private var foreground = false

    @Volatile
    private var uiLocked = false

    /** 上一轮完整结果跑完于哪个日历日（yyyy-MM-dd），用来判断"今天是否还一轮都没跑完" */
    private var lastCompletedDay = ""

    // ---- 对外状态流 ----

    private val _recommendations = MutableStateFlow<List<StockRecommendation>>(emptyList())
    val recommendations: StateFlow<List<StockRecommendation>> = _recommendations.asStateFlow()

    private val _poolInfo = MutableStateFlow("")
    val poolInfo: StateFlow<String> = _poolInfo.asStateFlow()

    private val _lastUpdateTime = MutableStateFlow<Long?>(null)
    val lastUpdateTime: StateFlow<Long?> = _lastUpdateTime.asStateFlow()

    private val _insights = MutableStateFlow<Map<String, Insight>>(emptyMap())
    val insights: StateFlow<Map<String, Insight>> = _insights.asStateFlow()

    private val _progress = MutableStateFlow("")
    val progress: StateFlow<String> = _progress.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** 候选池：330 只热门 A 股（实测 330 个代码，旧注释里写的"约200只"是更早的遗留），名单与 v4.6.0 一字未改 */
    internal val candidates = listOf(
        // 沪市主板
        "sh600000", "sh600009", "sh600016", "sh600019", "sh600028",
        "sh600029", "sh600030", "sh600031", "sh600036", "sh600048",
        "sh600050", "sh600089", "sh600104", "sh600111", "sh600115",
        "sh600150", "sh600176", "sh600183", "sh600188", "sh600196",
        "sh600219", "sh600276", "sh600298", "sh600309", "sh600316",
        "sh600332", "sh600346", "sh600352", "sh600362", "sh600383",
        "sh600392", "sh600398", "sh600406", "sh600426", "sh600436",
        "sh600438", "sh600460", "sh600487", "sh600489", "sh600498",
        "sh600516", "sh600519", "sh600521", "sh600536", "sh600547",
        "sh600549", "sh600570", "sh600583", "sh600585", "sh600588",
        "sh600606", "sh600655", "sh600660", "sh600674", "sh600688",
        "sh600690", "sh600703", "sh600741", "sh600745", "sh600760",
        "sh600763", "sh600795", "sh600809", "sh600837", "sh600845",
        "sh600848", "sh600862", "sh600867", "sh600886", "sh600887",
        "sh600893", "sh600900", "sh600905", "sh600918", "sh600919",
        "sh600941", "sh600958", "sh600977", "sh600989", "sh600999",
        "sh601006", "sh601009", "sh601012", "sh601021", "sh601066",
        "sh601088", "sh601100", "sh601108", "sh601111", "sh601117",
        "sh601138", "sh601155", "sh601162", "sh601166", "sh601169",
        "sh601186", "sh601211", "sh601225", "sh601228", "sh601229",
        "sh601231", "sh601236", "sh601238", "sh601288", "sh601298",
        "sh601318", "sh601319", "sh601328", "sh601336", "sh601360",
        "sh601377", "sh601390", "sh601398", "sh601600", "sh601601",
        "sh601607", "sh601618", "sh601628", "sh601633", "sh601658",
        "sh601668", "sh601669", "sh601688", "sh601698", "sh601727",
        "sh601766", "sh601788", "sh601800", "sh601808", "sh601816",
        "sh601818", "sh601828", "sh601838", "sh601857", "sh601877",
        "sh601878", "sh601881", "sh601888", "sh601898", "sh601899",
        "sh601901", "sh601919", "sh601933", "sh601939", "sh601958",
        "sh601985", "sh601988", "sh601989", "sh601992", "sh601995",
        "sh601998", "sh603000", "sh603019", "sh603156", "sh603185",
        "sh603195", "sh603259", "sh603260", "sh603288", "sh603290",
        "sh603501", "sh603517", "sh603568", "sh603613", "sh603659",
        "sh603799", "sh603806", "sh603833", "sh603882", "sh603899",
        "sh603986",
        // 科创板
        "sh688005", "sh688008", "sh688009", "sh688012", "sh688036",
        "sh688111", "sh688126", "sh688169", "sh688180", "sh688185",
        "sh688390", "sh688561", "sh688599",
        // 深市主板
        "sz000001", "sz000002", "sz000063", "sz000066", "sz000069",
        "sz000100", "sz000157", "sz000166", "sz000301", "sz000333",
        "sz000338", "sz000400", "sz000401", "sz000423", "sz000425",
        "sz000538", "sz000568", "sz000596", "sz000625", "sz000651",
        "sz000661", "sz000683", "sz000703", "sz000708", "sz000709",
        "sz000723", "sz000725", "sz000733", "sz000738", "sz000768",
        "sz000776", "sz000778", "sz000783", "sz000786", "sz000800",
        "sz000807", "sz000858", "sz000860", "sz000876", "sz000895",
        "sz000938", "sz000960", "sz000963", "sz000975", "sz000977",
        "sz000999", "sz002001", "sz002007", "sz002008", "sz002024",
        "sz002027", "sz002032", "sz002049", "sz002050", "sz002064",
        "sz002080", "sz002120", "sz002129", "sz002142", "sz002146",
        "sz002152", "sz002153", "sz002156", "sz002179", "sz002180",
        "sz002202", "sz002230", "sz002236", "sz002241", "sz002271",
        "sz002304", "sz002311", "sz002340", "sz002352", "sz002371",
        "sz002384", "sz002385", "sz002389", "sz002399", "sz002410",
        "sz002415", "sz002422", "sz002430", "sz002460", "sz002466",
        "sz002468", "sz002475", "sz002493", "sz002555", "sz002594",
        "sz002600", "sz002601", "sz002602", "sz002607", "sz002648",
        "sz002709", "sz002714", "sz002736", "sz002739", "sz002756",
        "sz002812", "sz002821", "sz002831", "sz002841", "sz002850",
        "sz002916", "sz002920", "sz002938", "sz002945", "sz002966",
        // 创业板
        "sz300003", "sz300009", "sz300014", "sz300015", "sz300033",
        "sz300059", "sz300122", "sz300124", "sz300142", "sz300144",
        "sz300146", "sz300223", "sz300274", "sz300285", "sz300308",
        "sz300316", "sz300347", "sz300394", "sz300408", "sz300413",
        "sz300433", "sz300450", "sz300496", "sz300498", "sz300601",
        "sz300628", "sz300661", "sz300750", "sz300751", "sz300760",
        "sz300782", "sz300832", "sz300866", "sz300896", "sz300979",
        "sz300999"
    )

    /**
     * 行情页默认那 30 只。一轮开始时先算它们，用户切到「行情」不至于全看到"排队中"。
     * 只影响顺序，不改变集合，所以分位池的口径不受影响。
     */
    internal val quickCodes = listOf(
        "sh600519", "sh600036", "sh600030", "sz000858", "sz000651",
        "sz000333", "sh601318", "sh600276", "sh600048", "sz000002",
        "sh601888", "sz300750", "sz002594", "sz300059", "sh600900",
        "sz000001", "sh601398", "sh601288", "sh601939", "sh601012",
        "sz000725", "sz300015", "sh600887", "sz000568", "sz002304",
        "sh603288", "sz002415", "sz300760", "sh601166", "sz002142"
    )

    // ================================================================== 生命周期

    /** 幂等：`Application.onCreate()` 调一次，各 ViewModel 再调也不会重复起循环 */
    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            appContext = context.applicationContext
            repository = StockRepository(appContext)
        }
        restoreSnapshot()
        scope.launch { loop() }
    }

    /** 由 [com.stock.analyzer.AnalyzerApplication] 的前后台计数驱动 */
    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        // 回到前台：被"界面看不见"停下的那一轮立刻接着跑；没有断点的就按新鲜度决定要不要催
        if (value) {
            if (pausedMidPass) nextRunAt = 0L else requestPassIfStale()
        }
        signalWake()
        publishStatus()
    }

    /** 应用锁挡着的时候界面也算可见，但什么都不显示 —— 由 MainActivity 报进来 */
    fun setUiLocked(value: Boolean) {
        if (uiLocked == value) return
        uiLocked = value
        if (!value) {
            if (pausedMidPass) nextRunAt = 0L else requestPassIfStale()
        }
        signalWake()
        publishStatus()
    }

    /**
     * 顶部刷新键 / 进页面发现结果太旧：催一轮。
     * 正在跑的那一轮**不会**被打断回第 1 只，只是跑完立刻接下一轮。
     */
    fun refreshNow() {
        wantImmediate = true
        nextRunAt = 0L
        signalWake()
        publishStatus()
    }

    /** 结果太旧才催一轮；不旧就什么都不做 —— 这正是"别每次进来都从 1 开始" */
    fun requestPassIfStale() {
        if (_status.value.analyzing) return
        val stamp = _lastUpdateTime.value
        if (stamp == null) {
            refreshNow()
            return
        }
        val limit = if (inTradingSession()) STALE_TRADING else STALE_CLOSED
        if (System.currentTimeMillis() - stamp > limit) refreshNow()
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
            if (!usefulToRunNow(now) && !wantImmediate) {
                // 收盘了、今天这一轮也交过卷了 —— 那就只醒来看表，不发请求。
                // 周末和夜里靠这条才不会白跑 330 次日线
                nextRunAt = now + minOf(GAP_OFF_SESSION, maxOf(IDLE_POLL_BG, msToNextSession(now)))
                sleepUntil(nextRunAt)
                continue
            }
            try {
                runPass()
            } catch (e: Exception) {
                // 一轮里的任何意外都不该把循环弄死：记一笔，隔一会儿再试
                _error.value = "分析异常: ${e.message}"
                failStreak++
                nextRunAt = System.currentTimeMillis() + failureGap(System.currentTimeMillis())
                pausedMidPass = false
                analyzingNow = false
                publishStatus()
            }
        }
    }

    /**
     * 「后台持续分析」开着 → 一直在跑，包括解锁页挡着的时候（解锁就能看到新结果，
     * 这也是用户要的"别每次解锁都重算"）。关着 → 只在界面可见且已解锁时跑。
     */
    private fun shouldRun(): Boolean = continuousEnabled() || (foreground && !uiLocked)

    private fun continuousEnabled(): Boolean = try {
        AppSettings.get(appContext).backgroundAnalysis
    } catch (e: Exception) {
        false
    }

    /** @return 是否真的跑完了一轮（没网、被暂停都返回 false） */
    private suspend fun runPass(): Boolean {
        analyzingNow = true
        if (cursor == 0) {
            if (!startNewPass()) {
                analyzingNow = false
                publishStatus()
                return false
            }
        } else {
            // 上一轮是被谁停下的？这里接着数，一个都不重算
            passResumed = true
        }
        pausedMidPass = false
        _error.value = null
        publishStatus()

        val list = quotes
        var index = cursor
        while (index < list.size) {
            if (!shouldRun()) {
                // 停在原地，已算好的分数全部保留 —— 回来后从第 index+1 只继续，不回到 1
                pausedMidPass = true
                analyzingNow = false
                _progress.value = "已暂停在第 $index/${list.size} 只，已评分 ${scored.size} 只留着，回来接着算"
                nextRunAt = System.currentTimeMillis() + IDLE_POLL_MS
                publishStatus()
                return false
            }
            val stock = list[index]
            _progress.value = "分析中 ${index + 1}/${list.size}: ${stock.name}"
            scoreOne(stock)
            index++
            cursor = index
            if (index % PUBLISH_EVERY == 0 || index == list.size) publishInsights()
            publishStatus()
            if (index < list.size) delay(THROTTLE_MS)
        }
        finishPass(list.size)
        return true
    }

    /** 起新一轮：先拿整批实时行情。拿不到（没网）就什么都不动，界面上保留上一轮结果 */
    private suspend fun startNewPass(): Boolean {
        scored.clear()
        cursor = 0
        noKline = 0
        shortHistory = 0
        passStartedAt = System.currentTimeMillis()
        passResumed = false
        quotes = emptyList()
        _progress.value = "正在获取行情快照（${candidates.size} 只）..."
        val fetched = try {
            repository.getRealtimeQuotes(universe())
        } catch (e: Exception) {
            Result.failure(e)
        }
        val list = fetched.getOrElse {
            _error.value = "获取行情失败: ${it.message}"
            failStreak++
            nextRunAt = System.currentTimeMillis() + failureGap(System.currentTimeMillis())
            passStartedAt = 0L
            publishStatus()
            return false
        }
        quotes = list
        return true
    }

    /** 抓取顺序：先行情页那 30 只，再其余的。集合 = 那 330 只，一个不多一个不少 */
    internal fun universe(): List<String> {
        val ordered = LinkedHashSet<String>()
        ordered.addAll(quickCodes)
        ordered.addAll(candidates)
        return ordered.toList()
    }

    /** 单只：取日线 → 打分 → 记逐只结论。判据与 v4.6.0 的 `MarketViewModel` 逐字一致 */
    private suspend fun scoreOne(stock: Stock) {
        val result = try {
            // 130 根日线：MA60 + 10 日斜率 + Wilder 递推需要这段历史，
            // 与回测（backtest/precompute.py）的口径一致
            repository.getKLineData(stock.code, "daily", StockAnalyzer.BARS_FULL)
        } catch (e: Exception) {
            Result.failure<List<KLineData>>(e)
        }
        val kline = result.getOrElse { emptyList() }
        when {
            kline.isEmpty() -> {
                noKline++
                notes[stock.code] = Insight(
                    0, "未分析",
                    "没有日线数据：${result.exceptionOrNull()?.message ?: "数据源未返回"}（停牌、退市或代码已变更）"
                )
            }
            kline.size < StockAnalyzer.BARS_MIN -> {
                shortHistory++
                notes[stock.code] = Insight(
                    0, "数据不足",
                    "日线仅 ${kline.size} 根，算不出 MA60 及斜率（需 ≥${StockAnalyzer.BARS_MIN} 根）"
                )
            }
            else -> {
                val rec = analyzer.generateRecommendation(stock, kline)
                if (rec == null) {
                    shortHistory++
                    notes[stock.code] = Insight(0, "数据不足", "评分未产出（K线 ${kline.size} 根）")
                } else {
                    scored[stock.code] = rec
                    val lag = StockRepository.lagDays(kline.last().date)
                    notes[stock.code] = Insight(
                        score = rec.analysis.score,
                        tier = tierOf(rec.analysis.score, rec.analysis.dataSufficient),
                        note = if (lag != null && lag > StockRepository.STALE_LAG_DAYS)
                            "最后交易日 ${kline.last().date}（$lag 天前），多半已停牌或退市"
                        else if (rec.analysis.dataSufficient) null
                        else "K线仅 ${kline.size} 根，分数不完整"
                    )
                }
            }
        }
    }

    /** 档位口径与持仓建议完全一致（同一个 score 函数、同一组阈值） */
    private fun tierOf(score: Int, sufficient: Boolean): String = when {
        !sufficient -> "数据不足"
        score >= StockAnalyzer.BUY_SCORE -> "达到加仓线"
        score > StockAnalyzer.SELL_SCORE -> "持有"
        score > StockAnalyzer.STRONG_SELL_SCORE -> "减仓"
        else -> "强烈减仓"
    }

    private suspend fun finishPass(passSize: Int) {
        val all = scored.values.toList()
        val elapsed = System.currentTimeMillis() - passStartedAt

        // 整轮一只都没评分上（接口全挂之类），别把上一轮的好结果冲掉
        if (all.isNotEmpty()) {
            // 关键：不按评分排序取前几名。
            // iter36（30 只口径，同一批信号日）实测：池内"分数最高的 30 只"日均超额 −0.430%
            // （20 个换仓相位只有 4 个跑赢基准），"按分位等距取 30 只"+0.669%（t_HAC +1.51，
            // 19/20 相位跑赢），两者配对差 +1.100%、块自助 p 0.035 —— 这是全模型最硬的一条结论。
            // 入池不再用 ≥68 这个绝对门槛：门槛的代价见 StockAnalyzer 类注释（iter32 / iter37）。
            val eligible = analyzer.eligibleCount(all)
            val picks = analyzer.selectDailyCandidates(all)
                .mapIndexed { i, rec -> rec.copy(rank = i + 1) }
            val nBuy = picks.count { it.analysis.recommendation == Recommendation.BUY }

            _recommendations.value = picks
            _poolInfo.value = "今日 $eligible 只进入前 ${(StockAnalyzer.POOL_FRACTION * 100).toInt()}% 分位池，" +
                "等距取 ${picks.size} 只等权（不是排行榜）；" +
                "其中 $nBuy 只在加仓线以上，其余为持有档——档位只回答\"手里这只该不该再加\"，" +
                "回测赚的是整篮子的分位差" +
                coverageNote(noKline, shortHistory) +
                "。本轮 ${all.size} 只评分用了 ${formatDuration(elapsed)}"
            _lastUpdateTime.value = System.currentTimeMillis()
            lastCompletedDay = dayKey(_lastUpdateTime.value!!)
            _progress.value = "本轮已更新，共评分 ${all.size} 只"
            lastScored = all.size
            failStreak = 0
            saveSnapshot()
        } else {
            _progress.value = "本轮没评出任何分数（数据源没返回日线），界面仍是 ${formatTime(_lastUpdateTime.value ?: 0L)} 那一轮的结果"
        }

        // 为下一轮腾位置。界面继续显示这一轮的名单（recommendations 不清）；
        // 逐只评分 notes 也不清 —— 下一轮跑到哪只就覆盖哪只，不会出现整页"排队中"。
        scored.clear()
        cursor = 0
        quotes = emptyList()
        passStartedAt = 0L
        passResumed = false
        analyzingNow = false

        val now = System.currentTimeMillis()
        nextRunAt = when {
            wantImmediate -> now
            // 一轮一只都没评上，基本是接口挂了或没网。按错误的节奏退避，
            // 否则 nextGap 的"当天没跑完过就补一轮"会把这当成要尽快补，整夜每 20 秒重跑 330 只。
            all.isEmpty() -> {
                failStreak++
                now + failureGap(now)
            }
            else -> now + nextGap(now)
        }
        wantImmediate = false
        publishStatus(passSize)
    }

    /**
     * 这一轮跑了有没有意义。
     *
     * 盘中随时有意义；不是盘中时，只在"今天还没交过卷"的工作日补一轮（拿当天的收盘数据），
     * 周末直接不跑。`wantImmediate`（用户按刷新、或回到前台催的一轮）不看这里 —— 用户主动要的就给。
     */
    private fun usefulToRunNow(now: Long): Boolean {
        if (inTradingSession(now)) return true
        if (lastCompletedDay.isEmpty()) return true
        if (!isWeekday(now)) return false
        return dayKey(now) != lastCompletedDay
    }

    private fun isWeekday(now: Long): Boolean {
        val day = calendarAt(now).get(Calendar.DAY_OF_WEEK)
        return day != Calendar.SATURDAY && day != Calendar.SUNDAY
    }

    /**
     * 睡到 points 为止，但可被 [wake] 提前叫醒：按刷新、回前台、解锁这些动作
     * 不需要等到下次检查点才生效。省电与响应之间就是这么取的。
     */
    private suspend fun sleepUntil(until: Long) {
        val remaining = until - System.currentTimeMillis()
        if (remaining <= 0L) return
        withTimeoutOrNull(remaining) { wake.receive() }
    }

    private fun signalWake() {
        wake.trySend(Unit)
    }

    /** 下一轮隔多久：盘中短歇；收盘后当天先补一轮；夜里/周末睡到下个时段前再醒（有上限） */
    private fun nextGap(now: Long): Long = when {
        inTradingSession(now) -> GAP_TRADING
        dayKey(now) != lastCompletedDay -> GAP_CATCH_UP
        else -> minOf(GAP_OFF_SESSION, msToNextSession(now))
    }

    /**
     * "这一轮没拿到数据"之后的退避。盘中值得尽快重试；收盘后没必要每分钟把全市场重放一遍，
     * 于是按连续失败次数拉长（最多 10 分钟一轮，跟正常休息的上限一致）。
     * 醒过来要不要真跑，仍由 [usefulToRunNow] 决定 —— 周末那几天根本不会有新数据。
     */
    private fun failureGap(now: Long): Long =
        if (inTradingSession(now)) GAP_ERROR
        else minOf(GAP_OFF_SESSION, GAP_ERROR * minOf(failStreak, 10).coerceAtLeast(2))

    /** 没被分析的股票要说出来，不能悄悄丢掉：以前它们直接从列表里消失 */
    private fun coverageNote(noKline: Int, shortHistory: Int): String {
        if (noKline == 0 && shortHistory == 0) return ""
        return "；另有 $noKline 只取不到日线、$shortHistory 只历史不足 ${StockAnalyzer.BARS_MIN} 根，未参与今日候选"
    }

    private fun publishInsights() {
        _insights.value = LinkedHashMap(notes)
    }

    /** [fallbackTotal] 用于一轮刚跑完、quotes 已清空时，状态栏还能显示这一轮实际跑了多少只 */
    private fun publishStatus(fallbackTotal: Int = 0) {
        val running = quotes.isNotEmpty()
        _status.value = _status.value.copy(
            analyzing = analyzingNow,
            paused = pausedMidPass,
            resumed = passResumed,
            done = cursor,
            total = if (running) quotes.size else fallbackTotal,
            scored = if (running) scored.size else lastScored,
            passStartedAt = passStartedAt,
            nextRunAt = nextRunAt,
            continuous = continuousEnabled(),
            foreground = foreground
        )
    }

    // ================================================================== 落盘 / 读回

    private class Snapshot(
        val version: Int = SNAPSHOT_VERSION,
        val savedAt: Long = 0L,
        val dayKey: String = "",
        val picks: List<StockRecommendation> = emptyList(),
        val poolInfo: String = "",
        val insights: Map<String, Insight> = emptyMap()
    )

    private fun snapshotFile(): File = File(appContext.filesDir, SNAPSHOT_NAME)

    /**
     * 冷启动先把上一轮的完整结果放回界面。
     *
     * 名单只认"当天"的快照：隔夜的名单会让用户以为还是今天选出来的，而分位池本就是
     * 按当日全样本算的，隔一天完全是另一批。隔夜的只保留逐只评分给行情页兜个底。
     */
    private fun restoreSnapshot() {
        val snap = try {
            val file = snapshotFile()
            if (!file.exists()) null else gson.fromJson(file.readText(), Snapshot::class.java)
        } catch (e: Exception) {
            null
        } ?: return
        if (snap.insights.isNotEmpty()) {
            notes.putAll(snap.insights)
            publishInsights()
            lastScored = notes.size
        }
        if (snap.picks.isEmpty() || snap.savedAt <= 0L) return
        if (snap.dayKey != dayKey(snap.savedAt)) return
        _recommendations.value = snap.picks
        _poolInfo.value = snap.poolInfo
        _lastUpdateTime.value = snap.savedAt
        lastCompletedDay = snap.dayKey
        _progress.value = "已载入 ${formatTime(snap.savedAt)} 那一轮（${snap.picks.size} 只），正在后台续算"
    }

    private suspend fun saveSnapshot() = withContext(Dispatchers.IO) {
        try {
            val stamp = _lastUpdateTime.value ?: System.currentTimeMillis()
            val snap = Snapshot(
                savedAt = stamp,
                dayKey = dayKey(stamp),
                picks = _recommendations.value,
                poolInfo = _poolInfo.value,
                insights = LinkedHashMap(notes)
            )
            snapshotFile().writeText(gson.toJson(snap))
        } catch (e: Exception) {
            // 快照只是冷启动的体验优化，写失败不影响分析
        }
    }

    // ================================================================== 时间工具

    private fun calendarAt(millis: Long): Calendar =
        Calendar.getInstance(shanghai).apply { timeInMillis = millis }

    /**
     * A 股连续竞价时段（含 9:15 起的集合竞价、15:00 后 5 分钟的收盘数据落地）。
     * 只看星期，不认识法定节假日 —— 节假日会按工作日节奏多跑一轮，代价是白跑一批请求。
     */
    internal fun inTradingSession(now: Long = System.currentTimeMillis()): Boolean {
        val c = calendarAt(now)
        if (c.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY ||
            c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
        ) return false
        val minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return (minute >= 9 * 60 + 15 && minute <= 11 * 60 + 30) ||
            (minute >= 13 * 60 && minute <= 15 * 60 + 5)
    }

    /**
     * 离下一个时段开始还有多少毫秒：两个时段起点（9:15、13:00）里取最近的未来那个。
     * 夜里算到明早 9:15，午休算到 13:00，周五收盘后直接跳过周末算到周一 9:15。
     * 只在"当前不在时段里"时调用；它不认识法定节假日，节假日会算成下一个工作日。
     */
    internal fun msToNextSession(now: Long): Long {
        var best = Long.MAX_VALUE
        for (offset in 0..7) {
            val c = calendarAt(now)
            c.add(Calendar.DAY_OF_YEAR, offset)
            if (c.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY ||
                c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
            ) continue
            for (startMinute in intArrayOf(9 * 60 + 15, 13 * 60)) {
                c.set(Calendar.HOUR_OF_DAY, startMinute / 60)
                c.set(Calendar.MINUTE, startMinute % 60)
                c.set(Calendar.SECOND, 0)
                c.set(Calendar.MILLISECOND, 0)
                val start = c.timeInMillis
                if (start > now && start - now < best) best = start - now
            }
        }
        return if (best == Long.MAX_VALUE) GAP_OFF_SESSION else best
    }

    private fun dayKey(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = shanghai }.format(millis)

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(millis)

    private fun formatDuration(millis: Long): String {
        val s = millis / 1000
        return if (s < 60) "$s 秒" else "${s / 60} 分 ${"%02d".format(s % 60)} 秒"
    }
}
