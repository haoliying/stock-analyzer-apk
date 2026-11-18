package com.stock.analyzer.data.model

/**
 * 资产大类。界面上的「A股 / 贵金属」切换、持仓里的分类、详情页要不要显示评分，
 * 全部以这个枚举为唯一开关来源 —— 以后加大宗商品、外汇是加一个条目 + 一个 provider，
 * 不是在界面上到处补 if。
 *
 * [supportsAdvice] 是**回测结论**，不是配置项：
 * A 股为 true，是因为 68/42/30 这套阈值在本仓库 `backtest/` 里按"同日横截面选股"验证过；
 * 贵金属为 false，是因为同一套分数在 `backtest/metals/README.md` 里被证明
 * 量的是另一种东西（择时每笔超额 0/13 为正、CI [−0.56%, −0.29%]；低档绝对收益为正 11/13；
 * AUC 0.42~0.58；ATR 惩罚项在黄金上几乎从不触发）。
 * 后来又换了一套**属于贵金属自己的**口径重跑（iter9~iter12：趋势/突破/止损/回调/带宽/波动目标，
 * 加上跨市场的领先关系与价差偏离），20 个择时配置 0 个通过判据 —— 所以"换一种方式"也没有给出建议。
 * 唯一在样本内外都稳定成立的是波动目标对**回撤**的作用（收益那条 CI 跨着 0），
 * 因此这一类显示一个不带方向的风险刻度
 * （`analysis/MetalsRisk.kt`），其余仍只有行情、K 线、单位，没有评分、没有加减仓区间、不进今日候选池。
 */
enum class AssetClass(
    val key: String,
    val label: String,
    val supportsAdvice: Boolean,
    /** 界面顶部一句话说明：这一类里能看到什么、看不到什么 */
    val scopeNote: String
) {
    A_SHARE(
        key = "a_share",
        label = "A股",
        supportsAdvice = true,
        scopeNote = "评分与 68/42 区间是同日横截面选股口径，不是择时信号"
    ),
    PRECIOUS_METALS(
        key = "metals",
        label = "贵金属",
        supportsAdvice = false,
        scopeNote = "行情 / K线 / 单位，加一个不带方向的风险刻度；不出评分与加减仓区间（回测未通过）"
    );

    companion object {
        fun fromKey(key: String?): AssetClass =
            entries.firstOrNull { it.key == key } ?: A_SHARE

        /**
         * 老数据里没有分类字段，用代码前缀兜底：
         * 新浪外盘 `hf_`、国内期货 `nf_` 一定是贵金属，其余（sh/sz/bj + 6 位数字）是 A 股。
         */
        fun fromCode(code: String?): AssetClass {
            val c = code?.trim()?.lowercase() ?: return A_SHARE
            return if (c.startsWith("hf_") || c.startsWith("nf_")) PRECIOUS_METALS else A_SHARE
        }
    }
}

/** 新浪贵金属的报价接口族：外盘用 hf_（含自己的收盘时间），国内期货用 nf_（含夜盘） */
enum class SinaFeed { GLOBAL_FUTURES, DOMESTIC_FUTURES }

/**
 * 贵金属里可交易的报价品种。
 *
 * [unit] / [unitSymbol] 必须照实显示：XAU/XAG/XPT/XPD 是**美元/盎司**，
 * 沪金 AU0 是**元/克**，沪银 AG0 是**元/千克** —— 这几个数量级差 30 倍，
 * 界面省略单位就等于写错价格。
 */
data class MetalInstrument(
    val code: String,             // 持久化与路由用的代码，含前缀，如 "hf_XAU"
    val symbol: String,           // 新浪接口里的裸代码，如 "XAU"
    val name: String,
    val feed: SinaFeed,
    val unit: String,             // "美元/盎司"
    val unitSymbol: String,       // "USD/oz"
    val currency: String,         // "USD" / "CNY" —— 持仓盈亏必须按币种分开算
    val qtyUnit: String,          // 数量单位："盎司" / "克" / "千克"
    val source: String            // 数据源说明
) {
    /** 报价接口的 list= 参数 */
    val quoteKey: String get() = code

    /** 界面上一行价格的写法：数字不会自己说明单位，4000 和 400 差着数量级 */
    fun priceText(v: Double): String = String.format("%,.2f %s", v, unitSymbol)

    val currencySymbol: String get() = when (currency) {
        "USD" -> "$"; "CNY" -> "¥"; else -> "$currency "
    }

    companion object {
        /**
         * 与 `backtest/metals/fetch_metals.py` 里已经跑通的序列一一对应。
         * 上金所现货（Au99.99 等）只有日线历史接口、没有免 Key 的实时报价，
         * 所以实时列表里先不放了 —— 宁缺毋滥，别在界面上摆一个取不到数的行。
         *
         * 顺序是**固定的品种表**（先境外再境内），列表就按这个顺序显示：
         * 贵金属这一类不出评分，任何排序都会被读成推荐。
         */
        val ALL: List<MetalInstrument> = listOf(
            MetalInstrument("hf_XAU", "XAU", "伦敦金（现货黄金）", SinaFeed.GLOBAL_FUTURES,
                "美元/盎司", "USD/oz", "USD", "盎司", "新浪财经·外盘现货"),
            MetalInstrument("hf_XAG", "XAG", "伦敦银（现货白银）", SinaFeed.GLOBAL_FUTURES,
                "美元/盎司", "USD/oz", "USD", "盎司", "新浪财经·外盘现货"),
            MetalInstrument("hf_XPT", "XPT", "纽约铂金", SinaFeed.GLOBAL_FUTURES,
                "美元/盎司", "USD/oz", "USD", "盎司", "新浪财经·外盘期货"),
            MetalInstrument("hf_XPD", "XPD", "纽约钯金", SinaFeed.GLOBAL_FUTURES,
                "美元/盎司", "USD/oz", "USD", "盎司", "新浪财经·外盘期货"),
            MetalInstrument("nf_AU0", "AU0", "沪金主力（上期所）", SinaFeed.DOMESTIC_FUTURES,
                "元/克", "CNY/g", "CNY", "克", "新浪财经·上期所连续"),
            MetalInstrument("nf_AG0", "AG0", "沪银主力（上期所）", SinaFeed.DOMESTIC_FUTURES,
                "元/千克", "CNY/kg", "CNY", "千克", "新浪财经·上期所连续")
        )

        val BY_CODE: Map<String, MetalInstrument> = ALL.associateBy { it.code }

        fun of(code: String?): MetalInstrument? = BY_CODE[code]
    }
}

/**
 * 一条贵金属实时报价。
 *
 * [quoteDate] / [quoteTime] 是**数据源自己那一市场的日期与时间**，不是手机本地时间。
 * 境外那一戳是境外交易日，境内是境内交易日，两者经常错开一天（夜盘还挂在下一个交易日上）。
 * 注意：**错开一天不等于谁领先谁** —— 这个滞后在 iter10 里量过，重叠口径 IC +0.53~+0.66
 * 全是同一段行情被两条收盘各记了一次，干净口径 IC 只剩 −0.04~+0.02。
 * 界面上以前那句「corr(伦敦金今日, 沪金次日)=0.66 所以下一天跟涨」是按重叠口径讲的，已经改掉。
 * 保留的结论只有一条：两个「今日价」不能直接相减算溢价，必须各看各的时间戳。
 */
data class MetalQuote(
    val code: String,
    val name: String,
    val unit: String,
    val unitSymbol: String,
    val source: String,
    val price: Double,
    val preSettle: Double,     // 昨结算（涨跌幅用它算，期货的口径与股票不同）
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

    /** 缺字段就整行不显示，不给它印个 0 冒充数据（外盘现货没有成交量） */
    val hasVolume: Boolean get() = volume > 0
    val hasOpen: Boolean get() = open > 0
}

/** 涨跌幅的分母叫什么：境外对昨收，境内期货对昨结算（结算价才是保证金基准） */
fun MetalInstrument.settleLabel(): String =
    if (feed == SinaFeed.DOMESTIC_FUTURES) "昨结算" else "昨收"

/**
 * 持仓属于哪一类，由代码前缀决定 —— 存量数据里没有一个 hf_/nf_ 开头的 A 股代码，
 * 所以不用给 PortfolioStock 加字段、也不用迁移老数据。
 */
val PortfolioStock.assetClass: AssetClass get() = AssetClass.fromCode(code)

val PortfolioStock.isMetal: Boolean get() = assetClass == AssetClass.PRECIOUS_METALS

/** 贵金属持仓的计价品种信息；A 股持仓返回 null */
val PortfolioStock.metalInstrument: MetalInstrument?
    get() = if (isMetal) MetalInstrument.of(code) else null
