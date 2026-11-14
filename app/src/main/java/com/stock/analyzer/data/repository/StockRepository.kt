package com.stock.analyzer.data.repository

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.stock.analyzer.data.api.SinaStockApi
import com.stock.analyzer.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 股票数据仓库 - 带本地缓存
 * 支持全市场分析和持仓管理
 * 历史数据本地缓存，只下载增量数据
 */
class StockRepository(private val context: Context) {
    
    private val sinaApi = SinaStockApi()
    private val gson = Gson()
    private val prefs = context.getSharedPreferences("portfolio", Context.MODE_PRIVATE)
    private val cacheDir = File(context.filesDir, "stock_cache")
    
    init {
        // 确保缓存目录存在
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    }

    companion object {
        /**
         * 缓存结构版本。
         *
         * v4.3.0 及以前：`getKLineData` 把"调用方要的条数"当成了"缓存保留的条数"，
         * 于是同一个 `kline_{code}_daily.json` 会被分时(1 根)、补价(5 根)、日线(60 根)
         * 这几个入口反复 `takeLast(count)` 越截越短，分析需要的 130 根永远凑不齐，
         * 增量下载又只在"有新 K 线"时才合并 —— 用户看到的就是
         * "有的股票没有日线月线数据，也没被分析"。
         * 现在缓存按 [storageBars] 保底存储，返回时才按调用方的 count 截断；
         * 版本号不一致的旧缓存视为不可信，直接全量重下（装上新版本后自动修复）。
         */
        private const val CACHE_VERSION = 2

        /** 缓存至少保留多少根 K 线（必须显著大于任何调用方的 count，否则又会互相截断） */
        private fun storageBars(period: String): Int = when (period) {
            "daily" -> 260      // 分析口径 130 根（≈1 年），留一倍余量
            "weekly" -> 150
            "monthly" -> 120
            else -> 260         // 分钟线
        }

        /**
         * 最后一根 K 线距今多少个日历日。
         * 停牌、退市、代码换段（北交所 83xxxx→920xxx）时数据源仍会返回一段**很旧**的历史，
         * 接口"成功"但内容是死的；调用方用它来判断"这不是可用数据，别当现价分析"。
         * 解析不出来返回 null（当作未知，不做判断）。
         */
        fun lagDays(lastDate: String?): Int? {
            if (lastDate.isNullOrBlank()) return null
            return try {
                val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(
                    if (lastDate.length >= 10) lastDate.substring(0, 10) else lastDate
                ) ?: return null
                ((System.currentTimeMillis() - d.time) / (24L * 3600_000L)).toInt()
            } catch (e: Exception) {
                null
            }
        }

        /** 超过这个天数没有新 K 线，就认为这只股票已经停牌/退市/代码失效 */
        const val STALE_LAG_DAYS = 20
    }
    
    // ========== 缓存管理 ==========
    
    /**
     * 获取K线缓存文件路径
     */
    private fun getKlineCacheFile(code: String, period: String): File {
        return File(cacheDir, "kline_${code}_${period}.json")
    }
    
    /**
     * 获取缓存的K线数据
     */
    private fun getCachedKlineData(code: String, period: String): CachedKlineData? {
        return try {
            val file = getKlineCacheFile(code, period)
            if (!file.exists()) return null
            
            val json = file.readText()
            gson.fromJson(json, CachedKlineData::class.java)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 保存K线数据到缓存
     */
    private fun saveKlineCacheData(code: String, period: String, data: CachedKlineData) {
        try {
            val file = getKlineCacheFile(code, period)
            val json = gson.toJson(data)
            file.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * K线缓存数据结构
     */
    data class CachedKlineData(
        val code: String,
        val period: String,
        val lastUpdate: Long,
        val lastDate: String,  // 最后一条数据的日期
        val klines: List<KLineData>,
        val version: Int = 0   // 老缓存没这个字段 → 读出 0 → 视为过期重下
    )
    
    // ========== 数据获取（带缓存） ==========
    
    /**
     * 获取实时行情数据
     */
    suspend fun getRealtimeQuotes(codes: List<String>): Result<List<Stock>> {
        return withContext(Dispatchers.IO) {
            try {
                val stocks = sinaApi.getRealtimeQuotes(codes)
                if (stocks.isEmpty()) {
                    Result.failure(Exception("无法获取股票数据"))
                } else {
                    Result.success(stocks)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    /**
     * 获取全市场股票代码
     */
    suspend fun getAllStockCodes(): Result<List<String>> {
        return withContext(Dispatchers.IO) {
            try {
                val codes = sinaApi.getAllStockCodes()
                Result.success(codes)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    /**
     * 获取K线历史数据 - 带缓存支持
     *
     * 缓存保留 [storageBars] 根（不随调用方 count 缩小），返回值才截到 count 根；
     * 每次都用同一份完整序列去合并接口结果，所以分时/补价/日线/分析
     * 混着调用也不会把数据截断。
     */
    suspend fun getKLineData(
        code: String,
        period: String = "daily",
        count: Int = 100
    ): Result<List<KLineData>> {
        return withContext(Dispatchers.IO) {
            val stored = maxOf(count, storageBars(period))
            val cached = getCachedKlineData(code, period)
            val inSync = cached?.takeIf {
                it.version == CACHE_VERSION && it.klines.isNotEmpty() && it.klines.size >= count
            }
            try {
                val fresh: List<KLineData> = sinaApi.getKLineData(code, period, stored)

                if (fresh.isEmpty()) {
                    // 接口此刻取不到（限流/停牌/退市/代码失效）：有缓存就用缓存，别把页面打成空白
                    val salvage = inSync?.klines ?: cached?.klines
                    return@withContext if (!salvage.isNullOrEmpty()) {
                        Result.success(salvage)
                    } else {
                        Result.failure(Exception("没有取到K线数据（可能停牌、退市，或代码已变更）"))
                    }
                }

                val merged = if (inSync != null) {
                    mergeKlineData(inSync.klines, fresh).takeLast(stored)
                } else {
                    fresh.takeLast(stored)
                }

                saveKlineCacheData(
                    code, period,
                    CachedKlineData(
                        code = code,
                        period = period,
                        lastUpdate = System.currentTimeMillis(),
                        lastDate = merged.lastOrNull()?.date ?: "",
                        klines = merged,
                        version = CACHE_VERSION
                    )
                )

                Result.success(merged.takeLast(count))
            } catch (e: Exception) {
                val salvage = inSync?.klines ?: cached?.klines
                if (salvage != null && salvage.isNotEmpty()) {
                    Result.success(salvage.takeLast(count))
                } else {
                    Result.failure(e)
                }
            }
        }
    }

    /**
     * 合并K线数据（去重）
     */
    private fun mergeKlineData(cached: List<KLineData>, new: List<KLineData>): List<KLineData> {
        val map = mutableMapOf<String, KLineData>()
        
        // 先添加缓存数据
        cached.forEach { map[it.date] = it }
        
        // 再添加新数据（会覆盖相同日期的旧数据）
        new.forEach { map[it.date] = it }
        
        // 按日期排序
        return map.values.sortedBy { it.date }
    }
    
    /**
     * 获取分时数据（不缓存，因为是实时数据）
     */
    suspend fun getTimelineData(code: String): Result<List<TimelineData>> {
        return withContext(Dispatchers.IO) {
            try {
                val timelineData = sinaApi.getTimelineData(code)
                if (timelineData.isEmpty()) {
                    Result.failure(Exception("无法获取分时数据"))
                } else {
                    Result.success(timelineData)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    /**
     * 获取单只股票实时行情
     */
    suspend fun getStockQuote(code: String): Result<Stock?> {
        return withContext(Dispatchers.IO) {
            try {
                val stock = sinaApi.getStockQuote(code)
                if (stock != null) {
                    Result.success(stock)
                } else {
                    // 测试网络
                    val networkOk = sinaApi.testConnection()
                    if (networkOk) {
                        Result.failure(Exception("股票数据为空，可能已停牌"))
                    } else {
                        Result.failure(Exception("网络连接失败，请检查网络"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    // ========== 持仓管理 ==========
    
    /**
     * 保存持仓列表
     */
    fun savePortfolio(portfolio: List<PortfolioStock>) {
        val json = gson.toJson(portfolio)
        prefs.edit().putString("portfolio_list", json).apply()
    }
    
    /**
     * 加载持仓列表
     */
    fun loadPortfolio(): List<PortfolioStock> {
        val json = prefs.getString("portfolio_list", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<PortfolioStock>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * 添加持仓股票
     */
    fun addPortfolioStock(stock: PortfolioStock) {
        val portfolio = loadPortfolio().toMutableList()
        portfolio.add(stock)
        savePortfolio(portfolio)
    }
    
    /**
     * 更新持仓股票
     */
    fun updatePortfolioStock(stock: PortfolioStock) {
        val portfolio = loadPortfolio().toMutableList()
        val index = portfolio.indexOfFirst { it.code == stock.code }
        if (index >= 0) {
            portfolio[index] = stock
            savePortfolio(portfolio)
        }
    }
    
    /**
     * 删除持仓股票
     */
    fun removePortfolioStock(code: String) {
        val portfolio = loadPortfolio().toMutableList()
        portfolio.removeAll { it.code == code }
        savePortfolio(portfolio)
    }
    
    /**
     * 更新持仓股票的当前价格
     */
    suspend fun updatePortfolioPrices(): Result<List<PortfolioStock>> {
        return withContext(Dispatchers.IO) {
            try {
                val portfolio = loadPortfolio().toMutableList()
                if (portfolio.isEmpty()) return@withContext Result.success(emptyList())
                
                val codes = portfolio.map { it.code }
                val stocks = sinaApi.getRealtimeQuotes(codes)
                
                portfolio.forEach { ps ->
                    val stock = stocks.find { it.code == ps.code }
                    if (stock != null) {
                        ps.updateCurrentPrice(stock.price)
                    }
                }
                
                savePortfolio(portfolio)
                Result.success(portfolio)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    /**
     * 清除所有缓存
     */
    fun clearCache() {
        try {
            cacheDir.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * 获取缓存大小
     */
    fun getCacheSize(): Long {
        return try {
            cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
