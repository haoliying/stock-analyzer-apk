package com.stock.analyzer.ui.asset

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stock.analyzer.data.model.AssetClass
import com.stock.analyzer.data.repository.AssetClassSelection

/**
 * 「A股 / 贵金属」切换条。今日候选、我的持仓、行情三页顶上都有同一条。
 *
 * 用 [AssetClass.entries] 循环渲染，不写死两个 if：以后加大宗商品、外汇，
 * 只要在枚举里添一条 + 配一个 provider，这条切换栏和持仓分类自己就长出来了。
 *
 * 切换只改变"看哪一类的数据"，不改变任何一类的算法口径 —— A 股那一轮的进度
 * 不会因为切到贵金属而被清掉（分析在 [com.stock.analyzer.analysis.MarketEngine] 里，
 * 跟界面无关）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetClassSwitcher(
    modifier: Modifier = Modifier,
    showScopeNote: Boolean = true
) {
    val context = LocalContext.current
    val selected by AssetClassSelection.current.collectAsState()

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssetClass.entries.forEach { ac ->
                FilterChip(
                    selected = ac == selected,
                    onClick = { AssetClassSelection.select(context, ac) },
                    label = {
                        Text(ac.label, fontSize = 13.sp, fontWeight = if (ac == selected) FontWeight.Bold else FontWeight.Normal)
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (showScopeNote) {
            Text(
                text = selected.scopeNote,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 0.dp)
            )
        }
    }
}
