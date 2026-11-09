package com.stock.analyzer.ui.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stock.analyzer.data.model.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockDetailScreen(
    stockCode: String,
    onBack: () -> Unit,
    viewModel: StockDetailViewModel = viewModel()
) {
    val stock by viewModel.stock.collectAsState()
    val klineData by viewModel.klineData.collectAsState()
    val analysis by viewModel.analysis.collectAsState()
    val period by viewModel.period.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    LaunchedEffect(stockCode) {
        viewModel.loadStock(stockCode)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stock?.name ?: stockCode) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isLoading && stock == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (error != null && stock == null) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(error ?: "未知错误", color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { viewModel.loadStock(stockCode) }) { Text("重试") }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    // 价格卡片
                    stock?.let { PriceCard(stock = it) }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 周期选择
                    PeriodTabs(
                        selectedPeriod = period,
                        onPeriodSelected = { viewModel.setPeriod(it) }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // K线图
                    if (klineData.isNotEmpty()) {
                        KLineChartCard(klineData = klineData, period = period)
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // 分析结果
                    analysis?.let { result ->
                        AnalysisCard(analysis = result)
                        Spacer(modifier = Modifier.height(16.dp))

                        IndicatorCard(analysis = result)
                        Spacer(modifier = Modifier.height(16.dp))

                        SignalCard(analysis = result)
                    }

                    if (isLoading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
fun PriceCard(stock: Stock) {
    val profitColor = if (stock.isRise) Color(0xFFE53935) else Color(0xFF43A047)

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = String.format("¥%.2f", stock.price),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = profitColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row {
                Text(
                    text = String.format("%+.2f", stock.change),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = profitColor
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = String.format("%+.2f%%", stock.changePercent),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = profitColor
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Divider()
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("今开", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(String.format("%.2f", stock.open), fontSize = 14.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("最高", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(String.format("%.2f", stock.high), fontSize = 14.sp, color = Color(0xFFE53935))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("最低", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(String.format("%.2f", stock.low), fontSize = 14.sp, color = Color(0xFF43A047))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("昨收", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(String.format("%.2f", stock.close), fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
fun PeriodTabs(
    selectedPeriod: StockDetailViewModel.ChartPeriod,
    onPeriodSelected: (StockDetailViewModel.ChartPeriod) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StockDetailViewModel.ChartPeriod.values().forEach { period ->
            Button(
                onClick = { onPeriodSelected(period) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (period == selectedPeriod)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (period == selectedPeriod)
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                ),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
            ) {
                Text(period.label, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun KLineChartCard(klineData: List<KLineData>, period: StockDetailViewModel.ChartPeriod = StockDetailViewModel.ChartPeriod.DAILY) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("K线图", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))

            val displayData = klineData.takeLast(30)
            if (displayData.isNotEmpty()) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                ) {
                    val allPrices = displayData.flatMap { listOf(it.high, it.low) }
                    val maxPrice = allPrices.maxOrNull() ?: return@Canvas
                    val minPrice = allPrices.minOrNull() ?: return@Canvas
                    val priceRange = maxPrice - minPrice
                    if (priceRange <= 0) return@Canvas

                    val chartWidth = size.width
                    val chartHeight = size.height
                    val barWidth = chartWidth / displayData.size
                    val padding = 4.dp.toPx()

                    displayData.forEachIndexed { index, kline ->
                        val x = (index * barWidth + barWidth / 2).toFloat()
                        val isRise = kline.close >= kline.open
                        val color = if (isRise) Color(0xFFE53935) else Color(0xFF43A047)

                        // 影线
                        val highY = (chartHeight - ((kline.high - minPrice) / priceRange * (chartHeight - 2 * padding) + padding)).toFloat()
                        val lowY = (chartHeight - ((kline.low - minPrice) / priceRange * (chartHeight - 2 * padding) + padding)).toFloat()
                        drawLine(
                            color = color,
                            start = Offset(x, highY),
                            end = Offset(x, lowY),
                            strokeWidth = 1.dp.toPx()
                        )

                        // 实体
                        val openY = (chartHeight - ((kline.open - minPrice) / priceRange * (chartHeight - 2 * padding) + padding)).toFloat()
                        val closeY = (chartHeight - ((kline.close - minPrice) / priceRange * (chartHeight - 2 * padding) + padding)).toFloat()
                        val bodyTop = minOf(openY, closeY)
                        val bodyHeight = maxOf(kotlin.math.abs(openY - closeY).toFloat(), 1.dp.toPx())
                        val bodyWidth = (barWidth * 0.6f).toFloat()

                        if (isRise) {
                            drawRect(
                                color = color,
                                topLeft = Offset(x - bodyWidth / 2, bodyTop),
                                size = androidx.compose.ui.geometry.Size(bodyWidth, bodyHeight)
                            )
                        } else {
                            drawRect(
                                color = color,
                                topLeft = Offset(x - bodyWidth / 2, bodyTop),
                                size = androidx.compose.ui.geometry.Size(bodyWidth, bodyHeight)
                            )
                        }
                    }
                }

                // 日期/时间标签
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val dates = displayData.map { it.date }
                    if (dates.isNotEmpty()) {
                        Text(
                            text = formatDateLabel(dates.first(), period),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (dates.size > 2) {
                            val midIndex = dates.size / 2
                            Text(
                                text = formatDateLabel(dates[midIndex], period),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = formatDateLabel(dates.last(), period),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * 根据周期格式化日期标签
 */
fun formatDateLabel(dateStr: String, period: StockDetailViewModel.ChartPeriod): String {
    return when (period) {
        StockDetailViewModel.ChartPeriod.TIMELINE, StockDetailViewModel.ChartPeriod.FIVE_DAY -> {
            // 分时数据：显示时间 MM-dd HH:mm
            if (dateStr.contains(" ") && dateStr.length > 10) {
                dateStr.substring(5, 16)
            } else if (dateStr.contains(":")) {
                dateStr
            } else {
                dateStr
            }
        }
        StockDetailViewModel.ChartPeriod.DAILY, StockDetailViewModel.ChartPeriod.WEEKLY -> {
            // 日线/周线：显示 MM-dd
            if (dateStr.length >= 10) {
                dateStr.substring(5, 10)
            } else {
                dateStr
            }
        }
        StockDetailViewModel.ChartPeriod.MONTHLY -> {
            // 月线：显示 YYYY-MM
            if (dateStr.length >= 7) {
                dateStr.substring(0, 7)
            } else {
                dateStr
            }
        }
    }
}

@Composable
fun AnalysisCard(analysis: AnalysisResult) {
    val recColor = when (analysis.recommendation) {
        Recommendation.BUY -> Color(0xFFE53935)
        Recommendation.SELL -> Color(0xFF43A047)
        Recommendation.HOLD -> Color(0xFFFFA726)
        Recommendation.WATCH -> Color(0xFF757575)
    }
    val recText = when (analysis.recommendation) {
        Recommendation.BUY -> "建议买入"
        Recommendation.SELL -> "建议卖出"
        Recommendation.HOLD -> "建议持有"
        Recommendation.WATCH -> "建议观望"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("综合分析", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("综合评分", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${analysis.score}/100", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = recColor.copy(alpha = 0.1f)
                ) {
                    Text(
                        text = recText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = recColor,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun IndicatorCard(analysis: AnalysisResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("技术指标", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                analysis.ma5?.let {
                    IndicatorItem("MA5", String.format("%.2f", it))
                }
                analysis.ma10?.let {
                    IndicatorItem("MA10", String.format("%.2f", it))
                }
                analysis.ma20?.let {
                    IndicatorItem("MA20", String.format("%.2f", it))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                analysis.rsi?.let {
                    val rsiColor = when {
                        it > 70 -> Color(0xFF43A047)
                        it < 30 -> Color(0xFFE53935)
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    IndicatorItem("RSI", String.format("%.1f", it), rsiColor)
                }
                analysis.macd?.let {
                    val macdColor = if (it.macd > 0) Color(0xFFE53935) else Color(0xFF43A047)
                    IndicatorItem("MACD", String.format("%.3f", it.macd), macdColor)
                }
                analysis.kdj?.let {
                    IndicatorItem("KDJ", "K:${String.format("%.1f", it.k)}")
                }
            }
        }
    }
}

@Composable
fun IndicatorItem(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(4.dp))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

@Composable
fun SignalCard(analysis: AnalysisResult) {
    if (analysis.signals.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("交易信号", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))

            analysis.signals.forEach { signal ->
                val signalColor = when (signal.type) {
                    SignalType.GOLDEN_CROSS, SignalType.OVERSOLD,
                    SignalType.TREND_UP, SignalType.BREAKOUT -> Color(0xFFE53935)
                    else -> Color(0xFF43A047)
                }
                val strengthText = when (signal.strength) {
                    SignalStrength.STRONG -> "强"
                    SignalStrength.MEDIUM -> "中"
                    SignalStrength.WEAK -> "弱"
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = signalColor,
                        modifier = Modifier.size(8.dp)
                    ) {}
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = signal.message,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = strengthText,
                        fontSize = 12.sp,
                        color = signalColor,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

private fun abs(value: Float): Float = if (value < 0) -value else value
