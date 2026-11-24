package com.netcontrol.data.backup

import com.netcontrol.data.local.entity.FirewallAuditLogEntity
import com.netcontrol.data.local.entity.GroupEntity
import com.netcontrol.data.local.entity.GroupNetworkPolicyEntity
import com.netcontrol.data.local.entity.NetworkProfileEntity
import com.netcontrol.data.local.entity.ScheduleRuleEntity
import com.netcontrol.data.local.entity.TrafficUsageBucketEntity
import com.netcontrol.data.local.entity.WifiBssidMappingEntity

enum class BackupType(val label: String) {
    FULL("完整备份（规则 + 流量统计）"),
    RULES_ONLY("仅规则与分组备份"),
    STATS_ONLY("仅流量统计备份")
}

data class BackupAppRule(
    val packageName: String,
    val appName: String,
    val signatureSha256: String,
    val manualOverride: Int,
    val splashBlockEnabled: Boolean,
    val splashDurationMs: Int,
    val splashCooldownSec: Int,
    val splashRelaxActivityCheck: Boolean,
    val groupIds: List<Long>
)

data class BackupPayload(
    val schemaVersion: Int = 2,
    val backupType: BackupType,
    val createdAtMs: Long,
    val sourceDevice: String,
    val includeAuditLogs: Boolean,
    val groups: List<GroupEntity> = emptyList(),
    val groupPolicies: List<GroupNetworkPolicyEntity> = emptyList(),
    val scheduleRules: List<ScheduleRuleEntity> = emptyList(),
    val networkProfiles: List<NetworkProfileEntity> = emptyList(),
    val bssidMappings: List<WifiBssidMappingEntity> = emptyList(),
    val appRules: List<BackupAppRule> = emptyList(),
    val trafficBuckets: List<TrafficUsageBucketEntity> = emptyList(),
    val auditLogs: List<FirewallAuditLogEntity> = emptyList()
)

data class SignatureMismatchEntry(
    val packageName: String,
    val appName: String,
    val backupSignaturePrefix: String,
    val deviceSignaturePrefix: String
)

data class BackupRestorePreview(
    val payload: BackupPayload,
    val matchedInstalledApps: List<BackupAppRule>,
    val uninstalledBackupApps: List<BackupAppRule>,
    val signatureMismatchApps: List<SignatureMismatchEntry>
)
