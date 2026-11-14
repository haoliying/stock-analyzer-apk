package com.stock.analyzer.ui.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import com.stock.analyzer.data.model.Stock
import com.stock.analyzer.data.repository.StockInfoRepository
import com.stock.analyzer.data.model.StockInfo
import com.stock.analyzer.data.repository.StockRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * 股票列表界面 - 行情页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockListScreen(
    onStockClick: (String) -> Unit,
    viewModel: StockListViewModel = viewModel()
) {
    val stocks by viewModel.stocks.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val insights by viewModel.insights.collectAsState()
    
    // 搜索的股票实时行情
    var searchedStock by remember { mutableStateOf<Stock?>(null) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("行情") },
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
                
                // 股票列表
                when {
                    isLoading && stocks.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize()) {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                    error != null && stocks.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = error ?: "未知错误",
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(onClick = { viewModel.refresh() }) {
                                    Text("重试")
                                }
                            }
                        }
                    }
                    stocks.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Text(
                                text = "暂无股票数据",
                                modifier = Modifier.align(Alignment.Center),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            items(stocks) { stock ->
                                StockItem(
                                    stock = stock,
                                    insight = insights[stock.code],
                                    onClick = { onStockClick(stock.code) }
                                )
                            }
                        }
                    }
                }
            }
            
            // 下拉刷新指示器
            if (isLoading && stocks.isNotEmpty()) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                )
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
    onStockSearched: (Stock) -> Unit,
    onStockClick: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val stockInfoRepo = remember { StockInfoRepository(context) }
    val repository = remember { StockRepository(context) }
    
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<StockInfo>>(emptyList()) }
    var isExpanded by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            searchResults = emptyList()
        } else {
            val local = stockInfoRepo.searchStocks(searchQuery)
            if (local.isNotEmpty()) {
                searchResults = local
            } else {
                // 内置列表是静态的（新股、改名、北交所换段都不在里面）——
                // 停一下再联网搜，否则每敲一个字发一次请求
                kotlinx.coroutines.delay(320)
                searchResults = stockInfoRepo.searchOnline(searchQuery)
            }
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
                                val code = stock.code
                                coroutineScope.launch {
                                    try {
                                        val result = repository.getStockQuote(code)
                                        result.onSuccess { quote ->
                                            quote?.let {
                                                onStockSearched(it)
                                                searchQuery = stock.name
                                                isExpanded = false
                                            }
                                        }
                                    } catch (e: Exception) {
                                        // 错误处理
                                    } finally {
                                        isLoading = false
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
    stock: Stock,
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

/**
 * 股票列表项
 */
@Composable
fun StockItem(
    stock: Stock,
    insight: StockListViewModel.Insight? = null,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：股票名称、代码、分析档位
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stock.name,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stock.code,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    val tier = insight?.tier
                    val tierColor = when (tier) {
                        "达到加仓线" -> Color(0xFFE53935)
                        "持有" -> Color(0xFFFFA726)
                        "减仓", "强烈减仓" -> Color(0xFF43A047)
                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = tierColor.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = if (insight != null && insight.score > 0)
                                "$tier ${insight.score}分"
                            else tier ?: "分析中…",
                            fontSize = 11.sp,
                            color = tierColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                // 拿不到日线/历史不足/数据陈旧 —— 明写原因，不要只留一个空白
                insight?.note?.let {
                    Text(
                        text = it,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            
            // 中间：当前价格
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Text(
                    text = String.format("%.2f", stock.price),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (stock.isRise) Color(0xFFE53935) else Color(0xFF43A047)
                )
                Text(
                    text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(stock.timestamp)),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // 右侧：涨跌额和涨跌幅
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.width(100.dp)
            ) {
                Text(
                    text = String.format("%+.2f", stock.change),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (stock.isRise) Color(0xFFE53935) else Color(0xFF43A047)
                )
                Text(
                    text = String.format("%+.2f%%", stock.changePercent),
                    fontSize = 14.sp,
                    color = if (stock.isRise) Color(0xFFE53935) else Color(0xFF43A047)
                )
            }
        }
    }
}
