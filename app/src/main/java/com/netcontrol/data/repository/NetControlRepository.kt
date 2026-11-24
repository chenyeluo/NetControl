package com.netcontrol.data.repository

import androidx.room.withTransaction
import com.netcontrol.data.local.AppDatabase
import com.netcontrol.data.local.entity.AppEntity
import com.netcontrol.data.local.entity.AppGroupCrossRef
import com.netcontrol.data.local.entity.AppWithGroups
import com.netcontrol.data.local.entity.FirewallAuditLogEntity
import com.netcontrol.data.local.entity.GroupEntity
import com.netcontrol.data.local.entity.GroupNetworkPolicyEntity
import com.netcontrol.data.local.entity.GroupWithApps
import com.netcontrol.data.local.entity.ManualOverride
import com.netcontrol.data.local.entity.NetworkProfileEntity
import com.netcontrol.data.local.entity.NetworkProfileType
import com.netcontrol.data.local.entity.NetworkProfileWithBssids
import com.netcontrol.data.local.entity.ProfileScope
import com.netcontrol.data.local.entity.SchedulePresetEntity
import com.netcontrol.data.local.entity.ScheduleRuleEntity
import com.netcontrol.data.local.entity.TempTargetType
import com.netcontrol.data.local.entity.TemporaryRuleEntity
import com.netcontrol.data.local.entity.WifiBssidMappingEntity
import java.util.UUID
import com.netcontrol.data.prefs.SettingsDataStore
import com.netcontrol.data.scanner.AppScanner
import com.netcontrol.data.scanner.AppSyncMerger
import com.netcontrol.data.scanner.SignatureChangeAlert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis

data class AppSyncSummary(
    val totalInstalled: Int,
    val userAppCount: Int,
    val systemAppCount: Int,
    val sharedUidCount: Int,
    val newlyDiscovered: Int,
    val newlyUninstalled: Int,
    val signatureAlerts: List<SignatureChangeAlert>,
    val durationMs: Long,
    val syncedAtMs: Long
)

class NetControlRepository(
    private val database: AppDatabase,
    private val settingsDataStore: SettingsDataStore,
    private val appScanner: AppScanner
) {
    private val appDao = database.appDao()
    private val groupDao = database.groupDao()
    private val networkProfileDao = database.networkProfileDao()
    private val scheduleRuleDao = database.scheduleRuleDao()
    private val schedulePresetDao = database.schedulePresetDao()
    private val temporaryRuleDao = database.temporaryRuleDao()
    private val auditLogDao = database.auditLogDao()

    private val syncMutex = Mutex()

    private val _lastSyncSummary = MutableStateFlow<AppSyncSummary?>(null)
    val lastSyncSummary: StateFlow<AppSyncSummary?> = _lastSyncSummary.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    val installedAppsFlow: Flow<List<AppEntity>> = appDao.observeInstalledApps()
    val allAppsFlow: Flow<List<AppEntity>> = appDao.observeAllApps()
    val installedAppsWithGroupsFlow: Flow<List<AppWithGroups>> = appDao.observeInstalledAppsWithGroups()
    val signatureAlertAppsFlow: Flow<List<AppEntity>> = appDao.observeSignatureAlertApps()
    val groupsFlow: Flow<List<GroupEntity>> = groupDao.observeGroups()
    val groupsWithAppsFlow: Flow<List<GroupWithApps>> = groupDao.observeGroupsWithApps()
    val groupPoliciesFlow: Flow<List<GroupNetworkPolicyEntity>> = groupDao.observeAllPolicies()
    val networkProfilesFlow: Flow<List<NetworkProfileWithBssids>> = networkProfileDao.observeProfilesWithBssids()
    val scheduleRulesFlow: Flow<List<ScheduleRuleEntity>> = scheduleRuleDao.observeAllRules()
    val schedulePresetsFlow: Flow<List<SchedulePresetEntity>> = schedulePresetDao.observeAllPresets()
    val recentAuditLogsFlow: Flow<List<FirewallAuditLogEntity>> = auditLogDao.observeRecentLogs(300)

    fun observeTrafficBuckets(bucketType: String, fromEpochMs: Long) =
        database.trafficUsageDao().observeBuckets(bucketType, fromEpochMs)

    suspend fun clearAllTrafficStats() = withContext(Dispatchers.IO) {
        database.trafficUsageDao().deleteAll()
    }

    /**
     * 幂等初始化内置网络档案（5 个系统内置档案）与首次启动默认分组（仅空库时写入）。
     */
    suspend fun ensureInitialSeed() = withContext(Dispatchers.IO) {
        val settings = settingsDataStore.getSnapshot()
        if (settings.initialSeedCompleted) return@withContext

        database.withTransaction {
            val existingProfiles = networkProfileDao.getAllProfilesOnce().map { it.profileId }.toSet()
            val missingProfiles = AppDatabase.DEFAULT_NETWORK_PROFILES.filter { it.profileId !in existingProfiles }
            if (missingProfiles.isNotEmpty()) {
                networkProfileDao.upsertProfiles(missingProfiles)
            }
            if (groupDao.countGroups() == 0) {
                groupDao.upsertGroups(AppDatabase.DEFAULT_STARTER_GROUPS)
            }
            if (schedulePresetDao.countPresets() == 0) {
                schedulePresetDao.upsertPresets(AppDatabase.DEFAULT_SCHEDULE_PRESETS)
            }
        }
        settingsDataStore.markInitialSeedCompleted()
    }

    /**
     * 全量异步扫描设备内所有已安装应用（含系统应用），合并同步至 Room，
     * 自动识别共享 UID、新装应用、已卸载应用及 SHA-256 签名变更。
     */
    suspend fun syncAllInstalledApps(): AppSyncSummary = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            _isSyncing.value = true
            try {
                ensureInitialSeed()
                val now = System.currentTimeMillis()
                var summary: AppSyncSummary? = null

                val elapsed = measureTimeMillis {
                    val scanned = appScanner.scanAllInstalledApps()
                    val existing = appDao.getAllAppsOnce()
                    val plan = AppSyncMerger.mergeFullScan(scanned, existing, now)

                    database.withTransaction {
                        if (plan.appsToUpsert.isNotEmpty()) {
                            appDao.upsertAll(plan.appsToUpsert)
                        }
                        if (plan.packagesToStripGroups.isNotEmpty()) {
                            plan.packagesToStripGroups.chunked(400).forEach { chunk ->
                                groupDao.removeAppsFromAllGroups(chunk)
                            }
                        }
                        if (plan.signatureAlerts.isNotEmpty()) {
                            val logs = plan.signatureAlerts.map { alert ->
                                FirewallAuditLogEntity(
                                    timestamp = now,
                                    packageName = alert.packageName,
                                    uid = alert.uid,
                                    blocked = false,
                                    reason = "SIGNATURE_MISMATCH_ISOLATED:${alert.oldSignatureSha256.take(8)}->${alert.newSignatureSha256.take(8)}",
                                    networkProfileId = "",
                                    backendType = "SCANNER"
                                )
                            }
                            auditLogDao.insertAll(logs)
                        }
                    }

                    val installedCount = scanned.size
                    val systemCount = scanned.count { it.isSystemApp }
                    val userCount = installedCount - systemCount
                    val sharedUidCount = plan.appsToUpsert.count { !it.isUninstalled && it.isSharedUid }

                    summary = AppSyncSummary(
                        totalInstalled = installedCount,
                        userAppCount = userCount,
                        systemAppCount = systemCount,
                        sharedUidCount = sharedUidCount,
                        newlyDiscovered = plan.newlyDiscoveredPackages.size,
                        newlyUninstalled = plan.newlyUninstalledPackages.size,
                        signatureAlerts = plan.signatureAlerts,
                        durationMs = 0L,
                        syncedAtMs = now
                    )
                }

                val finalSummary = summary!!.copy(durationMs = elapsed)
                _lastSyncSummary.value = finalSummary
                finalSummary
            } finally {
                _isSyncing.value = false
            }
        }
    }

    /**
     * 响应系统 ACTION_PACKAGE_ADDED / ACTION_PACKAGE_REPLACED 广播的增量同步。
     */
    suspend fun handlePackageAddedOrReplaced(packageName: String) = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            ensureInitialSeed()
            val scanned = appScanner.scanSinglePackage(packageName) ?: return@withContext
            val now = System.currentTimeMillis()

            database.withTransaction {
                val existing = appDao.getByPackage(packageName)
                val sameUidPeers = appDao.getInstalledPackagesByUid(scanned.uid)
                val isShared = (sameUidPeers + packageName).toSet().size > 1

                val sigChanged = existing != null &&
                    AppSyncMerger.hasSignatureChanged(existing.signatureSha256, scanned.signatureSha256)

                val entity = if (existing == null) {
                    AppEntity(
                        packageName = scanned.packageName,
                        uid = scanned.uid,
                        appName = scanned.appName,
                        isSystemApp = scanned.isSystemApp,
                        isSharedUid = isShared,
                        signatureSha256 = scanned.signatureSha256,
                        signatureChanged = false,
                        isUninstalled = false,
                        launcherActivity = scanned.launcherActivity,
                        manualOverride = ManualOverride.FOLLOW,
                        installedAt = if (scanned.installedAt > 0L) scanned.installedAt else now,
                        updatedAt = now
                    )
                } else {
                    existing.copy(
                        uid = scanned.uid,
                        appName = scanned.appName,
                        isSystemApp = scanned.isSystemApp,
                        isSharedUid = isShared,
                        signatureSha256 = scanned.signatureSha256.ifBlank { existing.signatureSha256 },
                        signatureChanged = if (sigChanged) true else existing.signatureChanged,
                        isUninstalled = false,
                        launcherActivity = scanned.launcherActivity,
                        manualOverride = if (sigChanged) ManualOverride.FOLLOW else existing.manualOverride,
                        updatedAt = now
                    )
                }

                appDao.upsert(entity)

                // 如果存在同 UID 应用，同步更新它们的共享 UID 标记
                if (isShared) {
                    sameUidPeers.filter { it != packageName }.forEach { peerPkg ->
                        appDao.getByPackage(peerPkg)?.let { peer ->
                            if (!peer.isSharedUid) {
                                appDao.upsert(peer.copy(isSharedUid = true, updatedAt = now))
                            }
                        }
                    }
                }

                if (sigChanged) {
                    val oldHashPrefix = existing?.signatureSha256.orEmpty().take(8)
                    groupDao.removeAppFromAllGroups(packageName)
                    auditLogDao.insert(
                        FirewallAuditLogEntity(
                            timestamp = now,
                            packageName = packageName,
                            uid = scanned.uid,
                            blocked = false,
                            reason = "SIGNATURE_MISMATCH_ISOLATED:$oldHashPrefix->${scanned.signatureSha256.take(8)}",
                            backendType = "PACKAGE_RECEIVER"
                        )
                    )
                } else if (existing == null) {
                    auditLogDao.insert(
                        FirewallAuditLogEntity(
                            timestamp = now,
                            packageName = packageName,
                            uid = scanned.uid,
                            blocked = false,
                            reason = "NEW_APP_DISCOVERED",
                            backendType = "PACKAGE_RECEIVER"
                        )
                    )
                }
            }
        }
    }

    /**
     * 响应系统 ACTION_PACKAGE_REMOVED（非替换升级）广播：保留记录以维护历史流量统计，但置卸载标记并清理分组。
     */
    suspend fun handlePackageRemoved(packageName: String) = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            database.withTransaction {
                val existing = appDao.getByPackage(packageName) ?: return@withTransaction
                appDao.markUninstalled(packageName, now)
                groupDao.removeAppFromAllGroups(packageName)

                // 重新检查同 UID 剩余应用是否仍共享 UID
                val remainingPeers = appDao.getInstalledPackagesByUid(existing.uid)
                if (remainingPeers.size == 1) {
                    appDao.getByPackage(remainingPeers.first())?.let { solePeer ->
                        if (solePeer.isSharedUid) {
                            appDao.upsert(solePeer.copy(isSharedUid = false, updatedAt = now))
                        }
                    }
                }

                auditLogDao.insert(
                    FirewallAuditLogEntity(
                        timestamp = now,
                        packageName = packageName,
                        uid = existing.uid,
                        blocked = false,
                        reason = "APP_UNINSTALLED_CLEANED",
                        backendType = "PACKAGE_RECEIVER"
                    )
                )
            }
        }
    }

    suspend fun acknowledgeSignatureAlert(packageName: String) {
        appDao.acknowledgeSignatureChange(packageName, System.currentTimeMillis())
    }

    suspend fun updateManualOverride(packageName: String, overrideMode: Int) {
        appDao.updateManualOverride(packageName, overrideMode, System.currentTimeMillis())
    }

    suspend fun updateSplashConfig(
        packageName: String,
        enabled: Boolean,
        durationMs: Int,
        cooldownSec: Int,
        relaxActivityCheck: Boolean
    ) {
        appDao.updateSplashConfig(
            packageName = packageName,
            enabled = enabled,
            durationMs = durationMs.coerceIn(500, 15_000),
            cooldownSec = cooldownSec.coerceIn(0, 600),
            relaxActivityCheck = relaxActivityCheck,
            updatedAt = System.currentTimeMillis()
        )
    }

    suspend fun addAppToGroup(packageName: String, groupId: Long) {
        groupDao.addAppToGroup(AppGroupCrossRef(packageName, groupId))
    }

    suspend fun removeAppFromGroup(packageName: String, groupId: Long) {
        groupDao.removeAppFromGroup(packageName, groupId)
    }

    suspend fun pruneExpiredAuditLogs(retentionDays: Long = 7L): Int {
        val cutoff = System.currentTimeMillis() - retentionDays * 24L * 60L * 60L * 1000L
        return auditLogDao.deleteOlderThan(cutoff)
    }

    suspend fun pruneExpiredTemporaryRules(): Int {
        return temporaryRuleDao.deleteExpiredRules(System.currentTimeMillis())
    }

    /**
     * 将当前 Wi-Fi BSSID 登记为可信网络档案（若同名可信档案已存在则追加该 BSSID，适配多 AP / Mesh）。
     */
    suspend fun registerTrustedWifiBssid(
        bssid: String,
        ssid: String?,
        profileName: String
    ): String = withContext(Dispatchers.IO) {
        val normalized = bssid.trim().lowercase()
        val displayName = profileName.trim().ifBlank { ssid?.ifBlank { null } ?: "可信 Wi-Fi" }
        val now = System.currentTimeMillis()

        database.withTransaction {
            val existingProfile = networkProfileDao.getAllProfilesOnce().firstOrNull {
                it.type == NetworkProfileType.WIFI_TRUSTED && it.name.equals(displayName, ignoreCase = true)
            }
            val profileId = existingProfile?.profileId ?: "wifi_${UUID.randomUUID().toString().take(8)}"
            if (existingProfile == null) {
                networkProfileDao.upsertProfile(
                    NetworkProfileEntity(
                        profileId = profileId,
                        name = displayName,
                        type = NetworkProfileType.WIFI_TRUSTED,
                        defaultAllow = true,
                        isSystemBuiltin = false
                    )
                )
            }
            networkProfileDao.upsertBssidMapping(
                WifiBssidMappingEntity(
                    bssid = normalized,
                    profileId = profileId,
                    ssidHint = ssid.orEmpty(),
                    addedAt = now
                )
            )
            profileId
        }
    }

    suspend fun removeTrustedBssid(bssid: String) = withContext(Dispatchers.IO) {
        networkProfileDao.deleteBssidMapping(bssid.trim().lowercase())
    }

    suspend fun setTemporaryAppRule(
        packageName: String,
        action: String,
        durationMinutes: Long,
        profileScope: String = ProfileScope.ALL
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            temporaryRuleDao.clearRulesForTarget(TempTargetType.APP, packageName)
            temporaryRuleDao.upsertRule(
                TemporaryRuleEntity(
                    targetType = TempTargetType.APP,
                    packageName = packageName,
                    action = action,
                    profileScope = profileScope,
                    expiresAtEpochMs = now + durationMinutes * 60_000L,
                    createdAt = now
                )
            )
        }
    }

    suspend fun clearTemporaryAppRules(packageName: String) = withContext(Dispatchers.IO) {
        temporaryRuleDao.clearRulesForTarget(TempTargetType.APP, packageName)
    }

    suspend fun upsertScheduleRule(rule: ScheduleRuleEntity): Long = withContext(Dispatchers.IO) {
        scheduleRuleDao.upsertRule(rule)
    }

    suspend fun deleteScheduleRule(ruleId: Long) = withContext(Dispatchers.IO) {
        scheduleRuleDao.deleteRule(ruleId)
    }

    suspend fun setScheduleRuleEnabled(ruleId: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
        scheduleRuleDao.setRuleEnabled(ruleId, enabled)
    }

    suspend fun upsertSchedulePreset(preset: SchedulePresetEntity): Long = withContext(Dispatchers.IO) {
        val normalized = if (preset.presetId == 0L && preset.sortOrder == 0) {
            preset.copy(name = preset.name.trim(), sortOrder = schedulePresetDao.countPresets())
        } else {
            preset.copy(name = preset.name.trim())
        }
        schedulePresetDao.upsertPreset(normalized)
    }

    suspend fun deleteSchedulePreset(presetId: Long) = withContext(Dispatchers.IO) {
        schedulePresetDao.deletePreset(presetId)
    }

    suspend fun applySchedulePresetToGroup(groupId: Long, preset: SchedulePresetEntity): Long =
        withContext(Dispatchers.IO) {
            scheduleRuleDao.upsertRule(
                ScheduleRuleEntity(
                    groupId = groupId,
                    profileScope = preset.profileScope,
                    startMinute = preset.startMinute,
                    endMinute = preset.endMinute,
                    daysMask = preset.daysMask,
                    isEnabled = true
                )
            )
        }

    suspend fun setGroupEnabled(group: GroupEntity, enabled: Boolean) = withContext(Dispatchers.IO) {
        groupDao.upsertGroups(listOf(group.copy(isEnabled = enabled)))
    }

    suspend fun createGroup(
        name: String,
        colorHex: String,
        iconKey: String = "folder",
        description: String = ""
    ): Long = withContext(Dispatchers.IO) {
        val count = groupDao.countGroups()
        groupDao.insertGroup(
            GroupEntity(
                name = name.trim(),
                colorHex = colorHex,
                iconKey = iconKey,
                description = description.trim(),
                sortOrder = count,
                isEnabled = true
            )
        )
    }

    suspend fun swapGroupOrder(first: GroupEntity, second: GroupEntity) = withContext(Dispatchers.IO) {
        groupDao.upsertGroups(
            listOf(
                first.copy(sortOrder = second.sortOrder),
                second.copy(sortOrder = first.sortOrder)
            )
        )
    }

    suspend fun updateGroup(group: GroupEntity) = withContext(Dispatchers.IO) {
        groupDao.upsertGroups(listOf(group))
    }

    suspend fun deleteGroup(group: GroupEntity) = withContext(Dispatchers.IO) {
        groupDao.deleteGroup(group)
    }

    suspend fun replaceGroupMembers(groupId: Long, packageNames: Collection<String>) = withContext(Dispatchers.IO) {
        groupDao.replaceGroupMembers(groupId, packageNames)
    }

    suspend fun upsertGroupNetworkPolicy(
        groupId: Long,
        profileScope: String,
        blockByDefault: Boolean
    ) = withContext(Dispatchers.IO) {
        if (blockByDefault) {
            groupDao.upsertPolicy(GroupNetworkPolicyEntity(groupId, profileScope, true))
        } else {
            groupDao.deletePolicy(groupId, profileScope)
        }
    }

    suspend fun createTrustedWifiProfile(name: String, defaultAllow: Boolean = true): String =
        withContext(Dispatchers.IO) {
            val profileId = "wifi_${UUID.randomUUID().toString().take(8)}"
            networkProfileDao.upsertProfile(
                NetworkProfileEntity(
                    profileId = profileId,
                    name = name.trim().ifBlank { "自定义 Wi-Fi" },
                    type = NetworkProfileType.WIFI_TRUSTED,
                    defaultAllow = defaultAllow,
                    isSystemBuiltin = false
                )
            )
            profileId
        }

    suspend fun attachBssidToProfile(bssid: String, profileId: String, ssidHint: String?) =
        withContext(Dispatchers.IO) {
            networkProfileDao.upsertBssidMapping(
                WifiBssidMappingEntity(
                    bssid = bssid.trim().lowercase(),
                    profileId = profileId,
                    ssidHint = ssidHint.orEmpty(),
                    addedAt = System.currentTimeMillis()
                )
            )
        }

    suspend fun updateNetworkProfileDefaultAllow(profile: NetworkProfileEntity, defaultAllow: Boolean) =
        withContext(Dispatchers.IO) {
            networkProfileDao.upsertProfile(profile.copy(defaultAllow = defaultAllow))
        }

    suspend fun deleteCustomNetworkProfile(profileId: String) = withContext(Dispatchers.IO) {
        networkProfileDao.deleteCustomProfile(profileId)
    }
}
