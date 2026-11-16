package com.stock.analyzer.data

import com.stock.analyzer.data.api.SinaMetalsApi
import com.stock.analyzer.data.model.AssetClass
import com.stock.analyzer.data.model.KLineData
import com.stock.analyzer.data.model.MetalInstrument
import com.stock.analyzer.data.model.SinaFeed
import com.stock.analyzer.data.model.isMetal
import com.stock.analyzer.data.model.metalInstrument
import com.stock.analyzer.data.repository.MetalsRepository
import com.stock.analyzer.ui.portfolio.PortfolioViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 贵金属这一路的数据层测试。
 *
 * 报价那两条 fixture 是 **2026-11-16/17 从 `hq.sinajs.cn` 原样抓回来的行**
 * （外盘一行、境内一行），不是编的。之所以要把真实字符串钉在测试里：
 * 这一路的字段是"下标"而不是字段名，接口那边挪一位，界面上就是**另一个数**——
 * 而价格错了没人会看出来，涨跌 4000 和 3800 长得一样像。
 *
 * 外盘只取核过的 0/4/5/7/12/6 六个下标，其余一个都不读：纽约铂金第 8 位的值
 * 拿去对日线 open 对不上（差 4.7 美元），所以宁可少显示一个字段。
 */
class MetalsDataTest {

    private val hfFixture = """
        var hq_str_hf_XAU="3856.24,3879.920,3856.24,3856.59,3901.15,3832.36,05:59:00,3879.92,3873.03,0,0,0,2026-11-17,伦敦金（现货黄金）";
        var hq_str_hf_XPT="1446.706,,1447.200,1447.700,1511.900,1440.700,05:59:55,1489.700,1487.100,0,1,2,2026-11-17,纽约铂金,0";
    """.trimIndent()

    private val nfFixture = """
        var hq_str_nf_AU0="黄金连续,023000,834.440,838.880,828.340,0.000,830.620,830.940,830.620,0.000,835.780,9,1,139773.000,52673,沪,黄金,2026-11-17,1,,,,,,,,,834.366,830.600,24,830.560,14,830.540,4,830.520,10,831.000,1,831.020,1,831.360,3,832.700,2";
        var hq_str_nf_AG0="白银连续,023000,11321.000,11372.000,11088.000,0.000,11205.000,11207.000,11204.000,0.000,11644.000,6,44,305839.000,619289,沪,白银,2026-11-17,1,,,,,,,,,11220.768,11204.000,24,11204.000,19";
    """.trimIndent()

    private val api = SinaMetalsApi()

    @Test
    fun `外盘报价只读核过的那几个下标`() {
        val q = api.parseQuotes(hfFixture, listOf(MetalInstrument.of("hf_XAU")!!, MetalInstrument.of("hf_XPT")!!))

        val xau = q["hf_XAU"]!!
        assertEquals(3856.24, xau.price, 1e-6)
        assertEquals(3901.15, xau.high, 1e-6)
        assertEquals(3832.36, xau.low, 1e-6)
        // 7 是昨收，与日线上一根的 close 对得上（3879.92）
        assertEquals(3879.92, xau.preSettle, 1e-6)
        assertEquals(-0.61, xau.changePercent, 0.01)
        // 12/6 是那个市场自己的日期与时间，不是手机时间
        assertEquals("2026-11-17", xau.quoteDate)
        assertEquals("05:59:00", xau.quoteTime)
        // 8 号下标拿去对日线 open 对不上，所以这里一个都不取：必须是 0 / 缺省
        assertFalse("外盘的今开不许显示", xau.hasOpen)
        assertFalse("外盘现货没有成交量", xau.hasVolume)

        // XPT 第 1 位是空串：解析必须不被空字段带崩，且仍能取到后面的值
        val xpt = q["hf_XPT"]!!
        assertEquals(1446.706, xpt.price, 1e-6)
        assertEquals(1511.900, xpt.high, 1e-6)
        assertEquals(1440.700, xpt.low, 1e-6)
        assertEquals(1489.700, xpt.preSettle, 1e-6)
        assertFalse(xpt.isRise)
    }

    @Test
    fun `境内期货的昨结算与量仓取自对得上的下标`() {
        val q = api.parseQuotes(nfFixture, listOf(MetalInstrument.of("nf_AU0")!!, MetalInstrument.of("nf_AG0")!!))

        val au = q["nf_AU0"]!!
        assertEquals(834.44, au.open, 1e-6)
        assertEquals(838.88, au.high, 1e-6)
        assertEquals(828.34, au.low, 1e-6)
        assertEquals(830.62, au.price, 1e-6)
        // 10 是昨结算：上期所的 s 字段与它对得上（835.780）
        assertEquals(835.78, au.preSettle, 1e-6)
        assertEquals(52673L, au.volume)
        assertEquals(139773L, au.openInterest)
        assertEquals("2026-11-17", au.quoteDate)
        // 境内时间是 6 位数字，界面要的是能读的 HH:mm:ss
        assertEquals("02:30:00", au.quoteTime)
        assertTrue("夜盘对昨结算是跌的", au.change < 0)

        // 沪银：价格量级是"元/千克"，不是"元/克"，单位写错就等于差 1000 倍
        val ag = q["nf_AG0"]!!
        assertEquals(11205.0, ag.price, 1e-6)
        assertEquals(11644.0, ag.preSettle, 1e-6)
        assertEquals(619289L, ag.volume)
        assertEquals("元/千克", MetalInstrument.of("nf_AG0")!!.unit)
        assertEquals("CNY/kg", MetalInstrument.of("nf_AG0")!!.unitSymbol)
    }

    @Test
    fun `两种字段名的日线都能解并且按日期升序`() {
        val overseas = """var t=([{"date":"2026-11-13","open":"3877.210","high":"3922.990","low":"3867.550","close":"3879.920","volume":"0","position":"0","s":"0.000"},""" +
            """{"date":"2026-11-16","open":"3873.030","high":"3901.150","low":"3832.360","close":"3856.240","volume":"0","position":"0","s":"0.000"}]);"""
        val domestic = """var t=([{"d":"2026-11-16","o":"838.760","h":"843.060","l":"826.700","c":"830.560","v":"120403","p":"138530","s":"835.780"},""" +
            """{"d":"2026-11-13","o":"845.660","h":"848.860","l":"829.600","c":"839.000","v":"332909","p":"123911","s":"838.200"}]);"""

        val a = api.parseDailyBars(overseas)
        assertEquals(listOf("2026-11-13", "2026-11-16"), a.map { it.date })
        assertEquals(3856.24, a.last().close, 1e-6)
        assertEquals(0L, a.last().volume)

        val b = api.parseDailyBars(domestic)
        assertEquals("接口给的是倒序，解完必须变成升序", listOf("2026-11-13", "2026-11-16"), b.map { it.date })
        assertEquals(839.00, b.first().close, 1e-6)
        assertEquals(332909L, b.first().volume)

        // 空返回、null 返回、垃圾返回都不能抛
        assertTrue(api.parseDailyBars("var t=();").isEmpty())
        assertTrue(api.parseDailyBars("var t=null;").isEmpty())
        assertTrue(api.parseDailyBars("<html>403</html>").isEmpty())
    }

    @Test
    fun `周线月线由日线聚合口径固定`() {
        // 2026-11-09 是周一，13 是周五；16 是下一个周一
        val daily = listOf(
            bar("2026-11-09", 100.0, 104.0, 99.0, 102.0, 10),
            bar("2026-11-10", 102.0, 108.0, 101.0, 106.0, 20),
            bar("2026-11-12", 106.0, 106.5, 95.0, 97.0, 30),
            bar("2026-11-13", 97.0, 99.0, 96.0, 98.0, 40),
            bar("2026-11-16", 98.0, 120.0, 97.0, 118.0, 50)
        )

        val weekly = MetalsRepository.aggregate(daily, MetalsRepository.PERIOD_WEEKLY)
        assertEquals(2, weekly.size)
        val w0 = weekly[0]
        assertEquals("开=组内第一根的开", 100.0, w0.open, 1e-9)
        assertEquals("收=组内最后一根的收", 98.0, w0.close, 1e-9)
        assertEquals(108.0, w0.high, 1e-9)
        assertEquals(95.0, w0.low, 1e-9)
        assertEquals(100L, w0.volume)
        assertEquals("日期取组内最后一个交易日，这样图上那个日期真的有过成交", "2026-11-13", w0.date)
        assertEquals("2026-11-16", weekly[1].date)

        val monthly = MetalsRepository.aggregate(daily, MetalsRepository.PERIOD_MONTHLY)
        assertEquals(1, monthly.size)
        assertEquals(100.0, monthly[0].open, 1e-9)
        assertEquals(118.0, monthly[0].close, 1e-9)
        assertEquals(120.0, monthly[0].high, 1e-9)
        assertEquals(95.0, monthly[0].low, 1e-9)
        assertEquals(150L, monthly[0].volume)
        assertEquals("2026-11-16", monthly[0].date)

        // 日线原样返回，不重新排一遍
        assertEquals(daily, MetalsRepository.aggregate(daily, MetalsRepository.PERIOD_DAILY))
        // 解析不出日期的行丢掉，不猜
        assertEquals(1, MetalsRepository.aggregate(listOf(bar("", 1.0, 1.0, 1.0, 1.0, 1), daily[0]), MetalsRepository.PERIOD_WEEKLY).size)
    }

    @Test
    fun `六条报价四个独立资产单位必须照数据源写`() {
        assertEquals(6, MetalInstrument.ALL.size)
        assertEquals(6, MetalInstrument.ALL.map { it.code }.toSet().size)
        // 金 2 条（境外 + 境内）、银 2 条、铂 1、钯 1 → 独立资产只有 4 个。
        // 这条断言是给"13 个样本 = 13 个观测"那种错误计数兜底的（回测文档 §0 的独立性警告）
        val assets = MetalInstrument.ALL.map { it.symbol.trimStart('X').removeSuffix("0") }.toSet()
        assertEquals(setOf("AU", "AG", "PT", "PD"), assets)
        assertEquals(4, assets.size)

        val usd = MetalInstrument.ALL.filter { it.currency == "USD" }
        assertEquals(listOf("hf_XAU", "hf_XAG", "hf_XPT", "hf_XPD"), usd.map { it.code })
        usd.forEach {
            assertEquals("美元/盎司", it.unit)
            assertEquals("盎司", it.qtyUnit)
        }
        assertEquals("元/克", MetalInstrument.of("nf_AU0")!!.unit)
        assertEquals("克", MetalInstrument.of("nf_AU0")!!.qtyUnit)
        assertEquals("元/千克", MetalInstrument.of("nf_AG0")!!.unit)
        assertEquals("千克", MetalInstrument.of("nf_AG0")!!.qtyUnit)

        // 外盘与境内走的是两个接口族，搞错了就取不到数
        assertEquals(SinaFeed.GLOBAL_FUTURES, MetalInstrument.of("hf_XAU")!!.feed)
        assertEquals(SinaFeed.DOMESTIC_FUTURES, MetalInstrument.of("nf_AU0")!!.feed)
        // 裸 symbol 才是接口参数
        assertEquals("XAU", MetalInstrument.of("hf_XAU")!!.symbol)
        assertEquals("AU0", MetalInstrument.of("nf_AU0")!!.symbol)
        assertNull(MetalInstrument.of("sh600519"))
    }

    @Test
    fun `分类只靠代码前缀老持仓不用迁移`() {
        assertEquals(AssetClass.PRECIOUS_METALS, AssetClass.fromCode("hf_XAU"))
        assertEquals(AssetClass.PRECIOUS_METALS, AssetClass.fromCode("nf_AG0"))
        assertEquals(AssetClass.A_SHARE, AssetClass.fromCode("sh600519"))
        assertEquals(AssetClass.A_SHARE, AssetClass.fromCode("600519"))
        assertEquals(AssetClass.A_SHARE, AssetClass.fromCode(null))
        // 认不出来的 key 一律退回 A 股：这一类才是验证过的那套
        assertEquals(AssetClass.A_SHARE, AssetClass.fromKey("forex"))
        assertEquals("metals", AssetClass.PRECIOUS_METALS.key)

        // 大小写就是接口参数的一部分：转成小写新浪就查不到了，所以一个字符都不动
        assertEquals("hf_XAU", PortfolioViewModel.normalizeStockCode("  hf_XAU  "))
        assertEquals("nf_AG0", PortfolioViewModel.normalizeStockCode("nf_AG0"))
        // A 股那套补前缀的规则不受影响
        assertEquals("sh600519", PortfolioViewModel.normalizeStockCode("600519"))
        assertEquals("bj920001", PortfolioViewModel.normalizeStockCode("920001"))
        // 显示时也不剥 hf_/nf_ 前缀 —— 前缀说的就是哪个市场
        assertEquals("hf_XAU", PortfolioViewModel.displayCode("hf_XAU"))
        assertEquals("600519", PortfolioViewModel.displayCode("sh600519"))
    }

    @Test
    fun `贵金属持仓与A股持仓走不同的链路`() {
        val metal = com.stock.analyzer.data.model.PortfolioStock(
            code = "hf_XAU", name = "伦敦金（现货黄金）", buyPrice = 3000.0,
            quantity = 2, buyDate = 0L
        )
        val stock = com.stock.analyzer.data.model.PortfolioStock(
            code = "sh600519", name = "贵州茅台", buyPrice = 1500.0,
            quantity = 100, buyDate = 0L
        )
        assertTrue(metal.isMetal)
        assertFalse(stock.isMetal)
        // 定价只在自己的币种里算：2 盎司 × 3856.24 美元
        metal.updateCurrentPrice(3856.24)
        assertEquals(1712.48, metal.profit, 0.01)
        assertEquals("USD", metal.metalInstrument?.currency)
        assertNull(stock.metalInstrument)
    }

    private fun bar(date: String, o: Double, h: Double, l: Double, c: Double, v: Long): KLineData =
        KLineData(date = date, open = o, close = c, high = h, low = l, volume = v)
}
