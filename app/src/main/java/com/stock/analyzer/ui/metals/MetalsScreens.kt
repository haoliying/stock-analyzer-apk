package com.stock.analyzer.ui.metals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stock.analyzer.analysis.MetalsEngine
import com.stock.analyzer.data.model.MetalInstrument
import com.stock.analyzer.data.model.MetalQuote
import com.stock.analyzer.data.model.settleLabel
import com.stock.analyzer.ui.asset.AssetClassSwitcher
import java.util.Locale
import kotlin.math.abs

/**
 * 贵金属这一类的解释文案，只在这里写一遍。
 *
 * 今日候选、行情、详情页、持仓页都从这里取，免得四个页面各说一套。
 * 界面上说「出厂那套分数在贵金属上回测没过」「换一种方式也没过」，就必须给出可核对的数字，
 * 依据在仓库 `backtest/metals/README.md`（iter1~iter12 的原始输出都在仓库里，
 * 包括 iter9 第一版那个把仓位写成赚当天收益的错跑 —— 两份输出都留着）。
 */
object MetalsCopy {

    /** 为什么这一类没有评分、没有加减仓区间 */
    val noAdvice: List<String> = listOf(
        "A 股那一套分数（130 根日线、68 加仓线 / 42 减仓线）在这里一字未改地跑过一遍回测，" +
            "结论是不能用：13 条报价序列里每笔超额收益为正的有 0 条，中位 −0.42%，" +
            "95% 置信区间整段在零下 [−0.56%, −0.29%]。",
        "更麻烦的是方向反了：分数最低那一档（≤30）的 20 日绝对收益为正的有 11/13 个标的 —— " +
            "按 A 股的读法它该叫「强烈减仓」，实测却是这里最不容易亏的一档。",
        "也不是分数够不着：贵金属停在 68 以上的日子占 19%~32%，每年跨线 11.5~17.0 次。" +
            "一条几乎每周都被跨过去、又常年停在上面的线，不构成动作。",
        "评分里唯一的惩罚项（日波幅超过 2.0% 才扣分）在黄金上几乎从不触发" +
            "（黄金类日波幅中位 1.30%，A 股是 3.05%）—— 分差几乎全来自趋势结构，" +
            "而趋势结构在贵金属上量不到 A 股那套「同日横截面选股」要的那个东西。",
        "换一套属于贵金属自己的口径也试过：均线在场、双均线、时序动量、波动率目标、" +
            "唐奇安突破、ATR 跟踪止损、趋势内回调、带宽阶梯，8 族 20 个择时配置，判据在跑之前写死 —— " +
            "20 个里 0 个通过，6 个配置的日超额 95%CI 整段在零下。" +
            "（预注册那份表头写的「7 族 21 个配置」不准：21 是把「一直持有」那个基准也数进去了，族数漏了一族；" +
            "还有第一遍跑的时候我把仓位错写成赚当天那一段收益，等于偷看一天，数字好看得不可能 —— " +
            "这两条都记在仓库的犯错清单里，改完前后的两份输出都留着。）",
        "跨市场那条路也试过：外盘收盘对境内的领先关系、境内外溢价偏离常态就减半仓、金银比偏离常态就减半仓。" +
            "按信号真正到账的时间算，没有一条跑过一直持有：外盘涨就满仓、跌就空仓这一族 6 个配置，" +
            "日超额 95%CI 整段在零下；溢价减半仓在 6 组配对 × 2 个窗口共 12 个配置上也一样整段在零下；" +
            "金银比那条只有金的 120 日窗口显著为负（[−4.0, −0.6]bp），其余跨着 0。",
        "所以这一类到今天还是不出评分、不出加减仓区间。页面上只留两个不带方向的东西：" +
            "波动折算出来的仓位刻度（它只改变回撤，不改变收益）和相对偏离读数" +
            "（它只说现在处在自己历史的哪一段）。依据：仓库 backtest/metals/README.md 的 iter9~iter12。"
    )

    /** 看这些价格时必须知道的三件事 */
    val readingRules: List<String> = listOf(
        "单位各不相同：伦敦金 / 银 / 铂 / 钯是美元/盎司，沪金是元/克，沪银是元/千克。" +
            "同一个数字换个单位能差 30 倍，所以每一行都必须带着自己的单位。",
        "这 6 条报价其实只有 4 个独立资产（金、银、铂、钯）。伦敦金和沪金是同一样东西在两个市场的价格，" +
            "并排放是给人当参照，不是两只可以分散的标的。",
        "两个市场的交易日经常错开一天，但别把这种错开读成谁领先谁。" +
            "这一版界面以前写过「corr(伦敦金今日, 沪金次日)=0.66，而 corr(今日, 今日)=0.28」——那句是错的：" +
            "那个 +0.59~+0.66 量的是同一个夜晚的行情被两条收盘价各记了一次（窗口重叠）。" +
            "把信号按它真正到账的时间挪开（外盘收盘约在境内次日凌晨 5 点），" +
            "IC 只剩 −0.04 到 −0.01；6 组配对合起来，「外盘涨 vs 外盘跌」对境内下一窗的差是 −1.8bp。" +
            "所以两个「今日价」不能直接相减算溢价，只能各看各的时间戳。"
    )

    /** 「风险参考」那张卡下面必须跟的一句：它改的是回撤，不是收益 */
    val riskCardNote: List<String> = listOf(
        "波动折算出来的那个仓位只改变了回撤，没有改变收益：4 个独立资产在 2021 年以后和 2021 年以前" +
            "的最大回撤都比一直持有小（平均小 15~31 个百分点），但日超额的 95% 置信区间跨着 0" +
            "（15% 目标那一档：−1.1bp [−3.9, +1.0]），夏普 4 个资产里只有 1 个够得上" +
            "「比一直持有高 0.15」。",
        "所以它不是「该拿多少仓位」，是「这一类现在的波动有多大、按一个固定波动目标折算会挪到哪」。" +
            "目标 15%、5% 一档取整、10% 以内不动、下限 25% —— 这四条就是回测里跑过的那一套，" +
            "界面上的数与回测里的数是同一个算法（单测拿 Python 生成的夹具把关）。",
        "比值与溢价那几个偏离读数只说「相对自己 120 日的常态偏了多远」，它猜不动接下来往哪走：" +
            "拿金银比做减仓（比值高出常态 0.5σ 就只留半仓），干净滞后下金的夏普 0.56、" +
            "一直持有 0.74，日超额 95%CI [−4.0, −0.6]bp 整段在零下。" +
            "境内那一行还是不同币种（元/克 对 美元/盎司），看的是偏离常态多少个 σ，不是溢价了多少元。"
    )

    fun price(v: Double): String = String.format(Locale.US, "%,.2f", v)

    fun pct(v: Double): String = String.format(Locale.US, "%+.2f%%", v)

    fun upDown(rise: Boolean): Color = if (rise) COLOR_UP else COLOR_DOWN

    val COLOR_UP = Color(0xFFE53935)
    val COLOR_DOWN = Color(0xFF43A047)
}

/** 一段说明文字卡：贵金属那几个「为什么」都用它排版 */
@Composable
fun MetalsNoteCard(
    title: String,
    lines: List<String>,
    accent: Color = MaterialTheme.colorScheme.error
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accent)
            lines.forEach {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 引擎那一行：报价取于几点、下一次什么时候刷、收市了为什么不刷 */
@Composable
fun MetalsEngineLine(viewModel: MetalsViewModel) {
    val status by viewModel.status.collectAsState()
    val fetchedAt by viewModel.fetchedAt.collectAsState()
    val error by viewModel.error.collectAsState()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        color = if (error != null) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = listOfNotNull(MetalsEngine.statusLine(status, fetchedAt), error)
                .joinToString("\n"),
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = if (error != null) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

/**
 * 行情页（贵金属）：一列报价。
 *
 * 顺序永远是 [MetalInstrument.ALL] 那张固定品种表：这一类不出分数，
 * 任何排序（涨跌幅、成交量）都会被人当成推荐来读，而这里没有一样东西验证过。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetalsMarketScreen(
    onMetalClick: (String) -> Unit,
    viewModel: MetalsViewModel = viewModel()
) {
    val quotes by viewModel.quotes.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("行情 · 贵金属") },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AssetClassSwitcher()
            MetalsEngineLine(viewModel)
            Text(
                text = "共 ${MetalInstrument.ALL.size} 条报价、4 个独立资产（金 / 银 / 铂 / 钯）：" +
                    "这里只有价格、单位、涨跌幅和各自的报价时间，没有评分，也没有加减仓区间。" +
                    "点一行看它的日线 / 周线 / 月线。",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(MetalInstrument.ALL) { inst ->
                    MetalQuoteCard(
                        inst = inst,
                        quote = quotes[inst.code],
                        onClick = { onMetalClick(inst.code) }
                    )
                }
                item {
                    MetalsNoteCard(
                        title = "看这些数字时必须知道的三件事",
                        lines = MetalsCopy.readingRules,
                        accent = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/**
 * 「今日候选」切到贵金属时看到的就是这一页：没有名单，只有原因。
 *
 * 为什么不干脆把这一类的候选页藏掉：藏起来没人知道为什么少了一类，
 * 而这里写的每一条都有仓库里的原始输出撑着。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetalsCandidateScreen(
    onOpenMarket: () -> Unit,
    onOpenAStock: () -> Unit,
    viewModel: MetalsViewModel = viewModel()
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("今日候选 · 贵金属") },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AssetClassSwitcher()
            MetalsEngineLine(viewModel)
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    MetalsNoteCard(
                        title = "这一类不出候选名单：出厂那套分数在贵金属上回测没过",
                        lines = MetalsCopy.noAdvice
                    )
                }
                item {
                    MetalsNoteCard(
                        title = "看这些数字时必须知道的三件事",
                        lines = MetalsCopy.readingRules,
                        accent = MaterialTheme.colorScheme.onSurface
                    )
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(onClick = onOpenMarket, modifier = Modifier.weight(1f)) {
                            Text("看行情 / K线", fontSize = 14.sp)
                        }
                        OutlinedButton(onClick = onOpenAStock, modifier = Modifier.weight(1f)) {
                            Text("回 A 股那一类", fontSize = 14.sp)
                        }
                    }
                }
                item {
                    Text(
                        text = "依据：仓库 backtest/metals/README.md。以后若要给贵金属做一套自己的口径，" +
                            "仍然是先跑回测、跑过了再出建议；在那之前这一类只有行情。",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
fun MetalQuoteCard(inst: MetalInstrument, quote: MetalQuote?, onClick: () -> Unit) {
    val color = MetalsCopy.upDown(quote?.isRise ?: true)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(inst.name, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = "${inst.code} · ${inst.source}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = quote?.let { MetalsCopy.price(it.price) } ?: "—",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (quote == null) MaterialTheme.colorScheme.onSurfaceVariant else color
                    )
                    Text(inst.unit, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (quote == null) {
                Text(
                    text = "还没取到这个品种的报价：点右上角刷新，或等后台那一轮（境外凌晨与周末不刷新）",
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetalCell(inst.settleLabel(), MetalsCopy.price(quote.preSettle))
                    MetalCell("涨跌", if (quote.isRise) "+${MetalsCopy.price(abs(quote.change))}"
                        else "−${MetalsCopy.price(abs(quote.change))}", color)
                    MetalCell("涨跌幅", MetalsCopy.pct(quote.changePercent), color)
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (quote.hasOpen) MetalCell("今开", MetalsCopy.price(quote.open))
                    MetalCell("最高", MetalsCopy.price(quote.high))
                    MetalCell("最低", MetalsCopy.price(quote.low))
                    // 外盘那一路"今开"所在的下标在不同品种上语义不稳（对不上日线），
                    // 所以取不到就不显示这一格，不印一个数冒充
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // 这是数据源那个市场的日期与时间，不是手机时间 —— 两个市场经常差一天
                    Text(
                        text = "报价时间 ${quote.quoteDate} ${quote.quoteTime}（该市场当地）",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (quote.hasVolume) {
                        Text(
                            text = "量 ${quote.volume} 手 · 持仓 ${quote.openInterest} 手",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetalCell(
    label: String,
    value: String,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

/** 报价时间戳的短格式（持仓那一行用） */
fun MetalQuote.shortStamp(): String = "${quoteDate.takeLast(5)} ${quoteTime.take(5)}"
