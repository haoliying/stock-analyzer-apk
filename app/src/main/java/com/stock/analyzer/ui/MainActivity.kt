package com.stock.analyzer.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.stock.analyzer.data.repository.AppSettings
import com.stock.analyzer.ui.detail.StockDetailScreen
import com.stock.analyzer.ui.list.StockListScreen
import com.stock.analyzer.ui.market.MarketScreen
import com.stock.analyzer.ui.portfolio.PortfolioScreen
import com.stock.analyzer.ui.settings.AppLock
import com.stock.analyzer.ui.settings.SettingsScreen
import com.stock.analyzer.ui.theme.StockAnalyzerTheme

/**
 * 入口 Activity。
 *
 * 用 FragmentActivity（而不是 ComponentActivity）只是因为 androidx.biometric 1.1.0
 * 的 BiometricPrompt 要求 FragmentActivity；它本来就是 ComponentActivity 的子类，
 * Compose 那套照旧。
 */
class MainActivity : FragmentActivity() {

    private val settings by lazy { AppSettings.get(this) }

    /** 是否还锁着。整个进程只有这一个入口 Activity，直接存在它上面就够 */
    private val locked = mutableStateOf(false)

    /** 解锁页上的提示（识别失败原因、不可用原因） */
    private val lockHint = mutableStateOf<String?>(null)

    /** onStop 记一笔，回到前台时才知道"这是从后台回来的"，不是首次进入 */
    private var leftBackground = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 冷启动：开关是开着的那就锁上，未解锁前不组 NavHost —— 数据请求都不会发出
        locked.value = settings.appLockEnabled
        setContent {
            StockAnalyzerTheme {
                if (locked.value) {
                    LockScreen(
                        hint = lockHint.value,
                        onUnlock = { requestUnlock() },
                        onExit = { finish() }
                    )
                } else {
                    StockAnalyzerApp()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (settings.appLockEnabled && leftBackground) {
            leftBackground = false
            lockHint.value = null
            locked.value = true
        }
    }

    override fun onStop() {
        super.onStop()
        if (settings.appLockEnabled) leftBackground = true
    }

    private fun requestUnlock() {
        AppLock.authenticate(this, "解锁「股票分析」") { ok, message ->
            if (ok) {
                locked.value = false
                lockHint.value = null
            } else {
                lockHint.value = if (message == AppLock.CANCELLED)
                    "已取消：没解锁就什么都不显示。要进来请点「解锁」。"
                else
                    message
            }
        }
    }
}

/**
 * 解锁页。进画面就自己弹一次系统识别框（识别不到就停在这儿，不绕过去）。
 *
 * 刻意不显示任何行情、名称、上次页面 —— 锁的意义就是没认出来时什么都看不到。
 */
@Composable
private fun LockScreen(hint: String?, onUnlock: () -> Unit, onExit: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Text("股票分析", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "应用锁已开启：要用人脸或指纹解锁才能看到行情、持仓和分析",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            hint?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(22.dp))
            Button(onClick = onUnlock) { Text("解锁") }
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onExit) { Text("退出应用", fontSize = 13.sp) }
            Spacer(Modifier.height(18.dp))
            Text(
                "生物特征只在设备本地比对，本应用不读取、不上传、不保存；" +
                    "如果识别一直不可用，请到手机「设置 → 生物识别和密码」检查录入。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

val bottomNavItems = listOf(
    BottomNavItem("market", "今日候选", Icons.Default.Star),
    BottomNavItem("portfolio", "我的持仓", Icons.Default.AccountBalanceWallet),
    BottomNavItem("stock_list", "行情", Icons.Default.ShowChart),
    BottomNavItem("settings", "设置", Icons.Default.Settings)
)

@Composable
fun StockAnalyzerApp() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showBottomBar = currentRoute in bottomNavItems.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        NavigationBarItem(
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                            selected = currentRoute == item.route,
                            onClick = {
                                if (currentRoute != item.route) {
                                    navController.navigate(item.route) {
                                        popUpTo(navController.graph.startDestinationId) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "market",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("market") {
                MarketScreen(
                    onStockClick = { code ->
                        navController.navigate("stock_detail/$code")
                    }
                )
            }
            composable("portfolio") {
                PortfolioScreen(
                    onStockClick = { code ->
                        navController.navigate("stock_detail/$code")
                    }
                )
            }
            composable("stock_list") {
                StockListScreen(
                    onStockClick = { code ->
                        navController.navigate("stock_detail/$code")
                    }
                )
            }
            composable("settings") {
                SettingsScreen()
            }
            composable(
                route = "stock_detail/{stockCode}",
                arguments = listOf(navArgument("stockCode") { type = NavType.StringType })
            ) { backStackEntry ->
                val stockCode = backStackEntry.arguments?.getString("stockCode") ?: ""
                StockDetailScreen(
                    stockCode = stockCode,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
