package com.stock.analyzer.ui.portfolio

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.stock.analyzer.analysis.NonStockEngine
import com.stock.analyzer.data.model.*
import java.util.Locale
import com.stock.analyzer.data.repository.AssetClassSelection
import com.stock.analyzer.data.repository.StockInfoRepository
import com.stock.analyzer.ui.asset.AssetClassSwitcher
import com.stock.analyzer.ui.nonstock.NonStockCopy
import com.stock.analyzer.ui.nonstock.NonStockNoteCard
import com.stock.analyzer.ui.nonstock.shortStamp

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
    val assetClass by AssetClassSelection.current.collectAsState()

    // 一屏只看一类：持仓列表里两类是混着存的（代码前缀区分），切换只影响这里显示谁
    val visible = portfolio.filter { it.assetClass == assetClass }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的持仓 · ${assetClass.label}") },
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
            AssetClassSwitcher()

            if (assetClass.supportsAdvice) {
                if (visible.isNotEmpty()) {
                    PortfolioSummary(
                        totalValue = viewModel.getTotalValue(),
                        totalProfit = viewModel.getTotalProfit(),
                        totalProfitPercent = viewModel.getTotalProfitPercent()
                    )
                }
            } else if (visible.isNotEmpty()) {
                // 贵金属那一类不合成一个总数：币种不同（美元/盎司、元/克、元/千克），
                // 折到一起需要一个没核过的汇率源，宁可分币种各报各的
                NonStockTotalsCard(totals = viewModel.metalTotals())
            }

            if (isLoading && visible.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (visible.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.AccountBalanceWallet, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("这一类暂无持仓", style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            if (assetClass.supportsAdvice) "点击右下角 + 添加股票"
                            else "点击右下角 + 添加非股票资产持仓（按品种的单位填数量）",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 80.dp)) {
                    items(visible) { stock ->
                        if (stock.isNonStock) {
                            NonStockHoldCard(
                                stock = stock,
                                onClick = { onStockClick(stock.code) },
                                onEdit = { viewModel.updateStock(it) },
                                onDelete = { viewModel.removeStock(stock.code) }
                            )
                        } else {
                            val advice = advices.find { it.code == stock.code }
                            PortfolioStockCard(
                                stock = stock, advice = advice,
                                onClick = { onStockClick(stock.code) },
                                onEdit = { viewModel.updateStock(it) },
                                onDelete = { viewModel.removeStock(stock.code) }
                            )
                        }
                    }
                    if (!assetClass.supportsAdvice) {
                        item {
                            NonStockNoteCard(
                                title = "这一类持仓为什么没有加仓区间、减仓区间和止损价",
                                lines = NonStockCopy.noAdvice(assetClass),
                                accent = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }
            }
        }

        if (showAddDialog) {
            if (assetClass.supportsAdvice) {
                AddStockDialog(
                    onDismiss = { viewModel.hideAddDialog() },
                    onConfirm = { code, name, price, quantity -> viewModel.addStock(code, name, price, quantity) }
                )
            } else {
                AddNonStockDialog(
                    assetClass = assetClass,
                    onDismiss = { viewModel.hideAddDialog() },
                    onConfirm = { code, name, price, quantity -> viewModel.addStock(code, name, price, quantity) }
                )
            }
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

/**
 * 贵金属持仓卡：只有成本、现价、数量与盈亏，没有建议区。
 *
 * 三个数字都必须带单位。伦敦金 4,000 是美元/盎司、沪金 830 是元/克、沪银 15,000 是元/千克，
 * 少写单位就等于写错价格；盈亏金额也在自己那个币种里算，不跨币种相加。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NonStockHoldCard(
    stock: PortfolioStock,
    onClick: () -> Unit,
    onEdit: (PortfolioStock) -> Unit,
    onDelete: () -> Unit
) {
    val inst = stock.nonStockInstrument
    val unit = inst?.unit ?: "每单位"
    val qtyUnit = inst?.qtyUnit ?: "单位"
    val symbol = inst?.currencySymbol ?: ""
    var showMenu by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    val quotes by NonStockEngine.quotes.collectAsState()
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
                    Text(
                        text = "${stock.code} · $unit${inst?.let { " · ${it.source}" } ?: ""}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                Column {
                    Text("买入价（$unit）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(String.format(Locale.US, "%s%,.2f", symbol, stock.buyPrice), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("现价（$unit）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(String.format(Locale.US, "%s%,.2f", symbol, stock.currentPrice), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = profitColor)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("盈亏", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(String.format(Locale.US, "%s%+,.2f (%+.2f%%)", symbol, stock.profit, stock.profitPercent), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = profitColor)
                }
            }
            Text(
                text = "持有 $qtyUnit 数 ${String.format(Locale.US, "%,d", stock.quantity)}　$qtyUnit × 现价 = ${String.format(Locale.US, "%s%,.2f", symbol, stock.currentPrice * stock.quantity)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // 报价时间是该市场当地的时间：境外与境内经常差一个交易日，这里只报自己那一条
            val stamp = quotes[stock.code]?.let { "报价时间 ${it.shortStamp()}（该市场当地）" }
                ?: "还没有取到这个品种的现价，上面的数字是上一次的结果"
            Text(stamp, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
    if (showEditDialog) {
        EditStockDialog(
            stock = stock,
            priceLabel = "买入单价（$unit）",
            qtyLabel = "持有数量（$qtyUnit）",
            onDismiss = { showEditDialog = false },
            onConfirm = { onEdit(it); showEditDialog = false }
        )
    }
}

/** 非股票持仓的合计：按币种各列一条，不折算成同一个数 */
@Composable
fun NonStockTotalsCard(totals: List<MetalTotal>) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("非股票持仓（按币种分列，不折算）", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            totals.forEach { t ->
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("${t.currency} 市值（${t.holds} 笔）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(String.format(Locale.US, "%s%,.2f", t.symbol, t.value), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    val c = when { t.profit > 0 -> Color(0xFFE53935); t.profit < 0 -> Color(0xFF43A047); else -> MaterialTheme.colorScheme.onSurface }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("盈亏", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(String.format(Locale.US, "%s%+,.2f (%+.2f%%)", t.symbol, t.profit, t.profitPercent), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c)
                    }
                }
            }
        }
    }
}

/**
 * 添加非股票资产持仓：品种从该大类的固定表里选，不做搜索。
 *
 * 每一类的品种数是固定的（贵金属 6 条、大宗商品 25 条、外汇 8 条），搜索框只会让人以为
 * 可以搜到别的；数量和价格都必须带着单位写，避免"沪银按克填了数量、价格是每千克的"这种错。
 */
@Composable
fun AddNonStockDialog(
    assetClass: AssetClass,
    onDismiss: () -> Unit,
    onConfirm: (code: String, name: String, buyPrice: Double, quantity: Int) -> Unit
) {
    val instruments = NonStockCatalog.forClass(assetClass)
    var selected by remember { mutableStateOf(instruments.first()) }
    var buyPrice by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val quotes by NonStockEngine.quotes.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加${assetClass.label}持仓") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                instruments.forEach { inst ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = inst }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = inst.code == selected.code, onClick = { selected = inst })
                        Column(modifier = Modifier.weight(1f)) {
                            Text(inst.name, fontSize = 14.sp, fontWeight = if (inst.code == selected.code) FontWeight.Bold else FontWeight.Normal)
                            Text("${inst.code} · ${inst.unit}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            quotes[inst.code]?.let { NonStockCopy.price(it.price) } ?: "",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = buyPrice, onValueChange = { buyPrice = it; error = null },
                    label = { Text("买入单价（${selected.unit}）") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = quantity, onValueChange = { quantity = it; error = null },
                    label = { Text("持有数量（${selected.qtyUnit}）") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "盈亏按「现价 − 买入价 × 数量」自己算，这一类不给加减仓区间与止损价（原因见今日候选那一页的说明）。",
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                error?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val price = buyPrice.toDoubleOrNull()
                val qty = quantity.toIntOrNull()
                when {
                    price == null || price <= 0 -> error = "请输入有效的买入单价（${selected.unit}）"
                    qty == null || qty <= 0 -> error = "请输入有效的持有数量（${selected.qtyUnit}）"
                    else -> onConfirm(selected.code, selected.name, price, qty)
                }
            }) { Text("确认") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
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
                    // 连现价都没有（行情和日线都取不到）时不编造区间，直接写"无法估算"
                    Text(rangeText(advice.addPriceRange), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFFE53935))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("减仓区间", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(rangeText(advice.reducePriceRange), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF43A047))
                }
            }
            // 止损价 + 风险提示（v4.1.0 新增：这套模型的价值主要在尾部风险控制）
            advice.stopLoss?.let {
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("止损价（2×ATR）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(String.format("¥%.2f", it), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF7B1FA2))
                }
            }
            advice.riskNote?.let {
                Spacer(modifier = Modifier.height(6.dp))
                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 16.sp)
            }
        }
    }
}

/** 区间为 0 说明连现价都没取到，这时不印 "0.00 - 0.00" */
private fun rangeText(range: PriceRange): String =
    if (range.high <= 0 || range.low <= 0) "无法估算"
    else "${String.format("%.2f", range.low)} - ${String.format("%.2f", range.high)}"

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
            // 输入完整代码（可不带前缀）时优先精确命中，直接选中
            val normalizedCode = PortfolioViewModel.normalizeStockCode(searchText.trim())
            val typedCode = searchText.trim().replace(Regex("[^0-9]"), "")
            val exactMatch = if (typedCode.length == 6) stockInfoRepo.resolveCode(normalizedCode) else null
            if (exactMatch != null) {
                selectedStock = exactMatch
                searchResults = emptyList()
            } else {
                val local = stockInfoRepo.searchByPinyin(searchText)
                if (local.isNotEmpty()) {
                    searchResults = local
                } else {
                    // 内置列表查不到就联网搜，别让"添加持仓"只认几千只老股票
                    kotlinx.coroutines.delay(320)
                    searchResults = stockInfoRepo.searchOnline(searchText)
                }
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
    stock: PortfolioStock,
    onDismiss: () -> Unit,
    onConfirm: (PortfolioStock) -> Unit,
    // 贵金属那一类每单位都不一样（元/克、元/千克、美元/盎司），标签必须跟着品种走
    priceLabel: String = "买入价格（加仓后修改为均价）",
    qtyLabel: String = "持有数量（股）"
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
                OutlinedTextField(value = buyPrice, onValueChange = { buyPrice = it; error = null }, label = { Text(priceLabel) }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = quantity, onValueChange = { quantity = it; error = null }, label = { Text(qtyLabel) }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
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
