package com.stock.analyzer.ui.metals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stock.analyzer.data.model.MetalInstrument
import com.stock.analyzer.data.model.settleLabel
import com.stock.analyzer.ui.detail.KLineChartCard
import com.stock.analyzer.ui.detail.StockDetailViewModel.ChartPeriod

/**
 * 贵金属详情页：报价 + 日 / 周 / 月 K 线。
 *
 * 与 A 股详情页的区别写在界面上，不靠用户自己发现：
 *   · 每个数字都带单位（美元/盎司 vs 元/克 vs 元/千克，差 30 倍）；
 *   · 报价时间是**该市场当地**的时间，境外与境内经常差一个交易日；
 *   · 没有分时和 5 分钟 —— 那两路接口不覆盖境外品种，不留点了报错的标签；
 *   · 没有评分、没有加减仓区间、没有指标卡（依据见 [MetalsCopy.noAdvice]）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetalDetailScreen(
    code: String,
    onBack: () -> Unit,
    viewModel: MetalDetailViewModel = viewModel()
) {
    val inst by viewModel.instrument.collectAsState()
    val bars by viewModel.bars.collectAsState()
    val daily by viewModel.daily.collectAsState()
    val period by viewModel.period.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val note by viewModel.note.collectAsState()
    val quotes by viewModel.quotes.collectAsState()

    LaunchedEffect(code) { viewModel.load(code) }

    val instrument = inst
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(instrument?.name ?: code) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (instrument == null && isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (instrument == null) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(error ?: "不认识的贵金属代码：$code", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onBack) { Text("返回行情列表") }
                }
            } else {
                val quote = quotes[instrument.code]
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    MetalPriceCard(instrument, quote)

                    error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, lineHeight = 17.sp)
                    }

                    Spacer(Modifier.height(16.dp))
                    MetalPeriodTabs(period) { viewModel.setPeriod(it) }
                    Spacer(Modifier.height(12.dp))

                    if (isLoading && bars.isEmpty()) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                    KLineChartCard(
                        klineData = bars,
                        period = period,
                        // 一次就把全量日线取回来了（周线/月线由它聚合），
                        // 所以没有"往前加载更早"这一说：直接告诉图表已经到底了
                        isLoadingMore = false,
                        atHistoryEnd = true,
                        onRetry = { viewModel.refresh() }
                    )
                    if (bars.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${period.label}共 ${bars.size} 根（由 ${daily.size} 根日线" +
                                (if (period == ChartPeriod.DAILY) "" else "聚合") +
                                "，最早 ${bars.first().date}）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    note?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (isLoading && bars.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    Spacer(Modifier.height(12.dp))
                    MetalsNoteCard(
                        title = "这一页为什么没有评分和加减仓区间",
                        lines = MetalsCopy.noAdvice,
                        accent = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/**
 * 报价卡。
 *
 * 外盘的"今开"那个下标在不同品种上语义不稳（拿纽约铂金核过：报价里的值和日线 open 差 4.7 美元），
 * 所以取不到就不显示这一格；境内期货那一路字段逐个对过日线，才敢把开高低一起摆出来。
 */
@Composable
private fun MetalPriceCard(inst: MetalInstrument, quote: com.stock.analyzer.data.model.MetalQuote?) {
    val color = MetalsCopy.upDown(quote?.isRise ?: true)
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(
                    text = quote?.let { MetalsCopy.price(it.price) } ?: "—",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (quote == null) MaterialTheme.colorScheme.onSurfaceVariant else color
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "  ${inst.unit}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (quote != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${if (quote.isRise) "+" else "−"}${MetalsCopy.price(kotlin.math.abs(quote.change))}　" +
                        "${MetalsCopy.pct(quote.changePercent)}（对${inst.settleLabel()}）",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = color
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "${inst.code} · ${inst.source}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Divider()
            Spacer(Modifier.height(8.dp))
            if (quote == null) {
                Text(
                    text = "还没有这个品种的报价：右上角刷新，或回行情页等后台那一轮。",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Cell(inst.settleLabel(), MetalsCopy.price(quote.preSettle))
                    if (quote.hasOpen) Cell("今开", MetalsCopy.price(quote.open))
                    Cell("最高", MetalsCopy.price(quote.high), MetalsCopy.COLOR_UP)
                    Cell("最低", MetalsCopy.price(quote.low), MetalsCopy.COLOR_DOWN)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "报价时间 ${quote.quoteDate} ${quote.quoteTime} —— 这是该市场当地的时间，" +
                        "境外与境内经常差一个交易日，两个市场的价格不要直接相减算溢价。",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun Cell(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

@Composable
private fun MetalPeriodTabs(
    selected: ChartPeriod,
    onSelected: (ChartPeriod) -> Unit
) {
    // 只给日 / 周 / 月：分时和 5 分钟那两路接口不覆盖境外现货期货
    val periods = listOf(ChartPeriod.DAILY, ChartPeriod.WEEKLY, ChartPeriod.MONTHLY)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        periods.forEach { p ->
            TextButton(
                onClick = { onSelected(p) },
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    p.label,
                    fontSize = 14.sp,
                    fontWeight = if (p == selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (p == selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
