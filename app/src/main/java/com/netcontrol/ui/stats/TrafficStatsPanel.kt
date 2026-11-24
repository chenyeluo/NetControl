package com.netcontrol.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netcontrol.data.local.entity.TrafficBucketType
import com.netcontrol.data.repository.NetControlRepository
import com.netcontrol.data.stats.AppTrafficRankItem
import com.netcontrol.data.stats.GroupTrafficRankItem
import com.netcontrol.data.stats.TimeSeriesBarPoint
import com.netcontrol.data.stats.TrafficStatsAggregator
import com.netcontrol.data.stats.TrafficStatsCollector
import com.netcontrol.ui.components.AppIconImage
import com.netcontrol.ui.groups.resolveGroupIcon
import kotlinx.coroutines.launch

private enum class TrafficRangeOption(val label: String, val bucketType: String, val lookbackMs: Long) {
    HOURS_24("近 24 小时", TrafficBucketType.HOURLY, 24L * 3600_000L),
    DAYS_7("近 7 天", TrafficBucketType.DAILY, 7L * 24L * 3600_000L),
    DAYS_30("近 30 天", TrafficBucketType.DAILY, 30L * 24L * 3600_000L),
    MONTHLY("按月汇总", TrafficBucketType.MONTHLY, 365L * 24L * 3600_000L)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrafficStatsPanel(
    repository: NetControlRepository,
    trafficStatsCollector: TrafficStatsCollector,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedRange by remember { mutableStateOf(TrafficRangeOption.DAYS_7) }
    var showGroupTab by remember { mutableStateOf(false) }

    val hasPermission = trafficStatsCollector.hasUsageStatsPermission()
    val isSyncing by trafficStatsCollector.isSyncing.collectAsStateWithLifecycle()
    val syncStatus by trafficStatsCollector.syncStatus.collectAsStateWithLifecycle()

    val fromMs = remember(selectedRange) { System.currentTimeMillis() - selectedRange.lookbackMs }
    val bucketsFlow = remember(selectedRange, fromMs) {
        repository.observeTrafficBuckets(selectedRange.bucketType, fromMs)
    }
    val buckets by bucketsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val allApps by repository.allAppsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val groupsWithApps by repository.groupsWithAppsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    val summary = remember(buckets, allApps, groupsWithApps) {
        TrafficStatsAggregator.aggregate(buckets, allApps, groupsWithApps)
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission && syncStatus.lastSyncedAtMs == 0L) {
            trafficStatsCollector.syncTrafficStats()
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. 权限引导或零耗电采集控制卡
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                )
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("零耗电系统级流量统计", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "基于 Android 原生 NetworkStatsManager 统计，不接管或转发任何流量",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (isSyncing) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    if (!hasPermission) {
                        Text(
                            "需要授予「使用情况访问权限 (Usage Access)」才能读取各应用的 Wi-Fi 与移动数据流量。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(onClick = {
                            runCatching { context.startActivity(trafficStatsCollector.buildUsageAccessSettingsIntent()) }
                        }) { Text("前往授予使用情况访问权限") }
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !isSyncing,
                                onClick = { scope.launch { trafficStatsCollector.syncTrafficStats() } }
                            ) { Text(if (isSyncing) "采集中..." else "立即刷新同步") }

                            TextButton(onClick = {
                                scope.launch { repository.clearAllTrafficStats() }
                            }) { Text("清空本地汇总") }
                        }
                    }

                    Text(
                        syncStatus.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 2. 时间维度切换 + 总览卡片 + Canvas 堆叠柱状图
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TrafficRangeOption.entries.forEach { opt ->
                    FilterChip(
                        selected = selectedRange == opt,
                        onClick = { selectedRange = opt },
                        label = { Text(opt.label) }
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Column {
                            Text("总流量 (${selectedRange.label})", style = MaterialTheme.typography.labelMedium)
                            Text(
                                text = TrafficStatsAggregator.formatBytes(summary.totalBytes),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "↓ ${TrafficStatsAggregator.formatBytes(summary.totalRxBytes)}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                "↑ ${TrafficStatsAggregator.formatBytes(summary.totalTxBytes)}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    val wifiRatio = if (summary.totalBytes > 0L) {
                        (summary.wifiBytes.toFloat() / summary.totalBytes.toFloat()).coerceIn(0f, 1f)
                    } else 0f

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            "Wi-Fi: ${TrafficStatsAggregator.formatBytes(summary.wifiBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "移动数据: ${TrafficStatsAggregator.formatBytes(summary.mobileBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(wifiRatio)
                                .height(10.dp)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }

                    if (summary.timeSeries.isNotEmpty()) {
                        HorizontalDivider()
                        Text("时段趋势（蓝=Wi-Fi / 橙=移动数据）", style = MaterialTheme.typography.labelSmall)
                        TrafficBarChart(
                            points = summary.timeSeries.takeLast(14),
                            wifiColor = MaterialTheme.colorScheme.primary,
                            mobileColor = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                        )
                    }
                }
            }
        }

        // 3. 排行视角切换：按应用 vs 按自定义分组
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !showGroupTab,
                    onClick = { showGroupTab = false },
                    label = { Text("单应用排行 (${summary.appRanking.size})") }
                )
                FilterChip(
                    selected = showGroupTab,
                    onClick = { showGroupTab = true },
                    label = { Text("分组累计排行 (${summary.groupRanking.size})") }
                )
            }
        }

        if (showGroupTab) {
            if (summary.groupRanking.isEmpty()) {
                item {
                    Text(
                        "当前时段暂无分组流量数据（请先将应用加入自定义分组并同步流量）。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else {
                items(items = summary.groupRanking, key = { it.groupId }) { item ->
                    GroupTrafficRow(item = item, maxBytes = summary.groupRanking.first().totalBytes.coerceAtLeast(1L))
                }
            }
        } else {
            if (summary.appRanking.isEmpty()) {
                item {
                    Text(
                        "暂无流量明细，授权后点击「立即刷新同步」即可拉取系统统计。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else {
                val maxAppBytes = summary.appRanking.first().totalBytes.coerceAtLeast(1L)
                items(items = summary.appRanking.take(50), key = { it.packageName }) { item ->
                    AppTrafficRow(item = item, maxBytes = maxAppBytes)
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun TrafficBarChart(
    points: List<TimeSeriesBarPoint>,
    wifiColor: Color,
    mobileColor: Color,
    modifier: Modifier = Modifier
) {
    val maxTotal = points.maxOfOrNull { it.totalBytes }?.coerceAtLeast(1L) ?: 1L
    Canvas(modifier = modifier) {
        val count = points.size.coerceAtLeast(1)
        val slotWidth = size.width / count
        val barWidth = (slotWidth * 0.62f).coerceAtLeast(4f)
        val xOffset = (slotWidth - barWidth) / 2f

        points.forEachIndexed { idx, p ->
            val totalRatio = (p.totalBytes.toFloat() / maxTotal.toFloat()).coerceIn(0f, 1f)
            val totalHeight = size.height * totalRatio
            val wifiHeight = if (p.totalBytes > 0L) totalHeight * (p.wifiBytes.toFloat() / p.totalBytes.toFloat()) else 0f
            val mobileHeight = (totalHeight - wifiHeight).coerceAtLeast(0f)

            val left = idx * slotWidth + xOffset
            if (wifiHeight > 0f) {
                drawRoundRect(
                    color = wifiColor,
                    topLeft = Offset(left, size.height - wifiHeight),
                    size = Size(barWidth, wifiHeight),
                    cornerRadius = CornerRadius(4f, 4f)
                )
            }
            if (mobileHeight > 0f) {
                drawRoundRect(
                    color = mobileColor,
                    topLeft = Offset(left, size.height - wifiHeight - mobileHeight),
                    size = Size(barWidth, mobileHeight),
                    cornerRadius = CornerRadius(4f, 4f)
                )
            }
        }
    }
}

@Composable
private fun AppTrafficRow(item: AppTrafficRankItem, maxBytes: Long) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AppIconImage(packageName = item.packageName, appName = item.appName, size = 36.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = item.appName + if (item.isUninstalled) " [已卸载]" else "",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = TrafficStatsAggregator.formatBytes(item.totalBytes),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                LinearProgressIndicator(
                    progress = { (item.totalBytes.toFloat() / maxBytes.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Wi-Fi ${TrafficStatsAggregator.formatBytes(item.wifiBytes)} · " +
                        "流量 ${TrafficStatsAggregator.formatBytes(item.mobileBytes)} · " +
                        "↓${TrafficStatsAggregator.formatBytes(item.rxBytes)} ↑${TrafficStatsAggregator.formatBytes(item.txBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun GroupTrafficRow(item: GroupTrafficRankItem, maxBytes: Long) {
    val color = remember(item.colorHex) {
        runCatching { Color(android.graphics.Color.parseColor(item.colorHex)) }.getOrElse { Color(0xFF0B57D0) }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(resolveGroupIcon(item.iconKey), contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${item.groupName} (${item.appCount} 应用)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        TrafficStatsAggregator.formatBytes(item.totalBytes),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                LinearProgressIndicator(
                    progress = { (item.totalBytes.toFloat() / maxBytes.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Wi-Fi ${TrafficStatsAggregator.formatBytes(item.wifiBytes)} · 移动数据 ${TrafficStatsAggregator.formatBytes(item.mobileBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
