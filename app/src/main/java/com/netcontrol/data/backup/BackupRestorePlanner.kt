package com.netcontrol.data.backup

import com.netcontrol.data.local.entity.AppEntity

/**
 * 纯 Kotlin 恢复预览与安全过滤规划器：
 * 1. 比对备份中的应用与当前设备已安装应用；
 * 2. 识别设备未安装的应用（不强行恢复其权限规则，但保留历史流量统计导入能力）；
 * 3. 核心安全防线：若设备已安装同名应用但 SHA-256 签名指纹与备份不一致，
 *    明确列入 signatureMismatchApps 并在恢复时拒绝继承其旧联网白名单与分组归属！
 */
object BackupRestorePlanner {

    fun buildPreview(
        payload: BackupPayload,
        currentApps: List<AppEntity>
    ): BackupRestorePreview {
        val installedByPkg = currentApps
            .filter { !it.isUninstalled }
            .associateBy { it.packageName }

        val matched = mutableListOf<BackupAppRule>()
        val uninstalled = mutableListOf<BackupAppRule>()
        val mismatches = mutableListOf<SignatureMismatchEntry>()

        for (backupApp in payload.appRules) {
            val deviceApp = installedByPkg[backupApp.packageName]
            if (deviceApp == null) {
                uninstalled.add(backupApp)
                continue
            }

            val sigMismatch = backupApp.signatureSha256.isNotBlank() &&
                deviceApp.signatureSha256.isNotBlank() &&
                !backupApp.signatureSha256.equals(deviceApp.signatureSha256, ignoreCase = true)

            if (sigMismatch) {
                mismatches.add(
                    SignatureMismatchEntry(
                        packageName = backupApp.packageName,
                        appName = deviceApp.appName,
                        backupSignaturePrefix = backupApp.signatureSha256.take(12),
                        deviceSignaturePrefix = deviceApp.signatureSha256.take(12)
                    )
                )
            } else {
                matched.add(backupApp)
            }
        }

        return BackupRestorePreview(
            payload = payload,
            matchedInstalledApps = matched,
            uninstalledBackupApps = uninstalled,
            signatureMismatchApps = mismatches
        )
    }
}
