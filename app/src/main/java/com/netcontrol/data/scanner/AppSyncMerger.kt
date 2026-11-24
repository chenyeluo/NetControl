package com.netcontrol.data.scanner

import com.netcontrol.data.local.entity.AppEntity
import com.netcontrol.data.local.entity.ManualOverride

data class ScannedAppInfo(
    val packageName: String,
    val uid: Int,
    val appName: String,
    val isSystemApp: Boolean,
    val launcherActivity: String?,
    val signatureSha256: String,
    val installedAt: Long
)

data class SignatureChangeAlert(
    val packageName: String,
    val appName: String,
    val uid: Int,
    val oldSignatureSha256: String,
    val newSignatureSha256: String
)

data class AppSyncPlan(
    val appsToUpsert: List<AppEntity>,
    val newlyDiscoveredPackages: List<String>,
    val newlyUninstalledPackages: List<String>,
    val packagesToStripGroups: List<String>,
    val signatureAlerts: List<SignatureChangeAlert>
)

/**
 * 纯 Kotlin 应用同步合并引擎（无 Android 依赖，可完整 JVM 单元测试）：
 * 1. 自动识别共享 UID（uid 对应 >= 2 个已安装应用）；
 * 2. 新应用自动发现并按默认策略入库；
 * 3. 已卸载应用保留数据库记录（仅置 isUninstalled = true）以保留历史流量统计，并清理其分组关联；
 * 4. 重装/覆盖更新时比对 SHA-256 签名指纹：若签名变化，强制剥离旧的手动覆盖与分组关联并标记告警，
 *    防止恶意/假冒同名应用继承原应用的联网白名单。
 */
object AppSyncMerger {

    fun mergeFullScan(
        scannedApps: List<ScannedAppInfo>,
        existingApps: List<AppEntity>,
        nowMs: Long
    ): AppSyncPlan {
        val existingByPkg = existingApps.associateBy { it.packageName }
        val scannedByPkg = scannedApps.associateBy { it.packageName }

        // 统计已安装应用的 UID 共享情况
        val uidCounts = scannedApps.groupingBy { it.uid }.eachCount()

        val appsToUpsert = mutableListOf<AppEntity>()
        val newlyDiscovered = mutableListOf<String>()
        val signatureAlerts = mutableListOf<SignatureChangeAlert>()
        val packagesToStripGroups = mutableListOf<String>()

        for (scanned in scannedApps) {
            val isSharedUid = (uidCounts[scanned.uid] ?: 0) > 1
            val existing = existingByPkg[scanned.packageName]

            if (existing == null) {
                newlyDiscovered.add(scanned.packageName)
                appsToUpsert.add(
                    AppEntity(
                        packageName = scanned.packageName,
                        uid = scanned.uid,
                        appName = scanned.appName,
                        isSystemApp = scanned.isSystemApp,
                        isSharedUid = isSharedUid,
                        signatureSha256 = scanned.signatureSha256,
                        signatureChanged = false,
                        isUninstalled = false,
                        launcherActivity = scanned.launcherActivity,
                        manualOverride = ManualOverride.FOLLOW,
                        installedAt = if (scanned.installedAt > 0L) scanned.installedAt else nowMs,
                        updatedAt = nowMs
                    )
                )
            } else {
                val sigChanged = hasSignatureChanged(existing.signatureSha256, scanned.signatureSha256)
                if (sigChanged) {
                    signatureAlerts.add(
                        SignatureChangeAlert(
                            packageName = scanned.packageName,
                            appName = scanned.appName,
                            uid = scanned.uid,
                            oldSignatureSha256 = existing.signatureSha256,
                            newSignatureSha256 = scanned.signatureSha256
                        )
                    )
                    packagesToStripGroups.add(scanned.packageName)
                }

                val effectiveOverride = if (sigChanged) {
                    // 安全隔离：签名改变后绝不继承旧的强制放行或旧规则覆盖
                    ManualOverride.FOLLOW
                } else {
                    existing.manualOverride
                }

                val merged = existing.copy(
                    uid = scanned.uid,
                    appName = scanned.appName,
                    isSystemApp = scanned.isSystemApp,
                    isSharedUid = isSharedUid,
                    signatureSha256 = scanned.signatureSha256.ifBlank { existing.signatureSha256 },
                    signatureChanged = if (sigChanged) true else existing.signatureChanged,
                    isUninstalled = false,
                    launcherActivity = scanned.launcherActivity,
                    manualOverride = effectiveOverride,
                    installedAt = if (existing.installedAt > 0L) existing.installedAt else scanned.installedAt,
                    updatedAt = nowMs
                )
                appsToUpsert.add(merged)
            }
        }

        // 处理已卸载应用：不硬删除以保留历史流量，但标记 isUninstalled=true 并清理其分组关联
        val newlyUninstalled = mutableListOf<String>()
        for (existing in existingApps) {
            if (existing.packageName !in scannedByPkg && !existing.isUninstalled) {
                newlyUninstalled.add(existing.packageName)
                packagesToStripGroups.add(existing.packageName)
                appsToUpsert.add(
                    existing.copy(
                        isUninstalled = true,
                        isSharedUid = false,
                        updatedAt = nowMs
                    )
                )
            }
        }

        return AppSyncPlan(
            appsToUpsert = appsToUpsert,
            newlyDiscoveredPackages = newlyDiscovered,
            newlyUninstalledPackages = newlyUninstalled,
            packagesToStripGroups = packagesToStripGroups.distinct(),
            signatureAlerts = signatureAlerts
        )
    }

    internal fun hasSignatureChanged(oldSignature: String, newSignature: String): Boolean {
        if (oldSignature.isBlank() || newSignature.isBlank()) return false
        return !oldSignature.equals(newSignature, ignoreCase = true)
    }
}
