package com.stock.analyzer.data

import com.stock.analyzer.data.api.SinaFuturesApi
import com.stock.analyzer.data.api.TencentForexApi
import com.stock.analyzer.data.model.AssetClass
import com.stock.analyzer.data.model.CommodityInstrument
import com.stock.analyzer.data.model.ForexInstrument
import com.stock.analyzer.data.model.NonStockCatalog
import com.stock.analyzer.data.model.NonStockInstrument
import com.stock.analyzer.data.model.SinaFeed
import com.stock.analyzer.data.model.isNonStock
import com.stock.analyzer.ui.portfolio.PortfolioViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 大宗商品与外汇这两路的数据层测试。
 *
 * 报价夹具是 **2026-11-19 从真实接口原样抓回来的行**（新浪期货一行、腾讯现汇一行），不是编的。
 * 之所以要把真实字符串钉在测试里：这一路的字段是"下标"而不是字段名，接口那边挪一位，
 * 界面上就是**另一个数**——而价格错了没人会看出来。
 *
 * 大宗商品复用贵金属那套新浪期货字段布局（外盘 0/4/5/7/12/6、境内 2/3/4/6/10/13/14/17/1），
 * 所以这里重点验证"泛化后没有串位"；外汇是腾讯现汇，字段布局完全不同，单独验证。
 */
class NonStockDataTest {

    // ---- 大宗商品：新浪期货 ----
    private val commodityHfFixture = """
        var hq_str_hf_CL="82.279,,82.320,82.340,83.110,81.690,15:36:18,82.310,81.850,0,9,3,2026-11-19,纽约原油,0";
        var hq_str_hf_HG="576.336,,576.000,576.100,580.200,570.200,15:36:39,575.550,574.800,0,1,4,2026-11-19,美铜,0";
    """.trimIndent()

    private val commodityNfFixture = """
        var hq_str_nf_RB0="螺纹钢连续,150000,2985.000,2993.000,2975.000,2983.000,2982.000,2983.000,2983.000,2983.000,2968.000,4,216,1703132.000,665277,沪,螺纹钢,2026-11-19,1,,,,,,,,,2983.663,2981.000,903,2980.000,1895,2979.000,449,2978.000,1028,2984.000,844,2985.000,1257,2986.000,960,2987.000,839";
        var hq_str_nf_I0="铁矿石连续,150403,648.000,653.000,646.000,648.000,648.000,649.000,648.000,648.500,645.000,288,122,572513.000,245767,连,铁矿石,2026-11-19,1,,,,,,,,,648.649,0.000,0,0.000,0,0.000,0,0.000,0,0.000,0,0.000,0,0.000,0,0.000,0";
    """.trimIndent()

    // ---- 外汇：腾讯现汇 ----
    private val forexFixture = """
        v_whUSDCNY="310~美元人民币~USDCNY~6.6651~0~20261119153830~6.6678~6.6672~6.6675~6.6645~6.6651~6.6652~-0.0027~-0.04~-0.07~-0.04~-0.14~-0.93~-4.60~7.1181~6.6449~2026-11-19";
        v_whEURCNY="310~欧元人民币~EURCNY~7.3144~0~20261119153831~7.3179~7.3185~7.3205~7.3032~7.3144~7.3148~-0.0035~-0.05~-0.82~-2.22~-2.29~-6.92~-10.87~8.3912~7.3018~2026-11-19";
    """.trimIndent()

    private val futuresApi = SinaFuturesApi()
    private val forexApi = TencentForexApi()

    @Test
    fun `大宗商品外盘报价走期货字段布局不串位`() {
        val q = futuresApi.parseQuotes(
            commodityHfFixture,
            listOf(CommodityInstrument.of("hf_CL")!!, CommodityInstrument.of("hf_HG")!!)
        )
        val cl = q["hf_CL"]!!
        assertEquals(82.279, cl.price, 1e-6)
        assertEquals(83.110, cl.high, 1e-6)   // 下标 4
        assertEquals(81.690, cl.low, 1e-6)    // 下标 5
        assertEquals(82.310, cl.preSettle, 1e-6) // 下标 7 昨收
        assertEquals("2026-11-19", cl.quoteDate)
        assertEquals("15:36:18", cl.quoteTime)
        assertFalse("外盘今开没核过，不显示", cl.hasOpen)
        assertFalse("外盘现货无量", cl.hasVolume)

        // 美铜的 1 位是空串：解析必须不被空字段带崩
        val hg = q["hf_HG"]!!
        assertEquals(576.336, hg.price, 1e-6)
        assertEquals(580.200, hg.high, 1e-6)
        assertEquals(570.200, hg.low, 1e-6)
    }

    @Test
    fun `大宗商品境内期货的昨结算与量仓取自对得上的下标`() {
        val q = futuresApi.parseQuotes(
            commodityNfFixture,
            listOf(CommodityInstrument.of("nf_RB0")!!, CommodityInstrument.of("nf_I0")!!)
        )
        val rb = q["nf_RB0"]!!
        assertEquals(2985.0, rb.open, 1e-6)    // 下标 2
        assertEquals(2993.0, rb.high, 1e-6)   // 下标 3
        assertEquals(2975.0, rb.low, 1e-6)    // 下标 4
        assertEquals(2982.0, rb.price, 1e-6)  // 下标 6 最新
        assertEquals(2968.0, rb.preSettle, 1e-6) // 下标 10 昨结算
        assertEquals(665277L, rb.volume)       // 下标 14
        assertEquals(1703132L, rb.openInterest) // 下标 13 持仓
        assertEquals("2026-11-19", rb.quoteDate)
        assertEquals("15:00:00", rb.quoteTime) // 150000 -> 15:00:00

        val iron = q["nf_I0"]!!
        assertEquals(648.0, iron.price, 1e-6)
        assertEquals(645.0, iron.preSettle, 1e-6)
    }

    @Test
    fun `外汇现汇报价按腾讯字段解析`() {
        val q = forexApi.parseQuotes(
            forexFixture,
            listOf(ForexInstrument.of("fx_USDCNY")!!, ForexInstrument.of("fx_EURCNY")!!)
        )
        val usdcny = q["fx_USDCNY"]!!
        assertEquals(6.6651, usdcny.price, 1e-6)        // 下标 3 最新
        assertEquals(6.6678, usdcny.preSettle, 1e-6)   // 下标 6 昨收
        assertEquals(6.6672, usdcny.open, 1e-6)        // 下标 7 今开
        assertEquals(6.6675, usdcny.high, 1e-6)        // 下标 8
        assertEquals(6.6645, usdcny.low, 1e-6)         // 下标 9
        assertEquals(6.6651, usdcny.bid, 1e-6)         // 下标 10
        assertEquals(6.6652, usdcny.ask, 1e-6)         // 下标 11
        assertEquals("2026-11-19", usdcny.quoteDate)
        assertEquals("15:38:30", usdcny.quoteTime)     // 20261119153830 -> 15:38:30
        // 涨跌 = 最新 − 昨收 = 6.6651 − 6.6678 = −0.0027，与字段 12 一致
        assertEquals(-0.0027, usdcny.change, 1e-6)
        assertFalse("现汇无成交量", usdcny.hasVolume)

        val eurusd = q["fx_EURCNY"]!!
        assertEquals(7.3144, eurusd.price, 1e-6)
        assertEquals(7.3179, eurusd.preSettle, 1e-6)
        assertTrue("欧元对人民币是跌的", eurusd.change < 0)
    }

    @Test
    fun `外汇日线按开收高低顺序解析且升序`() {
        // 腾讯日线顺序：[日期, 开, 收, 高, 低, 量]，高在收之后，按 OHLC 解会把高低写反
        val body = """{"code":0,"msg":"","data":{"whUSDCNY":{"day":[
            ["2026-11-18","6.6666","6.6675","6.6694","6.6645","0.0000"],
            ["2026-11-17","6.6660","6.6659","6.6664","6.6650","0.0000"],
            ["2026-11-19","6.6672","6.6661","6.6675","6.6659","0.0000"]],"qt":{}}}}"""
        val bars = forexApi.parseDailyBars(body, "whUSDCNY")
        assertEquals(3, bars.size)
        // 升序
        assertEquals(listOf("2026-11-17", "2026-11-18", "2026-11-19"), bars.map { it.date })
        val last = bars.last()
        assertEquals(6.6672, last.open, 1e-6)   // 开
        assertEquals(6.6661, last.close, 1e-6)  // 收
        assertEquals(6.6675, last.high, 1e-6)   // 高（在收之后）
        assertEquals(6.6659, last.low, 1e-6)    // 低（在最后）
        // 空返回 / 垃圾返回不抛
        assertTrue(forexApi.parseDailyBars("", "whUSDCNY").isEmpty())
        assertTrue(forexApi.parseDailyBars("""{"code":1,"data":null}""", "whUSDCNY").isEmpty())
    }

    @Test
    fun `大宗商品品种目录单位与币种照数据源写`() {
        assertEquals(25, CommodityInstrument.ALL.size)
        // 境外 4 个美元计价，境内 21 个人民币计价
        assertEquals(4, CommodityInstrument.ALL.filter { it.currency == "USD" }.size)
        assertEquals(21, CommodityInstrument.ALL.filter { it.currency == "CNY" }.size)

        // 境外单位
        assertEquals("美元/桶", CommodityInstrument.of("hf_CL")!!.unit)
        assertEquals("USD/bbl", CommodityInstrument.of("hf_CL")!!.unitSymbol)
        assertEquals("美分/磅", CommodityInstrument.of("hf_HG")!!.unit)
        assertEquals("美元/百万英热", CommodityInstrument.of("hf_NG")!!.unit)
        // 境内单位
        assertEquals("元/吨", CommodityInstrument.of("nf_RB0")!!.unit)
        assertEquals("元/桶", CommodityInstrument.of("nf_SC0")!!.unit)
        assertEquals("元/500千克", CommodityInstrument.of("nf_JD0")!!.unit)
        // 数量单位一律「手」
        CommodityInstrument.ALL.forEach {
            assertEquals("手", it.qtyUnit)
            assertEquals("昨结算", it.preCloseLabel)
            assertEquals(AssetClass.COMMODITIES, it.assetClass)
        }
        // 裸 symbol 才是接口参数
        assertEquals("CL", CommodityInstrument.of("hf_CL")!!.symbol)
        assertEquals("RB0", CommodityInstrument.of("nf_RB0")!!.symbol)
    }

    @Test
    fun `外汇品种目录单位读法与币种正确`() {
        assertEquals(8, ForexInstrument.ALL.size)
        // USDCNY：1 美元 = 6.66 人民币，报价币是 CNY，数量单位（基准币）是 USD
        val usdcny = ForexInstrument.of("fx_USDCNY")!!
        assertEquals("CNY/USD", usdcny.unit)
        assertEquals("CNY", usdcny.currency)
        assertEquals("USD", usdcny.qtyUnit)
        assertEquals("昨收", usdcny.preCloseLabel)
        assertEquals(AssetClass.FOREX, usdcny.assetClass)
        // 腾讯接口代码是 wh + symbol，不是 fx_ 前缀
        assertEquals("whUSDCNY", usdcny.quoteKey)
        assertEquals("whEURCNY", ForexInstrument.of("fx_EURCNY")!!.quoteKey)
        // 交叉盘 EURUSD：报价币是 USD
        assertEquals("USD", ForexInstrument.of("fx_EURUSD")!!.currency)
        assertEquals("USD/EUR", ForexInstrument.of("fx_EURUSD")!!.unit)
    }

    @Test
    fun `品种目录把三类合在一起且分类正确`() {
        val all = NonStockCatalog.all
        // 贵金属 6 + 大宗商品 25 + 外汇 8 = 39
        assertEquals(39, all.size)
        assertEquals(39, all.map { it.code }.toSet().size)

        assertEquals(6, NonStockCatalog.forClass(AssetClass.PRECIOUS_METALS).size)
        assertEquals(25, NonStockCatalog.forClass(AssetClass.COMMODITIES).size)
        assertEquals(8, NonStockCatalog.forClass(AssetClass.FOREX).size)

        // hf_/nf_ 在贵金属与大宗商品两类里是同一代码空间，只有 fx_ 是外汇独有
        assertEquals(AssetClass.PRECIOUS_METALS, NonStockCatalog.of("hf_XAU")!!.assetClass)
        assertEquals(AssetClass.COMMODITIES, NonStockCatalog.of("hf_CL")!!.assetClass)
        assertEquals(AssetClass.PRECIOUS_METALS, NonStockCatalog.of("nf_AU0")!!.assetClass)
        assertEquals(AssetClass.COMMODITIES, NonStockCatalog.of("nf_RB0")!!.assetClass)
        assertEquals(AssetClass.FOREX, NonStockCatalog.of("fx_USDCNY")!!.assetClass)
        assertNull(NonStockCatalog.of("sh600519"))
    }

    @Test
    fun `分类只靠代码与目录老持仓不用迁移`() {
        // 目录里能精确匹配的按自身大类
        assertEquals(AssetClass.PRECIOUS_METALS, AssetClass.fromCode("hf_XAU"))
        assertEquals(AssetClass.COMMODITIES, AssetClass.fromCode("hf_CL"))
        assertEquals(AssetClass.COMMODITIES, AssetClass.fromCode("nf_RB0"))
        assertEquals(AssetClass.FOREX, AssetClass.fromCode("fx_USDCNY"))
        // 查不到的 fx_ 认作外汇；查不到的 hf_/nf_ 兜底成贵金属（存量持仓都是 v4.8~v4.9 存的）
        assertEquals(AssetClass.FOREX, AssetClass.fromCode("fx_XXX"))
        assertEquals(AssetClass.PRECIOUS_METALS, AssetClass.fromCode("hf_UNKNOWN"))
        assertEquals(AssetClass.PRECIOUS_METALS, AssetClass.fromCode("nf_UNKNOWN"))
        // A 股不受影响
        assertEquals(AssetClass.A_SHARE, AssetClass.fromCode("sh600519"))
        assertEquals(AssetClass.A_SHARE, AssetClass.fromCode("600519"))
        assertEquals(AssetClass.A_SHARE, AssetClass.fromCode(null))

        // 大小写就是接口参数的一部分：一个字符都不动
        assertEquals("hf_CL", PortfolioViewModel.normalizeStockCode("  hf_CL  "))
        assertEquals("fx_USDCNY", PortfolioViewModel.normalizeStockCode("fx_USDCNY"))
        assertEquals("sh600519", PortfolioViewModel.normalizeStockCode("600519"))
        // 显示时不剥 hf_/nf_/fx_ 前缀
        assertEquals("hf_CL", PortfolioViewModel.displayCode("hf_CL"))
        assertEquals("fx_USDCNY", PortfolioViewModel.displayCode("fx_USDCNY"))
        assertEquals("600519", PortfolioViewModel.displayCode("sh600519"))
    }

    @Test
    fun `非股票持仓与A股持仓走不同的链路`() {
        val metal = com.stock.analyzer.data.model.PortfolioStock(
            code = "hf_XAU", name = "伦敦金（现货黄金）", buyPrice = 3000.0,
            quantity = 2, buyDate = 0L
        )
        val commodity = com.stock.analyzer.data.model.PortfolioStock(
            code = "hf_CL", name = "纽约原油（WTI）", buyPrice = 80.0,
            quantity = 3, buyDate = 0L
        )
        val forex = com.stock.analyzer.data.model.PortfolioStock(
            code = "fx_USDCNY", name = "美元兑人民币（在岸）", buyPrice = 6.5,
            quantity = 1000, buyDate = 0L
        )
        val stock = com.stock.analyzer.data.model.PortfolioStock(
            code = "sh600519", name = "贵州茅台", buyPrice = 1500.0,
            quantity = 100, buyDate = 0L
        )
        assertTrue(metal.isNonStock)
        assertTrue(commodity.isNonStock)
        assertTrue(forex.isNonStock)
        assertFalse(stock.isNonStock)
        // 定价只在自己的币种里算
        metal.updateCurrentPrice(3856.24)
        assertEquals(1712.48, metal.profit, 0.01)
        assertEquals("USD", metal.nonStockInstrument?.currency)
        commodity.updateCurrentPrice(82.279)
        assertEquals(6.837, commodity.profit, 0.01) // (82.279-80)*3
        assertEquals("USD", commodity.nonStockInstrument?.currency)
        forex.updateCurrentPrice(6.6651)
        assertEquals(165.1, forex.profit, 0.01) // (6.6651-6.5)*1000
        assertEquals("CNY", forex.nonStockInstrument?.currency)
        assertNull(stock.nonStockInstrument)
    }

    @Test
    fun `feed 路由正确期货走新浪外汇走腾讯`() {
        assertEquals(SinaFeed.GLOBAL_FUTURES, CommodityInstrument.of("hf_CL")!!.feed)
        assertEquals(SinaFeed.DOMESTIC_FUTURES, CommodityInstrument.of("nf_RB0")!!.feed)
        assertEquals(SinaFeed.FOREX, ForexInstrument.of("fx_USDCNY")!!.feed)
        // 非外汇品种 quoteKey 就是 code，外汇是 wh+symbol
        val inst: NonStockInstrument = NonStockCatalog.of("fx_USDCNY")!!
        assertEquals("whUSDCNY", inst.quoteKey)
    }
}
