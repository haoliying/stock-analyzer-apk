package com.stock.analyzer.data.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.stock.analyzer.data.model.KLineData
import com.stock.analyzer.data.model.Stock
import com.stock.analyzer.data.model.StockInfo
import com.stock.analyzer.data.model.TimelineData
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 新浪财经API实现 - 增强版
 * 支持获取全市场股票数据
 */
class SinaStockApi {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    
    private val gson = Gson()
    
    /**
     * 获取实时股票行情
     */
    fun getRealtimeQuotes(codes: List<String>): List<Stock> {
        val stocks = mutableListOf<Stock>()

        try {
            // 分批请求，每批80只，减少请求次数
            codes.chunked(80).forEach { batch ->
                val codesStr = batch.joinToString(",")
                val url = "https://hq.sinajs.cn/list=$codesStr"

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Referer", "https://finance.sina.com.cn")
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: return@forEach

                if (responseBody.isBlank()) {
                    println("Sina API returned empty response for: $codesStr")
                    return@forEach
                }

                val parsedStocks = parseSinaData(responseBody)
                stocks.addAll(parsedStocks)

                // 减少请求间隔
                Thread.sleep(200)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 新浪某几只没返回（北交所、临停、限流）时用腾讯补齐，
        // 否则这些股票会直接从列表里消失，用户看到的就是"有的股票没有数据"
        val got = stocks.map { it.code }.toHashSet()
        val missing = codes.filter { it.isNotBlank() && it !in got }
        if (missing.isNotEmpty()) {
            stocks.addAll(getQuotesFromTencent(missing))
        }

        return stocks
    }
    
    /**
     * 获取沪深A股全部股票代码列表 - 优化版，快速获取
     */
    fun getAllStockCodes(): List<String> {
        val codes = mutableListOf<String>()
        
        try {
            // 快速获取前20页（约2000只股票），足够分析使用
            for (page in 1..20) {
                val url = "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/Market_Center/getHQNodeData?page=$page&num=100&sort=symbol&asc=1&node=hs_a&symbol=&_s_r_a=page"
                
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Referer", "https://finance.sina.com.cn")
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .build()
                
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: break
                
                if (responseBody.isBlank() || responseBody == "null" || responseBody == "[]") {
                    break
                }
                
                try {
                    val jsonArray = gson.fromJson(responseBody, Array<JsonObject>::class.java)
                    if (jsonArray == null || jsonArray.isEmpty()) {
                        break
                    }
                    
                    jsonArray.forEach { obj ->
                        val code = obj.get("code")?.asString ?: ""
                        if (code.isNotEmpty()) {
                            val prefix = when {
                                code.startsWith("6") || code.startsWith("9") -> "sh"
                                else -> "sz"
                            }
                            codes.add("$prefix$code")
                        }
                    }
                    
                    if (jsonArray.size < 100) break
                    Thread.sleep(50) // 减少延迟
                } catch (e: Exception) {
                    break
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return if (codes.isEmpty()) getCommonStockCodes() else codes
    }
    
    /**
     * 常用股票代码列表（备用）
     */
    private fun getCommonStockCodes(): List<String> {
        return listOf(
            // 沪市主板
            "sh600000", "sh600009", "sh600016", "sh600019", "sh600028",
            "sh600029", "sh600030", "sh600031", "sh600036", "sh600048",
            "sh600050", "sh600104", "sh600276", "sh600309", "sh600346",
            "sh600436", "sh600519", "sh600585", "sh600588", "sh600690",
            "sh600837", "sh600887", "sh600900", "sh600918", "sh600919",
            "sh600958", "sh600999", "sh601006", "sh601009", "sh601012",
            "sh601066", "sh601088", "sh601111", "sh601117", "sh601138",
            "sh601155", "sh601162", "sh601166", "sh601169", "sh601186",
            "sh601211", "sh601225", "sh601228", "sh601229", "sh601231",
            "sh601236", "sh601238", "sh601288", "sh601298", "sh601318",
            "sh601328", "sh601336", "sh601360", "sh601377", "sh601390",
            "sh601398", "sh601601", "sh601607", "sh601618", "sh601628",
            "sh601633", "sh601668", "sh601669", "sh601688", "sh601698",
            "sh601727", "sh601766", "sh601788", "sh601800", "sh601808",
            "sh601816", "sh601818", "sh601828", "sh601838", "sh601857",
            "sh601877", "sh601878", "sh601881", "sh601888", "sh601898",
            "sh601899", "sh601901", "sh601919", "sh601933", "sh601939",
            "sh601958", "sh601985", "sh601988", "sh601989", "sh601992",
            "sh601998", "sh603000", "sh603019", "sh603156", "sh603259",
            "sh603288", "sh603501", "sh603659", "sh603799", "sh603833",
            
            // 深市主板
            "sz000001", "sz000002", "sz000063", "sz000066", "sz000069",
            "sz000100", "sz000157", "sz000333", "sz000338", "sz000423",
            "sz000538", "sz000568", "sz000596", "sz000625", "sz000651",
            "sz000661", "sz000703", "sz000708", "sz000709", "sz000725",
            "sz000768", "sz000776", "sz000778", "sz000783", "sz000786",
            "sz000858", "sz000860", "sz000876", "sz000895", "sz000938",
            "sz000963", "sz000977", "sz000999", "sz002001", "sz002007",
            "sz002008", "sz002024", "sz002027", "sz002032", "sz002049",
            "sz002050", "sz002120", "sz002142", "sz002146", "sz002153",
            "sz002179", "sz002202", "sz002230", "sz002236", "sz002241",
            "sz002271", "sz002304", "sz002311", "sz002352", "sz002371",
            "sz002415", "sz002460", "sz002466", "sz002468", "sz002493",
            "sz002555", "sz002594", "sz002601", "sz002602", "sz002607",
            "sz002714", "sz002736", "sz002739", "sz002812", "sz002831",
            "sz002841", "sz002916", "sz002938", "sz002945", "sz002966",
            
            // 创业板
            "sz300003", "sz300009", "sz300014", "sz300015", "sz300033",
            "sz300059", "sz300122", "sz300124", "sz300136", "sz300142",
            "sz300144", "sz300223", "sz300253", "sz300274", "sz300308",
            "sz300316", "sz300347", "sz300394", "sz300408", "sz300413",
            "sz300433", "sz300498", "sz300529", "sz300601", "sz300628",
            "sz300750", "sz300760", "sz300782", "sz300832", "sz300866",
            "sz300896", "sz300979", "sz300999"
        )
    }
    
    /**
     * 东方财富 secid 的市场号候选：1=沪市，0=深市与北交所。
     *
     * 旧实现写死 `if (code.startsWith("sh")) "1" else "0"`，于是
     *   · 北交所 920xxx（2024 年起的新代码段，替代原 83xxxx）会被拼成 0.920xxx 才对，
     *     但持仓里若存的是没有前缀的裸代码，就会掉进错误分支；
     *   · 沪市 900xxx（B 股）、688 段一旦前缀缺失同样拼错；
     *   · 一旦拼错，接口回 `data: null`，上层拿到空列表 → "这只股票没有日线/月线"。
     * 所以这里按前缀优先、其次按代码段推断，并额外给一个"翻转市场"的候选做兜底重试。
     */
    private fun eastMoneySecids(code: String): List<String> {
        val lower = code.trim().lowercase()
        val bare = lower.removePrefix("sh").removePrefix("sz").removePrefix("bj")
        if (bare.length < 6 || !bare.all { it.isDigit() }) return emptyList()
        val preferred = when {
            lower.startsWith("bj") -> "0"            // 北交所（43/83/87/92 段都挂市场 0）
            lower.startsWith("sh") -> "1"
            lower.startsWith("sz") -> "0"
            else -> when (bare[0]) {
                '5', '6', '9' -> "1"
                else -> "0"
            }
        }
        val other = if (preferred == "1") "0" else "1"
        return listOf("$preferred.$bare", "$other.$bare")
    }

    /** 去掉 sh/sz/bj 前缀的纯数字代码 */
    private fun bareCode(code: String): String =
        code.trim().lowercase().removePrefix("sh").removePrefix("sz").removePrefix("bj")

    /**
     * 获取K线历史数据
     * 东方财富为主，两个市场候选都试完仍空则改走腾讯前复权接口。
     */
    fun getKLineData(code: String, period: String = "daily", count: Int = 100): List<KLineData> {
        for (secid in eastMoneySecids(code)) {
            val em = fetchKlineFromEastMoney(secid, period, count)
            if (em.isNotEmpty()) return em
        }
        return fetchKlineFromTencent(code, period, count)
    }

    private fun fetchKlineFromEastMoney(secid: String, period: String, count: Int): List<KLineData> {
        val klineList = mutableListOf<KLineData>()

        try {
            val klt = when (period) {
                "daily" -> "101"
                "weekly" -> "102"
                "monthly" -> "103"
                "5min" -> "5"
                "15min" -> "15"
                "30min" -> "30"
                "60min" -> "60"
                else -> "101"
            }

            val url = "https://push2his.eastmoney.com/api/qt/stock/kline/get?" +
                    "secid=$secid&" +
                    "fields1=f1,f2,f3,f4,f5,f6&" +
                    "fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61&" +
                    "klt=$klt&" +
                    "fqt=1&" +
                    "end=20500101&" +
                    "lmt=$count&" +
                    "rand=${System.currentTimeMillis()}"

            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0")
                .addHeader("Referer", "https://quote.eastmoney.com")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return emptyList()

            val jsonObject = gson.fromJson(responseBody, JsonObject::class.java)
            val data = jsonObject?.getAsJsonObject("data") ?: return emptyList()
            val klines = data.getAsJsonArray("klines") ?: return emptyList()

            for (klineElement in klines) {
                val klineStr = klineElement.asString
                val parts = klineStr.split(",")
                if (parts.size < 7) continue

                try {
                    val kline = KLineData(
                        date = parts[0],
                        open = parts[1].toDoubleOrNull() ?: 0.0,
                        close = parts[2].toDoubleOrNull() ?: 0.0,
                        high = parts[3].toDoubleOrNull() ?: 0.0,
                        low = parts[4].toDoubleOrNull() ?: 0.0,
                        volume = (parts[5].toDoubleOrNull() ?: 0.0).toLong()
                    )
                    klineList.add(kline)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return klineList
    }

    /**
     * 腾讯前复权K线（备用源）。日/周/月三种周期实测可用，返回
     * data[<symbol>].qfqday|qfqweek|qfqmonth = [[日期, 开, 收, 高, 低, 成交量(手)], ...]
     * —— 字段顺序和东方财富不同（东方财富是 开,收,高,低），别照抄。
     */
    private fun fetchKlineFromTencent(code: String, period: String, count: Int): List<KLineData> {
        val kind = when (period) {
            "daily" -> "day"
            "weekly" -> "week"
            "monthly" -> "month"
            else -> return emptyList()   // 分钟线不走这个兜底
        }
        val symbol = tencentSymbol(code) ?: return emptyList()

        return try {
            val url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=$symbol,$kind,,,$count,qfq"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0")
                .addHeader("Referer", "https://gu.qq.com")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return emptyList()

            val root = gson.fromJson(body, JsonObject::class.java) ?: return emptyList()
            val node = root.getAsJsonObject("data")?.getAsJsonObject(symbol) ?: return emptyList()
            val rows = node.getAsJsonArray("qfq$kind") ?: node.getAsJsonArray(kind) ?: return emptyList()

            val out = mutableListOf<KLineData>()
            for (row in rows) {
                val arr = row.asJsonArray
                if (arr.size() < 6) continue
                val d = arr[0].asString
                if (!d.matches(Regex("\\d{4}-\\d{2}-\\d{2}.*"))) continue
                out.add(
                    KLineData(
                        date = d,
                        open = arr[1].asString.toDoubleOrNull() ?: continue,
                        close = arr[2].asString.toDoubleOrNull() ?: continue,
                        high = arr[3].asString.toDoubleOrNull() ?: continue,
                        low = arr[4].asString.toDoubleOrNull() ?: continue,
                        volume = (arr[5].asString.toDoubleOrNull() ?: 0.0).toLong()
                    )
                )
            }
            out
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /** sh/sz/bj + 6 位数字；裸数字代码按市场号推断出等价 symbol */
    private fun tencentSymbol(code: String): String? {
        val lower = code.trim().lowercase()
        if (lower.startsWith("sh") || lower.startsWith("sz") || lower.startsWith("bj")) return lower
        val bare = bareCode(lower)
        if (bare.length != 6 || !bare.all { it.isDigit() }) return null
        val secid = eastMoneySecids(bare).firstOrNull() ?: return null
        return (if (secid.startsWith("1")) "sh" else "sz") + bare
    }
    
    /**
     * 获取分时数据（当天）
     */
    fun getTimelineData(code: String): List<TimelineData> {
        for (secid in eastMoneySecids(code)) {
            val t = fetchTrendsFromEastMoney(secid)
            if (t.isNotEmpty()) return t
        }
        return emptyList()
    }

    private fun fetchTrendsFromEastMoney(secid: String): List<TimelineData> {
        val timelineList = mutableListOf<TimelineData>()

        try {
            val url = "https://push2his.eastmoney.com/api/qt/stock/trends2/get?" +
                    "secid=$secid&" +
                    "fields1=f1,f2,f3,f4,f5,f6,f7,f8,f9,f10,f11,f12,f13&" +
                    "fields2=f51,f52,f53,f54,f55,f56,f57,f58&" +
                    "iscr=0&" +
                    "ndays=1&" +
                    "rand=${System.currentTimeMillis()}"

            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0")
                .addHeader("Referer", "https://quote.eastmoney.com")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return emptyList()

            val jsonObject = gson.fromJson(responseBody, JsonObject::class.java)
            val data = jsonObject?.getAsJsonObject("data") ?: return emptyList()
            val trends = data.getAsJsonArray("trends") ?: return emptyList()
            val preClose = data.get("preClose")?.asDouble ?: 0.0

            for (trendElement in trends) {
                val trendStr = trendElement.asString
                val parts = trendStr.split(",")
                if (parts.size < 8) continue

                try {
                    val timeStr = parts[0].split(" ").getOrNull(1)?.substring(0, 5) ?: ""
                    val timeline = TimelineData(
                        time = timeStr,
                        price = parts[2].toDoubleOrNull() ?: 0.0,
                        avgPrice = parts[7].toDoubleOrNull() ?: 0.0,
                        volume = (parts[5].toDoubleOrNull() ?: 0.0).toLong(),
                        preClose = preClose
                    )
                    timelineList.add(timeline)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return timelineList
    }
    
    /**
     * 获取单只股票实时行情 - 带备用API
     */
    fun getStockQuote(code: String): Stock? {
        try {
            val stocks = getRealtimeQuotes(listOf(code))
            if (stocks.isNotEmpty()) {
                return stocks.firstOrNull()
            }
            
            // 新浪API失败，尝试腾讯API
            println("Sina API failed, trying Tencent API for: $code")
            return getStockQuoteFromTencent(code)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
    
    /**
     * 从腾讯API获取股票行情（备用）。支持一次多只：`q=sh600519,sz000001`
     */
    private fun getQuotesFromTencent(codes: List<String>): List<Stock> {
        if (codes.isEmpty()) return emptyList()
        val out = mutableListOf<Stock>()

        codes.chunked(60).forEach { batch ->
            try {
                val url = "https://qt.gtimg.cn/q=" + batch.joinToString(",")
                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: return@forEach
                if (responseBody.isBlank()) return@forEach

                // 一行一只：v_sh600519="1~贵州茅台~...";
                for (line in responseBody.split(";")) {
                    if (!line.contains("=")) continue
                    val code = line.substringAfter("v_", "").substringBefore("=").trim()
                    if (code.isBlank()) continue
                    val stock = parseTencentData(line, code) ?: continue
                    // 返回值里的 key 可能带 bj/sh/sz 前缀，与请求时的写法对齐后再返回
                    val requested = batch.firstOrNull { it.equals(code, ignoreCase = true) } ?: code
                    out.add(stock.copy(code = requested))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return out
    }

    private fun getStockQuoteFromTencent(code: String): Stock? =
        getQuotesFromTencent(listOf(code)).firstOrNull()
    
    /**
     * 解析腾讯API数据
     */
    private fun parseTencentData(responseBody: String, code: String): Stock? {
        return try {
            // 腾讯格式: v_sz000858="51~五粮液~000858~149.50~149.50~149.50~...";
            val dataStr = responseBody.substringAfter("=").trim().removeSurrounding("\"").removeSuffix(";")
            if (dataStr.isBlank()) return null
            
            val parts = dataStr.split("~")
            if (parts.size < 50) return null
            
            val name = parts[1]
            val price = parts[3].toDoubleOrNull() ?: return null
            val yesterdayClose = parts[4].toDoubleOrNull() ?: price
            val open = parts[5].toDoubleOrNull() ?: price
            val high = parts[33].toDoubleOrNull() ?: price
            val low = parts[34].toDoubleOrNull() ?: price
            val volume = parts[36].toDoubleOrNull()?.toLong() ?: 0
            val turnover = parts[37].toDoubleOrNull() ?: 0.0
            
            val change = price - yesterdayClose
            val changePercent = if (yesterdayClose > 0) (change / yesterdayClose) * 100 else 0.0
            
            Stock(
                code = code,
                name = name,
                price = price,
                change = change,
                changePercent = changePercent,
                volume = volume,
                turnover = turnover,
                high = high,
                low = low,
                open = open,
                close = yesterdayClose,
                timestamp = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 名称/代码/拼音搜索（东方财富 suggest 接口）。
     *
     * 内置列表是静态的、会过时（北交所已经整段换成 920xxx），
     * 用户搜一只列表里没有的股票就会看到"未找到匹配的股票"，以为这股票没数据。
     * 这里只收 A 股：沪A/深A/京A，基金债券港股一律不返回。
     */
    fun searchStocksRemote(keyword: String, limit: Int = 20): List<StockInfo> {
        if (keyword.isBlank()) return emptyList()
        return try {
            val kw = java.net.URLEncoder.encode(keyword.trim(), "UTF-8")
            val url = "https://searchapi.eastmoney.com/api/suggest/get?" +
                    "input=$kw&type=14&token=D43BF722C8E33BDC906FB84D85E326E8&count=$limit"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0")
                .addHeader("Referer", "https://quote.eastmoney.com")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return emptyList()
            val root = gson.fromJson(body, JsonObject::class.java) ?: return emptyList()
            val rows = root.getAsJsonObject("QuotationCodeTable")?.getAsJsonArray("Data")
                ?: return emptyList()

            val out = mutableListOf<StockInfo>()
            for (row in rows) {
                val o = row.asJsonObject
                val code = o.get("Code")?.asString ?: continue
                val prefix = when (o.get("SecurityTypeName")?.asString) {
                    "沪A" -> "sh"
                    "深A" -> "sz"
                    "京A" -> "bj"
                    else -> continue
                }
                if (code.length != 6 || !code.all { it.isDigit() }) continue
                out.add(
                    StockInfo(
                        code = "$prefix$code",
                        name = o.get("Name")?.asString ?: code,
                        pinyin = o.get("PinYin")?.asString ?: ""
                    )
                )
                if (out.size >= limit) break
            }
            out
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * 测试网络连接
     */
    fun testConnection(): Boolean {
        return try {
            val request = Request.Builder()
                .url("https://hq.sinajs.cn/list=sh600519")
                .addHeader("Referer", "https://finance.sina.com.cn")
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()
            
            val response = client.newCall(request).execute()
            response.isSuccessful && response.body?.string()?.isNotBlank() == true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 解析新浪API返回的数据
     */
    private fun parseSinaData(responseBody: String): List<Stock> {
        val stocks = mutableListOf<Stock>()
        
        val lines = responseBody.split("\n")
        for (line in lines) {
            if (line.isBlank() || !line.contains("=")) continue
            
            val parts = line.split("=")
            if (parts.size != 2) continue
            
            val code = parts[0].substringAfter("var hq_str_").trim()
            val dataStr = parts[1].trim().removeSurrounding("\"").removeSuffix(";")
            
            if (dataStr.isBlank()) continue
            
            val dataParts = dataStr.split(",")
            if (dataParts.size < 32) continue
            
            try {
                val stock = Stock(
                    code = code,
                    name = dataParts[0],
                    open = dataParts[1].toDoubleOrNull() ?: 0.0,
                    close = dataParts[2].toDoubleOrNull() ?: 0.0,
                    price = dataParts[3].toDoubleOrNull() ?: 0.0,
                    high = dataParts[4].toDoubleOrNull() ?: 0.0,
                    low = dataParts[5].toDoubleOrNull() ?: 0.0,
                    volume = (dataParts[8].toDoubleOrNull() ?: 0.0).toLong() / 100,
                    turnover = dataParts[9].toDoubleOrNull() ?: 0.0,
                    change = dataParts[3].toDoubleOrNull()!!.let { current ->
                        val yesterdayClose = dataParts[2].toDoubleOrNull() ?: current
                        current - yesterdayClose
                    },
                    changePercent = dataParts[3].toDoubleOrNull()!!.let { current ->
                        val yesterdayClose = dataParts[2].toDoubleOrNull() ?: current
                        if (yesterdayClose != 0.0) {
                            ((current - yesterdayClose) / yesterdayClose) * 100
                        } else 0.0
                    },
                    timestamp = System.currentTimeMillis()
                )
                stocks.add(stock)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        return stocks
    }
}
