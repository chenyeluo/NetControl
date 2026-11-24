package com.netcontrol.ui.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SettingsApplications
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netcontrol.core.engine.RuleEngine
import com.netcontrol.core.engine.ScheduleCalculator
import com.netcontrol.data.local.entity.AppEntity
import com.netcontrol.data.local.entity.BuiltinProfileId
import com.netcontrol.data.local.entity.GroupEntity
import com.netcontrol.data.local.entity.GroupWithApps
import com.netcontrol.data.local.entity.NetworkProfileWithBssids
import com.netcontrol.data.local.entity.ProfileScope
import com.netcontrol.data.local.entity.SchedulePresetEntity
import com.netcontrol.data.local.entity.ScheduleRuleEntity
import com.netcontrol.data.repository.NetControlRepository
import com.netcontrol.ui.components.AppIconImage
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

private val GROUP_COLOR_SWATCHES = listOf(
    "#0B57D0", "#1E88E5", "#00838F", "#00695C",
    "#2E7D32", "#EF6C00", "#D84315", "#C2185B",
    "#7B1FA2", "#3949AB", "#5D4037", "#455A64"
)

private data class GroupIconOption(val key: String, val label: String, val icon: ImageVector)

private val GROUP_ICON_OPTIONS = listOf(
    GroupIconOption("folder", "通用", Icons.Default.Folder),
    GroupIconOption("chat", "社交", Icons.AutoMirrored.Filled.Chat),
    GroupIconOption("video", "影音", Icons.Default.PlayCircle),
    GroupIconOption("game", "游戏", Icons.Default.SportsEsports),
    GroupIconOption("work", "办公", Icons.Default.Work),
    GroupIconOption("night", "夜间", Icons.Default.Bedtime),
    GroupIconOption("wifi", "仅WiFi", Icons.Default.Wifi),
    GroupIconOption("shop", "购物", Icons.Default.ShoppingCart),
    GroupIconOption("finance", "金融", Icons.Default.AccountBalance),
    GroupIconOption("study", "学习", Icons.AutoMirrored.Filled.MenuBook),
    GroupIconOption("cloud", "云盘", Icons.Default.Cloud),
    GroupIconOption("ai", "AI", Icons.Default.AutoAwesome),
    GroupIconOption("battery", "省电", Icons.Default.BatteryAlert),
    GroupIconOption("shield", "隐私", Icons.Default.Shield),
    GroupIconOption("system", "系统", Icons.Default.SettingsApplications)
)

private data class GroupTemplate(
    val name: String,
    val iconKey: String,
    val colorHex: String,
    val description: String,
    val autoBlockScope: String? = null
)

private val GROUP_TEMPLATES = listOf(
    GroupTemplate("夜间睡眠防熬夜", "night", "#3949AB", "配合 23:00→07:00 跨夜定时断网"),
    GroupTemplate("仅 Wi-Fi 免流", "wifi", "#00838F", "移动数据下自动断网，节省流量", ProfileScope.MOBILE_ALL),
    GroupTemplate("购物与外卖", "shop", "#EF6C00", "电商、外卖与促销推送应用"),
    GroupTemplate("金融与支付", "finance", "#2E7D32", "银行与支付应用，建议严格保护"),
    GroupTemplate("学习与阅读", "study", "#1E88E5", "电子书、词典、课程与笔记"),
    GroupTemplate("云盘与大文件同步", "cloud", "#0B57D0", "禁止走流量，仅在家庭 Wi-Fi 同步", ProfileScope.MOBILE_ALL),
    GroupTemplate("AI 与开发工具", "ai", "#5D4037", "AI 助手与效率工具"),
    GroupTemplate("高耗电后台限制", "battery", "#D84315", "限制频繁后台联网保活的应用")
)

fun resolveGroupIcon(iconKey: String): ImageVector =
    GROUP_ICON_OPTIONS.firstOrNull { it.key == iconKey }?.icon ?: Icons.Default.Folder

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroupsAndSchedulesScreen(
    repository: NetControlRepository,
    ruleEngine: RuleEngine,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val groupsWithApps by repository.groupsWithAppsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val allPolicies by repository.groupPoliciesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scheduleRules by repository.scheduleRulesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val schedulePresets by repository.schedulePresetsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val installedApps by repository.installedAppsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val networkProfiles by repository.networkProfilesFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    var subTab by remember { mutableIntStateOf(0) }
    var showGroupEditor by remember { mutableStateOf<GroupEntity?>(null) }
    var isCreatingGroup by remember { mutableStateOf(false) }
    var batchPickerGroup by remember { mutableStateOf<GroupWithApps?>(null) }
    var showScheduleEditor by remember { mutableStateOf(false) }
    var isCreatingPreset by remember { mutableStateOf(false) }
    var editingPreset by remember { mutableStateOf<SchedulePresetEntity?>(null) }

    val policiesByGroup = remember(allPolicies) { allPolicies.groupBy { it.groupId } }
    val groupNameById = remember(groupsWithApps) { groupsWithApps.associate { it.group.groupId to it.group.name } }
    val customWifiProfiles = remember(networkProfiles) { networkProfiles.filter { !it.profile.isSystemBuiltin } }

    if (isCreatingGroup || showGroupEditor != null) {
        GroupEditorDialog(
            initial = showGroupEditor,
            onDismiss = {
                isCreatingGroup = false
                showGroupEditor = null
            },
            onSave = { name, colorHex, iconKey, description, autoBlockScope ->
                scope.launch {
                    val editing = showGroupEditor
                    if (editing == null) {
                        val newId = repository.createGroup(name, colorHex, iconKey, description)
                        if (!autoBlockScope.isNullOrBlank()) {
                            repository.upsertGroupNetworkPolicy(newId, autoBlockScope, true)
                        }
                    } else {
                        repository.updateGroup(
                            editing.copy(
                                name = name,
                                colorHex = colorHex,
                                iconKey = iconKey,
                                description = description
                            )
                        )
                    }
                    ruleEngine.notifyRulesChanged("GROUP_SAVED")
                    isCreatingGroup = false
                    showGroupEditor = null
                }
            }
        )
    }

    batchPickerGroup?.let { target ->
        BatchAppPickerDialog(
            groupName = target.group.name,
            allInstalledApps = installedApps,
            initialSelectedPackages = target.apps.map { it.packageName }.toSet(),
            onDismiss = { batchPickerGroup = null },
            onConfirm = { selectedPkgs ->
                scope.launch {
                    repository.replaceGroupMembers(target.group.groupId, selectedPkgs)
                    ruleEngine.notifyRulesChanged("GROUP_MEMBERS_REPLACED:${target.group.groupId}")
                    batchPickerGroup = null
                }
            }
        )
    }

    if (isCreatingPreset || editingPreset != null) {
        SchedulePresetEditorDialog(
            initial = editingPreset,
            networkProfiles = networkProfiles,
            onDismiss = {
                isCreatingPreset = false
                editingPreset = null
            },
            onSave = { preset ->
                scope.launch {
                    repository.upsertSchedulePreset(preset)
                    isCreatingPreset = false
                    editingPreset = null
                }
            }
        )
    }

    if (showScheduleEditor && groupsWithApps.isNotEmpty()) {
        ScheduleEditorDialog(
            groups = groupsWithApps.map { it.group },
            networkProfiles = networkProfiles,
            presets = schedulePresets,
            onDismiss = { showScheduleEditor = false },
            onSave = { newRule ->
                scope.launch {
                    repository.upsertScheduleRule(newRule)
                    ruleEngine.notifyRulesChanged("SCHEDULE_ADDED")
                    showScheduleEditor = false
                }
            },
            onSaveAsPreset = { newPreset ->
                scope.launch {
                    repository.upsertSchedulePreset(newPreset)
                }
            },
            onRequestNewPreset = { isCreatingPreset = true }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (subTab == 0) {
                        isCreatingGroup = true
                    } else if (groupsWithApps.isNotEmpty()) {
                        showScheduleEditor = true
                    }
                }
            ) {
                Icon(Icons.Default.Add, contentDescription = if (subTab == 0) "新建分组" else "新建定时规则")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                SegmentedButton(
                    selected = subTab == 0,
                    onClick = { subTab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)
                ) { Text("自定义分组 (${groupsWithApps.size})") }

                SegmentedButton(
                    selected = subTab == 1,
                    onClick = { subTab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) { Text("定时规则 (${scheduleRules.size})") }
            }

            if (subTab == 0) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(items = groupsWithApps, key = { _, it -> it.group.groupId }) { index, gwa ->
                        val policies = policiesByGroup[gwa.group.groupId].orEmpty()
                        val blockedScopes = policies.filter { it.blockByDefault }.map { it.profileScope }.toSet()
                        GroupCard(
                            item = gwa,
                            blockedScopes = blockedScopes,
                            customWifiProfiles = customWifiProfiles,
                            canMoveUp = index > 0,
                            canMoveDown = index < groupsWithApps.lastIndex,
                            onMoveUp = {
                                if (index > 0) {
                                    scope.launch {
                                        repository.swapGroupOrder(gwa.group, groupsWithApps[index - 1].group)
                                    }
                                }
                            },
                            onMoveDown = {
                                if (index < groupsWithApps.lastIndex) {
                                    scope.launch {
                                        repository.swapGroupOrder(gwa.group, groupsWithApps[index + 1].group)
                                    }
                                }
                            },
                            onToggleEnabled = { enabled ->
                                scope.launch {
                                    repository.setGroupEnabled(gwa.group, enabled)
                                    ruleEngine.notifyRulesChanged("GROUP_TOGGLE:${gwa.group.groupId}")
                                }
                            },
                            onToggleScopePolicy = { profileScope, block ->
                                scope.launch {
                                    repository.upsertGroupNetworkPolicy(gwa.group.groupId, profileScope, block)
                                    ruleEngine.notifyRulesChanged("GROUP_POLICY:${gwa.group.groupId}:$profileScope")
                                }
                            },
                            onPickMembers = { batchPickerGroup = gwa },
                            onEdit = { showGroupEditor = gwa.group },
                            onDelete = {
                                scope.launch {
                                    repository.deleteGroup(gwa.group)
                                    ruleEngine.notifyRulesChanged("GROUP_DELETED:${gwa.group.groupId}")
                                }
                            }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(72.dp)) }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 自定义定时模块库（用户可自由新增、命名、编辑、删除）
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "我的定时模块 (${schedulePresets.size})",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            "自定义命名常用时段，添加定时或配置模式时可一键直接选用",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    TextButton(onClick = { isCreatingPreset = true }) {
                                        Text("+ 新建模块")
                                    }
                                }
                                if (schedulePresets.isEmpty()) {
                                    Text(
                                        "暂无自定义定时模块，点击右上角「+ 新建模块」创建。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    schedulePresets.forEach { preset ->
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    Text(
                                                        preset.name,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    Text(
                                                        "${ScheduleCalculator.formatMinute(preset.startMinute)} → ${ScheduleCalculator.formatMinute(preset.endMinute)} · ${formatWeekdayMask(preset.daysMask)} · ${formatScopeLabel(preset.profileScope, networkProfiles)}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    IconButton(onClick = { editingPreset = preset }) {
                                                        Icon(Icons.Default.Edit, contentDescription = "编辑模块", modifier = Modifier.size(18.dp))
                                                    }
                                                    IconButton(onClick = {
                                                        scope.launch { repository.deleteSchedulePreset(preset.presetId) }
                                                    }) {
                                                        Icon(Icons.Default.Delete, contentDescription = "删除模块", modifier = Modifier.size(18.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (scheduleRules.isEmpty()) {
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("暂无定时规则", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "点击右下角 + 号添加分组定时断网规则，支持跨午夜时段（如 23:00 → 次日 07:00）、星期循环及按网络档案生效。",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                    items(items = scheduleRules, key = { it.ruleId }) { rule ->
                        ScheduleRuleCard(
                            rule = rule,
                            groupName = groupNameById[rule.groupId] ?: "分组#${rule.groupId}",
                            scopeLabel = formatScopeLabel(rule.profileScope, networkProfiles),
                            onToggle = { enabled ->
                                scope.launch {
                                    repository.setScheduleRuleEnabled(rule.ruleId, enabled)
                                    ruleEngine.notifyRulesChanged("SCHEDULE_TOGGLE:${rule.ruleId}")
                                }
                            },
                            onDelete = {
                                scope.launch {
                                    repository.deleteScheduleRule(rule.ruleId)
                                    ruleEngine.notifyRulesChanged("SCHEDULE_DELETE:${rule.ruleId}")
                                }
                            }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(72.dp)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupCard(
    item: GroupWithApps,
    blockedScopes: Set<String>,
    customWifiProfiles: List<NetworkProfileWithBssids>,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleScopePolicy: (String, Boolean) -> Unit,
    onPickMembers: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val g = item.group
    val badgeColor = remember(g.colorHex) {
        runCatching { Color(android.graphics.Color.parseColor(g.colorHex)) }
            .getOrElse { Color(0xFF0B57D0) }
    }
    val activeMembers = remember(item.apps) { item.apps.filter { !it.isUninstalled } }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(badgeColor.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = resolveGroupIcon(g.iconKey),
                            contentDescription = null,
                            tint = badgeColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(g.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "${activeMembers.size} 个应用" + if (g.description.isNotBlank()) " · ${g.description}" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                }
                Switch(checked = g.isEnabled, onCheckedChange = onToggleEnabled)
            }

            if (activeMembers.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    activeMembers.take(6).forEach { app ->
                        AppIconImage(
                            packageName = app.packageName,
                            appName = app.appName,
                            size = 24.dp
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
                val preview = activeMembers.take(5).joinToString("、") { it.appName } +
                    if (activeMembers.size > 5) " 等 ${activeMembers.size} 项" else ""
                Text(
                    text = "成员：$preview",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text("常驻断网网络作用域（点击切换）：", style = MaterialTheme.typography.labelSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val scopeChoices = buildList {
                    add(ProfileScope.ALL to "全网络禁网")
                    add(ProfileScope.WIFI_ALL to "Wi-Fi 禁网")
                    add(ProfileScope.MOBILE_ALL to "移动数据禁网")
                    add(BuiltinProfileId.MOBILE_SIM_1 to "SIM 1 禁网")
                    add(BuiltinProfileId.MOBILE_SIM_2 to "SIM 2 禁网")
                    customWifiProfiles.forEach { p ->
                        add(p.profile.profileId to "Wi-Fi「${p.profile.name}」禁网")
                    }
                }
                scopeChoices.forEach { (scopeKey, label) ->
                    val selected = scopeKey in blockedScopes
                    FilterChip(
                        selected = selected,
                        onClick = { onToggleScopePolicy(scopeKey, !selected) },
                        label = { Text(label) }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onPickMembers) {
                    Text("管理应用 (${activeMembers.size})")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(enabled = canMoveUp, onClick = onMoveUp) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "上移")
                    }
                    IconButton(enabled = canMoveDown, onClick = onMoveDown) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = "下移")
                    }
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "编辑分组")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "删除分组")
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleRuleCard(
    rule: ScheduleRuleEntity,
    groupName: String,
    scopeLabel: String,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val isCrossMidnight = rule.startMinute > rule.endMinute
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "${ScheduleCalculator.formatMinute(rule.startMinute)} → ${ScheduleCalculator.formatMinute(rule.endMinute)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (isCrossMidnight) {
                        AssistChip(onClick = {}, label = { Text("跨午夜") })
                    }
                }
                Text("分组：$groupName · 作用：$scopeLabel", style = MaterialTheme.typography.bodySmall)
                Text(
                    "重复：${formatWeekdayMask(rule.daysMask)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "删除定时") }
                Switch(checked = rule.isEnabled, onCheckedChange = onToggle)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GroupEditorDialog(
    initial: GroupEntity?,
    onDismiss: () -> Unit,
    onSave: (name: String, colorHex: String, iconKey: String, description: String, autoBlockScope: String?) -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var description by remember(initial) { mutableStateOf(initial?.description ?: "") }
    var colorHex by remember(initial) { mutableStateOf(initial?.colorHex ?: GROUP_COLOR_SWATCHES.first()) }
    var iconKey by remember(initial) { mutableStateOf(initial?.iconKey ?: "folder") }
    var autoBlockScope by remember(initial) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建自定义应用分组" else "自定义编辑分组") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (initial == null) {
                    Text("一键套用场景模板（可继续自由修改）：", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GROUP_TEMPLATES.forEach { tpl ->
                            AssistChip(
                                onClick = {
                                    name = tpl.name
                                    iconKey = tpl.iconKey
                                    colorHex = tpl.colorHex
                                    description = tpl.description
                                    autoBlockScope = tpl.autoBlockScope
                                },
                                leadingIcon = {
                                    Icon(
                                        resolveGroupIcon(tpl.iconKey),
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                label = { Text(tpl.name) }
                            )
                        }
                    }
                    HorizontalDivider()
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("分组名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("分组用途说明（选填）") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("自定义图标：", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GROUP_ICON_OPTIONS.forEach { opt ->
                        FilterChip(
                            selected = iconKey == opt.key,
                            onClick = { iconKey = opt.key },
                            leadingIcon = { Icon(opt.icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text(opt.label) }
                        )
                    }
                }

                Text("标签配色：", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GROUP_COLOR_SWATCHES.forEach { hex ->
                        FilterChip(
                            selected = colorHex == hex,
                            onClick = { colorHex = hex },
                            label = {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(Color(android.graphics.Color.parseColor(hex)))
                                )
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onSave(name.trim(), colorHex, iconKey, description.trim(), autoBlockScope) }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
internal fun BatchAppPickerDialog(
    groupName: String,
    allInstalledApps: List<AppEntity>,
    initialSelectedPackages: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    var selected by remember { mutableStateOf(initialSelectedPackages) }
    var search by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }

    val visibleApps = remember(allInstalledApps, search, showSystem) {
        val q = search.trim().lowercase()
        allInstalledApps
            .filter { showSystem || !it.isSystemApp }
            .filter { q.isEmpty() || it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
            .sortedWith(compareByDescending<AppEntity> { it.packageName in initialSelectedPackages }.thenBy { it.appName.lowercase() })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("管理「$groupName」应用 (${selected.size})") },
        text = {
            Column(modifier = Modifier.height(440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("搜索应用名或包名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showSystem, onCheckedChange = { showSystem = it })
                    Text("包含系统应用", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(
                        items = visibleApps,
                        key = { it.packageName },
                        contentType = { "picker_row" }
                    ) { app ->
                        val checked = app.packageName in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (!checked) selected + app.packageName else selected - app.packageName
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    selected = if (isChecked) selected + app.packageName else selected - app.packageName
                                }
                            )
                            AppIconImage(
                                packageName = app.packageName,
                                appName = app.appName,
                                size = 36.dp
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.appName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(
                                    app.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text("确定保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScheduleEditorDialog(
    groups: List<GroupEntity>,
    networkProfiles: List<NetworkProfileWithBssids>,
    presets: List<SchedulePresetEntity>,
    initialGroupId: Long? = null,
    onDismiss: () -> Unit,
    onSave: (ScheduleRuleEntity) -> Unit,
    onSaveAsPreset: ((SchedulePresetEntity) -> Unit)? = null,
    onRequestNewPreset: (() -> Unit)? = null
) {
    var selectedGroupId by remember(initialGroupId, groups) {
        mutableStateOf(initialGroupId ?: groups.first().groupId)
    }
    var startHourText by remember { mutableStateOf("23") }
    var startMinText by remember { mutableStateOf("00") }
    var endHourText by remember { mutableStateOf("07") }
    var endMinText by remember { mutableStateOf("00") }
    var daysMask by remember { mutableIntStateOf(0b1111111) }
    var profileScope by remember { mutableStateOf(ProfileScope.ALL) }
    var showSavePresetPrompt by remember { mutableStateOf(false) }
    var newPresetName by remember { mutableStateOf("") }
    var presetSavedTip by remember { mutableStateOf<String?>(null) }

    val startHour = startHourText.toIntOrNull()?.coerceIn(0, 23) ?: 0
    val startMin = startMinText.toIntOrNull()?.coerceIn(0, 59) ?: 0
    val endHour = endHourText.toIntOrNull()?.coerceIn(0, 23) ?: 0
    val endMin = endMinText.toIntOrNull()?.coerceIn(0, 59) ?: 0
    val startTotal = startHour * 60 + startMin
    val endTotal = endHour * 60 + endMin
    val isCrossMidnight = startTotal > endTotal

    if (showSavePresetPrompt && onSaveAsPreset != null) {
        AlertDialog(
            onDismissRequest = { showSavePresetPrompt = false },
            title = { Text("存为自定义定时模块") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "时段：${ScheduleCalculator.formatMinute(startTotal)} → ${ScheduleCalculator.formatMinute(endTotal)} · ${formatWeekdayMask(daysMask)} · ${formatScopeLabel(profileScope, networkProfiles)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = newPresetName,
                        onValueChange = { newPresetName = it },
                        label = { Text("定时模块名称（如：上课专注、夜间睡眠）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newPresetName.isNotBlank() && daysMask != 0,
                    onClick = {
                        val trimmed = newPresetName.trim()
                        onSaveAsPreset(
                            SchedulePresetEntity(
                                name = trimmed,
                                startMinute = startTotal,
                                endMinute = endTotal,
                                daysMask = daysMask,
                                profileScope = profileScope
                            )
                        )
                        presetSavedTip = "已保存定时模块「$trimmed」"
                        newPresetName = ""
                        showSavePresetPrompt = false
                    }
                ) { Text("保存模块") }
            },
            dismissButton = {
                TextButton(onClick = { showSavePresetPrompt = false }) { Text("取消") }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建定时断网规则") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("1. 选择目标分组", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    groups.forEach { g ->
                        FilterChip(
                            selected = selectedGroupId == g.groupId,
                            onClick = { selectedGroupId = g.groupId },
                            label = { Text(g.name) }
                        )
                    }
                }

                // 2. 我的定时模块（点「一键定时」直接完成，无需重复设置；点卡片回填微调）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "2. 我的定时模块（点右侧直接定时）",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    if (onRequestNewPreset != null) {
                        TextButton(onClick = onRequestNewPreset) { Text("+ 新建模块") }
                    }
                }
                if (presets.isEmpty()) {
                    Text(
                        "暂无自定义定时模块，可在下方配置后点击「存为新模块」。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        presets.forEach { preset ->
                            Card(
                                onClick = {
                                    startHourText = "%02d".format(preset.startMinute / 60)
                                    startMinText = "%02d".format(preset.startMinute % 60)
                                    endHourText = "%02d".format(preset.endMinute / 60)
                                    endMinText = "%02d".format(preset.endMinute % 60)
                                    daysMask = preset.daysMask
                                    profileScope = preset.profileScope
                                    presetSavedTip = "已载入模块「${preset.name}」，可微调后保存"
                                },
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
                                            preset.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            "${ScheduleCalculator.formatMinute(preset.startMinute)}→${ScheduleCalculator.formatMinute(preset.endMinute)} · ${formatWeekdayMask(preset.daysMask)} · ${formatScopeLabel(preset.profileScope, networkProfiles)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    FilledTonalButton(
                                        onClick = {
                                            onSave(
                                                ScheduleRuleEntity(
                                                    groupId = selectedGroupId,
                                                    profileScope = preset.profileScope,
                                                    startMinute = preset.startMinute,
                                                    endMinute = preset.endMinute,
                                                    daysMask = preset.daysMask,
                                                    isEnabled = true
                                                )
                                            )
                                        }
                                    ) { Text("一键定时") }
                                }
                            }
                        }
                    }
                }
                presetSavedTip?.let { tip ->
                    Text(tip, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "3. 自定义时段（${ScheduleCalculator.formatMinute(startTotal)} → ${ScheduleCalculator.formatMinute(endTotal)}）",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        val now = ZonedDateTime.now().plusMinutes(1)
                        val end = now.plusMinutes(2)
                        startHourText = "%02d".format(now.hour)
                        startMinText = "%02d".format(now.minute)
                        endHourText = "%02d".format(end.hour)
                        endMinText = "%02d".format(end.minute)
                    }) { Text("1分钟后测试") }
                }

                ScheduleTimeInputs(
                    startHourText = startHourText,
                    onStartHourChange = { startHourText = it },
                    startMinText = startMinText,
                    onStartMinChange = { startMinText = it },
                    endHourText = endHourText,
                    onEndHourChange = { endHourText = it },
                    endMinText = endMinText,
                    onEndMinChange = { endMinText = it }
                )

                if (isCrossMidnight) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Text(
                            text = "跨午夜时段提示：将从当日 ${ScheduleCalculator.formatMinute(startTotal)} 持续断网至次日 ${ScheduleCalculator.formatMinute(endTotal)}。",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                Text("4. 重复星期", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                WeekdayMaskSelector(
                    daysMask = daysMask,
                    onDaysMaskChange = { daysMask = it }
                )

                Text("5. 生效网络范围", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                ScheduleScopeSelector(
                    selectedScope = profileScope,
                    networkProfiles = networkProfiles,
                    onSelectScope = { profileScope = it }
                )

                if (onSaveAsPreset != null) {
                    OutlinedButton(
                        enabled = daysMask != 0,
                        onClick = { showSavePresetPrompt = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Alarm, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(" 将当前配置存为新定时模块")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = daysMask != 0,
                onClick = {
                    onSave(
                        ScheduleRuleEntity(
                            groupId = selectedGroupId,
                            profileScope = profileScope,
                            startMinute = startTotal,
                            endMinute = endTotal,
                            daysMask = daysMask,
                            isEnabled = true
                        )
                    )
                }
            ) { Text("保存并排程") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SchedulePresetEditorDialog(
    initial: SchedulePresetEntity?,
    networkProfiles: List<NetworkProfileWithBssids>,
    onDismiss: () -> Unit,
    onSave: (SchedulePresetEntity) -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var startHourText by remember(initial) { mutableStateOf("%02d".format((initial?.startMinute ?: 23 * 60) / 60)) }
    var startMinText by remember(initial) { mutableStateOf("%02d".format((initial?.startMinute ?: 23 * 60) % 60)) }
    var endHourText by remember(initial) { mutableStateOf("%02d".format((initial?.endMinute ?: 7 * 60) / 60)) }
    var endMinText by remember(initial) { mutableStateOf("%02d".format((initial?.endMinute ?: 7 * 60) % 60)) }
    var daysMask by remember(initial) { mutableIntStateOf(initial?.daysMask ?: 0b1111111) }
    var profileScope by remember(initial) { mutableStateOf(initial?.profileScope ?: ProfileScope.ALL) }

    val startTotal = (startHourText.toIntOrNull()?.coerceIn(0, 23) ?: 0) * 60 +
        (startMinText.toIntOrNull()?.coerceIn(0, 59) ?: 0)
    val endTotal = (endHourText.toIntOrNull()?.coerceIn(0, 23) ?: 0) * 60 +
        (endMinText.toIntOrNull()?.coerceIn(0, 59) ?: 0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建自定义定时模块" else "编辑定时模块") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("模块名称（如：夜间防熬夜、上课专注）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    "断网时间段（${ScheduleCalculator.formatMinute(startTotal)} → ${ScheduleCalculator.formatMinute(endTotal)}）",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                ScheduleTimeInputs(
                    startHourText = startHourText,
                    onStartHourChange = { startHourText = it },
                    startMinText = startMinText,
                    onStartMinChange = { startMinText = it },
                    endHourText = endHourText,
                    onEndHourChange = { endHourText = it },
                    endMinText = endMinText,
                    onEndMinChange = { endMinText = it }
                )

                Text("重复星期", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                WeekdayMaskSelector(
                    daysMask = daysMask,
                    onDaysMaskChange = { daysMask = it }
                )

                Text("默认生效网络范围", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                ScheduleScopeSelector(
                    selectedScope = profileScope,
                    networkProfiles = networkProfiles,
                    onSelectScope = { profileScope = it }
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && daysMask != 0,
                onClick = {
                    onSave(
                        (initial ?: SchedulePresetEntity(
                            name = name.trim(),
                            startMinute = startTotal,
                            endMinute = endTotal,
                            daysMask = daysMask,
                            profileScope = profileScope
                        )).copy(
                            name = name.trim(),
                            startMinute = startTotal,
                            endMinute = endTotal,
                            daysMask = daysMask,
                            profileScope = profileScope
                        )
                    )
                }
            ) { Text("保存模块") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun ScheduleTimeInputs(
    startHourText: String,
    onStartHourChange: (String) -> Unit,
    startMinText: String,
    onStartMinChange: (String) -> Unit,
    endHourText: String,
    onEndHourChange: (String) -> Unit,
    endMinText: String,
    onEndMinChange: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "开始",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(36.dp)
            )
            OutlinedTextField(
                value = startHourText,
                onValueChange = { onStartHourChange(it.filter(Char::isDigit).take(2)) },
                label = { Text("时 (0-23)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            Text(":", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = startMinText,
                onValueChange = { onStartMinChange(it.filter(Char::isDigit).take(2)) },
                label = { Text("分 (0-59)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "结束",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(36.dp)
            )
            OutlinedTextField(
                value = endHourText,
                onValueChange = { onEndHourChange(it.filter(Char::isDigit).take(2)) },
                label = { Text("时 (0-23)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            Text(":", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = endMinText,
                onValueChange = { onEndMinChange(it.filter(Char::isDigit).take(2)) },
                label = { Text("分 (0-59)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekdayMaskSelector(
    daysMask: Int,
    onDaysMaskChange: (Int) -> Unit
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val dayNames = listOf("一", "二", "三", "四", "五", "六", "日")
        dayNames.forEachIndexed { idx, label ->
            val bit = 1 shl idx
            val selected = (daysMask and bit) != 0
            FilterChip(
                selected = selected,
                onClick = { onDaysMaskChange(if (selected) daysMask and bit.inv() else daysMask or bit) },
                label = { Text(label) }
            )
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistChip(onClick = { onDaysMaskChange(0b1111111) }, label = { Text("每天") })
        AssistChip(onClick = { onDaysMaskChange(0b0011111) }, label = { Text("工作日") })
        AssistChip(onClick = { onDaysMaskChange(0b1100000) }, label = { Text("周末") })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleScopeSelector(
    selectedScope: String,
    networkProfiles: List<NetworkProfileWithBssids>,
    onSelectScope: (String) -> Unit
) {
    val scopeOptions = buildList {
        add(ProfileScope.ALL to "全部网络")
        add(ProfileScope.WIFI_ALL to "仅 Wi-Fi")
        add(ProfileScope.MOBILE_ALL to "仅移动数据")
        add(BuiltinProfileId.MOBILE_SIM_1 to "仅 SIM 1")
        add(BuiltinProfileId.MOBILE_SIM_2 to "仅 SIM 2")
        networkProfiles.filter { !it.profile.isSystemBuiltin }.forEach {
            add(it.profile.profileId to "Wi-Fi「${it.profile.name}」")
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        scopeOptions.forEach { (key, label) ->
            FilterChip(
                selected = selectedScope == key,
                onClick = { onSelectScope(key) },
                label = { Text(label) }
            )
        }
    }
}

internal fun formatWeekdayMask(mask: Int): String {
    if (mask == 0b1111111) return "每天"
    if (mask == 0b0011111) return "周一至周五"
    if (mask == 0b1100000) return "周末"
    val labels = listOf("一", "二", "三", "四", "五", "六", "日")
    return labels.mapIndexedNotNull { i, n -> if ((mask and (1 shl i)) != 0) "周$n" else null }
        .joinToString(" ")
        .ifBlank { "未选" }
}

internal fun formatScopeLabel(scope: String, profiles: List<NetworkProfileWithBssids>): String {
    return when (scope) {
        ProfileScope.ALL -> "全部网络"
        ProfileScope.WIFI_ALL -> "全部 Wi-Fi"
        ProfileScope.MOBILE_ALL -> "全部移动数据"
        BuiltinProfileId.MOBILE_SIM_1 -> "SIM 1"
        BuiltinProfileId.MOBILE_SIM_2 -> "SIM 2"
        else -> profiles.firstOrNull { it.profile.profileId == scope }?.profile?.name?.let { "Wi-Fi「$it」" } ?: scope
    }
}
