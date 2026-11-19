package com.stock.analyzer.data.model

/**
 * 资产大类。界面上的「A股 / 贵金属 / 大宗商品 / 外汇」切换、持仓里的分类、
 * 详情页要不要显示评分，全部以这个枚举为唯一开关来源 —— 加一个大类就是加一个条目 + 一个 provider，
 * 不是在界面上到处补 if。
 *
 * [supportsAdvice] 是**回测结论**，不是配置项：
 * A 股为 true，是因为 68/42/30 这套阈值在本仓库 `backtest/` 里按"同日横截面选股"验证过；
 * 贵金属 / 大宗商品 / 外汇 为 false，是因为同一套分数在这一类上量不到它要的东西
 * （贵金属的完整证据在 `backtest/metals/README.md`；大宗商品与外汇沿用同一条产品口径：
 * 没有跑过回测的想法不上界面，宁可只给行情、K 线、单位与一个不带方向的风险刻度）。
 *
 * 三类非股票资产共用同一条数据链路（`analysis/NonStockEngine.kt`），区别只在品种目录与数据源：
 * 贵金属、大宗商品走新浪期货（`hf_` 外盘 / `nf_` 境内），外汇走腾讯现汇（`fx_`），
 * 分类不靠前缀猜，靠 [NonStockCatalog] 的精确匹配（`hf_`/`nf_` 在两类里是同一代码空间）。
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
    ),
    COMMODITIES(
        key = "commodities",
        label = "大宗商品",
        supportsAdvice = false,
        scopeNote = "行情 / K线 / 单位，加一个不带方向的风险刻度；不出评分与加减仓区间（未跑回测，不硬给）"
    ),
    FOREX(
        key = "forex",
        label = "外汇",
        supportsAdvice = false,
        scopeNote = "行情 / K线 / 单位，加一个不带方向的风险刻度；不出评分与加减仓区间（未跑回测，不硬给）"
    );

    companion object {
        fun fromKey(key: String?): AssetClass =
            entries.firstOrNull { it.key == key } ?: A_SHARE

        /**
         * 持仓属于哪一类，先查品种目录（`NonStockCatalog`），查不到再按前缀兜底。
         *
         * 为什么不能只靠前缀：`hf_` / `nf_` 在贵金属与大宗商品两类里是**同一个代码空间**
         * （hf_XAU 是贵金属、hf_CL 是大宗商品；nf_AU0 是贵金属、nf_RB0 是大宗商品），
         * 只有 `fx_` 是外汇独有的。目录里查得到就直接返回它自己的大类；
         * 查不到的 `fx_` 认作外汇；查不到的 `hf_`/`nf_` 兜底成贵金属 ——
         * 存量持仓里所有 hf_/nf_ 代码都是 v4.8.0~v4.9.0 存的贵金属，这个兜底就是为它们留的；
         * 其余（sh/sz/bj + 6 位数字）是 A 股。
         */
        fun fromCode(code: String?): AssetClass {
            val c = code?.trim() ?: return A_SHARE
            NonStockCatalog.of(c)?.let { return it.assetClass }
            return when {
                c.startsWith("fx_", ignoreCase = false) -> FOREX
                c.startsWith("hf_", ignoreCase = false) || c.startsWith("nf_", ignoreCase = false) -> PRECIOUS_METALS
                else -> A_SHARE
            }
        }
    }
}

/**
 * 贵金属里可交易的报价品种。
 *
 * [unit] / [unitSymbol] 必须照实显示：XAU/XAG/XPT/XPD 是**美元/盎司**，
 * 沪金 AU0 是**元/克**，沪银 AG0 是**元/千克** —— 这几个数量级差 30 倍，
 * 界面省略单位就等于写错价格。
 *
 * 涨跌口径：外盘现货对昨收（[preCloseLabel] = "昨收"），国内期货对昨结算
 * （结算价才是保证金基准，"昨结算"）。
 */
data class MetalInstrument(
    override val code: String,             // 持久化与路由用的代码，含前缀，如 "hf_XAU"
    override val symbol: String,           // 新浪接口里的裸代码，如 "XAU"
    override val name: String,
    override val feed: SinaFeed,
    override val unit: String,             // "美元/盎司"
    override val unitSymbol: String,       // "USD/oz"
    override val currency: String,         // "USD" / "CNY" —— 持仓盈亏必须按币种分开算
    override val qtyUnit: String,          // 数量单位："盎司" / "克" / "千克"
    override val source: String,           // 数据源说明
    override val preCloseLabel: String,    // "昨收" / "昨结算"
    override val assetClass: AssetClass = AssetClass.PRECIOUS_METALS
) : NonStockInstrument {
    /** 报价接口的 list= 参数 */
    override val quoteKey: String get() = code

    companion object {
        /**
         * 与 `backtest/metals/fetch_metals.py` 里已经跑通的序列一一对应。
         * 上金所现货（Au99.99 等）只有日线历史接口、没有免 Key 的实时报价，
         * 所以实时列表里先不放了 —— 宁缺毋滥，别在界面上摆一个取不到数的行。
         *
         * 顺序是**固定的品种表**（先境外再境内），列表就按这个顺序显示：
         * 这一类不出评分，任何排序都会被读成推荐。
         */
        val ALL: List<MetalInstrument> = listOf(
            MetalInstrument("hf_XAU", "XAU", "伦敦金（现货黄金）", SinaFeed.GLOBAL_FUTURES,
                "美元/盎司", "USD/oz", "USD", "盎司", "新浪财经·外盘现货", "昨收"),
            MetalInstrument("hf_XAG", "XAG", "伦敦银（现货白银）", SinaFeed.GLOBAL_FUTURES,
                "美元/盎司", "USD/oz", "USD", "盎司", "新浪财经·外盘现货", "昨收"),
            MetalInstrument("hf_XPT", "XPT", "纽约铂金", SinaFeed.GLOBAL_FUTURES,
                "美元/盎司", "USD/oz", "USD", "盎司", "新浪财经·外盘期货", "昨收"),
            MetalInstrument("hf_XPD", "XPD", "纽约钯金", SinaFeed.GLOBAL_FUTURES,
                "美元/盎司", "USD/oz", "USD", "盎司", "新浪财经·外盘期货", "昨收"),
            MetalInstrument("nf_AU0", "AU0", "沪金主力（上期所）", SinaFeed.DOMESTIC_FUTURES,
                "元/克", "CNY/g", "CNY", "克", "新浪财经·上期所连续", "昨结算"),
            MetalInstrument("nf_AG0", "AG0", "沪银主力（上期所）", SinaFeed.DOMESTIC_FUTURES,
                "元/千克", "CNY/kg", "CNY", "千克", "新浪财经·上期所连续", "昨结算")
        )

        val BY_CODE: Map<String, MetalInstrument> = ALL.associateBy { it.code }

        fun of(code: String?): MetalInstrument? = BY_CODE[code]
    }
}

/**
 * 持仓属于哪一类，由代码决定。
 *
 * [isNonStock] / [nonStockInstrument] 是新增的通用判据（覆盖三类非股票资产）；
 * [isMetal] / [metalInstrument] 保留给 v4.8.0~v4.9.0 的存量代码与界面，语义等价于
 * "属于贵金属"。老数据里没有分类字段，全部靠 [AssetClass.fromCode] 推断，不用迁移。
 */
val PortfolioStock.assetClass: AssetClass get() = AssetClass.fromCode(code)

val PortfolioStock.isNonStock: Boolean get() = assetClass != AssetClass.A_SHARE

/** 持仓对应的非股票品种信息；A 股持仓返回 null */
val PortfolioStock.nonStockInstrument: NonStockInstrument?
    get() = if (isNonStock) NonStockCatalog.of(code) else null

val PortfolioStock.isMetal: Boolean get() = assetClass == AssetClass.PRECIOUS_METALS

/** 贵金属持仓的计价品种信息；A 股持仓返回 null（等价于 nonStockInstrument 在贵金属上的投影） */
val PortfolioStock.metalInstrument: MetalInstrument?
    get() = if (isMetal) MetalInstrument.of(code) else null

/**
 * 兼容别名：v4.8.0~v4.9.0 全链路叫 [MetalQuote]，v5.0.0 起三类非股票资产共用 [NonStockQuote]。
 * 保留别名让存量引用继续编译，新代码一律用 [NonStockQuote]。
 */
typealias MetalQuote = NonStockQuote

/** 涨跌幅分母的叫法：境内期货对昨结算，外盘现货与现汇对昨收（与 [NonStockInstrument.preCloseLabel] 同口径） */
fun MetalInstrument.settleLabel(): String = preCloseLabel
