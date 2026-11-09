package com.stock.analyzer.ui.portfolio

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stock.analyzer.data.model.*
import com.stock.analyzer.data.repository.StockInfoRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioScreen(
    onStockClick: (String) -> Unit,
    viewModel: PortfolioViewModel = viewModel()
) {
    val portfolio by viewModel.portfolio.collectAsState()
    val advices by viewModel.advices.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val showAddDialog by viewModel.showAddDialog.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的持仓") },
                actions = {
                    IconButton(onClick = { viewModel.refreshPrices() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.showAddDialog() }) {
                Icon(Icons.Default.Add, contentDescription = "添加持仓")
            }
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (portfolio.isNotEmpty()) {
                PortfolioSummary(
                    totalValue = viewModel.getTotalValue(),
                    totalProfit = viewModel.getTotalProfit(),
                    totalProfitPercent = viewModel.getTotalProfitPercent()
                )
            }
            
            if (isLoading && portfolio.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (portfolio.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.AccountBalanceWallet, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("暂无持仓", style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("点击右下角 + 添加股票", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 80.dp)) {
                    items(portfolio) { stock ->
                        val advice = advices.find { it.code == stock.code }
                        PortfolioStockCard(
                            stock = stock, advice = advice,
                            onClick = { onStockClick(stock.code) },
                            onEdit = { viewModel.updateStock(it) },
                            onDelete = { viewModel.removeStock(stock.code) }
                        )
                    }
                }
            }
        }
        
        if (showAddDialog) {
            AddStockDialog(
                onDismiss = { viewModel.hideAddDialog() },
                onConfirm = { code, name, price, quantity -> viewModel.addStock(code, name, price, quantity) }
            )
        }
    }
}

@Composable
fun PortfolioSummary(totalValue: Double, totalProfit: Double, totalProfitPercent: Double) {
    val profitColor = when { totalProfit > 0 -> Color(0xFFE53935); totalProfit < 0 -> Color(0xFF43A047); else -> MaterialTheme.colorScheme.onSurface }
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("总资产概览", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("总市值", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(String.format("¥%.2f", totalValue), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("总盈亏", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(String.format("%+.2f (%+.2f%%)", totalProfit, totalProfitPercent), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = profitColor)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioStockCard(
    stock: PortfolioStock, advice: PositionAdvice?,
    onClick: () -> Unit, onEdit: (PortfolioStock) -> Unit, onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    val profitColor = when { stock.profit > 0 -> Color(0xFFE53935); stock.profit < 0 -> Color(0xFF43A047); else -> MaterialTheme.colorScheme.onSurface }
    
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        onClick = onClick
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stock.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(text = PortfolioViewModel.displayCode(stock.code), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, null) }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text("编辑持仓") }, onClick = { showMenu = false; showEditDialog = true }, leadingIcon = { Icon(Icons.Default.Edit, null) })
                        DropdownMenuItem(text = { Text("删除") }, onClick = { showMenu = false; onDelete() }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = Color.Red) })
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp)); Divider(); Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("买入价", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(String.format("¥%.2f", stock.buyPrice), fontSize = 16.sp, fontWeight = FontWeight.Medium) }
                Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("现价", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(String.format("¥%.2f", stock.currentPrice), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = profitColor) }
                Column(horizontalAlignment = Alignment.End) { Text("盈亏", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(String.format("%+.2f (%+.2f%%)", stock.profit, stock.profitPercent), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = profitColor) }
            }
            Text("持有 ${stock.quantity} 股", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (advice != null) {
                Spacer(modifier = Modifier.height(8.dp)); Divider(); Spacer(modifier = Modifier.height(8.dp))
                AdviceSection(advice = advice)
            }
        }
    }
    if (showEditDialog) {
        EditStockDialog(stock = stock, onDismiss = { showEditDialog = false }, onConfirm = { onEdit(it); showEditDialog = false })
    }
}

@Composable
fun AdviceSection(advice: PositionAdvice) {
    val adviceColor = when (advice.advice) {
        AdviceType.STRONG_BUY -> Color(0xFFD32F2F); AdviceType.BUY -> Color(0xFFE53935)
        AdviceType.HOLD -> Color(0xFFFFA726); AdviceType.SELL -> Color(0xFF43A047); AdviceType.STRONG_SELL -> Color(0xFF2E7D32)
    }
    val adviceText = when (advice.advice) {
        AdviceType.STRONG_BUY -> "强烈加仓"; AdviceType.BUY -> "建议加仓"
        AdviceType.HOLD -> "持有观望"; AdviceType.SELL -> "建议减仓"; AdviceType.STRONG_SELL -> "强烈减仓"
    }
    Surface(shape = MaterialTheme.shapes.small, color = adviceColor.copy(alpha = 0.1f)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(text = adviceText, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = adviceColor)
                Text(text = advice.reason, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 8.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("加仓区间", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${String.format("%.2f", advice.addPriceRange.low)} - ${String.format("%.2f", advice.addPriceRange.high)}", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFFE53935))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("减仓区间", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${String.format("%.2f", advice.reducePriceRange.low)} - ${String.format("%.2f", advice.reducePriceRange.high)}", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF43A047))
                }
            }
        }
    }
}

/**
 * 添加持仓对话框 - 支持拼音搜索和自动填充
 */
@Composable
fun AddStockDialog(
    onDismiss: () -> Unit,
    onConfirm: (code: String, name: String, buyPrice: Double, quantity: Int) -> Unit
) {
    val context = LocalContext.current
    val stockInfoRepo = remember { StockInfoRepository(context) }
    
    var searchText by remember { mutableStateOf("") }
    var selectedStock by remember { mutableStateOf<StockInfo?>(null) }
    var searchResults by remember { mutableStateOf<List<StockInfo>>(emptyList()) }
    var buyPrice by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    
    // 搜索逻辑
    LaunchedEffect(searchText) {
        if (searchText.length >= 1) {
            searchResults = stockInfoRepo.searchByPinyin(searchText)
            // 如果输入的是完整代码（可能不带前缀），自动选中
            val normalizedCode = PortfolioViewModel.normalizeStockCode(searchText.trim())
            val exactMatch = stockInfoRepo.getStockByCode(normalizedCode)
            if (exactMatch != null) {
                selectedStock = exactMatch
                searchResults = emptyList()
            }
        } else {
            searchResults = emptyList()
            selectedStock = null
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加持仓") },
        text = {
            Column {
                // 搜索框
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it; selectedStock = null; error = null },
                    label = { Text("输入代码/名称/拼音首字母") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (searchText.isNotEmpty()) {
                            IconButton(onClick = { searchText = ""; selectedStock = null; searchResults = emptyList() }) {
                                Icon(Icons.Default.Clear, null)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                // 搜索结果下拉列表
                if (searchResults.isNotEmpty() && selectedStock == null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                        shadowElevation = 4.dp,
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        LazyColumn {
                            items(searchResults) { stock ->
                                ListItem(
                                    headlineContent = { Text(stock.name, fontSize = 14.sp) },
                                    supportingContent = { Text("${stock.code}  ${stock.pinyin}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                    modifier = Modifier.clickable {
                                        selectedStock = stock
                                        searchText = stock.name
                                        searchResults = emptyList()
                                    }
                                )
                            }
                        }
                    }
                }
                
                // 已选中的股票
                selectedStock?.let { stock ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("${stock.name}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(stock.code, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = buyPrice, onValueChange = { buyPrice = it; error = null },
                    label = { Text("买入价格") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = quantity, onValueChange = { quantity = it; error = null },
                    label = { Text("持有数量（股）") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                error?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val stock = selectedStock
                val price = buyPrice.toDoubleOrNull()
                val qty = quantity.toIntOrNull()
                when {
                    stock == null -> error = "请先搜索并选择股票"
                    price == null || price <= 0 -> error = "请输入有效的买入价格"
                    qty == null || qty <= 0 -> error = "请输入有效的持有数量"
                    else -> onConfirm(stock.code, stock.name, price, qty)
                }
            }) { Text("确认") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun EditStockDialog(
    stock: PortfolioStock, onDismiss: () -> Unit, onConfirm: (PortfolioStock) -> Unit
) {
    var buyPrice by remember { mutableStateOf(stock.buyPrice.toString()) }
    var quantity by remember { mutableStateOf(stock.quantity.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑持仓 - ${stock.name}") },
        text = {
            Column {
                Text("股票代码: ${PortfolioViewModel.displayCode(stock.code)}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = buyPrice, onValueChange = { buyPrice = it; error = null }, label = { Text("买入价格（加仓后修改为均价）") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = quantity, onValueChange = { quantity = it; error = null }, label = { Text("持有数量（股）") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                error?.let { Spacer(modifier = Modifier.height(4.dp)); Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val price = buyPrice.toDoubleOrNull(); val qty = quantity.toIntOrNull()
                when {
                    price == null || price <= 0 -> error = "请输入有效的买入价格"
                    qty == null || qty <= 0 -> error = "请输入有效的持有数量"
                    else -> { val updated = stock.copy(buyPrice = price, quantity = qty); updated.updateCurrentPrice(stock.currentPrice); onConfirm(updated) }
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
