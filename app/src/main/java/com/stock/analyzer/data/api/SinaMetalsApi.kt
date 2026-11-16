package com.stock.analyzer.data.api

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.stock.analyzer.data.model.KLineData
import com.stock.analyzer.data.model.MetalInstrument
import com.stock.analyzer.data.model.MetalQuote
import com.stock.analyzer.data.model.SinaFeed
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 贵金属行情源（免 Key，与 `backtest/metals/fetch_metals.py` 同一批接口）。
 *
 * 三个接口：
 *  1. 实时报价  https://hq.sinajs.cn/list=hf_XAU,nf_AU0     （GBK 编码，逗号分隔，一次批量拿全部）
 *  2. 外盘日线  GlobalFuturesService.getGlobalFuturesDailyKLine?symbol=XAU
 *  3. 国内日线  InnerFuturesNewService.getDailyKLine?symbol=AU0
 * 必须带 Referer=finance.sina.com.cn，否则返回 403。
 *
 * 字段下标是**对着日线逐只核过的**（2026-11-16）：
 *   hf_*（外盘）: 0 最新、4 最高、5 最低、7 昨收/昨结、12 日期、6 时间 —— 这四个字段
 *                 与 getGlobalFuturesDailyKLine 最后一根完全一致；1/2/3/8 在不同品种上
 *                 语义不稳（XPT 的 8 与日线 open 差 4.7 美元），所以这里一个都不取。
 *   nf_*（国内）: 2 开、3 高、4 低、6 最新、10 昨结算、13 持仓、14 成交量、17 日期、1 时间
 *                 —— 10 与日线 s（昨结算）逐只对上。
 * 涨跌口径：外盘对昨收，国内期货对昨结算（期货的"昨收"没意义，结算价才是保证金基准）。
 */
class SinaMetalsApi {

    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** 一次批量请求取回全部贵金属现价；失败返回 null（由引擎保留上一轮数据并标注时间） */
    fun getQuotes(instruments: List<MetalInstrument>): Map<String, MetalQuote>? {
        if (instruments.isEmpty()) return emptyMap()
        return try {
            val url = "https://hq.sinajs.cn/list=" + instruments.joinToString(",") { it.quoteKey }
            val body = get(url, GBK) ?: return null
            parseQuotes(body, instruments)
        } catch (e: Exception) {
            System.err.println("metals quotes failed: ${e.javaClass.simpleName} ${e.message}")
            null
        }
    }

    /**
     * 日线（升序，最后一根是最新交易日）。
     * 外盘一般给到 20 年、国内期货给到 18 年，够周线/月线聚合与 130 根暖机用。
     */
    fun getDailyBars(inst: MetalInstrument): List<KLineData> {
        val svc = if (inst.feed == SinaFeed.GLOBAL_FUTURES)
            "GlobalFuturesService.getGlobalFuturesDailyKLine"
        else
            "InnerFuturesNewService.getDailyKLine"
        val url = "https://stock2.finance.sina.com.cn/futures/api/jsonp.php/var%20t=/" +
            svc + "?symbol=" + inst.symbol
        val body = get(url, UTF8) ?: return emptyList()
        return parseDailyBars(body)
    }

    // ---------------- 解析 ----------------

    internal fun parseQuotes(body: String, instruments: List<MetalInstrument>): Map<String, MetalQuote> {
        val out = LinkedHashMap<String, MetalQuote>()
        for (inst in instruments) {
            val line = Regex("var hq_str_" + Regex.escape(inst.quoteKey) + "=\"([^\"]*)\"")
                .find(body)?.groupValues?.getOrNull(1) ?: continue
            val f = line.split(",")
            val q = if (inst.feed == SinaFeed.GLOBAL_FUTURES) parseOverTheCounter(inst, f)
            else parseDomesticFutures(inst, f)
            if (q != null && q.price > 0) out[inst.code] = q
        }
        return out
    }

    private fun parseOverTheCounter(inst: MetalInstrument, f: List<String>): MetalQuote? {
        if (f.size < 14) return null
        val last = d(f, 0) ?: return null
        val high = d(f, 4) ?: last
        val low = d(f, 5) ?: last
        return MetalQuote(
            code = inst.code, name = inst.name, unit = inst.unit, unitSymbol = inst.unitSymbol,
            source = inst.source,
            price = last, preSettle = d(f, 7) ?: 0.0,
            open = 0.0,                        // 外盘这一路没核过的字段宁可不显示
            high = high, low = low,
            bid = 0.0, ask = 0.0,
            volume = 0, openInterest = 0,      // 现货无成交量，显示 0 会被当成"没量"，界面上按缺省处理
            quoteDate = f.getOrElse(12) { "" }.trim(),
            quoteTime = f.getOrElse(6) { "" }.trim()
        )
    }

    private fun parseDomesticFutures(inst: MetalInstrument, f: List<String>): MetalQuote? {
        if (f.size < 18) return null
        val last = d(f, 6) ?: return null
        return MetalQuote(
            code = inst.code, name = inst.name, unit = inst.unit, unitSymbol = inst.unitSymbol,
            source = inst.source,
            price = last, preSettle = d(f, 10) ?: 0.0,
            open = d(f, 2) ?: 0.0, high = d(f, 3) ?: last, low = d(f, 4) ?: last,
            bid = 0.0, ask = 0.0,
            volume = l(f, 14), openInterest = l(f, 13),
            quoteDate = f.getOrElse(17) { "" }.trim(),
            quoteTime = hhmmss(f.getOrElse(1) { "" }.trim())
        )
    }

    internal fun parseDailyBars(body: String): List<KLineData> {
        val s = body.substringAfter("(", "").substringBeforeLast(")").removeSuffix(";").trim()
        if (s.isEmpty() || s == "null") return emptyList()
        // 用 Gson 而不是 org.json：单测里 org.json 是 android.jar 的空壳，一调就抛
        // "Method ... not mocked"，这条链路就只能靠肉眼对数字了。
        val arr = runCatching { gson.fromJson(s, JsonArray::class.java) }.getOrNull() ?: return emptyList()
        val out = ArrayList<KLineData>(arr.size())
        for (e in arr) {
            if (!e.isJsonObject) continue
            val o = e.asJsonObject
            // 外盘用长字段名，国内用单字母；两种都在这张表里出现
            val date = o.str("date").ifBlank { o.str("d") }
            val open = o.num("open") ?: o.num("o") ?: continue
            val high = o.num("high") ?: o.num("h") ?: open
            val low = o.num("low") ?: o.num("l") ?: open
            val close = o.num("close") ?: o.num("c") ?: continue
            if (date.isBlank() || close <= 0) continue
            out += KLineData(
                date = date.take(10), open = open, close = close, high = high, low = low,
                volume = o.lng("volume").let { if (it > 0) it else o.lng("v") }
            )
        }
        return out.sortedBy { it.date }
    }

    // ---------------- 小工具 ----------------

    private fun get(url: String, charset: String): String? = try {
        val req = Request.Builder().url(url)
            .addHeader("Referer", "https://finance.sina.com.cn")
            .addHeader("User-Agent", UA)
            .build()
        client.newCall(req).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: return null
            if (!resp.isSuccessful) {
                System.err.println("metals http ${resp.code} for $url")
                return null
            }
            String(bytes, charset(charset))
        }
    } catch (e: Exception) {
        System.err.println("metals fetch error $url: ${e.javaClass.simpleName} ${e.message}")
        null
    }

    private fun d(f: List<String>, i: Int): Double? =
        f.getOrNull(i)?.trim()?.toDoubleOrNull()?.takeIf { it > 0 }

    private fun l(f: List<String>, i: Int): Long =
        f.getOrNull(i)?.trim()?.toDoubleOrNull()?.toLong() ?: 0L

    /** 字段缺失、null、非字符串都退回空串，不抛 */
    private fun JsonObject.str(key: String): String {
        val el = if (has(key)) get(key) else return ""
        if (el.isJsonNull) return ""
        return runCatching { if (el.isJsonPrimitive) el.asString.trim() else "" }.getOrDefault("")
    }

    /** 只接受正数：0 / 负数 / 空串一律当"这个字段没有"，与原来的 optDoubleOrNull 同口径 */
    private fun JsonObject.num(key: String): Double? {
        val el = if (has(key)) get(key) else return null
        if (el.isJsonNull) return null
        val v = runCatching {
            if (el.isJsonPrimitive && el.asJsonPrimitive.isNumber) el.asDouble else el.asString.trim().toDoubleOrNull()
        }.getOrNull()
        return v?.takeIf { !it.isNaN() && it > 0 }
    }

    private fun JsonObject.lng(key: String): Long {
        val el = if (has(key)) get(key) else return 0L
        if (el.isJsonNull) return 0L
        return runCatching {
            if (el.isJsonPrimitive && el.asJsonPrimitive.isNumber) el.asLong
            else el.asString.trim().toDoubleOrNull()?.toLong()
        }.getOrNull() ?: 0L
    }

    /** "023000" -> "02:30:00" */
    private fun hhmmss(t: String): String =
        if (t.length == 6 && t.all { it.isDigit() })
            "${t.substring(0, 2)}:${t.substring(2, 4)}:${t.substring(4, 6)}" else t

    private companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        const val GBK = "GBK"        // 新浪报价是 GBK，名字里有中文，用 UTF-8 解会乱码
        const val UTF8 = "UTF-8"
    }
}
