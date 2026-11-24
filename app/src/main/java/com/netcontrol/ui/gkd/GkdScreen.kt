package com.netcontrol.ui.gkd

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netcontrol.core.accessibility.SplashBlockService
import com.netcontrol.core.gkd.selector.GkdSelectorMatcher
import com.netcontrol.core.gkd.selector.GkdSelectorParser
import com.netcontrol.core.gkd.snapshot.GkdSnapshotDocument
import com.netcontrol.core.gkd.snapshot.GkdSnapshotManager
import com.netcontrol.core.gkd.snapshot.SnapshotNodeAdapter
import com.netcontrol.core.gkd.subscription.GkdSubscriptionRepository
import com.netcontrol.data.prefs.FirewallSettings
import com.netcontrol.data.prefs.SettingsDataStore
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.measureTimeMillis

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GkdScreen(
    settingsDataStore: SettingsDataStore,
    gkdRepository: GkdSubscriptionRepository,
    snapshotManager: GkdSnapshotManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val settings by settingsDataStore.settingsFlow.collectAsStateWithLifecycle(initialValue = FirewallSettings())
    val serviceConnected by SplashBlockService.isConnected.collectAsStateWithLifecycle()
    val subscriptions by gkdRepository.subscriptionsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val groupConfigs by gkdRepository.groupConfigsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val registry by gkdRepository.compiledRegistry.collectAsStateWithLifecycle()
    val actionLogs by gkdRepository.actionLogsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val snapshots by snapshotManager.snapshots.collectAsStateWithLifecycle()

    var subTab by remember { mutableIntStateOf(0) } // 0=订阅规则, 1=触发日志, 2=快照与试跑
    var isBusy by remember { mutableStateOf(false) }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    var showUrlDialog by remember { mutableStateOf(false) }
    var showJson5Dialog by remember { mutableStateOf(false) }
    var expandedSubId by remember { mutableStateOf<Long?>(null) }
    var pendingExportSnapshot by remember { mutableStateOf<GkdSnapshotDocument?>(null) }

    val exportSnapshotLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val doc = pendingExportSnapshot
        pendingExportSnapshot = null
        if (uri != null && doc != null) {
            scope.launch {
                val ok = snapshotManager.exportSnapshotToUri(doc, uri)
                statusBanner = if (ok) "快照 JSON 已导出（可直接导入 i.gkd.li 审查）" else "导出快照失败"
            }
        }
    }

    if (showUrlDialog) {
        var urlInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("通过 URL 导入 GKD 订阅") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "支持标准 GKD 远程订阅链接（.json / .json5，如 AIsouler、lisonge 或自建订阅源）：",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("https://.../gkd.json5") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = urlInput.isNotBlank() && !isBusy,
                    onClick = {
                        val targetUrl = urlInput.trim()
                        showUrlDialog = false
                        scope.launch {
                            isBusy = true
                            val res = gkdRepository.importFromUrl(targetUrl)
                            isBusy = false
                            res.onSuccess { sub ->
                                statusBanner = "导入成功：「${sub.name}」v${sub.version}（${sub.appCount} 应用，${sub.ruleCount} 条规则）"
                            }.onFailure { err ->
                                statusBanner = "导入失败：${err.message}"
                            }
                        }
                    }
                ) { Text("下载并导入") }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false }) { Text("取消") }
            }
        )
    }

    if (showJson5Dialog) {
        var jsonInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showJson5Dialog = false },
            title = { Text("粘贴本地 JSON5 规则订阅") },
            text = {
                OutlinedTextField(
                    value = jsonInput,
                    onValueChange = { jsonInput = it },
                    label = { Text("支持注释、无引号键与单引号字符串") },
                    minLines = 6,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = jsonInput.isNotBlank() && !isBusy,
                    onClick = {
                        val raw = jsonInput
                        showJson5Dialog = false
                        scope.launch {
                            isBusy = true
                            val res = gkdRepository.importRawJson5(raw)
                            isBusy = false
                            res.onSuccess { sub ->
                                statusBanner = "本地规则导入成功：「${sub.name}」（${sub.ruleCount} 条规则）"
                            }.onFailure { err ->
                                statusBanner = "JSON5 解析失败：${err.message}"
                            }
                        }
                    }
                ) { Text("解析并保存") }
            },
            dismissButton = {
                TextButton(onClick = { showJson5Dialog = false }) { Text("取消") }
            }
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (isBusy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // 顶部 GKD 引擎总控横幅
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            )
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("GKD 全量跳过引擎", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            text = "无障碍：${if (serviceConnected) "已连接" else "未开启"} · " +
                                "生效 ${registry.enabledGlobalGroups.size} 全局组 / ${registry.enabledAppGroupsByPkg.size} 应用",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Switch(
                        checked = settings.gkdEngineEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch { settingsDataStore.setGkdEngineEnabled(enabled) }
                        }
                    )
                }
                if (!serviceConnected) {
                    Button(onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    }) { Text("前往系统无障碍开启 NetControl") }
                }
                statusBanner?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            SegmentedButton(
                selected = subTab == 0,
                onClick = { subTab = 0 },
                shape = SegmentedButtonDefaults.itemShape(0, 3)
            ) { Text("订阅 (${subscriptions.size})") }
            SegmentedButton(
                selected = subTab == 1,
                onClick = { subTab = 1 },
                shape = SegmentedButtonDefaults.itemShape(1, 3)
            ) { Text("触发记录 (${actionLogs.size})") }
            SegmentedButton(
                selected = subTab == 2,
                onClick = { subTab = 2 },
                shape = SegmentedButtonDefaults.itemShape(2, 3)
            ) { Text("快照 (${snapshots.size})") }
        }

        when (subTab) {
            0 -> {
                val configMap = remember(groupConfigs) {
                    groupConfigs.associateBy { Triple(it.subId, it.appId, it.groupKey) }
                }
                val parsedSubMap = remember(registry) {
                    registry.subscriptions.associateBy { it.id }
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        FlowRow(
                            modifier = Modifier.padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(onClick = { showUrlDialog = true }) {
                                Icon(Icons.Default.AddLink, contentDescription = null)
                                Text(" 导入 URL 订阅")
                            }
                            OutlinedButton(onClick = { showJson5Dialog = true }) {
                                Icon(Icons.Default.Code, contentDescription = null)
                                Text(" 粘贴 JSON5")
                            }
                            OutlinedButton(
                                enabled = !isBusy,
                                onClick = {
                                    scope.launch {
                                        isBusy = true
                                        statusBanner = gkdRepository.updateAllRemoteSubscriptions()
                                        isBusy = false
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Text(" 全部更新")
                            }
                        }
                    }

                    items(items = subscriptions, key = { it.subId }) { sub ->
                        val parsed = parsedSubMap[sub.subId]
                        val expanded = expandedSubId == sub.subId
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("${sub.name} (v${sub.version})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            text = "全局组 ${sub.globalGroupCount} · 应用 ${sub.appCount} · 规则 ${sub.ruleCount}" +
                                                if (sub.author.isNotBlank()) " · ${sub.author}" else "",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = {
                                            scope.launch { gkdRepository.deleteSubscription(sub.subId) }
                                        }) { Icon(Icons.Default.Delete, contentDescription = "删除订阅") }
                                        Switch(
                                            checked = sub.isEnabled,
                                            onCheckedChange = { enabled ->
                                                scope.launch { gkdRepository.setSubscriptionEnabled(sub.subId, enabled) }
                                            }
                                        )
                                    }
                                }

                                OutlinedButton(
                                    onClick = { expandedSubId = if (expanded) null else sub.subId },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(if (expanded) "收起内部规则组" else "管理内部全局/应用规则组")
                                }

                                if (expanded && parsed != null) {
                                    HorizontalDivider()
                                    parsed.globalGroups.forEach { gg ->
                                        val isOn = configMap[Triple(sub.subId, "*", gg.key)]?.enabled ?: gg.enableByDefault
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("[全局] ${gg.name}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                                if (gg.desc.isNotBlank()) {
                                                    Text(gg.desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                            Switch(
                                                checked = isOn,
                                                onCheckedChange = { checked ->
                                                    scope.launch { gkdRepository.setGroupEnabled(sub.subId, "*", gg.key, checked) }
                                                }
                                            )
                                        }
                                    }
                                    parsed.apps.values.take(25).forEach { appSub ->
                                        appSub.groups.forEach { ag ->
                                            val isOn = configMap[Triple(sub.subId, appSub.appId, ag.key)]?.enabled ?: ag.enableByDefault
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    "[${appSub.name}] ${ag.name}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Switch(
                                                    checked = isOn,
                                                    onCheckedChange = { checked ->
                                                        scope.launch { gkdRepository.setGroupEnabled(sub.subId, appSub.appId, ag.key, checked) }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }

            1 -> {
                val fmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("最近自动跳过记录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = { scope.launch { gkdRepository.clearActionLogs() } }) { Text("清空记录") }
                        }
                    }
                    if (actionLogs.isEmpty()) {
                        item {
                            Text(
                                "暂无触发记录。开启无障碍后打开带广告的 App（如百度网盘），命中规则后将实时记录在此。",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    } else {
                        items(items = actionLogs, key = { it.id }) { log ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(
                                            "${log.groupName} · ${log.action} (${log.costMs}ms)",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(fmt.format(Date(log.timestamp)), style = MaterialTheme.typography.labelSmall)
                                    }
                                    Text(
                                        "${log.packageName} / ${log.activityId.substringAfterLast('.')}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    if (log.matchedText.isNotBlank()) {
                                        Text("命中文本：${log.matchedText}", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }

            else -> {
                SnapshotAndTesterPanel(
                    snapshots = snapshots,
                    serviceConnected = serviceConnected,
                    onScheduleCapture = {
                        statusBanner = "已启动 3 秒倒计时，请立刻切到目标 App 屏幕，3 秒后将自动抓取完整节点快照！"
                        SplashBlockService.scheduleDelayedSnapshotCapture(delaySec = 3) { doc ->
                            statusBanner = if (doc != null) {
                                "抓拍成功：${doc.appId}（共 ${doc.nodes.size} 个界面节点）"
                            } else {
                                "抓拍失败：请确认无障碍服务已开启"
                            }
                        }
                    },
                    onExportSnapshot = { doc ->
                        pendingExportSnapshot = doc
                        exportSnapshotLauncher.launch("gkd_snapshot_${doc.appId}_${doc.snapshotId}.json")
                    },
                    onDeleteSnapshot = { id -> snapshotManager.deleteSnapshot(id) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SnapshotAndTesterPanel(
    snapshots: List<GkdSnapshotDocument>,
    serviceConnected: Boolean,
    onScheduleCapture: () -> Unit,
    onExportSnapshot: (GkdSnapshotDocument) -> Unit,
    onDeleteSnapshot: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedSnapshotId by remember { mutableStateOf<Long?>(null) }
    var selectorInput by remember { mutableStateOf("""[text*="跳过"][text.length<=10][visibleToUser=true]""") }
    var testResult by remember { mutableStateOf<String?>(null) }

    val activeSnapshot = remember(snapshots, selectedSnapshotId) {
        snapshots.firstOrNull { it.snapshotId == selectedSnapshotId } ?: snapshots.firstOrNull()
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("界面节点快照抓取（兼容 i.gkd.li）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "点击下方按钮后 3 秒内切到任意广告/弹窗界面，即可完整抓拍屏幕节点树，用于编写或调试自定义 GKD 选择器。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(
                        enabled = serviceConnected,
                        onClick = onScheduleCapture,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null)
                        Text(" 3 秒后抓拍当前屏幕节点快照")
                    }
                }
            }
        }

        if (activeSnapshot != null) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "离线选择器实时试跑（目标：${activeSnapshot.appId} · ${activeSnapshot.nodes.size} 节点）",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        OutlinedTextField(
                            value = selectorInput,
                            onValueChange = { selectorInput = it },
                            label = { Text("输入任意 GKD 高级选择器") },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(onClick = {
                            val resText = try {
                                val parsed = GkdSelectorParser.parse(selectorInput)
                                val adapter = SnapshotNodeAdapter(activeSnapshot.nodes)
                                val root = adapter.root
                                if (root == null) {
                                    "快照为空"
                                } else {
                                    var matched: com.netcontrol.core.gkd.snapshot.GkdSnapshotNode? = null
                                    val ms = measureTimeMillis {
                                        matched = GkdSelectorMatcher.matchFirst(
                                            selector = parsed,
                                            root = root,
                                            adapter = adapter,
                                            packageName = activeSnapshot.appId,
                                            fastQuery = false
                                        )
                                    }
                                    if (matched != null) {
                                        val m = matched!!
                                        "命中 (${ms}ms)!\n" +
                                            "节点#${m.id} ${m.name}\n" +
                                            "vid=${m.vid} | text=${m.text} | desc=${m.desc}\n" +
                                            "clickable=${m.clickable} | bounds=[${m.left},${m.top},${m.right},${m.bottom}]"
                                    } else {
                                        "未命中任何节点 (${ms}ms)"
                                    }
                                }
                            } catch (e: Throwable) {
                                "选择器语法错误: ${e.message}"
                            }
                            testResult = resText
                        }) { Text("在当前快照上运行选择器") }

                        testResult?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }

        items(items = snapshots, key = { it.snapshotId }) { doc ->
            Card(
                onClick = { selectedSnapshotId = doc.snapshotId },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(doc.appId.ifBlank { "未知应用" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${doc.activityId.substringAfterLast('.')} · ${doc.nodes.size} 节点 · ${doc.screenWidth}x${doc.screenHeight}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Row {
                        TextButton(onClick = { onExportSnapshot(doc) }) { Text("导出 JSON") }
                        IconButton(onClick = { onDeleteSnapshot(doc.snapshotId) }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除快照")
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}
