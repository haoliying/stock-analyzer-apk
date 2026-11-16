package com.stock.analyzer.ui.market

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stock.analyzer.analysis.MarketEngine
import com.stock.analyzer.data.model.Recommendation
import com.stock.analyzer.data.model.StockRecommendation
import com.stock.analyzer.data.repository.StockInfoRepository
import com.stock.analyzer.ui.asset.AssetClassSwitcher
import com.stock.analyzer.data.model.StockInfo
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketScreen(
    onStockClick: (String) -> Unit,
    viewModel: MarketViewModel = viewModel()
) {
    val recommendations by viewModel.recommendations.collectAsState()
    val status by viewModel.status.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val error by viewModel.error.collectAsState()
    val lastUpdateTime by viewModel.lastUpdateTime.collectAsState()
    val poolInfo by viewModel.poolInfo.collectAsState()
    val isLoading = status.analyzing

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("今日候选池") },
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
            Column(modifier = Modifier.fillMaxSize()) {
                // 大类切换：这一页是 A 股那一类；切到贵金属换的是"看哪一类的数据"，
                // 后台那一轮的进度一个都不会少（分析在 MarketEngine 里，与界面无关）
                AssetClassSwitcher()

                // 引擎状态条：分析是进程级在跑的，这一行就是它的"心跳"
                EngineStatusBar(status)

                // 更新时间
                lastUpdateTime?.let { time ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Text(
                                text = "更新时间: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(time))}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (poolInfo.isNotEmpty()) {
                                Text(
                                    text = poolInfo,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            val perWeight = if (recommendations.isEmpty()) "" else
                                "（单只约 ${String.format("%.1f", 100.0 / recommendations.size)}%）"
                            Text(
                                text = "顺序不代表好坏：这是分位池的分层取样，${recommendations.size} 只等权$perWeight，" +
                                    "不要因为排名靠前就加注；本金凑不齐这么多手时，" +
                                    "按同样的间隔每隔 2~3 只挑 1 只（12~15 只即可），别改成只买前几名",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
                
                when {
                    isLoading && recommendations.isEmpty() -> {
                        Column(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(top = 100.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(text = progress, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    error != null && recommendations.isEmpty() -> {
                        Column(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(top = 100.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(text = error ?: "未知错误", color = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { viewModel.refresh() }) { Text("重试") }
                        }
                    }
                    recommendations.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = if (lastUpdateTime == null)
                                    "后台首轮分析进行中（第 ${status.done}/${status.total} 只），跑完自动出名单；" +
                                        "这一轮不会因为你切页或解锁而从头再来"
                                else
                                    "今日无股票同时满足趋势结构与波动要求（不勉强买入）",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            items(recommendations) { rec ->
                                RecommendationCard(
                                    recommendation = rec,
                                    onClick = { onStockClick(rec.stock.code) }
                                )
                            }
                        }
                    }
                }
                
                // 底部进度条
                if (isLoading && recommendations.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = progress, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * 引擎状态条。
 *
 * 这一行是 v4.7.0 的意义所在：分析不再挂在页面上，所以界面要能说清
 * "现在跑到哪了、什么时候换名单、切页会不会打断它"。
 */
@Composable
private fun EngineStatusBar(status: MarketEngine.Status) {
    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val text = when {
        status.analyzing -> "持续分析：第 ${status.done}/${status.total} 只（已评 ${status.scored} 只）" +
            "，本轮起 ${time.format(Date(status.passStartedAt))}" +
            (if (status.resumed) "（接着上一轮断点续跑）" else "") +
            "；名单等整轮跑完才整体替换——分位池是按当日全样本算的，半轮的池子不是同一个统计量"
        status.paused -> "分析暂停在第 ${status.done}/${status.total} 只，已评分的 ${status.scored} 只保留着；" +
            "回来从这一只接着算，不回第 1 只"
        status.nextRunAt > 0L -> "本轮已收口；下一轮 ${time.format(Date(status.nextRunAt))}" +
            (if (!status.continuous) "（「后台持续分析」已关，界面不可见时会停住）" else "")
        else -> "后台准备中"
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecommendationCard(
    recommendation: StockRecommendation,
    onClick: () -> Unit
) {
    // 这是一个分层取样的候选池，不是排行榜 —— 编号只是序号，不给金银铜
    val rankColor = MaterialTheme.colorScheme.primary
    
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
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 排名和股票信息
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = rankColor,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "#${recommendation.rank}",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = recommendation.stock.name,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = recommendation.stock.code,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                // 评分
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            tint = rankColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = " ${recommendation.analysis.score}",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = rankColor
                        )
                    }
                    Text(
                        text = getRecommendationLabel(recommendation.analysis.recommendation),
                        fontSize = 12.sp,
                        color = getRecommendationColor(recommendation.analysis.recommendation)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            Divider()
            Spacer(modifier = Modifier.height(12.dp))
            
            // 价格和涨跌
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("当前价", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = String.format("%.2f", recommendation.stock.price),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (recommendation.stock.isRise) Color(0xFFE53935) else Color(0xFF43A047)
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("涨跌幅", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = String.format("%+.2f%%", recommendation.stock.changePercent),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (recommendation.stock.isRise) Color(0xFFE53935) else Color(0xFF43A047)
                    )
                }
            }
            
            // 买入价格区间
            recommendation.buyPriceRange?.let { range ->
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFFE53935).copy(alpha = 0.1f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "回踩加仓区间",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFE53935)
                        )
                        Text(
                            text = "${String.format("%.2f", range.low)} - ${String.format("%.2f", range.high)}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE53935)
                        )
                    }
                }
            }
            
            // 卖出价格区间
            recommendation.sellPriceRange?.let { range ->
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF43A047).copy(alpha = 0.1f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "减仓 / 止盈区间",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF43A047)
                        )
                        Text(
                            text = "${String.format("%.2f", range.low)} - ${String.format("%.2f", range.high)}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF43A047)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 关键指标
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                recommendation.analysis.rsi?.let {
                    MiniIndicator("RSI", String.format("%.1f", it), getRSIColor(it))
                }
                recommendation.analysis.ma20?.let {
                    MiniIndicator("MA20", String.format("%.2f", it), MaterialTheme.colorScheme.onSurface)
                }
                recommendation.analysis.ma60?.let {
                    MiniIndicator("MA60", String.format("%.2f", it), MaterialTheme.colorScheme.onSurface)
                }
                // 日波幅（ATR/现价）：评分里唯一的"风险"项，越大扣分越多
                recommendation.analysis.atrPercent?.let {
                    val c = when {
                        it > 0.035 -> Color(0xFF43A047)
                        it < 0.020 -> Color(0xFFE53935)
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    MiniIndicator("日波幅", String.format("%.2f%%", it * 100), c)
                }
            }
            
            // 信号摘要
            if (recommendation.analysis.signals.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                val topSignals = recommendation.analysis.signals.take(3)
                topSignals.forEach { signal ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = getSignalColor(signal.type),
                            modifier = Modifier.size(6.dp)
                        ) {}
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = signal.message,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MiniIndicator(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

fun getRSIColor(rsi: Double): Color {
    return when {
        rsi > 70 -> Color(0xFF43A047)
        rsi < 30 -> Color(0xFFE53935)
        else -> Color(0xFF757575)
    }
}

fun getSignalColor(type: com.stock.analyzer.data.model.SignalType): Color {
    return when (type) {
        com.stock.analyzer.data.model.SignalType.GOLDEN_CROSS,
        com.stock.analyzer.data.model.SignalType.OVERSOLD,
        com.stock.analyzer.data.model.SignalType.TREND_UP,
        com.stock.analyzer.data.model.SignalType.BREAKOUT -> Color(0xFFE53935)
        else -> Color(0xFF43A047)
    }
}

fun getRecommendationLabel(rec: Recommendation): String {
    return when (rec) {
        Recommendation.BUY -> "达到加仓线"
        Recommendation.SELL -> "建议卖出"
        Recommendation.HOLD -> "建议持有"
        Recommendation.WATCH -> "建议观望"
    }
}

fun getRecommendationColor(rec: Recommendation): Color {
    return when (rec) {
        Recommendation.BUY -> Color(0xFFE53935)
        Recommendation.SELL -> Color(0xFF43A047)
        Recommendation.HOLD -> Color(0xFFFFA726)
        Recommendation.WATCH -> Color(0xFF757575)
    }
}
