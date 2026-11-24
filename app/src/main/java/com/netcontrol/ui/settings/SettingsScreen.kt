package com.netcontrol.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netcontrol.core.accessibility.SplashBlockService
import com.netcontrol.core.alarm.ExactAlarmScheduler
import com.netcontrol.core.backend.BackendType
import com.netcontrol.core.backend.DiagnosticProbeReport
import com.netcontrol.core.backend.shizuku.FirewallUserService
import com.netcontrol.core.backend.shizuku.ShizukuManager
import com.netcontrol.core.backend.shizuku.ShizukuStatus
import com.netcontrol.core.backend.vpn.BlackholeVpnBackend
import com.netcontrol.core.engine.RuleEngine
import com.netcontrol.core.notification.FirewallNotificationHelper
import com.netcontrol.data.backup.BackupManager
import com.netcontrol.data.prefs.FirewallSettings
import com.netcontrol.data.prefs.GlobalEmergencyMode
import com.netcontrol.data.prefs.SettingsDataStore
import com.netcontrol.data.repository.NetControlRepository
import com.netcontrol.data.stats.TrafficStatsCollector
import com.netcontrol.ui.stats.TrafficStatsPanel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    repository: NetControlRepository,
    settingsDataStore: SettingsDataStore,
    shizukuManager: ShizukuManager,
    vpnBackend: BlackholeVpnBackend,
    ruleEngine: RuleEngine,
    exactAlarmScheduler: ExactAlarmScheduler,
    trafficStatsCollector: TrafficStatsCollector,
    backupManager: BackupManager,
    onNavigateToGkd: () -> Unit = {},
    onNavigateToAuditLogs: () -> Unit = {},
    requestedSubTab: Int? = null,
    requestedSubTabNonce: Int = 0,
    modifier: Modifier = Modifier
) {
    var subTab by remember { mutableIntStateOf(requestedSubTab ?: 0) } // 0=流量统计, 1=加密备份, 2=权限与设置

    LaunchedEffect(requestedSubTabNonce) {
        if (requestedSubTab != null && requestedSubTabNonce > 0) {
            subTab = requestedSubTab
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            SegmentedButton(
                selected = subTab == 0,
                onClick = { subTab = 0 },
                shape = SegmentedButtonDefaults.itemShape(0, 3)
            ) { Text("流量统计") }
            SegmentedButton(
                selected = subTab == 1,
                onClick = { subTab = 1 },
                shape = SegmentedButtonDefaults.itemShape(1, 3)
            ) { Text("加密备份") }
            SegmentedButton(
                selected = subTab == 2,
                onClick = { subTab = 2 },
                shape = SegmentedButtonDefaults.itemShape(2, 3)
            ) { Text("权限与设置") }
        }

        when (subTab) {
            0 -> TrafficStatsPanel(
                repository = repository,
                trafficStatsCollector = trafficStatsCollector,
                modifier = Modifier.weight(1f)
            )
            1 -> BackupRestorePanel(
                backupManager = backupManager,
                modifier = Modifier.weight(1f)
            )
            else -> SystemAndLogsPanel(
                repository = repository,
                settingsDataStore = settingsDataStore,
                shizukuManager = shizukuManager,
                vpnBackend = vpnBackend,
                ruleEngine = ruleEngine,
                exactAlarmScheduler = exactAlarmScheduler,
                onNavigateToGkd = onNavigateToGkd,
                onNavigateToAuditLogs = onNavigateToAuditLogs,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SystemAndLogsPanel(
    repository: NetControlRepository,
    settingsDataStore: SettingsDataStore,
    shizukuManager: ShizukuManager,
    vpnBackend: BlackholeVpnBackend,
    ruleEngine: RuleEngine,
    exactAlarmScheduler: ExactAlarmScheduler,
    onNavigateToGkd: () -> Unit,
    onNavigateToAuditLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val settings by settingsDataStore.settingsFlow.collectAsStateWithLifecycle(initialValue = FirewallSettings())
    val shizukuStatus by shizukuManager.status.collectAsStateWithLifecycle()
    val alarmStatus by exactAlarmScheduler.status.collectAsStateWithLifecycle()
    val splashConnected by SplashBlockService.isConnected.collectAsStateWithLifecycle()
    val splashTraces by SplashBlockService.recentTraces.collectAsStateWithLifecycle()
    val recentLogs by repository.recentAuditLogsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    var probeReport by remember { mutableStateOf<DiagnosticProbeReport?>(null) }
    var probeStatus by remember { mutableStateOf<String?>(null) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var showVpnConflictDialog by remember { mutableStateOf(false) }

    val pm = context.getSystemService(PowerManager::class.java)
    val ignoringBatteryOpt = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
    var hasNotifPerm by remember { mutableStateOf(FirewallNotificationHelper.hasNotificationPermission(context)) }

    val notifPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotifPerm = granted
        feedbackMessage = if (granted) "通知权限已授予（Shizuku 断连/重启时可及时提醒）" else "未授予通知权限"
    }

    val vpnPrepareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            scope.launch {
                val run = ruleEngine.switchPreferredBackend(BackendType.BLACKHOLE_VPN)
                feedbackMessage = run.applyResult.message
            }
        } else {
            feedbackMessage = "已取消黑洞 VPN 权限授权"
        }
    }

    fun activateBlackholeVpn() {
        val intent = vpnBackend.prepareVpnIntent()
        if (intent != null) {
            vpnPrepareLauncher.launch(intent)
        } else {
            scope.launch {
                val run = ruleEngine.switchPreferredBackend(BackendType.BLACKHOLE_VPN)
                feedbackMessage = run.applyResult.message
            }
        }
    }

    if (showVpnConflictDialog) {
        AlertDialog(
            onDismissRequest = { showVpnConflictDialog = false },
            title = { Text("检测到其他 VPN / 代理运行中") },
            text = {
                Text("切换到「黑洞 VPN」会断开当前 Clash/v2ray 等代理软件。建议保持 Shizuku 主引擎以完美共存；确定要切换至黑洞 VPN 吗？")
            },
            confirmButton = {
                TextButton(onClick = {
                    showVpnConflictDialog = false
                    activateBlackholeVpn()
                }) { Text("仍然切换") }
            },
            dismissButton = {
                TextButton(onClick = { showVpnConflictDialog = false }) { Text("取消") }
            }
        )
    }

    val fmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. 系统权限与授权中心（从首页迁入）
        item {
            Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("系统权限与授权中心", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                    PermissionHealthRow(
                        icon = Icons.Default.Security,
                        title = "Shizuku 内核提权",
                        subtitle = when (shizukuStatus) {
                            ShizukuStatus.CONNECTED -> "已连接（免 Root 内核拦截，与 Clash 等代理共存）"
                            ShizukuStatus.PERMISSION_REQUIRED -> "已运行，等待授予 NetControl 权限"
                            ShizukuStatus.BINDING -> "正在绑定 UserService..."
                            ShizukuStatus.NOT_RUNNING -> "未启动（重启后需打开 Shizuku 激活）"
                        },
                        healthy = shizukuStatus == ShizukuStatus.CONNECTED,
                        actionLabel = when (shizukuStatus) {
                            ShizukuStatus.CONNECTED -> "重连"
                            ShizukuStatus.NOT_RUNNING -> "打开 Shizuku"
                            else -> "授权"
                        },
                        onAction = {
                            if (shizukuStatus == ShizukuStatus.NOT_RUNNING) {
                                val launch = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                if (launch != null) {
                                    context.startActivity(launch)
                                } else {
                                    shizukuManager.requestPermissionOrBind()
                                }
                            } else {
                                shizukuManager.requestPermissionOrBind()
                            }
                        }
                    )

                    HorizontalDivider()

                    PermissionHealthRow(
                        icon = Icons.Default.Bolt,
                        title = "无障碍服务（开屏断网 + GKD 跳过）",
                        subtitle = if (splashConnected) {
                            "已连接 · GKD引擎${if (settings.gkdEngineEnabled) "运行中" else "已关"} · 断网跳过${if (settings.splashBlockGlobalEnabled) "已开" else "已关"}"
                        } else {
                            "未开启（开启后驱动 GKD 订阅跳过与开屏断网）"
                        },
                        healthy = splashConnected,
                        actionLabel = if (splashConnected) "GKD 中心" else "去开启",
                        onAction = {
                            if (splashConnected) {
                                onNavigateToGkd()
                            } else {
                                runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                            }
                        }
                    )

                    HorizontalDivider()

                    PermissionHealthRow(
                        icon = Icons.Default.BatteryChargingFull,
                        title = "电池优化白名单（One UI 防冻结）",
                        subtitle = if (ignoringBatteryOpt) "已设为「不受限制」，息屏定时与广播不丢" else "未放行，三星深度休眠可能延迟定时任务",
                        healthy = ignoringBatteryOpt,
                        actionLabel = if (ignoringBatteryOpt) "已就绪" else "一键放行",
                        onAction = {
                            if (!ignoringBatteryOpt) {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                runCatching { context.startActivity(intent) }
                            }
                        }
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        HorizontalDivider()
                        PermissionHealthRow(
                            icon = Icons.Default.NotificationsActive,
                            title = "重启与 Shizuku 断连兜底提醒",
                            subtitle = if (hasNotifPerm) "已授权通知，断连时可一键切黑洞 VPN 兜底" else "未授权通知，重启后无法收到激活提醒",
                            healthy = hasNotifPerm,
                            actionLabel = if (hasNotifPerm) "已就绪" else "授予通知",
                            onAction = {
                                if (!hasNotifPerm) {
                                    notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        )
                    }

                    feedbackMessage?.let { msg ->
                        Text(msg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        // 2. 拦截引擎切换 & P0 全局紧急控制（从首页迁入）
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("防火墙引擎与紧急控制", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = settings.preferredBackend == BackendType.SHIZUKU,
                            onClick = {
                                scope.launch {
                                    val run = ruleEngine.switchPreferredBackend(BackendType.SHIZUKU)
                                    feedbackMessage = run.applyResult.message
                                }
                            },
                            label = { Text("主引擎: Shizuku（代理共存）") }
                        )
                        FilterChip(
                            selected = settings.preferredBackend == BackendType.BLACKHOLE_VPN,
                            onClick = {
                                if (vpnBackend.isAnotherVpnActive()) {
                                    showVpnConflictDialog = true
                                } else {
                                    activateBlackholeVpn()
                                }
                            },
                            label = { Text("兜底: 黑洞 VPN") }
                        )
                    }

                    HorizontalDivider()
                    Text("P0 全局紧急模式", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlobalEmergencyMode.entries.forEach { mode ->
                            val label = when (mode) {
                                GlobalEmergencyMode.NORMAL -> "正常按规则"
                                GlobalEmergencyMode.BLOCK_ALL -> "全局断网(仅用户App)"
                                GlobalEmergencyMode.ALLOW_ALL -> "临时全放行"
                            }
                            FilterChip(
                                selected = settings.globalEmergencyMode == mode,
                                onClick = {
                                    scope.launch {
                                        val run = ruleEngine.setGlobalEmergencyMode(mode)
                                        feedbackMessage = run.applyResult.message
                                    }
                                },
                                label = { Text(label) }
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val run = ruleEngine.emergencyRestoreAllNetwork()
                                feedbackMessage = "急救完成：${run.applyResult.message}"
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("一键急救：关闭内核链并恢复全机联网")
                    }
                }
            }
        }

        // 3. 定时保活与电池策略
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("定时调度与休眠保活", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("强力精准闹钟模式 (setAlarmClock)", style = MaterialTheme.typography.bodyMedium)
                            Text("激进省电 ROM 兜底选项，状态栏会显示闹钟图标", style = MaterialTheme.typography.labelSmall)
                        }
                        Switch(
                            checked = settings.strongAlarmClockMode,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    settingsDataStore.setStrongAlarmClockMode(enabled)
                                    exactAlarmScheduler.rescheduleNext("SETTINGS_TOGGLE_STRONG_ALARM")
                                }
                            }
                        )
                    }
                    Text(
                        text = "当前模式：${alarmStatus.modeLabel}\n下一次触发：${alarmStatus.nextBoundary?.let { "${fmt.format(Date(it.triggerAtEpochMs))} (${it.description})" } ?: "无"}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // 4. 开屏跳过全局开关与状态机诊断轨迹
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("开屏广告断网跳过", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "无障碍：${if (splashConnected) "已连接" else "未开启"} · 独立毫秒直连通道",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Switch(
                            checked = settings.splashBlockGlobalEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch { settingsDataStore.setSplashBlockGlobalEnabled(enabled) }
                            }
                        )
                    }
                    OutlinedButton(onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    }) { Text("无障碍系统设置") }
                }
            }
        }

        // 5. Shizuku 底层驱动与固件探针诊断
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("底层驱动与固件诊断", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · Shizuku=${shizukuStatus.name}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = settings.shizukuDriverName == FirewallUserService.DRIVER_CONNECTIVITY_CHAIN3,
                            onClick = {
                                scope.launch {
                                    settingsDataStore.setShizukuDriver(FirewallUserService.DRIVER_CONNECTIVITY_CHAIN3)
                                    shizukuManager.switchDriver(FirewallUserService.DRIVER_CONNECTIVITY_CHAIN3)
                                    ruleEngine.evaluateAndApply("DRIVER_SWITCH_CHAIN3", forceFullSync = true)
                                    probeStatus = "已切换为 connectivity_chain3（前后台全真断网，首选）"
                                }
                            },
                            label = { Text("chain3 前后台真断网 (首选)") }
                        )
                        FilterChip(
                            selected = settings.shizukuDriverName == FirewallUserService.DRIVER_NETPOLICY_RESTRICT,
                            onClick = {
                                scope.launch {
                                    settingsDataStore.setShizukuDriver(FirewallUserService.DRIVER_NETPOLICY_RESTRICT)
                                    shizukuManager.switchDriver(FirewallUserService.DRIVER_NETPOLICY_RESTRICT)
                                    ruleEngine.evaluateAndApply("DRIVER_SWITCH_NETPOLICY", forceFullSync = true)
                                    probeStatus = "已切换为 netpolicy_restrict（仅限后台流量，前台不断网）"
                                }
                            },
                            label = { Text("netpolicy 仅后台限流 (备选)") }
                        )
                    }
                    Button(
                        enabled = shizukuStatus == ShizukuStatus.CONNECTED,
                        onClick = {
                            scope.launch {
                                shizukuManager.runDiagnosticProbe("com.android.settings")
                                    .onSuccess {
                                        probeReport = it
                                        probeStatus = "探针成功：推荐驱动 ${it.recommendedDriver}"
                                    }
                                    .onFailure { probeStatus = "探针失败：${it.message}" }
                            }
                        }
                    ) { Text("运行固件与免定位 Wi-Fi 探针") }

                    probeStatus?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    probeReport?.let { r ->
                        Text(
                            text = "UID=${r.shellUid} | chain3=${r.chain3Supported} | netpolicy=${r.netPolicySupported}\n" +
                                "Wi-Fi: SSID=${r.wifiInfo.ssid}, BSSID=${r.wifiInfo.bssid}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // 6. 二级入口：审计日志与无障碍诊断轨迹
        item {
            Card(
                onClick = onNavigateToAuditLogs,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(
                                text = "审计日志与诊断轨迹",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "7 天规则记录 ${recentLogs.size} 条 · 无障碍轨迹 ${splashTraces.size} 条",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "进入日志页",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun PermissionHealthRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    healthy: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = if (healthy) Icons.Default.CheckCircle else icon,
            contentDescription = null,
            tint = if (healthy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(24.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (healthy && actionLabel == "已就绪") {
            Text("正常", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        } else {
            OutlinedButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
