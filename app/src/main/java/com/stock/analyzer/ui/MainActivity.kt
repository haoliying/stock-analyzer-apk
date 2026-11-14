package com.stock.analyzer.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.stock.analyzer.ui.market.MarketScreen
import com.stock.analyzer.ui.portfolio.PortfolioScreen
import com.stock.analyzer.ui.list.StockListScreen
import com.stock.analyzer.ui.detail.StockDetailScreen
import com.stock.analyzer.ui.theme.StockAnalyzerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StockAnalyzerTheme {
                StockAnalyzerApp()
            }
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
    BottomNavItem("stock_list", "行情", Icons.Default.ShowChart)
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
