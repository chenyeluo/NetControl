package com.netcontrol.data.backup

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.room.withTransaction
import com.netcontrol.core.engine.RuleEngine
import com.netcontrol.core.network.NetworkStateMonitor
import com.netcontrol.data.local.AppDatabase
import com.netcontrol.data.local.entity.AppGroupCrossRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BackupOperationReport(
    val success: Boolean,
    val message: String,
    val bytesProcessed: Int = 0
)

/**
 * 加密备份与恢复编排器：
 * 通过 Android Storage Access Framework (Uri) 读写用户主动选择的 `.ncbk` 加密备份文件，
 * 数据 100% 本地处理，不联网上传；恢复前必须先经过解密认证与签名差异预览。
 */
class BackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val networkStateMonitor: NetworkStateMonitor,
    private val ruleEngine: RuleEngine
) {
    suspend fun exportEncryptedBackup(
        targetUri: Uri,
        backupType: BackupType,
        includeAuditLogs: Boolean,
        password: CharArray
    ): BackupOperationReport = withContext(Dispatchers.IO) {
        try {
            val includeRules = backupType == BackupType.FULL || backupType == BackupType.RULES_ONLY
            val includeStats = backupType == BackupType.FULL || backupType == BackupType.STATS_ONLY

            val groups = if (includeRules) database.groupDao().getAllGroupsOnce() else emptyList()
            val policies = if (includeRules) database.groupDao().getAllPoliciesOnce() else emptyList()
            val schedules = if (includeRules) database.scheduleRuleDao().getEnabledRulesOnce() else emptyList()
            val profiles = if (includeRules) database.networkProfileDao().getAllProfilesOnce() else emptyList()
            val bssids = if (includeRules) database.networkProfileDao().getAllBssidMappingsOnce() else emptyList()

            val appRules = if (includeRules) {
                val crossRefsByPkg = database.groupDao().getAllCrossRefsOnce().groupBy({ it.packageName }, { it.groupId })
                database.appDao().getAllAppsOnce().map { app ->
                    BackupAppRule(
                        packageName = app.packageName,
                        appName = app.appName,
                        signatureSha256 = app.signatureSha256,
                        manualOverride = app.manualOverride,
                        splashBlockEnabled = app.splashBlockEnabled,
                        splashDurationMs = app.splashDurationMs,
                        splashCooldownSec = app.splashCooldownSec,
                        splashRelaxActivityCheck = app.splashRelaxActivityCheck,
                        groupIds = crossRefsByPkg[app.packageName].orEmpty()
                    )
                }
            } else {
                emptyList()
            }

            val trafficBuckets = if (includeStats) database.trafficUsageDao().getAllBucketsOnce() else emptyList()
            val auditLogs = if (includeAuditLogs) database.auditLogDao().getRecentLogsOnce(1000) else emptyList()

            val payload = BackupPayload(
                schemaVersion = 2,
                backupType = backupType,
                createdAtMs = System.currentTimeMillis(),
                sourceDevice = "${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})",
                includeAuditLogs = includeAuditLogs,
                groups = groups,
                groupPolicies = policies,
                scheduleRules = schedules,
                networkProfiles = profiles,
                bssidMappings = bssids,
                appRules = appRules,
                trafficBuckets = trafficBuckets,
                auditLogs = auditLogs
            )

            val encrypted = BackupCryptoManager.encryptPayload(payload, password)
            context.contentResolver.openOutputStream(targetUri)?.use { out ->
                out.write(encrypted)
                out.flush()
            } ?: return@withContext BackupOperationReport(false, "无法打开目标文件进行写入")

            BackupOperationReport(
                success = true,
                message = "加密备份成功（${encrypted.size / 1024.coerceAtLeast(1)} KB，AES-256-GCM 已认证）",
                bytesProcessed = encrypted.size
            )
        } catch (t: Throwable) {
            BackupOperationReport(false, t.message ?: "导出备份失败")
        } finally {
            password.fill('\u0000')
        }
    }

    suspend fun decryptAndPreviewBackup(
        sourceUri: Uri,
        password: CharArray
    ): Result<BackupRestorePreview> = withContext(Dispatchers.IO) {
        try {
            val bytes = context.contentResolver.openInputStream(sourceUri)?.use { it.readBytes() }
                ?: return@withContext Result.failure(BackupCryptoException("无法读取所选备份文件"))
            val payload = BackupCryptoManager.decryptPayload(bytes, password)
            val currentApps = database.appDao().getAllAppsOnce()
            val preview = BackupRestorePlanner.buildPreview(payload, currentApps)
            Result.success(preview)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            password.fill('\u0000')
        }
    }

    suspend fun commitRestore(preview: BackupRestorePreview): BackupOperationReport = withContext(Dispatchers.IO) {
        try {
            val payload = preview.payload
            val restoreRules = payload.backupType == BackupType.FULL || payload.backupType == BackupType.RULES_ONLY
            val restoreStats = payload.backupType == BackupType.FULL || payload.backupType == BackupType.STATS_ONLY

            database.withTransaction {
                if (restoreRules) {
                    if (payload.networkProfiles.isNotEmpty()) {
                        database.networkProfileDao().upsertProfiles(payload.networkProfiles)
                    }
                    payload.bssidMappings.forEach { mapping ->
                        database.networkProfileDao().upsertBssidMapping(mapping)
                    }

                    // 映射旧 groupId -> 当前库 groupId（同名分组复用，新分组创建，避免外键冲突）
                    val existingGroupsByName = database.groupDao().getAllGroupsOnce()
                        .associateBy { it.name.lowercase() }
                    val groupIdMap = HashMap<Long, Long>()

                    for (g in payload.groups) {
                        val matched = existingGroupsByName[g.name.lowercase()]
                        val targetId = if (matched != null) {
                            database.groupDao().upsertGroups(
                                listOf(
                                    matched.copy(
                                        colorHex = g.colorHex,
                                        iconKey = g.iconKey,
                                        description = g.description,
                                        sortOrder = g.sortOrder,
                                        isEnabled = g.isEnabled
                                    )
                                )
                            )
                            matched.groupId
                        } else {
                            database.groupDao().insertGroup(g.copy(groupId = 0L))
                        }
                        groupIdMap[g.groupId] = targetId
                    }

                    payload.groupPolicies.forEach { policy ->
                        val mappedId = groupIdMap[policy.groupId] ?: return@forEach
                        database.groupDao().upsertPolicy(policy.copy(groupId = mappedId))
                    }

                    payload.scheduleRules.forEach { rule ->
                        val mappedId = groupIdMap[rule.groupId] ?: return@forEach
                        database.scheduleRuleDao().upsertRule(rule.copy(ruleId = 0L, groupId = mappedId))
                    }

                    // 仅恢复已安装且签名校验通过的应用规则（签名不匹配项已被 BackupRestorePlanner 严格隔离！）
                    val now = System.currentTimeMillis()
                    for (appRule in preview.matchedInstalledApps) {
                        database.appDao().updateManualOverride(appRule.packageName, appRule.manualOverride, now)
                        database.appDao().updateSplashConfig(
                            packageName = appRule.packageName,
                            enabled = appRule.splashBlockEnabled,
                            durationMs = appRule.splashDurationMs,
                            cooldownSec = appRule.splashCooldownSec,
                            relaxActivityCheck = appRule.splashRelaxActivityCheck,
                            updatedAt = now
                        )
                        database.groupDao().removeAppFromAllGroups(appRule.packageName)
                        val mappedRefs = appRule.groupIds.mapNotNull { oldGid ->
                            groupIdMap[oldGid]?.let { newGid -> AppGroupCrossRef(appRule.packageName, newGid) }
                        }
                        if (mappedRefs.isNotEmpty()) {
                            database.groupDao().addAppsToGroup(mappedRefs)
                        }
                    }
                }

                if (restoreStats && payload.trafficBuckets.isNotEmpty()) {
                    payload.trafficBuckets.chunked(400).forEach { chunk ->
                        database.trafficUsageDao().upsertAll(chunk)
                    }
                }

                if (payload.includeAuditLogs && payload.auditLogs.isNotEmpty()) {
                    database.auditLogDao().insertAll(payload.auditLogs)
                }
            }

            networkStateMonitor.refreshNow("BACKUP_RESTORED")
            ruleEngine.notifyRulesChanged("BACKUP_RESTORED")

            BackupOperationReport(
                success = true,
                message = "恢复完成：已安全导入 ${preview.matchedInstalledApps.size} 个应用规则、" +
                    "${payload.groups.size} 个分组、${payload.trafficBuckets.size} 条流量汇总" +
                    if (preview.signatureMismatchApps.isNotEmpty()) {
                        "（已隔离 ${preview.signatureMismatchApps.size} 个签名不匹配应用）"
                    } else {
                        ""
                    }
            )
        } catch (t: Throwable) {
            BackupOperationReport(false, "恢复写入失败: ${t.message}")
        }
    }
}
