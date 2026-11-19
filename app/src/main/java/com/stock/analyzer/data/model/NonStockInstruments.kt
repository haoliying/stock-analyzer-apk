package com.stock.analyzer.data.model

import java.util.Locale

/**
 * 非股票资产（贵金属 / 大宗商品 / 外汇）的报价接口族。
 *
 * [GLOBAL_FUTURES] 与 [DOMESTIC_FUTURES] 走新浪那一路（`hf_` 外盘、`nf_` 境内，免 Key），
 * [FOREX] 走腾讯那一路（`wh_` 现汇）：新浪的外汇日线接口（`NewForexService.getDayKLine`）
 * 2026-11-19 实测对任何符号都只回 `data is empty`（USDCNY 更是直接 PHP 报错），
 * 而腾讯 `qt.gtimg.cn` 的现汇报价与 `web.ifzq.gtimg.cn` 的日线都是通的、且两边字段能互相核对，
 * 所以外汇这一类整条链路用腾讯，**不与新浪那一路混用**，避免报价与 K 线来自两个没核对过的源。
 */
enum class SinaFeed { GLOBAL_FUTURES, DOMESTIC_FUTURES, FOREX }

/**
 * 一条非股票资产的实时报价（与原 `MetalQuote` 同构，只是改了名字：
 * 这一路现在承载三类资产，名字里再挂"金属"就名不副实了）。
 *
 * [quoteDate] / [quoteTime] 是**数据源自己那一市场的日期与时间**，不是手机本地时间。
 * 境外那一戳是境外交易日，境内是境内交易日，两者经常错开一天（夜盘还挂在下一个交易日上）。
 * 注意：**错开一天不等于谁领先谁** —— 界面上以前那句「corr(伦敦金今日, 沪金次日)=0.66
 * 所以下一天跟涨」是按重叠口径讲的，已经撤掉（v4.9.0，依据 backtest/metals/README.md §5）。
 * 保留的操作结论只有一条：两个「今日价」不能直接相减算溢价，必须各看各的时间戳。
 */
data class NonStockQuote(
    val code: String,
    val name: String,
    val unit: String,
    val unitSymbol: String,
    val source: String,
    val price: Double,
    val preSettle: Double,     // 昨结算 / 昨收（见品种的 preCloseLabel，期货与现汇的口径不同）
    val open: Double,
    val high: Double,
    val low: Double,
    val bid: Double,
    val ask: Double,
    val volume: Long,
    val openInterest: Long,
    val quoteDate: String,
    val quoteTime: String
) {
    val change: Double get() = if (preSettle > 0) price - preSettle else 0.0
    val changePercent: Double get() = if (preSettle > 0) (price / preSettle - 1.0) * 100 else 0.0
    val isRise: Boolean get() = change >= 0

    /** 缺字段就整行不显示，不给它印个 0 冒充数据（外盘现货、现汇都没有成交量） */
    val hasVolume: Boolean get() = volume > 0
    val hasOpen: Boolean get() = open > 0
}

/**
 * 非股票资产里一个可报价的品种。
 *
 * 三类资产共用这一个接口：[code] 是持久化与路由用的代码，[symbol]/[feed] 是数据源参数，
 * [unit] / [unitSymbol] 必须照实显示（数量级差几十倍的品种并排，省略单位就等于写错价格），
 * [currency] 决定持仓盈亏按哪个币种算。
 *
 * [preCloseLabel] 是涨跌幅分母的叫法：境内期货对昨结算（结算价才是保证金基准），
 * 外盘现货与现汇对昨收。
 */
interface NonStockInstrument {
    val code: String             // 持久化与路由用的代码，含前缀，如 "hf_XAU" / "fx_USDCNY"
    val symbol: String           // 数据源里的裸代码，如 "XAU" / "RB0" / "USDCNY"
    val name: String
    val feed: SinaFeed
    val unit: String             // "美元/盎司" / "元/吨" / "CNY/USD"
    val unitSymbol: String       // "USD/oz" / "CNY/t" / "CNY/USD"
    val currency: String         // "USD" / "CNY" / "JPY" —— 持仓盈亏必须按币种分开算
    val qtyUnit: String          // 数量单位："盎司" / "手" / "USD"
    val source: String           // 数据源说明
    val preCloseLabel: String    // 涨跌幅分母的叫法："昨收" / "昨结算"
    val assetClass: AssetClass

    /** 报价接口里的完整代码（外汇那一路是腾讯的 whXXX，与 code 的 fx_ 前缀不同） */
    val quoteKey: String

    /** 界面上一行价格的写法：数字不会自己说明单位，4000 和 400 差着数量级 */
    fun priceText(v: Double): String = String.format("%,.2f %s", v, unitSymbol)

    val currencySymbol: String get() = when (currency) {
        "USD" -> "$"; "CNY" -> "¥"; "JPY" -> "¥"; "CHF" -> "Fr "; "CAD" -> "C$"; else -> "$currency "
    }
}

/**
 * 大宗商品里可交易的报价品种。
 *
 * 全部是**期货**（外盘商品期货 + 国内商品期货连续合约），所以数量单位一律是「手」，
 * 涨跌幅分母一律是「昨结算」—— 与贵金属里那两条现汇品种（XAU/XAG 对昨收）不同，
 * 差异写在每个品种自己的 [preCloseLabel] 上，不靠前缀猜。
 *
 * 顺序是**固定的品种表**（先境外能化/金属，再境内黑色/能化/农产品），列表就按这个顺序显示：
 * 这一类不出评分，任何排序都会被读成推荐。
 */
data class CommodityInstrument(
    override val code: String,
    override val symbol: String,
    override val name: String,
    override val feed: SinaFeed,
    override val unit: String,
    override val unitSymbol: String,
    override val currency: String,
    override val qtyUnit: String,
    override val source: String,
    override val preCloseLabel: String = "昨结算",
    override val assetClass: AssetClass = AssetClass.COMMODITIES
) : NonStockInstrument {
    override val quoteKey: String get() = code

    companion object {
        /**
         * 与 `backtest/` 的取数口径一致：全部是新浪免 Key 接口，
         * 字段下标 2026-11-19 逐只对着日线核过（与贵金属同一套路：外盘 0/4/5/7/12/6，
         * 境内 2/3/4/6/10/13/14/17/1）。
         *
         * 境外只放能化与铜：黄金/白银的期货合约（GC/SI）与贵金属那一类里的现货品种是
         * 同一样东西的另一个合约，并排放只会让人当成两个可分散的标的，所以不放在这里。
         */
        val ALL: List<CommodityInstrument> = listOf(
            // ── 境外（美元计价，外盘期货）──
            CommodityInstrument("hf_CL", "CL", "纽约原油（WTI）", SinaFeed.GLOBAL_FUTURES,
                "美元/桶", "USD/bbl", "USD", "手", "新浪财经·NYMEX 连续"),
            CommodityInstrument("hf_OIL", "OIL", "布伦特原油", SinaFeed.GLOBAL_FUTURES,
                "美元/桶", "USD/bbl", "USD", "手", "新浪财经·外盘期货"),
            CommodityInstrument("hf_NG", "NG", "美国天然气", SinaFeed.GLOBAL_FUTURES,
                "美元/百万英热", "USD/MMBtu", "USD", "手", "新浪财经·NYMEX 连续"),
            CommodityInstrument("hf_HG", "HG", "美铜（COMEX）", SinaFeed.GLOBAL_FUTURES,
                "美分/磅", "¢/lb", "USD", "手", "新浪财经·COMEX 连续"),
            // ── 境内（人民币计价，国内期货连续合约）──
            CommodityInstrument("nf_RB0", "RB0", "螺纹钢主力（上期所）", SinaFeed.DOMESTIC_FUTURES,
                "元/吨", "CNY/t", "CNY", "手", "新浪财经·上期所连续"),
            CommodityInstrument("nf_I0", "I0", "铁矿石主力（大商所）", SinaFeed.DOMESTIC_FUTURES,
                "元/吨", "CNY/t", "CNY", "手", "新浪财经·大商所连续"),
            CommodityInstrument("nf_CU0", "CU0", "沪铜主力（上期所）", SinaFeed.DOMESTIC_FUTURES,
                "元/吨", "CNY/t", "CNY", "手", "新浪财经·上期所连续"),
            CommodityInstrument("nf_SC0", "SC0", "上海原油主力（上期所）", SinaFeed.DOMESTIC_FUTURES,
                "元/桶", "CNY/bbl", "CNY", "手", "新浪财经·上期所连续"),
            CommodityInstrument("nf_RU0", "RU0", "天然橡胶主力（上期所）", SinaFeed.DOMESTIC_FUTURES,
                "元/吨", "CNY/t", "CNY", "手", "新浪财经·上期所连续"),
            CommodityInstrument("nf_MA0", "MA0", "甲醇主力（郑商所）", SinaFeed.DOMESTIC_FUTURES,
                "元/吨", "CNY/t", "CNY", "手", "新浪财经·郑商所连续"),
            CommodityInstrument("nf_FG0", "FG0", "玻璃主力（郑商所）", SinaFeed.DOMESTIC_FUTURES,
                "元/吨", "CNY/t", "CNY", "手", "新浪财经·郑商所连续"),
            CommodityInstrument("nf_SA0", "SA0", "纯碱主力（郑商所）", SinaFeed.DOMESTIC_FUTURES,
                "元/吨", "CNY/t", "CNY", "手", "新浪财经·郑商所连续"),
            CommodityInstrument("nf_PK0", "PK0", "花生主力（郑商所）", SinaFeed.DOMESTIC_FUTURES,
                "元/吨", "CNY/t", "CNY", "手", "新浪财经·郑商所连续"),
            CommodityInstrument("nf_LH0", "LH0", "生猪主力（大商所）", SinaFeed.DOMESTIC_FUTURES,
                "元/吨", "CNY/t", "CNY", "手", "新浪财经·大商所连续"),
            CommodityInstrument("nf_AP0", "AP0", "苹果主力（郑商所）", SinaFeed.DOMESTIC_FUTURES,
                "元/吨", "CNY/t", "CNY", "手", "新浪财经·郑商所连续"),
            CommodityInstrument("nf_C0", "C0", "玉米主力（大商所）", SinaFeed.DOMESTIC_FUTURES,
                "元/吨", "CNY/t", "CNY", "手", "新浪财经·大商所连续"),
            CommodityInstrument("nf_JD0", "JD0", "鸡蛋主力（大商所）", SinaFeed.DOMESTIC_FUTURES,
                "元/500千克", "CNY/500kg", "CNY", "手", "新浪财经·大商所连续"),
            CommodityInstrument("nf_SR0", "SR0", "白糖主力（郑商所）", SinaFeed.DOMESTIC_FUTURES,
                "元/吨", "CNY/t", "CNY", "手", "新浪财经·郑商所连续"),
            CommodityInstrument("nf_CF0", "CF0", "棉花主力（郑商所）", SinaFeed.DOMESTIC_FUTURES,
                "元/吨", "CNY/t", "CNY", "手", "新浪财经·郑商所连续"),
            CommodityInstrument("nf_CJ0", "CJ0", "红枣主力（郑商所）", SinaFeed.DOMESTIC_FUTURES,
                "元/吨", "CNY/t", "CNY", "手", "新浪财经·郑商所连续"),
            CommodityInstrument("nf_OI0", "OI0", "菜油主力（郑商所）", SinaFeed.DOMESTIC_FUTURES,
                "元/吨", "CNY/t", "CNY", "手", "新浪财经·郑商所连续"),
            CommodityInstrument("nf_M0", "M0", "豆粕主力（大商所）", SinaFeed.DOMESTIC_FUTURES,
                "元/吨", "CNY/t", "CNY", "手", "新浪财经·大商所连续"),
            CommodityInstrument("nf_Y0", "Y0", "豆油主力（大商所）", SinaFeed.DOMESTIC_FUTURES,
                "元/吨", "CNY/t", "CNY", "手", "新浪财经·大商所连续"),
            CommodityInstrument("nf_A0", "A0", "豆一主力（大商所）", SinaFeed.DOMESTIC_FUTURES,
                "元/吨", "CNY/t", "CNY", "手", "新浪财经·大商所连续"),
            CommodityInstrument("nf_P0", "P0", "棕榈油主力（大商所）", SinaFeed.DOMESTIC_FUTURES,
                "元/吨", "CNY/t", "CNY", "手", "新浪财经·大商所连续")
        )

        val BY_CODE: Map<String, CommodityInstrument> = ALL.associateBy { it.code }

        fun of(code: String?): CommodityInstrument? = BY_CODE[code]
    }
}

/**
 * 外汇里可报价的货币对（现汇）。
 *
 * [code] 用 `fx_` 前缀（如 `fx_USDCNY`）做本应用内的路由与持久化代码，
 * [quoteKey] 是腾讯接口要的 `whUSDCNY` —— 前缀是这一路与新浪那一路的区分，
 * 大小写就是接口参数本身，任何归一化都不能动它。
 *
 * [unit] / [unitSymbol] 的读法是「1 个基准币 = 多少报价币」：
 * USDCNY 的价格 6.66 就是 1 美元兑 6.66 元人民币，所以单位写作 "CNY/USD"。
 * [currency] 是**报价币**（盈亏按它算），[qtyUnit] 是**基准币**（持仓数量按它填）。
 *
 * 涨跌幅分母是「昨收」：现汇没有结算价，腾讯那一路字段 6 就是昨收
 * （与涨跌幅字段 12 核对过：latest − 字段 6 = 字段 12）。
 */
data class ForexInstrument(
    override val code: String,
    override val symbol: String,
    override val name: String,
    override val feed: SinaFeed,
    override val unit: String,
    override val unitSymbol: String,
    override val currency: String,
    override val qtyUnit: String,
    override val source: String,
    override val preCloseLabel: String = "昨收",
    override val assetClass: AssetClass = AssetClass.FOREX
) : NonStockInstrument {
    override val quoteKey: String get() = "wh" + symbol

    companion object {
        /**
         * 8 个主要货币对：2 个人民币（在岸 USDCNY、欧元交叉 EURCNY）+ 6 个美元直盘/交叉。
         * 顺序固定，列表按这个顺序显示 —— 这一类不出评分，任何排序都会被读成推荐。
         * 离岸人民币（USDCNH）在腾讯这一路实测没有（qt 返回空行），所以不放：
         * 宁缺毋滥，别在界面上摆一个取不到数的行。
         */
        val ALL: List<ForexInstrument> = listOf(
            ForexInstrument("fx_USDCNY", "USDCNY", "美元兑人民币（在岸）", SinaFeed.FOREX,
                "CNY/USD", "CNY/USD", "CNY", "USD", "腾讯财经·现汇"),
            ForexInstrument("fx_EURCNY", "EURCNY", "欧元兑人民币", SinaFeed.FOREX,
                "CNY/EUR", "CNY/EUR", "CNY", "EUR", "腾讯财经·现汇"),
            ForexInstrument("fx_USDJPY", "USDJPY", "美元兑日元", SinaFeed.FOREX,
                "JPY/USD", "JPY/USD", "JPY", "USD", "腾讯财经·现汇"),
            ForexInstrument("fx_USDCHF", "USDCHF", "美元兑瑞士法郎", SinaFeed.FOREX,
                "CHF/USD", "CHF/USD", "CHF", "USD", "腾讯财经·现汇"),
            ForexInstrument("fx_GBPUSD", "GBPUSD", "英镑兑美元", SinaFeed.FOREX,
                "USD/GBP", "USD/GBP", "USD", "GBP", "腾讯财经·现汇"),
            ForexInstrument("fx_EURUSD", "EURUSD", "欧元兑美元", SinaFeed.FOREX,
                "USD/EUR", "USD/EUR", "USD", "EUR", "腾讯财经·现汇"),
            ForexInstrument("fx_AUDUSD", "AUDUSD", "澳元兑美元", SinaFeed.FOREX,
                "USD/AUD", "USD/AUD", "USD", "AUD", "腾讯财经·现汇"),
            ForexInstrument("fx_USDCAD", "USDCAD", "美元兑加元", SinaFeed.FOREX,
                "CAD/USD", "CAD/USD", "CAD", "USD", "腾讯财经·现汇")
        )

        val BY_CODE: Map<String, ForexInstrument> = ALL.associateBy { it.code }

        fun of(code: String?): ForexInstrument? = BY_CODE[code]
    }
}

/**
 * 非股票资产的**总品种目录**：三类资产的品种表合在一处，是"代码 → 属于哪一类"的唯一判据。
 *
 * 为什么 [AssetClass.fromCode] 不能只靠前缀：`hf_` / `nf_` 在贵金属与大宗商品两类里
 * 是**同一个代码空间**（hf_XAU 是贵金属、hf_CL 是大宗商品；nf_AU0 是贵金属、nf_RB0 是大宗商品），
 * 只有 `fx_` 前缀是外汇独有的。所以先查目录、查不到再按前缀兜底成贵金属
 * （存量持仓里所有 hf_/nf_ 代码都是 v4.8.0~v4.9.0 存的贵金属，这个兜底就是为它们留的）。
 */
object NonStockCatalog {

    val all: List<NonStockInstrument> =
        MetalInstrument.ALL + CommodityInstrument.ALL + ForexInstrument.ALL

    private val byCode: Map<String, NonStockInstrument> = all.associateBy { it.code }

    /** 精确匹配（大小写敏感：hf_XAU 与 hf_xau 是两个世界，接口参数一个字符都不能动） */
    fun of(code: String?): NonStockInstrument? = byCode[code]

    /** 某一大类下的全部品种（行情列表、添加持仓弹窗都从这里取，不各自维护一份表） */
    fun forClass(assetClass: AssetClass): List<NonStockInstrument> =
        all.filter { it.assetClass == assetClass }
}
