package com.netcontrol.ui.dashboard

import android.app.Activity
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NetworkWifi
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netcontrol.core.accessibility.SplashBlockService
import com.netcontrol.core.alarm.ExactAlarmScheduler
import com.netcontrol.core.backend.BackendType
import com.netcontrol.core.backend.shizuku.ShizukuManager
import com.netcontrol.core.backend.shizuku.ShizukuStatus
import com.netcontrol.core.backend.vpn.BlackholeVpnBackend
import com.netcontrol.core.engine.RuleEngine
import com.netcontrol.core.engine.RuleEvaluator
import com.netcontrol.core.engine.ScheduleCalculator
import com.netcontrol.core.network.NetworkStateMonitor
import com.netcontrol.core.network.ResolvedNetworkContext
import com.netcontrol.core.notification.FirewallNotificationHelper
import com.netcontrol.data.local.entity.AppEntity
import com.netcontrol.data.local.entity.BuiltinProfileId
import com.netcontrol.data.local.entity.GroupEntity
import com.netcontrol.data.local.entity.GroupWithApps
import com.netcontrol.data.local.entity.NetworkProfileWithBssids
import com.netcontrol.data.local.entity.ProfileScope
import com.netcontrol.data.local.entity.SchedulePresetEntity
import com.netcontrol.data.local.entity.ScheduleRuleEntity
import com.netcontrol.data.prefs.FirewallSettings
import com.netcontrol.data.prefs.GlobalEmergencyMode
import com.netcontrol.data.prefs.SettingsDataStore
import com.netcontrol.data.repository.NetControlRepository
import com.netcontrol.ui.components.AppIconImage
import com.netcontrol.ui.groups.BatchAppPickerDialog
import com.netcontrol.ui.groups.GroupEditorDialog
import com.netcontrol.ui.groups.ScheduleEditorDialog
import com.netcontrol.ui.groups.SchedulePresetEditorDialog
import com.netcontrol.ui.groups.formatScopeLabel
import com.netcontrol.ui.groups.formatWeekdayMask
import com.netcontrol.ui.groups.resolveGroupIcon
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.ZonedDateTime
import java.util.Date
import java.util.Locale

private data class ModeRuntimeBadge(
    val isManuallyBlockingAll: Boolean,
    val isActiveNow: Boolean,
    val statusLabel: String,
    val autoTriggerSummary: String
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    repository: NetControlRepository,
    settingsDataStore: SettingsDataStore,
    shizukuManager: ShizukuManager,
    vpnBackend: BlackholeVpnBackend,
    networkStateMonitor: NetworkStateMonitor,
    ruleEngine: RuleEngine,
    exactAlarmScheduler: ExactAlarmScheduler,
    onNavigateToNetworks: () -> Unit,
    onNavigateToGroups: () -> Unit,
    onNavigateToBlockedApps: () -> Unit = {},
    onNavigateToPermissionsSettings: () -> Unit,
    onNavigateToAuditLogs: () -> Unit = {},
    onNavigateToGkd: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val settings by settingsDataStore.settingsFlow.collectAsStateWithLifecycle(initialValue = FirewallSettings())
    val shizukuStatus by shizukuManager.status.collectAsStateWithLifecycle()
    val networkCtx by networkStateMonitor.currentContext.collectAsStateWithLifecycle()
    val alarmStatus by exactAlarmScheduler.status.collectAsStateWithLifecycle()
    val evaluation by ruleEngine.latestEvaluation.collectAsStateWithLifecycle()
    val splashConnected by SplashBlockService.isConnected.collectAsStateWithLifecycle()
    val signatureAlerts by repository.signatureAlertAppsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val recentLogs by repository.recentAuditLogsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    val groupsWithApps by repository.groupsWithAppsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val allPolicies by repository.groupPoliciesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scheduleRules by repository.scheduleRulesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val schedulePresets by repository.schedulePresetsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val installedApps by repository.installedAppsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val networkProfiles by repository.networkProfilesFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    val policiesByGroup = remember(allPolicies) { allPolicies.groupBy { it.groupId } }
    val schedulesByGroup = remember(scheduleRules) { scheduleRules.groupBy { it.groupId } }
    val customWifiProfiles = remember(networkProfiles) { networkProfiles.filter { !it.profile.isSystemBuiltin } }

    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var configuringGroupId by remember { mutableStateOf<Long?>(null) }
    var isCreatingGroup by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<GroupEntity?>(null) }
    var batchPickerGroup by remember { mutableStateOf<GroupWithApps?>(null) }
    var scheduleEditorTargetGroupId by remember { mutableStateOf<Long?>(null) }
    var isCreatingSchedulePreset by remember { mutableStateOf(false) }

    val pm = context.getSystemService(PowerManager::class.java)
    val ignoringBatteryOpt = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
    val hasNotifPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        FirewallNotificationHelper.hasNotificationPermission(context)
    } else {
        true
    }
    val pendingPermissionCount = listOf(
        settings.preferredBackend == BackendType.SHIZUKU && shizukuStatus != ShizukuStatus.CONNECTED,
        !ignoringBatteryOpt,
        !hasNotifPerm
    ).count { it }

    val vpnPrepareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            scope.launch {
                val run = ruleEngine.setFirewallEnabled(true)
                feedbackMessage = run.applyResult.message
            }
        } else {
            feedbackMessage = "已取消黑洞 VPN 权限授权"
        }
    }

    fun ensureFirewallOnIfNeeded() {
        scope.launch {
            if (!settings.firewallEnabled) {
                if (settings.preferredBackend == BackendType.BLACKHOLE_VPN) {
                    val intent = vpnBackend.prepareVpnIntent()
                    if (intent != null) {
                        vpnPrepareLauncher.launch(intent)
                        return@launch
                    }
                }
                ruleEngine.setFirewallEnabled(true)
            }
        }
    }

    // 1. 新建 / 编辑模式（分组）弹窗
    if (isCreatingGroup || editingGroup != null) {
        GroupEditorDialog(
            initial = editingGroup,
            onDismiss = {
                isCreatingGroup = false
                editingGroup = null
            },
            onSave = { name, colorHex, iconKey, description, autoBlockScope ->
                scope.launch {
                    val editing = editingGroup
                    if (editing == null) {
                        val newId = repository.createGroup(name, colorHex, iconKey, description)
                        if (!autoBlockScope.isNullOrBlank()) {
                            repository.upsertGroupNetworkPolicy(newId, autoBlockScope, true)
                        }
                        feedbackMessage = "已创建模式「$name」"
                    } else {
                        repository.updateGroup(
                            editing.copy(
                                name = name,
                                colorHex = colorHex,
                                iconKey = iconKey,
                                description = description
                            )
                        )
                        feedbackMessage = "已更新模式「$name」"
                    }
                    ruleEngine.notifyRulesChanged("DASHBOARD_MODE_SAVED")
                    isCreatingGroup = false
                    editingGroup = null
                }
            }
        )
    }

    // 2. 带应用图标的批量成员管理弹窗
    batchPickerGroup?.let { target ->
        BatchAppPickerDialog(
            groupName = target.group.name,
            allInstalledApps = installedApps,
            initialSelectedPackages = target.apps.map { it.packageName }.toSet(),
            onDismiss = { batchPickerGroup = null },
            onConfirm = { selectedPkgs ->
                scope.launch {
                    repository.replaceGroupMembers(target.group.groupId, selectedPkgs)
                    ruleEngine.notifyRulesChanged("DASHBOARD_MODE_MEMBERS:${target.group.groupId}")
                    feedbackMessage = "已更新「${target.group.name}」包含 ${selectedPkgs.size} 个应用"
                    batchPickerGroup = null
                }
            }
        )
    }

    // 2.5 新建自定义定时模块弹窗
    if (isCreatingSchedulePreset) {
        SchedulePresetEditorDialog(
            initial = null,
            networkProfiles = networkProfiles,
            onDismiss = { isCreatingSchedulePreset = false },
            onSave = { preset ->
                scope.launch {
                    repository.upsertSchedulePreset(preset)
                    feedbackMessage = "已新建定时模块「${preset.name}」"
                    isCreatingSchedulePreset = false
                }
            }
        )
    }

    // 3. 新建自动启动定时规则弹窗
    scheduleEditorTargetGroupId?.let { targetId ->
        if (groupsWithApps.isNotEmpty()) {
            ScheduleEditorDialog(
                groups = groupsWithApps.map { it.group },
                networkProfiles = networkProfiles,
                presets = schedulePresets,
                initialGroupId = targetId,
                onDismiss = { scheduleEditorTargetGroupId = null },
                onSave = { newRule ->
                    scope.launch {
                        repository.upsertScheduleRule(newRule)
                        ruleEngine.notifyRulesChanged("DASHBOARD_SCHEDULE_ADDED")
                        feedbackMessage = "已添加自动定时触发条件"
                        scheduleEditorTargetGroupId = null
                    }
                },
                onSaveAsPreset = { preset ->
                    scope.launch {
                        repository.upsertSchedulePreset(preset)
                    }
                },
                onRequestNewPreset = { isCreatingSchedulePreset = true }
            )
        }
    }

    // 4. 三星「模式与日常程序」风格：点击模式卡片打开完整触发设计弹窗
    configuringGroupId?.let { gid ->
        val gwa = groupsWithApps.firstOrNull { it.group.groupId == gid }
        if (gwa != null) {
            val blockedScopes = policiesByGroup[gid].orEmpty()
                .filter { it.blockByDefault }
                .map { it.profileScope }
                .toSet()
            val groupSchedules = schedulesByGroup[gid].orEmpty()

            ModeRoutineConfigDialog(
                item = gwa,
                blockedScopes = blockedScopes,
                schedules = groupSchedules,
                schedulePresets = schedulePresets,
                networkProfiles = networkProfiles,
                customWifiProfiles = customWifiProfiles,
                onDismiss = { configuringGroupId = null },
                onToggleGroupEnabled = { enabled ->
                    scope.launch {
                        repository.setGroupEnabled(gwa.group, enabled)
                        if (enabled) ensureFirewallOnIfNeeded()
                        ruleEngine.notifyRulesChanged("MODE_ENABLE_TOGGLE:$gid")
                    }
                },
                onToggleScopePolicy = { profileScope, block ->
                    scope.launch {
                        if (block && !gwa.group.isEnabled) {
                            repository.setGroupEnabled(gwa.group, true)
                        }
                        repository.upsertGroupNetworkPolicy(gid, profileScope, block)
                        if (block) ensureFirewallOnIfNeeded()
                        ruleEngine.notifyRulesChanged("MODE_POLICY:$gid:$profileScope")
                    }
                },
                onToggleSchedule = { ruleId, enabled ->
                    scope.launch {
                        repository.setScheduleRuleEnabled(ruleId, enabled)
                        ruleEngine.notifyRulesChanged("MODE_SCHEDULE_TOGGLE:$ruleId")
                    }
                },
                onDeleteSchedule = { ruleId ->
                    scope.launch {
                        repository.deleteScheduleRule(ruleId)
                        ruleEngine.notifyRulesChanged("MODE_SCHEDULE_DELETE:$ruleId")
                    }
                },
                onApplyPreset = { preset ->
                    scope.launch {
                        if (!gwa.group.isEnabled) {
                            repository.setGroupEnabled(gwa.group, true)
                        }
                        repository.applySchedulePresetToGroup(gid, preset)
                        ensureFirewallOnIfNeeded()
                        ruleEngine.notifyRulesChanged("MODE_APPLY_PRESET:$gid:${preset.presetId}")
                        feedbackMessage = "已将定时模块「${preset.name}」套用到「${gwa.group.name}」"
                    }
                },
                onCreateNewPreset = {
                    isCreatingSchedulePreset = true
                },
                onAddSchedule = {
                    scheduleEditorTargetGroupId = gid
                },
                onPickMembers = {
                    batchPickerGroup = gwa
                },
                onEditMeta = {
                    editingGroup = gwa.group
                }
            )
        }
    }

    val nowZdt = remember(alarmStatus.nextBoundary, evaluation) { ZonedDateTime.now() }
    val nowDayIdx = RuleEvaluator.dayOfWeekToIndex(nowZdt.dayOfWeek)
    val nowMinute = nowZdt.hour * 60 + nowZdt.minute
    val timeHmFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. 签名安全告警横幅（仅存在重装签名不一致时出现）
        if (signatureAlerts.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Text(
                                "安全告警：${signatureAlerts.size} 个应用重装后签名变更",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        signatureAlerts.forEach { app ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${app.appName} (${app.packageName})",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = {
                                    scope.launch { repository.acknowledgeSignatureAlert(app.packageName) }
                                }) { Text("已核实") }
                            }
                        }
                    }
                }
            }
        }

        // 2. 顶部精简总控横幅（防火墙开启时单击面板直接进入已拦截应用列表）
        item(key = "hero_card", contentType = "hero") {
            val heroContainerColor = when {
                !settings.firewallEnabled -> MaterialTheme.colorScheme.surfaceContainerHigh
                settings.globalEmergencyMode == GlobalEmergencyMode.BLOCK_ALL -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.primaryContainer
            }
            Card(
                onClick = {
                    if (settings.firewallEnabled) {
                        onNavigateToBlockedApps()
                    }
                },
                enabled = settings.firewallEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (signatureAlerts.isEmpty()) 8.dp else 0.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = heroContainerColor,
                    disabledContainerColor = heroContainerColor,
                    disabledContentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (settings.firewallEnabled) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = if (settings.firewallEnabled) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = if (settings.firewallEnabled) "防火墙总闸已开启" else "防火墙已暂停",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                val blockedCount = evaluation?.blockedPackages?.size ?: 0
                                val engineLabel = if (settings.preferredBackend == BackendType.SHIZUKU) "Shizuku 内核" else "黑洞 VPN"
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = if (settings.firewallEnabled) {
                                            "当前拦截 $blockedCount 个应用 · $engineLabel · 点此查看"
                                        } else {
                                            "开启后「强断」与全部模式规则生效"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (settings.firewallEnabled) {
                                        Icon(
                                            imageVector = Icons.Default.ChevronRight,
                                            contentDescription = "查看已拦截应用",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                        Switch(
                            checked = settings.firewallEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    if (enabled && settings.preferredBackend == BackendType.BLACKHOLE_VPN) {
                                        val intent = vpnBackend.prepareVpnIntent()
                                        if (intent != null) {
                                            vpnPrepareLauncher.launch(intent)
                                            return@launch
                                        }
                                    }
                                    val run = ruleEngine.setFirewallEnabled(enabled)
                                    feedbackMessage = run.applyResult.message
                                }
                            }
                        )
                    }

                    // 紧凑状态胶囊：当前网络 & 下一次定时
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = onNavigateToNetworks,
                            leadingIcon = {
                                Icon(Icons.Default.NetworkWifi, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            label = { Text(networkCtx.profileName) }
                        )
                        val nextAlarmLabel = alarmStatus.nextBoundary?.let { b ->
                            "下次定时 " + timeHmFmt.format(Date(b.triggerAtEpochMs))
                        } ?: "暂无定时计划"
                        AssistChip(
                            onClick = onNavigateToGroups,
                            leadingIcon = {
                                Icon(Icons.Default.Alarm, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            label = { Text(nextAlarmLabel) }
                        )
                        if (settings.globalEmergencyMode != GlobalEmergencyMode.NORMAL) {
                            AssistChip(
                                onClick = {
                                    scope.launch {
                                        ruleEngine.setGlobalEmergencyMode(GlobalEmergencyMode.NORMAL)
                                        feedbackMessage = "已恢复正常按规则模式"
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                label = {
                                    Text(
                                        if (settings.globalEmergencyMode == GlobalEmergencyMode.BLOCK_ALL) "P0 全局断网中(点此恢复)"
                                        else "P0 全放行中(点此恢复)"
                                    )
                                }
                            )
                        }
                    }

                    feedbackMessage?.let { msg ->
                        Text(msg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        // 3. 轻量权限提醒条（仅存在待授权项时显示，点击直达右侧「统计与设置 -> 权限与设置」）
        if (pendingPermissionCount > 0) {
            item {
                Card(
                    onClick = onNavigateToPermissionsSettings,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                "有 $pendingPermissionCount 项系统权限待配置（点此前往「统计与设置」）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
        }

        // 4. 三星「模式与日常程序」核心区标题栏
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "模式与分组场景",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "一键手动开启/关闭，点卡片自由设计自动启动条件",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(
                    onClick = { isCreatingGroup = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(" 添加模式")
                }
            }
        }

        // 5. 分组模式卡片列表（每张卡片支持手动启停 + 自动条件预览 + 点击定制）
        if (groupsWithApps.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("还没有模式分组", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "点击右上角「添加模式」，像三星模式与日常程序一样创建睡眠断网、仅 Wi-Fi 免流或办公专注场景。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(
                items = groupsWithApps,
                key = { it.group.groupId },
                contentType = { "mode_card" }
            ) { gwa ->
                val gid = gwa.group.groupId
                val blockedScopes = policiesByGroup[gid].orEmpty()
                    .filter { it.blockByDefault }
                    .map { it.profileScope }
                    .toSet()
                val groupSchedules = schedulesByGroup[gid].orEmpty()
                val badge = computeModeRuntimeBadge(
                    group = gwa.group,
                    blockedScopes = blockedScopes,
                    schedules = groupSchedules,
                    networkCtx = networkCtx,
                    networkProfiles = networkProfiles,
                    firewallEnabled = settings.firewallEnabled,
                    nowDayIdx = nowDayIdx,
                    nowMinute = nowMinute
                )

                OneUiModeCard(
                    item = gwa,
                    badge = badge,
                    onCardClick = { configuringGroupId = gid },
                    onToggleManualLaunch = {
                        scope.launch {
                            if (badge.isActiveNow || badge.isManuallyBlockingAll) {
                                // 关闭：解除手动全网断网；若此时仍有自动规则在触发，则同步暂停该分组使「关闭」立竿见影
                                if (ProfileScope.ALL in blockedScopes) {
                                    repository.upsertGroupNetworkPolicy(gid, ProfileScope.ALL, false)
                                }
                                val stillTriggeredByAuto = gwa.group.isEnabled && (
                                    blockedScopes.any { it != ProfileScope.ALL && RuleEvaluator.matchesProfileScope(it, networkCtx) } ||
                                        groupSchedules.any {
                                            it.isEnabled &&
                                                RuleEvaluator.matchesProfileScope(it.profileScope, networkCtx) &&
                                                RuleEvaluator.isScheduleRuleActiveAt(it, nowDayIdx, nowMinute)
                                        }
                                    )
                                if (stillTriggeredByAuto) {
                                    repository.setGroupEnabled(gwa.group, false)
                                }
                                ruleEngine.notifyRulesChanged("MODE_MANUAL_STOP:$gid")
                                feedbackMessage = "已关闭「${gwa.group.name}」模式"
                            } else {
                                // 手动开启：立即启用该分组 + 全网络常驻断网，并确保防火墙总闸开启
                                if (!gwa.group.isEnabled) {
                                    repository.setGroupEnabled(gwa.group, true)
                                }
                                repository.upsertGroupNetworkPolicy(gid, ProfileScope.ALL, true)
                                if (!settings.firewallEnabled) {
                                    if (settings.preferredBackend == BackendType.BLACKHOLE_VPN) {
                                        val intent = vpnBackend.prepareVpnIntent()
                                        if (intent != null) {
                                            vpnPrepareLauncher.launch(intent)
                                            return@launch
                                        }
                                    }
                                    ruleEngine.setFirewallEnabled(true)
                                } else {
                                    ruleEngine.notifyRulesChanged("MODE_MANUAL_START:$gid")
                                }
                                feedbackMessage = "已手动开启「${gwa.group.name}」（全网络断网）"
                            }
                        }
                    }
                )
            }
        }

        // 6. 智能日常卡：GKD 自动跳过 + 开屏断网
        item {
            Card(
                onClick = onNavigateToGkd,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (splashConnected && settings.gkdEngineEnabled) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    }
                )
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
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Bolt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text("GKD 自动跳过广告", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(
                                text = if (splashConnected) {
                                    "无障碍双擎就绪 · 点击管理订阅规则与快照"
                                } else {
                                    "无障碍未开启 · 前往「统计与设置」授权"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = settings.gkdEngineEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch { settingsDataStore.setGkdEngineEnabled(enabled) }
                        }
                    )
                }
            }
        }

        // 7. 底部紧凑动态 & 一键急救安全阀
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("最近拦截动态", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = onNavigateToAuditLogs) {
                            Text("查看完整日志")
                        }
                    }
                    if (recentLogs.isEmpty()) {
                        Text(
                            "暂无拦截记录。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                        recentLogs.take(3).forEach { log ->
                            Text(
                                text = "[${fmt.format(Date(log.timestamp))}] ${if (log.blocked) "阻断" else "放行"} ${log.packageName}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
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
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(" 一键急救：恢复全机联网")
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

private fun computeModeRuntimeBadge(
    group: GroupEntity,
    blockedScopes: Set<String>,
    schedules: List<ScheduleRuleEntity>,
    networkCtx: ResolvedNetworkContext,
    networkProfiles: List<NetworkProfileWithBssids>,
    firewallEnabled: Boolean,
    nowDayIdx: Int,
    nowMinute: Int
): ModeRuntimeBadge {
    val hasManualAll = ProfileScope.ALL in blockedScopes
    val autoScopes = blockedScopes.filter { it != ProfileScope.ALL }
    val enabledSchedules = schedules.filter { it.isEnabled }

    val netMatchedNow = autoScopes.any { RuleEvaluator.matchesProfileScope(it, networkCtx) }
    val scheduleMatchedNow = enabledSchedules.any {
        RuleEvaluator.matchesProfileScope(it.profileScope, networkCtx) &&
            RuleEvaluator.isScheduleRuleActiveAt(it, nowDayIdx, nowMinute)
    }

    val isActiveNow = firewallEnabled && group.isEnabled && (hasManualAll || netMatchedNow || scheduleMatchedNow)
    val statusLabel = when {
        !group.isEnabled -> "模式已停用"
        !firewallEnabled && (hasManualAll || netMatchedNow || scheduleMatchedNow) -> "待防火墙开启"
        hasManualAll -> "运行中 · 手动断网"
        scheduleMatchedNow -> "运行中 · 定时自动触发"
        netMatchedNow -> "运行中 · 当前网络触发"
        autoScopes.isNotEmpty() || enabledSchedules.isNotEmpty() -> "自动待机中"
        else -> "手动模式"
    }

    val autoParts = buildList {
        enabledSchedules.take(2).forEach { r ->
            add("⏰ ${ScheduleCalculator.formatMinute(r.startMinute)}→${ScheduleCalculator.formatMinute(r.endMinute)}")
        }
        if (enabledSchedules.size > 2) {
            add("+${enabledSchedules.size - 2}条定时")
        }
        autoScopes.take(2).forEach { s ->
            add("📶 ${formatScopeLabel(s, networkProfiles)}")
        }
        if (autoScopes.size > 2) {
            add("+${autoScopes.size - 2}个网络")
        }
    }
    val autoSummary = if (autoParts.isNotEmpty()) {
        "自动启动：${autoParts.joinToString(" · ")}"
    } else {
        "点击卡片设计自动定时 / 网络触发条件"
    }

    return ModeRuntimeBadge(
        isManuallyBlockingAll = group.isEnabled && hasManualAll,
        isActiveNow = isActiveNow,
        statusLabel = statusLabel,
        autoTriggerSummary = autoSummary
    )
}

@Composable
private fun OneUiModeCard(
    item: GroupWithApps,
    badge: ModeRuntimeBadge,
    onCardClick: () -> Unit,
    onToggleManualLaunch: () -> Unit
) {
    val group = item.group
    val accentColor = remember(group.colorHex) {
        runCatching { Color(android.graphics.Color.parseColor(group.colorHex)) }
            .getOrElse { Color(0xFF0B57D0) }
    }
    val activeMembers = remember(item.apps) { item.apps.filter { !it.isUninstalled } }
    val highlighted = badge.isActiveNow

    Card(
        onClick = onCardClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        border = if (highlighted) BorderStroke(1.5.dp, accentColor.copy(alpha = 0.65f)) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) {
                accentColor.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (highlighted) accentColor else accentColor.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = resolveGroupIcon(group.iconKey),
                            contentDescription = null,
                            tint = if (highlighted) Color.White else accentColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = group.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (highlighted) accentColor.copy(alpha = 0.2f)
                            else MaterialTheme.colorScheme.surfaceContainerHighest
                        ) {
                            Text(
                                text = "${badge.statusLabel} · ${activeMembers.size} 个应用",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (highlighted) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                if (highlighted || badge.isManuallyBlockingAll) {
                    Button(
                        onClick = onToggleManualLaunch,
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text("关闭")
                    }
                } else {
                    FilledTonalButton(
                        onClick = onToggleManualLaunch,
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text("开启")
                    }
                }
            }

            // 成员图标微缩预览行
            if (activeMembers.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    activeMembers.take(6).forEach { app ->
                        AppIconImage(
                            packageName = app.packageName,
                            appName = app.appName,
                            size = 22.dp
                        )
                    }
                    if (activeMembers.size > 6) {
                        Text(
                            text = "+${activeMembers.size - 6}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 自动启动条件提示行
            Text(
                text = badge.autoTriggerSummary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeRoutineConfigDialog(
    item: GroupWithApps,
    blockedScopes: Set<String>,
    schedules: List<ScheduleRuleEntity>,
    schedulePresets: List<SchedulePresetEntity>,
    networkProfiles: List<NetworkProfileWithBssids>,
    customWifiProfiles: List<NetworkProfileWithBssids>,
    onDismiss: () -> Unit,
    onToggleGroupEnabled: (Boolean) -> Unit,
    onToggleScopePolicy: (String, Boolean) -> Unit,
    onToggleSchedule: (Long, Boolean) -> Unit,
    onDeleteSchedule: (Long) -> Unit,
    onApplyPreset: (SchedulePresetEntity) -> Unit,
    onCreateNewPreset: () -> Unit,
    onAddSchedule: () -> Unit,
    onPickMembers: () -> Unit,
    onEditMeta: () -> Unit
) {
    val g = item.group
    val activeMembers = remember(item.apps) { item.apps.filter { !it.isUninstalled } }
    val manualAllOn = ProfileScope.ALL in blockedScopes

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("模式设计：${g.name}", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = onEditMeta) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑名称与图标")
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. 手动启动区
                Text("一、手动启动", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启用此模式", style = MaterialTheme.typography.bodyMedium)
                        Text("关闭后该模式的手动与自动规则均暂停", style = MaterialTheme.typography.labelSmall)
                    }
                    Switch(checked = g.isEnabled, onCheckedChange = onToggleGroupEnabled)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("手动全网络立即断网", style = MaterialTheme.typography.bodyMedium)
                        Text("不限时间与网络，开启即断开组内应用", style = MaterialTheme.typography.labelSmall)
                    }
                    Switch(
                        checked = manualAllOn,
                        onCheckedChange = { onToggleScopePolicy(ProfileScope.ALL, it) }
                    )
                }

                HorizontalDivider()

                // 2. 自动启动 - 网络条件
                Text("二、自动启动 · 按网络条件", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(
                    "当手机连接到以下选中的网络时自动断开组内应用：",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val autoNetworkScopes = buildList {
                        add(ProfileScope.WIFI_ALL to "任意 Wi-Fi")
                        add(ProfileScope.MOBILE_ALL to "移动数据")
                        add(BuiltinProfileId.MOBILE_SIM_1 to "SIM 1")
                        add(BuiltinProfileId.MOBILE_SIM_2 to "SIM 2")
                        customWifiProfiles.forEach { p ->
                            add(p.profile.profileId to "Wi-Fi「${p.profile.name}」")
                        }
                    }
                    autoNetworkScopes.forEach { (scopeKey, label) ->
                        val selected = scopeKey in blockedScopes
                        FilterChip(
                            selected = selected,
                            onClick = { onToggleScopePolicy(scopeKey, !selected) },
                            label = { Text(label) }
                        )
                    }
                }

                HorizontalDivider()

                // 3. 自动启动 - 定时条件（支持点击自定义定时模块直接完成定时）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("三、自动启动 · 按时间段", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onAddSchedule) { Text("+ 自定义定时") }
                }
                Text(
                    "一键选用定时模块（点一下直接添加定时，无需重复设置）：",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    schedulePresets.forEach { preset ->
                        AssistChip(
                            onClick = { onApplyPreset(preset) },
                            leadingIcon = {
                                Icon(Icons.Default.Alarm, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            label = {
                                Text("${preset.name} (${ScheduleCalculator.formatMinute(preset.startMinute)}→${ScheduleCalculator.formatMinute(preset.endMinute)})")
                            }
                        )
                    }
                    AssistChip(
                        onClick = onCreateNewPreset,
                        leadingIcon = {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text("新建定时模块") }
                    )
                }
                if (schedules.isEmpty()) {
                    Text(
                        "当前模式尚未绑定定时规则，点上方模块即可立即添加。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    schedules.forEach { r ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "${ScheduleCalculator.formatMinute(r.startMinute)} → ${ScheduleCalculator.formatMinute(r.endMinute)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        "${formatWeekdayMask(r.daysMask)} · ${formatScopeLabel(r.profileScope, networkProfiles)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { onDeleteSchedule(r.ruleId) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除定时", modifier = Modifier.size(18.dp))
                                }
                                Switch(
                                    checked = r.isEnabled,
                                    onCheckedChange = { onToggleSchedule(r.ruleId, it) }
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                // 4. 执行对象 - 组内应用（带图标预览）
                Text("四、包含的应用 (${activeMembers.size})", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                if (activeMembers.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        activeMembers.take(8).forEach { app ->
                            AppIconImage(
                                packageName = app.packageName,
                                appName = app.appName,
                                size = 30.dp
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = onPickMembers,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("管理包含的应用（含应用图标）")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}
