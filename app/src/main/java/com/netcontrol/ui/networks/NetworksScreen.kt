package com.netcontrol.ui.networks

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netcontrol.core.engine.RuleEngine
import com.netcontrol.core.network.NetworkStateMonitor
import com.netcontrol.core.network.ResolvedProfileKind
import com.netcontrol.data.local.entity.NetworkProfileWithBssids
import com.netcontrol.data.prefs.FirewallSettings
import com.netcontrol.data.prefs.SettingsDataStore
import com.netcontrol.data.repository.NetControlRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NetworksScreen(
    repository: NetControlRepository,
    settingsDataStore: SettingsDataStore,
    networkStateMonitor: NetworkStateMonitor,
    ruleEngine: RuleEngine,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val settings by settingsDataStore.settingsFlow.collectAsStateWithLifecycle(initialValue = FirewallSettings())
    val networkCtx by networkStateMonitor.currentContext.collectAsStateWithLifecycle()
    val profiles by repository.networkProfilesFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    var showTrustCurrentDialog by remember { mutableStateOf(false) }
    var showCreateProfileDialog by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }

    val customProfiles = remember(profiles) { profiles.filter { !it.profile.isSystemBuiltin } }
    val builtinProfiles = remember(profiles) { profiles.filter { it.profile.isSystemBuiltin } }

    if (showTrustCurrentDialog && networkCtx.bssid != null) {
        val currentBssid = networkCtx.bssid!!
        var newName by remember { mutableStateOf(networkCtx.ssid ?: "家庭 Wi-Fi") }
        var attachToExistingId by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showTrustCurrentDialog = false },
            title = { Text("登记当前 Wi-Fi 为可信档案") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "SSID: ${networkCtx.ssid ?: "未知"}\nBSSID: $currentBssid",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    if (customProfiles.isNotEmpty()) {
                        Text("归入已有档案（适合 Mesh / 多 AP 路由器）：", style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = attachToExistingId == null,
                                onClick = { attachToExistingId = null },
                                label = { Text("新建独立档案") }
                            )
                            customProfiles.forEach { p ->
                                FilterChip(
                                    selected = attachToExistingId == p.profile.profileId,
                                    onClick = { attachToExistingId = p.profile.profileId },
                                    label = { Text(p.profile.name) }
                                )
                            }
                        }
                    }
                    if (attachToExistingId == null) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text("新可信 Wi-Fi 档案名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val existingId = attachToExistingId
                        if (existingId != null) {
                            repository.attachBssidToProfile(currentBssid, existingId, networkCtx.ssid)
                            feedback = "已将 BSSID $currentBssid 追加到现有可信档案"
                        } else {
                            repository.registerTrustedWifiBssid(currentBssid, networkCtx.ssid, newName)
                            feedback = "已创建可信 Wi-Fi 档案「$newName」"
                        }
                        networkStateMonitor.refreshNow("TRUST_WIFI_UPDATED")
                        showTrustCurrentDialog = false
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showTrustCurrentDialog = false }) { Text("取消") }
            }
        )
    }

    if (showCreateProfileDialog) {
        var profileName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateProfileDialog = false },
            title = { Text("新建空白可信 Wi-Fi 档案") },
            text = {
                OutlinedTextField(
                    value = profileName,
                    onValueChange = { profileName = it },
                    label = { Text("档案名称（如：公司办公网）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = profileName.isNotBlank(),
                    onClick = {
                        scope.launch {
                            repository.createTrustedWifiProfile(profileName.trim())
                            showCreateProfileDialog = false
                        }
                    }
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateProfileDialog = false }) { Text("取消") }
            }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. 当前活跃网络实时感知卡
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("当前识别档案：${networkCtx.profileName}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("类型：${networkCtx.kind.name}", style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = {
                            scope.launch {
                                val ctx = networkStateMonitor.refreshNow("MANUAL_NETWORKS_REFRESH")
                                feedback = "已刷新：${ctx.profileName}"
                            }
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新网络")
                        }
                    }

                    Text(
                        text = buildString {
                            if (networkCtx.isWifi) {
                                appendLine("SSID：${networkCtx.ssid ?: "未知"} | BSSID：${networkCtx.bssid ?: "不可读"}")
                                append("读取通道：${networkCtx.bssidSource}")
                                if (networkCtx.degradedFromUnreadableBssid) {
                                    append("（已触发防误锁优雅降级）")
                                }
                            } else if (networkCtx.isMobile) {
                                append("当前移动数据卡：${networkCtx.simSlotIndex?.let { "SIM ${it + 1}" } ?: "未知卡槽"}")
                            } else {
                                append("当前设备处于离线状态")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )

                    if (networkCtx.isWifi && !networkCtx.bssid.isNullOrBlank() && networkCtx.kind != ResolvedProfileKind.WIFI_TRUSTED) {
                        Button(onClick = { showTrustCurrentDialog = true }) {
                            Text("将当前 Wi-Fi 登记为可信档案")
                        }
                    }

                    feedback?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        // 2. 未知网络安全边界与优雅降级
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("未知网络安全边界策略（P1）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

                    PolicySwitchRow(
                        title = "未知 Wi-Fi 默认拒绝联网",
                        subtitle = "连入未登记 BSSID 的公共 Wi-Fi 时拦截全部用户应用（系统核心除外）",
                        checked = settings.blockUnknownWifi,
                        onCheckedChange = { checked ->
                            scope.launch {
                                settingsDataStore.setUnknownWifiPolicy(checked, settings.gracefulDegradeOnUnreadableBssid)
                                networkStateMonitor.refreshNow("TOGGLE_UNKNOWN_WIFI")
                            }
                        }
                    )
                    HorizontalDivider()
                    PolicySwitchRow(
                        title = "读不到 BSSID 时优雅降级为通用 Wi-Fi",
                        subtitle = "未开 Shizuku 且关闭定位时防止误判为未知 Wi-Fi 导致全机断网",
                        checked = settings.gracefulDegradeOnUnreadableBssid,
                        onCheckedChange = { checked ->
                            scope.launch {
                                settingsDataStore.setUnknownWifiPolicy(settings.blockUnknownWifi, checked)
                                networkStateMonitor.refreshNow("TOGGLE_GRACEFUL_DEGRADE")
                            }
                        }
                    )
                    HorizontalDivider()
                    PolicySwitchRow(
                        title = "未知移动网络默认拒绝联网",
                        subtitle = "无法确认承载 SIM 卡槽时执行拒绝策略",
                        checked = settings.blockUnknownMobile,
                        onCheckedChange = { checked ->
                            scope.launch {
                                settingsDataStore.setBlockUnknownMobile(checked)
                                networkStateMonitor.refreshNow("TOGGLE_UNKNOWN_MOBILE")
                            }
                        }
                    )
                }
            }
        }

        // 3. 已登记可信 Wi-Fi 档案（支持多 BSSID / Mesh）
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("可信 Wi-Fi 档案 (${customProfiles.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                OutlinedButton(onClick = { showCreateProfileDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("新建档案")
                }
            }
        }

        if (customProfiles.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "尚未登记任何可信 Wi-Fi。连接家庭或公司 Wi-Fi 后，点击上方按钮即可绑定 BSSID（同一档案可绑定多个 Mesh 节点）。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        } else {
            items(items = customProfiles, key = { it.profile.profileId }) { item ->
                NetworkProfileCard(
                    item = item,
                    onToggleDefaultAllow = { allow ->
                        scope.launch {
                            repository.updateNetworkProfileDefaultAllow(item.profile, allow)
                            networkStateMonitor.refreshNow("PROFILE_DEFAULT_CHANGED")
                        }
                    },
                    onRemoveBssid = { bssid ->
                        scope.launch {
                            repository.removeTrustedBssid(bssid)
                            networkStateMonitor.refreshNow("BSSID_REMOVED")
                        }
                    },
                    onDeleteCustom = {
                        scope.launch {
                            repository.deleteCustomNetworkProfile(item.profile.profileId)
                            networkStateMonitor.refreshNow("PROFILE_DELETED")
                            ruleEngine.notifyRulesChanged("PROFILE_DELETED")
                        }
                    }
                )
            }
        }

        // 4. 双 SIM 与系统内置档案
        item {
            Text("双 SIM 与系统内置档案", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        items(items = builtinProfiles, key = { it.profile.profileId }) { item ->
            NetworkProfileCard(
                item = item,
                onToggleDefaultAllow = { allow ->
                    scope.launch {
                        repository.updateNetworkProfileDefaultAllow(item.profile, allow)
                        networkStateMonitor.refreshNow("BUILTIN_PROFILE_CHANGED")
                    }
                },
                onRemoveBssid = {},
                onDeleteCustom = null
            )
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NetworkProfileCard(
    item: NetworkProfileWithBssids,
    onToggleDefaultAllow: (Boolean) -> Unit,
    onRemoveBssid: (String) -> Unit,
    onDeleteCustom: (() -> Unit)?
) {
    val p = item.profile
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "${p.type} · 未命中规则时${if (p.defaultAllow) "默认放行" else "默认拒绝"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onDeleteCustom != null) {
                        IconButton(onClick = onDeleteCustom) {
                            Icon(Icons.Default.Delete, contentDescription = "删除档案")
                        }
                    }
                    Switch(checked = p.defaultAllow, onCheckedChange = onToggleDefaultAllow)
                }
            }

            if (item.bssids.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.bssids.forEach { mapping ->
                        InputChip(
                            selected = false,
                            onClick = { onRemoveBssid(mapping.bssid) },
                            label = {
                                Text(
                                    text = if (mapping.ssidHint.isNotBlank()) "${mapping.ssidHint} (${mapping.bssid})" else mapping.bssid,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            trailingIcon = { Icon(Icons.Default.Close, contentDescription = "移除 BSSID") }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PolicySwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
