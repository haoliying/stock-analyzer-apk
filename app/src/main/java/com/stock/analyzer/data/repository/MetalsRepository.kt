package com.stock.analyzer.data.repository

import android.content.Context
import com.google.gson.Gson
import com.stock.analyzer.data.api.SinaMetalsApi
import com.stock.analyzer.data.model.KLineData
import com.stock.analyzer.data.model.MetalInstrument
import com.stock.analyzer.data.model.MetalQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 贵金属数据仓库：一条批量报价 + 每品种一份日线缓存，周线/月线**由日线自己聚合**。
 *
 * 为什么不向接口要周线月线：新浪这两个日线接口是贵金属这一路唯一核过的历史源，
 * 没有免 Key 的周线/月线接口。自己聚合的口径写在 [aggregate]，
 * 一根周线 = 周一到周日内最后一个交易日为止的开高低收，日期取这一组里最后一个交易日
 * （图表那一排日期标签因此永远落在真实存在的那一天）。
 *
 * 与 A 股那套 [StockRepository] 刻意分开：不同资产大类的代码规则、字段、单位都不一样，
 * 硬塞进同一个仓库只会让两边都长出 if。以后加大宗商品、外汇是再加一个 provider。
 */
class MetalsRepository(context: Context) {

    private val api = SinaMetalsApi()
    private val gson = Gson()
    private val cacheDir = File(context.applicationContext.filesDir, "metals_cache").apply {
        if (!exists()) mkdirs()
    }

    /** 一次批量请求取回全部品种现价。失败返回 null，由引擎保留上一轮数值并标注时间 */
    suspend fun quotes(instruments: List<MetalInstrument> = MetalInstrument.ALL): Map<String, MetalQuote>? =
        withContext(Dispatchers.IO) { api.getQuotes(instruments) }

    /**
     * 某个周期的 K 线（升序）。
     *
     * 日线优先用接口，接口挂了退回缓存（缓存里是全量历史，够图表拖到最早一根）；
     * 周线/月线不单独发请求，永远从日线聚合，所以三个周期天然对得上。
     */
    suspend fun bars(
        inst: MetalInstrument,
        period: String,
        force: Boolean = false
    ): Result<List<KLineData>> = withContext(Dispatchers.IO) {
        val history = daily(inst, force)
            ?: return@withContext Result.failure(
                Exception("没有取到${inst.name}的日线数据（数据源未返回，且本机还没有缓存）")
            )
        Result.success(aggregate(history, period))
    }

    /**
     * 取日线：缓存没过期就直接给，过期或 force 才联网。
     * 联网失败时退回旧缓存 —— 贵金属历史是死的，昨天的缓存今天照样能画。
     */
    private fun daily(inst: MetalInstrument, force: Boolean): List<KLineData>? {
        val cached = readCache(inst.code)
        val freshEnough = cached != null && cached.klines.isNotEmpty() &&
            System.currentTimeMillis() - cached.fetchedAt < DAILY_TTL_MS
        if (freshEnough && !force) return cached!!.klines

        val fetched = try {
            api.getDailyBars(inst)
        } catch (e: Exception) {
            emptyList()
        }
        if (fetched.isEmpty()) return cached?.klines
        if (cached != null && fetched.size < MIN_USEFUL_BARS && cached.klines.size > fetched.size) {
            // 接口突然只回一小截（限流、字段变更）时别把整份历史覆盖掉
            return cached.klines
        }
        val merged = mergeByDate(cached?.klines ?: emptyList(), fetched)
        writeCache(inst.code, merged)
        return merged
    }

    // ---------------- 缓存文件 ----------------

    private data class DailyCache(
        val code: String,
        val fetchedAt: Long,
        val version: Int = CACHE_VERSION,
        val klines: List<KLineData> = emptyList()
    )

    private fun cacheFile(code: String): File = File(cacheDir, "daily_$code.json")

    private fun readCache(code: String): DailyCache? = try {
        val f = cacheFile(code)
        if (!f.exists()) null
        else gson.fromJson(f.readText(), DailyCache::class.java)
            ?.takeIf { it.version == CACHE_VERSION }
    } catch (e: Exception) {
        null
    }

    private fun writeCache(code: String, klines: List<KLineData>) {
        try {
            cacheFile(code).writeText(
                gson.toJson(DailyCache(code = code, fetchedAt = System.currentTimeMillis(), klines = klines))
            )
        } catch (e: Exception) {
            // 缓存只是离线兜底，写失败不影响本次显示
        }
    }

    fun clearCache() {
        try {
            cacheDir.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {

        const val PERIOD_DAILY = "daily"
        const val PERIOD_WEEKLY = "weekly"
        const val PERIOD_MONTHLY = "monthly"

        private const val CACHE_VERSION = 1

        /** 日线历史变化很慢：半小时内的缓存直接用，下拉刷新走 force */
        private const val DAILY_TTL_MS = 30 * 60_000L

        /** 少于这个根数，周线/月线都拼不出像样的序列，宁可退回缓存 */
        private const val MIN_USEFUL_BARS = 60

        private fun mergeByDate(old: List<KLineData>, new: List<KLineData>): List<KLineData> {
            val map = LinkedHashMap<String, KLineData>()
            old.forEach { map[it.date] = it }
            new.forEach { map[it.date] = it }
            return map.values.sortedBy { it.date }
        }

        /**
         * 把日线聚合成周线（[PERIOD_WEEKLY]）或月线（[PERIOD_MONTHLY]）；传日线原样返回。
         *
         * 口径：开 = 组内第一根的开，收 = 组内最后一根的收，高/低取极值，量求和，
         * 日期 = 组内最后一个交易日。日期不用"周一"或"yyyy-MM"，是为了让图表上竖线
         * 报出来的那一天真的存在过成交 —— 停牌周、半月数据这种情况一眼就能看出来。
         *
         * 解析不出日期的行直接丢掉，不猜。
         */
        fun aggregate(daily: List<KLineData>, period: String): List<KLineData> {
            if (period != PERIOD_WEEKLY && period != PERIOD_MONTHLY) return daily
            val groups = LinkedHashMap<String, MutableList<KLineData>>()
            for (k in daily) {
                val d = parseDate(k.date) ?: continue
                val key = if (period == PERIOD_MONTHLY) k.date.substring(0, 7)
                          else d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
                groups.getOrPut(key) { ArrayList() }.add(k)
            }
            return groups.values.map { g ->
                KLineData(
                    date = g.last().date,
                    open = g.first().open,
                    close = g.last().close,
                    high = g.maxOf { it.high },
                    low = g.minOf { it.low },
                    volume = g.sumOf { it.volume }
                )
            }.sortedBy { it.date }
        }

        /** "2026-11-16" / "2026-11-16 15:00" 都认；认不出来返回 null */
        fun parseDate(date: String?): LocalDate? = try {
            if (date.isNullOrBlank() || date.length < 10) null
            else LocalDate.parse(date.substring(0, 10))
        } catch (e: Exception) {
            null
        }
    }
}
