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
import com.stock.analyzer.data.model.Recommendation
import com.stock.analyzer.data.repository.StockInfoRepository
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
    val isLoading by viewModel.isLoading.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val error by viewModel.error.collectAsState()
    val lastUpdateTime by viewModel.lastUpdateTime.collectAsState()
    var showSearchDialog by remember { mutableStateOf(false) }
    
    // 搜索的股票实时行情
    var searchedStock by remember { mutableStateOf<com.stock.analyzer.data.model.Stock?>(null) }
    var isSearchingStock by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("每日推荐 Top 5") },
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
                // 搜索栏
                StockSearchBar(
                    onStockSearched = { stock ->
                        searchedStock = stock
                    },
                    onSearching = { searching ->
                        isSearchingStock = searching
                    },
                    onStockClick = { code ->
                        onStockClick(code)
                    }
                )
                
                // 搜索到的股票行情
                searchedStock?.let { stock ->
                    SearchedStockCard(
                        stock = stock,
                        onClick = { onStockClick(stock.code) },
                        onDismiss = { searchedStock = null }
                    )
                }
                
                // 更新时间
                lastUpdateTime?.let { time ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = "更新时间: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(time))}  |  全A股分析，按推荐度排序",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
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
                                text = "暂无推荐数据",
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
 * 股票搜索栏 - 支持搜索并显示实时行情
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockSearchBar(
    onStockSearched: (com.stock.analyzer.data.model.Stock) -> Unit,
    onSearching: (Boolean) -> Unit,
    onStockClick: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val stockInfoRepo = remember { StockInfoRepository(context) }
    val repository = remember { com.stock.analyzer.data.repository.StockRepository(context) }
    
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<StockInfo>>(emptyList()) }
    var isExpanded by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    
    LaunchedEffect(searchQuery) {
        searchResults = if (searchQuery.isBlank()) {
            emptyList()
        } else {
            stockInfoRepo.searchStocks(searchQuery)
        }
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { 
                    searchQuery = it
                    isExpanded = it.isNotBlank()
                },
                label = { Text("搜索任意A股股票") },
                placeholder = { Text("输入代码、名称或拼音") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { 
                            searchQuery = ""
                            isExpanded = false
                        }) {
                            Icon(Icons.Default.Close, null)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            
            // 搜索结果
            if (isExpanded && searchResults.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))
                
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                ) {
                    items(searchResults) { stock ->
                        ListItem(
                            headlineContent = { Text(stock.name, fontSize = 14.sp) },
                            supportingContent = { 
                                Text("${stock.code}  ${stock.pinyin}", fontSize = 12.sp) 
                            },
                            modifier = Modifier.clickable {
                                // 获取实时行情
                                isLoading = true
                                onSearching(true)
                                val code = stock.code
                                kotlinx.coroutines.MainScope().launch {
                                    try {
                                        val result = repository.getStockQuote(code)
                                        result.onSuccess { quote ->
                                            quote?.let {
                                                onStockSearched(it)
                                                searchQuery = stock.name
                                                isExpanded = false
                                            }
                                        }.onFailure { e ->
                                            // 错误处理
                                        }
                                    } catch (e: Exception) {
                                        // 异常处理
                                    } finally {
                                        isLoading = false
                                        onSearching(false)
                                    }
                                }
                            },
                            trailingContent = {
                                if (isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                        )
                        Divider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            } else if (isExpanded && searchQuery.isNotEmpty() && searchResults.isEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "未找到匹配的股票",
                    modifier = Modifier.padding(16.dp),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 搜索到的股票行情卡片
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchedStockCard(
    stock: com.stock.analyzer.data.model.Stock,
    onClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val profitColor = if (stock.isRise) Color(0xFFE53935) else Color(0xFF43A047)
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
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
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stock.name,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = profitColor.copy(alpha = 0.1f)
                        ) {
                            Text(
                                text = if (stock.isRise) "上涨" else "下跌",
                                fontSize = 11.sp,
                                color = profitColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stock.code,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "关闭")
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
                        text = String.format("¥%.2f", stock.price),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = profitColor
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("涨跌额", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = String.format("%+.2f", stock.change),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = profitColor
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("涨跌幅", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = String.format("%+.2f%%", stock.changePercent),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = profitColor
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 其他信息
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("今开", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(String.format("%.2f", stock.open), fontSize = 14.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("最高", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(String.format("%.2f", stock.high), fontSize = 14.sp, color = Color(0xFFE53935))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("最低", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(String.format("%.2f", stock.low), fontSize = 14.sp, color = Color(0xFF43A047))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("昨收", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(String.format("%.2f", stock.close), fontSize = 14.sp)
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("成交量", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${stock.volume}手", fontSize = 13.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("成交额", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(String.format("%.2f万", stock.turnover / 10000), fontSize = 13.sp)
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "点击查看详情 →",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockSearchDialog(
    onDismiss: () -> Unit,
    onStockSelected: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val stockInfoRepo = remember { StockInfoRepository(context) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<StockInfo>>(emptyList()) }
    
    LaunchedEffect(searchQuery) {
        searchResults = if (searchQuery.isBlank()) {
            emptyList()
        } else {
            stockInfoRepo.searchStocks(searchQuery)
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("搜索任意A股股票") },
        text = {
            Column {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("输入代码、名称或拼音首字母") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "清除")
                            }
                        }
                    }
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                if (searchResults.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                    ) {
                        items(searchResults) { stock ->
                            ListItem(
                                headlineContent = { Text(stock.name) },
                                supportingContent = { Text("${stock.code}  ${stock.pinyin}") },
                                modifier = Modifier.clickable {
                                    onStockSelected(stock.code)
                                }
                            )
                            Divider()
                        }
                    }
                } else if (searchQuery.isNotEmpty()) {
                    Text(
                        text = "未找到匹配的股票",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecommendationCard(
    recommendation: StockRecommendation,
    onClick: () -> Unit
) {
    val rankColor = when (recommendation.rank) {
        1 -> Color(0xFFFFD700) // 金
        2 -> Color(0xFFC0C0C0) // 银
        3 -> Color(0xFFCD7F32) // 铜
        else -> MaterialTheme.colorScheme.primary
    }
    
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
                            text = "建议买入区间",
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
                            text = "建议卖出区间",
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
                recommendation.analysis.ma5?.let {
                    MiniIndicator("MA5", String.format("%.2f", it), MaterialTheme.colorScheme.onSurface)
                }
                recommendation.analysis.ma20?.let {
                    MiniIndicator("MA20", String.format("%.2f", it), MaterialTheme.colorScheme.onSurface)
                }
                recommendation.analysis.macd?.let {
                    MiniIndicator("MACD", String.format("%.3f", it.macd), if (it.macd > 0) Color(0xFFE53935) else Color(0xFF43A047))
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
        Recommendation.BUY -> "建议买入"
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
