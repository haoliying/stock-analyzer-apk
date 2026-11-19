package com.stock.analyzer.data.api

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.stock.analyzer.data.model.KLineData
import com.stock.analyzer.data.model.NonStockInstrument
import com.stock.analyzer.data.model.NonStockQuote
import com.stock.analyzer.data.model.SinaFeed
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 腾讯外汇（现汇）行情源（免 Key），承载**外汇**这一类的实时报价与日线。
 *
 * 为什么外汇单独走腾讯、不走新浪：新浪的外汇日线接口（`NewForexService.getDayKLine`）
 * 2026-11-19 实测对任何符号（USDCNY/EURUSD/usdcny…）都只回 `data is empty`，USDCNY 更是直接
 * PHP 报错；而腾讯这一路现报价与日线都是通的、且两边字段能互相核对。**不与新浪期货那一路混用**，
 * 避免报价与 K 线来自两个没核对过的源。
 *
 * 两个接口：
 *  1. 实时报价  https://qt.gtimg.cn/q=whUSDCNY,whEURCNY   （GBK，`~` 分隔，一次批量拿全部）
 *  2. 日线      https://web.ifzq.gtimg.cn/appstock/app/kline/kline?param=whUSDCNY,day,,,2000
 *
 * 报价字段下标（2026-11-19 抓数核对，与日线最后一根逐项对上）：
 *  0 市场码、1 名称、2 代码、3 最新、4 标志、5 时间(yyyyMMddHHmmss)、6 昨收、
 *  7 今开、8 最高、9 最低、10 买、11 卖、12 涨跌、13 涨跌幅、…、末位 日期。
 * 涨跌幅口径：现汇没有结算价，对**昨收**（字段 6）；涨跌 = 最新 − 昨收，与字段 12/13 一致。
 * 现汇没有成交量与持仓：那两路恒 0，界面上按缺省处理、不显示。
 *
 * 日线每根是 `[日期, 开, 收, 高, 低, 量]`（腾讯这一路的固定顺序，不是 OHLC ——
 * 高在收之后、低在最后，按 OHLC 解会把高低写反）。单次最多 2000 根（约 8 年），
 * 够 130 根暖机、周线/月线聚合与图表拖到最早一根。
 */
class TencentForexApi {

    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** 一次批量请求取回全部货币对现价；失败返回 null（由引擎保留上一轮数据并标注时间） */
    fun getQuotes(instruments: List<NonStockInstrument>): Map<String, NonStockQuote>? {
        val forex = instruments.filter { it.feed == SinaFeed.FOREX }
        if (forex.isEmpty()) return emptyMap()
        return try {
            val url = "https://qt.gtimg.cn/q=" + forex.joinToString(",") { it.quoteKey }
            val body = get(url, GBK) ?: return null
            parseQuotes(body, forex)
        } catch (e: Exception) {
            System.err.println("forex quotes failed: ${e.javaClass.simpleName} ${e.message}")
            null
        }
    }

    /** 日线（升序，最后一根是最新交易日）。2000 根是腾讯这一路的上限，约 8 年历史。 */
    fun getDailyBars(inst: NonStockInstrument): List<KLineData> {
        if (inst.feed != SinaFeed.FOREX) return emptyList()
        val url = "https://web.ifzq.gtimg.cn/appstock/app/kline/kline?param=" +
            inst.quoteKey + ",day,,,2000"
        val body = get(url, UTF8) ?: return emptyList()
        return parseDailyBars(body, inst.quoteKey)
    }

    // ---------------- 解析 ----------------

    internal fun parseQuotes(body: String, instruments: List<NonStockInstrument>): Map<String, NonStockQuote> {
        val out = LinkedHashMap<String, NonStockQuote>()
        for (inst in forex) {
            val line = Regex("v_" + Regex.escape(inst.quoteKey) + "=\"([^\"]*)\"")
                .find(body)?.groupValues?.getOrNull(1) ?: continue
            val f = line.split("~")
            if (f.size < 14) continue
            val price = d(f, 3) ?: continue
            val preSettle = d(f, 6) ?: 0.0
            val high = d(f, 8) ?: price
            val low = d(f, 9) ?: price
            val open = d(f, 7) ?: 0.0
            out[inst.code] = NonStockQuote(
                code = inst.code, name = inst.name, unit = inst.unit, unitSymbol = inst.unitSymbol,
                source = inst.source,
                price = price, preSettle = preSettle,
                open = open, high = high, low = low,
                bid = d(f, 10) ?: 0.0, ask = d(f, 11) ?: 0.0,
                volume = 0, openInterest = 0,      // 现汇无成交量/持仓，界面上按缺省处理
                quoteDate = f.last().trim(),
                quoteTime = hhmmss(f.getOrElse(5) { "" }.trim())
            )
        }
        return out
    }

    internal fun parseDailyBars(body: String, quoteKey: String): List<KLineData> {
        val arr = try {
            val root = gson.fromJson(body, JsonObject::class.java) ?: return emptyList()
            val data = root.get("data")?.asJsonObject ?: return emptyList()
            val node = data.get(quoteKey)?.asJsonObject ?: return emptyList()
            node.get("day")?.asJsonArray
        } catch (e: Exception) {
            return emptyList()
        } ?: return emptyList()
        val out = ArrayList<KLineData>(arr.size())
        for (e in arr) {
            if (!e.isJsonArray) continue
            val a = e.asJsonArray
            if (a.size() < 6) continue
            val date = a[0].asString.trim().take(10)
            // 腾讯日线顺序：[日期, 开, 收, 高, 低, 量]
            val open = num(a, 1) ?: continue
            val close = num(a, 2) ?: continue
            val high = num(a, 3) ?: open
            val low = num(a, 4) ?: open
            if (date.isEmpty() || close <= 0) continue
            out += KLineData(
                date = date, open = open, close = close, high = high, low = low,
                volume = a[5].asString.trim().toDoubleOrNull()?.toLong() ?: 0L
            )
        }
        return out.sortedBy { it.date }
    }

    // ---------------- 小工具 ----------------

    private fun get(url: String, charset: String): String? = try {
        val req = Request.Builder().url(url)
            .addHeader("Referer", "https://gu.qq.com")
            .addHeader("User-Agent", UA)
            .build()
        client.newCall(req).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: return null
            if (!resp.isSuccessful) {
                System.err.println("forex http ${resp.code} for $url")
                return null
            }
            String(bytes, charset(charset))
        }
    } catch (e: Exception) {
        System.err.println("forex fetch error $url: ${e.javaClass.simpleName} ${e.message}")
        null
    }

    private fun d(f: List<String>, i: Int): Double? =
        f.getOrNull(i)?.trim()?.toDoubleOrNull()?.takeIf { it > 0 }

    private fun num(a: JsonArray, i: Int): Double? = runCatching {
        if (a[i].isJsonPrimitive && a[i].asJsonPrimitive.isNumber) a[i].asDouble
        else a[i].asString.trim().toDoubleOrNull()
    }.getOrNull()?.takeIf { !it.isNaN() && it > 0 }

    /** "20261119153830" -> "15:38:30"；认不出来原样返回 */
    private fun hhmmss(t: String): String {
        if (t.length == 14 && t.all { it.isDigit() }) {
            return "${t.substring(8, 10)}:${t.substring(10, 12)}:${t.substring(12, 14)}"
        }
        return t
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        const val GBK = "GBK"
        const val UTF8 = "UTF-8"
    }
}
