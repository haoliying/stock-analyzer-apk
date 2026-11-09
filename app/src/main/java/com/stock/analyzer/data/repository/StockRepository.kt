package com.stock.analyzer.data.repository

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.stock.analyzer.data.api.SinaStockApi
import com.stock.analyzer.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
        val klines: List<KLineData>
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
     * 只下载增量数据
     */
    suspend fun getKLineData(
        code: String,
        period: String = "daily",
        count: Int = 100
    ): Result<List<KLineData>> {
        return withContext(Dispatchers.IO) {
            try {
                // 1. 检查本地缓存
                val cached = getCachedKlineData(code, period)
                
                if (cached != null && cached.klines.isNotEmpty()) {
                    // 有缓存数据
                    val lastDate = cached.lastDate
                    
                    // 2. 获取增量数据（从最后缓存日期到现在）
                    val newKlines = fetchIncrementalKlineData(code, period, lastDate, count)
                    
                    if (newKlines != null && newKlines.isNotEmpty()) {
                        // 3. 合并缓存和新数据
                        val mergedKlines = mergeKlineData(cached.klines, newKlines)
                        
                        // 4. 只保留最新的count条数据
                        val finalKlines = if (mergedKlines.size > count) {
                            mergedKlines.takeLast(count)
                        } else {
                            mergedKlines
                        }
                        
                        // 5. 更新缓存
                        val lastDate = finalKlines.lastOrNull()?.date ?: lastDate
                        val updatedCache = CachedKlineData(
                            code = code,
                            period = period,
                            lastUpdate = System.currentTimeMillis(),
                            lastDate = lastDate,
                            klines = finalKlines
                        )
                        saveKlineCacheData(code, period, updatedCache)
                        
                        Result.success(finalKlines)
                    } else {
                        // 没有新数据，返回缓存
                        Result.success(cached.klines)
                    }
                } else {
                    // 没有缓存，全量下载
                    val klineData = sinaApi.getKLineData(code, period, count)
                    if (klineData.isEmpty()) {
                        Result.failure(Exception("无法获取K线数据"))
                    } else {
                        // 保存到缓存
                        val lastDate = klineData.lastOrNull()?.date ?: ""
                        val cacheData = CachedKlineData(
                            code = code,
                            period = period,
                            lastUpdate = System.currentTimeMillis(),
                            lastDate = lastDate,
                            klines = klineData
                        )
                        saveKlineCacheData(code, period, cacheData)
                        
                        Result.success(klineData)
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    /**
     * 获取增量K线数据
     */
    private fun fetchIncrementalKlineData(
        code: String,
        period: String,
        fromDate: String,
        count: Int
    ): List<KLineData>? {
        return try {
            // 下载更多数据以确保覆盖增量部分
            val allData = sinaApi.getKLineData(code, period, count * 2)
            if (allData.isEmpty()) return null
            
            // 过滤出fromDate之后的数据
            val newData = allData.filter { it.date > fromDate }
            newData
        } catch (e: Exception) {
            e.printStackTrace()
            null
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
