package com.stock.analyzer.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 「设置」页：版本信息、应用锁开关、应用内操作说明。
 *
 * 三块内容都放这一页，是为了让"我装的是哪一版"和"这一版怎么用"在同一个地方能查到 ——
 * 早期版本界面里没写版本号，反馈时说不清装的是哪包，排查全靠猜。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val appLock by viewModel.appLockEnabled.collectAsState()
    val availability by viewModel.availability.collectAsState()
    val notice by viewModel.lockNotice.collectAsState()
    val info = viewModel.version
    // 分节的展开状态按标题记住，翻页回来不会全部缩回去
    val open: SnapshotStateMap<String, Boolean> = remember { mutableStateMapOf<String, Boolean>() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // ---------------------------------------------------------------- 版本
            SectionCard("版本") {
                Line("应用", "股票分析")
                Line("版本", "v${info.versionName}（versionCode ${info.versionCode}）")
                Line("安装包", if (info.debuggable) "debug（未混淆，仅供调试）" else "release")
                Line("包名", info.packageName)
                Line("评分口径", viewModel.engineLine)
                Line("源码与历史包", "github.com/haoliying/stock-analyzer-apk")

                Spacer(Modifier.height(10.dp))
                Text("这个版本改了什么", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                UsageGuide.releaseNotes.forEach { Bullet(it) }

                Spacer(Modifier.height(8.dp))
                Expandable(
                    title = "近几版（点开展开）",
                    expanded = open["history"] == true,
                    onToggle = { toggle(open, "history") }
                ) {
                    UsageGuide.history.forEach { Bullet(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }

            // ---------------------------------------------------------------- 应用锁
            SectionCard("应用锁") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("需要面部或指纹才能打开", fontSize = 15.sp)
                        Text(
                            "开启后：重新打开应用、或从后台切回来，都要过一次系统生物识别；点「先不进」就停在解锁页，行情不会露出来",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = appLock, onCheckedChange = { viewModel.setAppLock(it) })
                }

                Spacer(Modifier.height(6.dp))
                val statusColor = if (availability.usable) COLOR_OK else MaterialTheme.colorScheme.error
                Text("识别状态：${availability.status}", fontSize = 12.sp, color = statusColor)
                notice?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }

                TextButton(onClick = { viewModel.refreshAvailability() }) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("重新检测识别状态", fontSize = 13.sp)
                }

                UsageGuide.sections.first { it.title == "应用锁" }.lines.forEach { Bullet(it) }
            }

            // ---------------------------------------------------------------- 操作说明
            SectionCard("操作说明") {
                Text(
                    "这一节是仓库里 USAGE.md 的应用内版：逐条对着 v${info.versionName} 的代码写的，" +
                        "界面上出现过的文案尽量用原文。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                // 「应用锁」那一节的内容直接放在上面的开关卡里了，这里不重复列
                val guideSections = UsageGuide.sections.filter { it.title != "应用锁" }
                val allExpanded = guideSections.all { open[it.title] == true }
                TextButton(onClick = {
                    guideSections.forEach { open[it.title] = !allExpanded }
                }) {
                    Text(if (allExpanded) "全部收起" else "全部展开", fontSize = 13.sp)
                }
                UsageGuide.sections.filter { it.title != "应用锁" }.forEach { section ->
                    Expandable(
                        title = section.title,
                        expanded = open[section.title] == true,
                        onToggle = { toggle(open, section.title) }
                    ) {
                        section.lines.forEach { Bullet(it) }
                    }
                }
            }

            Text(
                "本应用仅供学习与研究，不构成投资建议。历史回测结果不代表未来收益，股市有风险。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
    }
}

private val COLOR_OK = Color(0xFF2E7D32)

/** 展开/收起某一节：显式接 Map 类型，省得类型推断在委托属性上绕圈 */
private fun toggle(open: SnapshotStateMap<String, Boolean>, key: String) {
    open[key] = open[key] != true
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp)
        )
        Text(value, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Bullet(text: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.padding(vertical = 1.dp)) {
        Text("· ", fontSize = 12.sp, color = color)
        Text(text, fontSize = 12.sp, color = color, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Expandable(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开"
            )
        }
        if (expanded) {
            Spacer(Modifier.height(2.dp))
            content()
        }
    }
}
