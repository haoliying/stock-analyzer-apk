package com.stock.analyzer.ui.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stock.analyzer.data.model.*
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

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
    val dataNote by viewModel.dataNote.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val atHistoryEnd by viewModel.historyAtEnd.collectAsState()

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
            // 只有"行情和K线都没有"才算整页失败；以前 error 一出现就把图表和评分一起藏掉，
            // 于是停牌/退市/代码变更的股票点进去只有一个"重试"按钮
            val nothingAtAll = stock == null && klineData.isEmpty() && analysis == null
            if (isLoading && nothingAtAll) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (nothingAtAll && error != null) {
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

                    error?.let {
                        Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 周期选择
                    PeriodTabs(
                        selectedPeriod = period,
                        onPeriodSelected = { viewModel.setPeriod(it) }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // K线图：竖线可点可拖，拖到最左边自动往前取历史
                    if (klineData.isNotEmpty() || !isLoading) {
                        KLineChartCard(
                            klineData = klineData,
                            period = period,
                            isLoadingMore = isLoadingMore,
                            atHistoryEnd = atHistoryEnd,
                            onLoadMore = { viewModel.loadOlderBars() },
                            onRetry = { viewModel.refresh() }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    dataNote?.let {
                        Text(
                            it,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 17.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // 分析结果
                    analysis?.let { result ->
                        Spacer(modifier = Modifier.height(8.dp))
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
fun KLineChartCard(
    klineData: List<KLineData>,
    period: StockDetailViewModel.ChartPeriod = StockDetailViewModel.ChartPeriod.DAILY,
    isLoadingMore: Boolean = false,
    atHistoryEnd: Boolean = false,
    onLoadMore: () -> Unit = {},
    onRetry: () -> Unit = {}
) {
    // 手势回调会被 pointerInput 长期持有，所以里面一律通过 rememberUpdatedState 读最新值，
    // 不直接抓参数 —— 抓了就会拿住拖动那一刻已经过期的列表。
    val dataState = rememberUpdatedState(klineData)
    val loadState = rememberUpdatedState(onLoadMore)
    val atEndState = rememberUpdatedState(atHistoryEnd)

    val windowStart = remember(period) { mutableIntStateOf((klineData.size - period.windowBars).coerceAtLeast(0)) }
    val cursor = remember(period) { mutableIntStateOf(-1) }
    val anchor = remember(period) { mutableStateOf<String?>(null) }
    val touched = remember(period) { mutableStateOf(false) }

    fun visNow(): Int = minOf(period.windowBars, maxOf(dataState.value.size, 1))
    fun maxStartNow(): Int = (dataState.value.size - visNow()).coerceAtLeast(0)

    fun setWindowFor(c: Int) {
        val vis = visNow()
        var s = windowStart.intValue
        if (c < s) s = c
        else if (c > s + vis - 1) s = c - vis + 1
        windowStart.intValue = s.coerceIn(0, maxStartNow())
    }

    fun setCursor(index: Int) {
        val data = dataState.value
        if (data.isEmpty()) return
        val c = index.coerceIn(0, data.size - 1)
        cursor.intValue = c
        anchor.value = data[c].date
        setWindowFor(c)
    }

    /** 竖线每挪一根就调一次；挪出最左边就顺着要更早的数据（动态加载） */
    fun stepCursor(n: Int) {
        val data = dataState.value
        if (data.isEmpty() || n == 0) return
        val target = cursor.intValue + n
        when {
            target < 0 -> {
                setCursor(0)
                if (!atEndState.value) loadState.value()
            }
            target > data.size - 1 -> setCursor(data.size - 1)
            else -> setCursor(target)
        }
    }

    fun cursorAtX(x: Float, widthPx: Int): Int {
        val data = dataState.value
        if (data.isEmpty() || widthPx <= 0) return -1
        val barW = widthPx.toFloat() / visNow()
        if (barW <= 0f) return -1
        return windowStart.intValue + floor(x / barW).toInt().coerceIn(0, visNow() - 1)
    }

    // 动态加载会往列表前面追加更早的根，靠锚点日期平移窗口和竖线，画面才不会跳
    LaunchedEffect(klineData.size) {
        val data = klineData
        if (data.isEmpty()) {
            cursor.intValue = -1; anchor.value = null; windowStart.intValue = 0
            return@LaunchedEffect
        }
        val a = anchor.value
        if (a != null && cursor.intValue >= 0) {
            val idx = data.indexOfLast { it.date == a }
            if (idx >= 0 && idx != cursor.intValue) {
                val shift = idx - cursor.intValue
                cursor.intValue = idx
                windowStart.intValue += shift
            }
        }
        if (cursor.intValue >= data.size) cursor.intValue = data.size - 1
        windowStart.intValue = windowStart.intValue.coerceIn(0, (data.size - minOf(period.windowBars, data.size)).coerceAtLeast(0))
        if (cursor.intValue >= 0) setWindowFor(cursor.intValue)
    }

    // 注意：这里不能再叫 size —— Canvas 的 DrawScope 和 pointerInput 的 PointerInputScope
    // 都自带 size（像素），同名会把它们挡住。
    val total = klineData.size
    val vis = visNow()
    val winStart = windowStart.intValue.coerceIn(0, maxStartNow())
    val display = if (total == 0) emptyList() else klineData.subList(winStart, minOf(winStart + vis, total))
    val cursorIndex = cursor.intValue
    val selected = klineData.getOrNull(cursorIndex)
    val previous = if (cursorIndex > 0) klineData.getOrNull(cursorIndex - 1) else null
    val shown = selected ?: klineData.lastOrNull()
    val shownPrev = if (selected != null) previous
        else if (total >= 2) klineData.getOrNull(total - 2) else null

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (total == 0) "${period.label}（无数据）"
                else "${period.label} · 第 ${winStart + 1}-${winStart + display.size} 根 / 共 $total 根",
                fontSize = 16.sp, fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))

            ChartLegend(point = shown, prev = shownPrev, period = period, isCursor = selected != null)
            Spacer(modifier = Modifier.height(8.dp))

            if (total > 0) {
                val textMeasurer = rememberTextMeasurer()
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .pointerInput(period) {
                            detectTapGestures { offset ->
                                val a = cursorAtX(offset.x, size.width)
                                if (a < 0) return@detectTapGestures
                                touched.value = true
                                if (cursor.intValue == a) {
                                    cursor.intValue = -1; anchor.value = null
                                } else setCursor(a)
                            }
                        }
                        .pointerInput(period) {
                            var acc = 0f
                            detectDragGestures(
                                onDragStart = { offset ->
                                    touched.value = true
                                    val a = cursorAtX(offset.x, size.width)
                                    if (a >= 0) setCursor(a)
                                    acc = 0f
                                },
                                onDrag = { _, amount ->
                                    val barW = size.width.toFloat() / visNow()
                                    if (barW > 0f) {
                                        acc += amount.x
                                        val n = (acc / barW).roundToInt()
                                        if (n != 0) {
                                            acc -= n * barW
                                            stepCursor(n)
                                        }
                                    }
                                },
                                onDragEnd = { acc = 0f },
                                onDragCancel = { acc = 0f }
                            )
                        }
                ) {
                    if (display.isEmpty()) return@Canvas
                    val highs = display.map { it.high }
                    val lows = display.map { it.low }
                    var hi = highs.maxOrNull() ?: return@Canvas
                    var lo = lows.minOrNull() ?: return@Canvas
                    // 一字板/分钟点全等价时给点余量，别让整张图算不出比例
                    if (hi - lo <= 0.0) { hi += 0.01; lo -= 0.01 }
                    val range = hi - lo
                    val padding = 4.dp.toPx()

                    fun yOf(v: Double): Float =
                        (size.height - ((v - lo) / range * (size.height - 2 * padding) + padding)).toFloat()

                    val barWidth = size.width / display.size
                    display.forEachIndexed { index, kline ->
                        val x = index * barWidth + barWidth / 2
                        val color = if (kline.close >= kline.open) Color(0xFFE53935) else Color(0xFF43A047)
                        drawLine(
                            color = color,
                            start = Offset(x, yOf(kline.high)),
                            end = Offset(x, yOf(kline.low)),
                            strokeWidth = 1.dp.toPx()
                        )
                        val openY = yOf(kline.open)
                        val closeY = yOf(kline.close)
                        val bodyTop = minOf(openY, closeY)
                        val bodyHeight = maxOf(abs(openY - closeY), 1.dp.toPx())
                        val bodyWidth = maxOf(barWidth * 0.6f, 1f)
                        drawRect(
                            color = color,
                            topLeft = Offset(x - bodyWidth / 2, bodyTop),
                            size = androidx.compose.ui.geometry.Size(bodyWidth, bodyHeight)
                        )
                    }

                    // 竖线 + 收盘价水平虚线 + 两个标签
                    val rel = cursorIndex - winStart
                    if (selected != null && rel in display.indices) {
                        val x = rel * barWidth + barWidth / 2
                        val y = yOf(selected.close)
                        val guide = Color(0xFF8D9AAE)
                        drawLine(guide, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                        drawLine(
                            guide.copy(alpha = 0.65f),
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                        )
                        drawCircle(Color(0xFF1565C0), radius = 3.4.dp.toPx(), center = Offset(x, y))
                        drawCircle(Color.White, radius = 1.5.dp.toPx(), center = Offset(x, y))
                        drawEdgeTag(textMeasurer, fmtPrice(selected.close), cx = size.width, cy = y, pinnedRight = true)
                        drawEdgeTag(
                            textMeasurer,
                            cursorAxisLabel(selected.date, period),
                            cx = x,
                            cy = size.height - 1f,
                            pinnedRight = false
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val dates = display.map { it.date }
                    if (dates.isNotEmpty()) {
                        Text(formatDateLabel(dates.first(), period), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (dates.size > 2) {
                            val midIndex = dates.size / 2
                            Text(formatDateLabel(dates[midIndex], period), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(formatDateLabel(dates.last(), period), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                val status = when {
                    isLoadingMore -> "正在往前加载更早的${period.label}…"
                    period == StockDetailViewModel.ChartPeriod.TIMELINE ->
                        "分时只有当日这一天的分钟数据；要看更早的请切日线"
                    cursorIndex < 0 && !touched.value -> "轻点图表出竖线，按住左右拖：竖线拖到最左边会自动往前取历史数据"
                    cursorIndex < 0 -> "轻点图表可重新出竖线"
                    atHistoryEnd && winStart == 0 ->
                        "已经到最早一根（${display.first().date}），再往前没有了"
                    else -> "共 $total 根，往前拖会自动加载（本周期上限 ${period.maxBars} 根）"
                }
                Text(
                    status,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 15.sp
                )
            } else if (!isLoadingMore) {
                Text("这个周期暂时没有取到数据", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onRetry) { Text("重新加载这个周期") }
            }
        }
    }
}

/** 竖线指着的那根（没指的时候是最新一根）的价量信息，位置固定不会让图表跳动 */
@Composable
private fun ChartLegend(
    point: KLineData?,
    prev: KLineData?,
    period: StockDetailViewModel.ChartPeriod,
    isCursor: Boolean
) {
    val p = point ?: return
    val base = when {
        p.preClose > 0 -> p.preClose
        prev != null && prev.close > 0 -> prev.close
        else -> 0.0
    }
    val change = if (base > 0) p.close - base else 0.0
    val changePct = if (base > 0) change / base * 100 else 0.0
    val up = change >= 0
    val profitColor = if (up) Color(0xFFE53935) else Color(0xFF43A047)

    Column(modifier = Modifier.fillMaxWidth().height(46.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = fullLabel(p.date, period),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isCursor) "竖线" else "最新",
                fontSize = 10.sp,
                color = if (isCursor) Color(0xFF1565C0) else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(fmtPrice(p.close), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = profitColor)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (base > 0) String.format("%+.2f  %+.2f%%", change, changePct) else "—",
                fontSize = 12.sp,
                color = profitColor
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        val second = if (period.isMinute && p.high == p.low) {
            // 分时点是单成交价，画不出开高低收，改成均价和量
            "均价 ${if (p.avgPrice > 0) fmtPrice(p.avgPrice) else "—"}　量 ${fmtVolume(p.volume)}手　昨收 ${if (p.preClose > 0) fmtPrice(p.preClose) else "—"}"
        } else {
            val basis = if (period.isMinute) "（涨跌按上一根${period.label}）" else ""
            "开 ${fmtPrice(p.open)}　高 ${fmtPrice(p.high)}　低 ${fmtPrice(p.low)}　收 ${fmtPrice(p.close)}　量 ${fmtVolume(p.volume)}手$basis"
        }
        Text(
            second,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            lineHeight = 15.sp
        )
    }
}

private const val TAG_BG = 0xFF455A64

/** 竖线端上的小标签：价格贴右边缘，日期贴底边，都夹在画布内 */
@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawEdgeTag(
    tm: TextMeasurer,
    text: String,
    cx: Float,
    cy: Float,
    pinnedRight: Boolean
) {
    val style = TextStyle(fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Medium)
    val result = tm.measure(text, style)
    val padX = 4.dp.toPx()
    val padY = 1.5.dp.toPx()
    val w = result.size.width + 2 * padX
    val h = result.size.height + 2 * padY
    val left = if (pinnedRight) size.width - w
    else (cx - w / 2).coerceIn(0f, (size.width - w).coerceAtLeast(0f))
    val top = (cy - h / 2).coerceIn(0f, (size.height - h).coerceAtLeast(0f))
    drawRoundRect(
        color = Color(TAG_BG),
        topLeft = Offset(left, top),
        size = androidx.compose.ui.geometry.Size(w, h),
        cornerRadius = CornerRadius(3.dp.toPx())
    )
    drawText(result, color = Color.White, topLeft = Offset(left + padX, top + padY))
}

private fun fmtPrice(v: Double): String = String.format("%.2f", v)

private fun fmtVolume(v: Long): String = when {
    v >= 100_000_000L -> String.format("%.2f亿", v / 100_000_000.0)
    v >= 10_000L -> String.format("%.2f万", v / 10_000.0)
    else -> v.toString()
}

/** 竖线底部标签：日线以上给日期，分钟级给时分 */
private fun cursorAxisLabel(dateStr: String, period: StockDetailViewModel.ChartPeriod): String {
    return if (period.isMinute) {
        if (dateStr.contains(" ") && dateStr.length >= 16) dateStr.substring(11, 16) else dateStr
    } else {
        if (period == StockDetailViewModel.ChartPeriod.MONTHLY && dateStr.length >= 7) dateStr.substring(0, 7)
        else if (dateStr.length >= 10) dateStr.substring(5, 10) else dateStr
    }
}

/** 信息条第一行：分钟级带日期+时分，日线以上带完整日期 */
private fun fullLabel(dateStr: String, period: StockDetailViewModel.ChartPeriod): String {
    return if (period.isMinute) {
        if (dateStr.length >= 16) "${dateStr.substring(5, 10)} ${dateStr.substring(11, 16)}" else dateStr
    } else {
        if (dateStr.length >= 10) dateStr.substring(0, 10) else dateStr
    }
}

/**
 * 根据周期格式化日期标签
 */
fun formatDateLabel(dateStr: String, period: StockDetailViewModel.ChartPeriod): String {
    return when (period) {
        StockDetailViewModel.ChartPeriod.TIMELINE -> {
            // 分时是一天内的分钟点，轴上只要时分
            if (dateStr.contains(" ") && dateStr.length >= 16) dateStr.substring(11, 16) else dateStr
        }
        StockDetailViewModel.ChartPeriod.FIVE_DAY -> {
            // 5 日跨天，轴上要 日期 + 时分
            if (dateStr.contains(" ") && dateStr.length >= 16) dateStr.substring(5, 16) else dateStr
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
        Recommendation.BUY -> "达到加仓线"
        Recommendation.SELL -> "建议减仓"
        Recommendation.HOLD -> "持有不新增"
        Recommendation.WATCH -> "数据不足·观望"
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
            Spacer(modifier = Modifier.height(10.dp))
            analysis.trendState?.let {
                Text("趋势结构：$it（评分只看现价/MA20/MA60 排列与 MA20 十日上斜率）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
            }
            analysis.stopLoss?.let {
                Text("止损参考：${String.format("%.2f", it)}　减仓参考：先涨 1×ATR 分批兑现", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
            }
            analysis.riskNote?.let {
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error.copy(alpha = 0.75f), lineHeight = 17.sp)
                Spacer(modifier = Modifier.height(4.dp))
            }
            analysis.confidence?.let {
                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
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

            // 评分模型真正依赖的两项 + 趋势强度
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                analysis.ma60?.let {
                    IndicatorItem("MA60", String.format("%.2f", it))
                }
                analysis.atrPercent?.let {
                    val atrColor = when {
                        it > 0.035 -> Color(0xFF43A047)
                        it < 0.020 -> Color(0xFFE53935)
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    IndicatorItem("日波幅", String.format("%.2f%%", it * 100), atrColor)
                }
                analysis.adx?.let {
                    IndicatorItem("ADX", String.format("%.1f", it))
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

