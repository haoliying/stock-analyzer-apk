package com.stock.analyzer.ui.nonstock

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
import com.stock.analyzer.analysis.NonStockEngine
import com.stock.analyzer.data.model.AssetClass
import com.stock.analyzer.data.model.NonStockCatalog
import com.stock.analyzer.data.model.NonStockInstrument
import com.stock.analyzer.data.model.NonStockQuote
import com.stock.analyzer.ui.asset.AssetClassSwitcher
import java.util.Locale
import kotlin.math.abs

/** 一段说明文字卡：非股票那几类「为什么」都用它排版 */
@Composable
fun NonStockNoteCard(
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

/** v4.8.0~v4.9.0 的组件名，泛化后保留别名 */
@Composable
fun MetalsNoteCard(
    title: String,
    lines: List<String>,
    accent: Color = MaterialTheme.colorScheme.error
) = NonStockNoteCard(title, lines, accent)

/** 引擎那一行：报价取于几点、下一次什么时候刷、收市了为什么不刷 */
@Composable
fun NonStockEngineLine(viewModel: NonStockViewModel) {
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
            text = listOfNotNull(NonStockEngine.statusLine(status, fetchedAt), error)
                .joinToString("\n"),
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = if (error != null) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

/** v4.8.0~v4.9.0 的组件名，泛化后保留别名 */
@Composable
fun MetalsEngineLine(viewModel: NonStockViewModel) = NonStockEngineLine(viewModel)

/**
 * 行情页（非股票某一大类）：一列报价。
 *
 * 顺序永远是该大类的固定品种表（[NonStockCatalog.forClass]）：这一类不出分数，
 * 任何排序（涨跌幅、成交量）都会被人当成推荐来读，而这里没有一样东西验证过。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NonStockMarketScreen(
    assetClass: AssetClass,
    onInstrumentClick: (String) -> Unit,
    viewModel: NonStockViewModel = viewModel()
) {
    val quotes by viewModel.quotes.collectAsState()
    val instruments = NonStockCatalog.forClass(assetClass)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("行情 · ${assetClass.label}") },
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
            NonStockEngineLine(viewModel)
            Text(
                text = "共 ${instruments.size} 条报价：这里只有价格、单位、涨跌幅和各自的报价时间，" +
                    "没有评分，也没有加减仓区间。点一行看它的日线 / 周线 / 月线。",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(instruments, key = { it.code }) { inst ->
                    NonStockQuoteCard(
                        inst = inst,
                        quote = quotes[inst.code],
                        onClick = { onInstrumentClick(inst.code) }
                    )
                }
                item {
                    NonStockNoteCard(
                        title = "看这些数字时必须知道的几件事",
                        lines = NonStockCopy.readingRules(assetClass),
                        accent = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/** v4.8.0~v4.9.0 的页面，泛化后保留别名（仅贵金属语义下等价） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetalsMarketScreen(
    onMetalClick: (String) -> Unit,
    viewModel: NonStockViewModel = viewModel()
) = NonStockMarketScreen(AssetClass.PRECIOUS_METALS, onMetalClick, viewModel)

/**
 * 「今日候选」切到某一非股票大类时看到的就是这一页：没有名单，只有原因。
 *
 * 为什么不干脆把这一类的候选页藏掉：藏起来没人知道为什么少了一类，
 * 而这里写的每一条都有仓库里的原始输出撑着（贵金属）或写明「还没跑过回测」（大宗商品/外汇）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NonStockCandidateScreen(
    assetClass: AssetClass,
    onOpenMarket: () -> Unit,
    onOpenAStock: () -> Unit,
    viewModel: NonStockViewModel = viewModel()
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("今日候选 · ${assetClass.label}") },
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
            NonStockEngineLine(viewModel)
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    NonStockNoteCard(
                        title = "这一类不出候选名单：出厂那套分数在这里回测没过，或还没跑过",
                        lines = NonStockCopy.noAdvice(assetClass)
                    )
                }
                item {
                    NonStockNoteCard(
                        title = "看这些数字时必须知道的几件事",
                        lines = NonStockCopy.readingRules(assetClass),
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
                        text = "依据：贵金属见仓库 backtest/metals/README.md；大宗商品与外汇沿用同一条产品口径" +
                            "（没跑过回测的想法不上界面）。以后若要给这一类做一套自己的口径，仍然是先跑回测、" +
                            "跑过了再出建议；在那之前这一类只有行情。",
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

/** v4.8.0~v4.9.0 的页面，泛化后保留别名（仅贵金属语义下等价） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetalsCandidateScreen(
    onOpenMarket: () -> Unit,
    onOpenAStock: () -> Unit,
    viewModel: NonStockViewModel = viewModel()
) = NonStockCandidateScreen(AssetClass.PRECIOUS_METALS, onOpenMarket, onOpenAStock, viewModel)

@Composable
fun NonStockQuoteCard(inst: NonStockInstrument, quote: NonStockQuote?, onClick: () -> Unit) {
    val color = NonStockCopy.upDown(quote?.isRise ?: true)
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
                        text = quote?.let { NonStockCopy.price(it.price) } ?: "—",
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
                    NonStockCell(inst.preCloseLabel, NonStockCopy.price(quote.preSettle))
                    NonStockCell("涨跌", if (quote.isRise) "+${NonStockCopy.price(abs(quote.change))}"
                        else "−${NonStockCopy.price(abs(quote.change))}", color)
                    NonStockCell("涨跌幅", NonStockCopy.pct(quote.changePercent), color)
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (quote.hasOpen) NonStockCell("今开", NonStockCopy.price(quote.open))
                    NonStockCell("最高", NonStockCopy.price(quote.high))
                    NonStockCell("最低", NonStockCopy.price(quote.low))
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
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

/** v4.8.0~v4.9.0 的卡片，泛化后保留别名 */
@Composable
fun MetalQuoteCard(inst: NonStockInstrument, quote: NonStockQuote?, onClick: () -> Unit) =
    NonStockQuoteCard(inst, quote, onClick)

@Composable
private fun NonStockCell(
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
fun NonStockQuote.shortStamp(): String = "${quoteDate.takeLast(5)} ${quoteTime.take(5)}"
