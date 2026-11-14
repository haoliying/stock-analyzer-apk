package com.stock.analyzer.ui.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
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
import androidx.compose.ui.text.style.TextAlign
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
                            onEnsureBars = { viewModel.ensureBars(it) },
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
    onEnsureBars: (Int) -> Unit = {},
    onRetry: () -> Unit = {}
) {
    // 手势和按钮回调会被 pointerInput / lambda 长期持有，所以里面一律通过
    // rememberUpdatedState 读最新值，直接抓参数就会拿住过期列表。
    val dataState = rememberUpdatedState(klineData)
    val loadState = rememberUpdatedState(onLoadMore)
    val ensureState = rememberUpdatedState(onEnsureBars)
    val atEndState = rememberUpdatedState(atHistoryEnd)

    val windowStart = remember(period) { mutableIntStateOf((klineData.size - period.windowBars).coerceAtLeast(0)) }
    val visCount = remember(period) { mutableIntStateOf(period.windowBars) }
    val cursor = remember(period) { mutableIntStateOf(-1) }
    val anchor = remember(period) { mutableStateOf<String?>(null) }
    val touched = remember(period) { mutableStateOf(false) }

    fun visNow(): Int = minOf(visCount.intValue, maxOf(dataState.value.size, 1))
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

    fun hideCursor() {
        cursor.intValue = -1
        anchor.value = null
    }

    fun keepCursorInWindow() {
        val data = dataState.value
        if (cursor.intValue < 0 || data.isEmpty()) return
        val vis = visNow()
        val c = cursor.intValue
            .coerceIn(windowStart.intValue, windowStart.intValue + vis - 1)
            .coerceAtMost(data.size - 1)
        cursor.intValue = c
        anchor.value = data.getOrNull(c)?.date
    }

    /** 竖线每挪一根就调一次；挪出已加载数据的最左边就顺着要更早的（动态加载） */
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
        val vis = visNow()
        val barW = widthPx.toFloat() / vis
        if (barW <= 0f) return -1
        return windowStart.intValue + floor(x / barW).toInt().coerceIn(0, vis - 1)
    }

    /** 箭头按钮：整屏左右平移；已经在最左边还往左按就自动往前取历史 */
    fun panBy(n: Int) {
        val data = dataState.value
        if (data.isEmpty() || n == 0) return
        val ns = (windowStart.intValue + n).coerceIn(0, maxStartNow())
        if (ns == windowStart.intValue) {
            if (n < 0 && windowStart.intValue == 0 && !atEndState.value) loadState.value()
            return
        }
        windowStart.intValue = ns
        keepCursorInWindow()
    }

    fun goLatest() {
        windowStart.intValue = maxStartNow()
        setCursor(dataState.value.size - 1)
    }

    /** 放大 = 一屏根数变少（看细节）；缩小 = 根数变多（看更长时间）。
     *  根数超过已加载的量时自动补数据。 */
    fun doZoom(zoomIn: Boolean) {
        val data = dataState.value
        if (data.isEmpty()) return
        val target = nextZoom(visCount.intValue, zoomIn, period.minWindow, period.maxWindow)
        if (target == visCount.intValue) return
        visCount.intValue = target
        if (target > data.size) ensureState.value(target)
        windowStart.intValue = windowStart.intValue.coerceIn(0, maxStartNow())
        keepCursorInWindow()
    }

    // 动态加载会往列表前面追加更早的根，靠锚点日期平移窗口和竖线，画面才不会跳
    LaunchedEffect(klineData.size, visCount.intValue) {
        val data = dataState.value
        if (data.isEmpty()) {
            hideCursor()
            windowStart.intValue = 0
            return@LaunchedEffect
        }
        val a = anchor.value
        if (a != null && cursor.intValue >= 0) {
            val idx = data.indexOfLast { it.date == a }
            if (idx >= 0 && idx != cursor.intValue) {
                val shift = idx - cursor.intValue
                cursor.intValue += shift
                windowStart.intValue += shift
            }
        }
        if (cursor.intValue >= 0) cursor.intValue = cursor.intValue.coerceIn(0, data.size - 1)
        windowStart.intValue = windowStart.intValue.coerceIn(0, maxStartNow())
        keepCursorInWindow()
    }

    // 注意不能叫 size —— Canvas 的 DrawScope 和 pointerInput 的 PointerInputScope 都自带 size（像素）
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
    val rel = cursorIndex - winStart
    val panStep = maxOf(1, vis / 4)
    val maxStart = maxStartNow()

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (total == 0) "${period.label}（无数据）"
                    else "${period.label} · 第 ${winStart + 1}-${winStart + display.size} 根 / 共 $total 根",
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                )
                if (isLoadingMore) Text("往前加载…", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.height(4.dp))

            // 没有竖线时这一行就是"最新一根"的摘要；有竖线时它跟着竖线走
            ChartSummaryLine(point = shown, prev = shownPrev, period = period, isCursor = selected != null)
            Spacer(modifier = Modifier.height(6.dp))

            if (total > 0) {
                val textMeasurer = rememberTextMeasurer()
                Box(modifier = Modifier.fillMaxWidth()) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .pointerInput(period) {
                                detectTapGestures { offset ->
                                    val a = cursorAtX(offset.x, size.width)
                                    if (a < 0) return@detectTapGestures
                                    touched.value = true
                                    if (cursor.intValue == a) hideCursor() else setCursor(a)
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
                        var hi = display.maxOf { it.high }
                        var lo = display.minOf { it.low }
                        // 一字板 / 分钟点全等价时给点余量，别让整张图算不出比例
                        if (hi - lo <= 0.0) { hi += 0.01; lo -= 0.01 }
                        val range = hi - lo
                        val padding = 4.dp.toPx()

                        fun yOf(v: Double): Float =
                            (size.height - ((v - lo) / range * (size.height - 2 * padding) + padding)).toFloat()

                        val barWidth = size.width / display.size
                        display.forEachIndexed { index, kline ->
                            val x = index * barWidth + barWidth / 2
                            val color = if (kline.close >= kline.open) COLOR_UP else COLOR_DOWN
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

                        // 竖线 + 收盘价水平虚线 + 两个小标签
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

                    // 浮层：竖线那一根的完整数据（开高低收/涨跌/振幅/量/额），同花顺左上角那种
                    if (selected != null) {
                        CrosshairInfoBox(
                            point = selected,
                            prev = previous,
                            period = period,
                            modifier = Modifier
                                .align(if (rel * 2 < vis) Alignment.TopEnd else Alignment.TopStart)
                                .padding(2.dp)
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
                            Text(formatDateLabel(dates[dates.size / 2], period), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(formatDateLabel(dates.last(), period), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                // 左右箭头 + 放大缩小
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ChartControlButton(Icons.Default.KeyboardArrowLeft, "左移 ${panStep} 根") { panBy(-panStep) }
                    ChartControlButton(Icons.Default.KeyboardArrowRight, "右移 ${panStep} 根") { panBy(panStep) }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "一屏 $vis 根",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(62.dp),
                        textAlign = TextAlign.Center
                    )
                    ChartControlButton(Icons.Default.Remove, "缩小：一屏显示更多根、更长时间") { doZoom(false) }
                    ChartControlButton(Icons.Default.Add, "放大：一屏显示更少根、看得更细") { doZoom(true) }
                    Spacer(modifier = Modifier.weight(1f))
                    if (winStart < maxStart) {
                        OutlinedButton(
                            onClick = { goLatest() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(36.dp)
                        ) { Text("最新", fontSize = 12.sp) }
                    } else if (cursorIndex >= 0) {
                        OutlinedButton(
                            onClick = { hideCursor() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(36.dp)
                        ) { Text("收线", fontSize = 12.sp) }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                val status = when {
                    isLoadingMore -> "正在往前加载更早的${period.label}…"
                    period == StockDetailViewModel.ChartPeriod.TIMELINE ->
                        "分时只有当日这一天的分钟数据；要看更早的请切日线"
                    cursorIndex < 0 && !touched.value ->
                        "轻点图表出竖线；◀▶ 平移，－／＋ 缩小放大（一屏根数）"
                    cursorIndex < 0 -> "轻点图表可重新出竖线"
                    atHistoryEnd && winStart == 0 -> "已经到最早一根（${display.first().date}），再往前没有了"
                    else -> "共 $total 根（本周期上限 ${period.maxBars} 根），拖到最左或按 ◀ 会自动往前取"
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

@Composable
private fun ChartControlButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(40.dp)
    ) {
        Icon(icon, contentDescription = desc, modifier = Modifier.size(20.dp))
    }
}

/** 图表上方一行摘要：日期/时间 + 收盘（现价）+ 涨跌，永远占同一行高度 */
@Composable
private fun ChartSummaryLine(
    point: KLineData?,
    prev: KLineData?,
    period: StockDetailViewModel.ChartPeriod,
    isCursor: Boolean
) {
    val p = point ?: return
    val changeData = changeOf(p, prev)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = fullLabel(p.date, period),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = if (isCursor) "竖线" else "最新",
            fontSize = 10.sp,
            color = if (isCursor) Color(0xFF1565C0) else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(fmtPrice(p.close), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = changeData.color)
        if (changeData.text != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(changeData.text, fontSize = 12.sp, color = changeData.color)
        }
    }
}

/** 竖线浮层：把这一根能给的都给出来 */
@Composable
private fun CrosshairInfoBox(
    point: KLineData,
    prev: KLineData?,
    period: StockDetailViewModel.ChartPeriod,
    modifier: Modifier = Modifier
) {
    val ch = changeOf(point, prev)
    val base = changeBase(point, prev)
    val amp = if (base > 0) (point.high - point.low) / base * 100 else 0.0

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = Color(0xCC263238)
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
            Text(
                text = "${fullLabel(point.date, period)}　${period.label}",
                fontSize = 10.sp,
                color = Color.White.copy(alpha = 0.75f)
            )
            Spacer(modifier = Modifier.height(2.dp))
            InfoRow("开", fmtPrice(point.open), "高", fmtPrice(point.high))
            InfoRow("低", fmtPrice(point.low), "收", fmtPrice(point.close), valueColor = ch.color)
            if (ch.text != null) {
                InfoRow("涨跌", ch.text, "", null, valueColor = ch.color)
            }
            InfoRow("振幅", if (base > 0) String.format("%.2f%%", amp) else "—",
                "量", fmtVolume(point.volume) + "手")
            if (point.amount > 0) {
                InfoRow("额", fmtMoney(point.amount), "", null)
            }
            if (period.isMinute) {
                if (point.avgPrice > 0) InfoRow("均价", fmtPrice(point.avgPrice), "", null)
                if (point.preClose > 0) InfoRow("昨收", fmtPrice(point.preClose), "", null)
            }
        }
    }
}

@Composable
private fun InfoRow(
    label1: String,
    value1: String,
    label2: String,
    value2: String?,
    valueColor: Color = Color.White
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.width(74.dp)) {
            Text("$label1 $value1", fontSize = 11.sp, color = valueColor, fontWeight = FontWeight.Medium)
        }
        if (label2.isNotEmpty() && value2 != null) {
            Text("$label2 $value2", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Medium)
        }
    }
}

private data class ChangeInfo(val color: Color, val text: String?)

private fun changeBase(point: KLineData, prev: KLineData?): Double = when {
    point.preClose > 0 -> point.preClose
    prev != null && prev.close > 0 -> prev.close
    else -> 0.0
}

private fun changeOf(point: KLineData, prev: KLineData?): ChangeInfo {
    val base = changeBase(point, prev)
    if (base <= 0) return ChangeInfo(MaterialThemePalette, null)
    val diff = point.close - base
    val pct = diff / base * 100
    return ChangeInfo(if (diff >= 0) COLOR_UP else COLOR_DOWN, String.format("%+.2f  %+.2f%%", diff, pct))
}

private val COLOR_UP = Color(0xFFE53935)
private val COLOR_DOWN = Color(0xFF43A047)
private val MaterialThemePalette = Color(0xFF757575)

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

/** 放大缩小的档位：只在周期允许的范围内跳档 */
private val ZOOM_STEPS = listOf(10, 15, 20, 30, 40, 60, 90, 120, 160, 200, 240, 320, 480)

private fun nextZoom(current: Int, zoomIn: Boolean, lo: Int, hi: Int): Int {
    val allowed = ZOOM_STEPS.filter { it in lo..hi }
    if (allowed.isEmpty()) return current.coerceIn(lo, hi)
    return if (zoomIn) {
        maxOf(lo, allowed.lastOrNull { it < current } ?: lo)
    } else {
        minOf(hi, allowed.firstOrNull { it > current } ?: hi)
    }
}

private fun fmtPrice(v: Double): String = String.format("%.2f", v)

private fun fmtVolume(v: Long): String = when {
    v >= 100_000_000L -> String.format("%.2f亿", v / 100_000_000.0)
    v >= 10_000L -> String.format("%.2f万", v / 10_000.0)
    else -> v.toString()
}

private fun fmtMoney(v: Double): String = when {
    v >= 100_000_000.0 -> String.format("%.2f亿", v / 100_000_000.0)
    v >= 10_000.0 -> String.format("%.0f万", v / 10_000.0)
    else -> String.format("%.0f元", v)
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

