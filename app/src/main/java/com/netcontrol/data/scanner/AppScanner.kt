package com.netcontrol.data.scanner

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 异步全量/增量应用扫描器：读取全部已安装用户应用与系统应用，
 * 提取 UID、桌面启动入口 Activity 与 SHA-256 签名指纹。
 */
class AppScanner(private val context: Context) {

    private val pm: PackageManager = context.packageManager

    suspend fun scanAllInstalledApps(): List<ScannedAppInfo> = withContext(Dispatchers.IO) {
        val packages = getInstalledPackagesWithSignatures()
        packages.mapNotNull { pkgInfo ->
            toScannedAppInfo(pkgInfo)
        }
    }

    suspend fun scanSinglePackage(packageName: String): ScannedAppInfo? = withContext(Dispatchers.IO) {
        val pkgInfo = getPackageInfoWithSignatures(packageName) ?: return@withContext null
        toScannedAppInfo(pkgInfo)
    }

    private fun toScannedAppInfo(pkgInfo: PackageInfo): ScannedAppInfo? {
        val appInfo = pkgInfo.applicationInfo ?: return null
        val pkgName = pkgInfo.packageName
        val label = try {
            pm.getApplicationLabel(appInfo).toString().ifBlank { pkgName }
        } catch (_: Throwable) {
            pkgName
        }
        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

        val launcherActivity = try {
            pm.getLaunchIntentForPackage(pkgName)?.component?.className
        } catch (_: Throwable) {
            null
        }

        val signatureSha256 = try {
            SignatureUtils.extractPackageSignatureSha256(pkgInfo)
        } catch (_: Throwable) {
            ""
        }

        return ScannedAppInfo(
            packageName = pkgName,
            uid = appInfo.uid,
            appName = label,
            isSystemApp = isSystem,
            launcherActivity = launcherActivity,
            signatureSha256 = signatureSha256,
            installedAt = pkgInfo.firstInstallTime
        )
    }

    @Suppress("DEPRECATION")
    private fun getInstalledPackagesWithSignatures(): List<PackageInfo> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
                )
            } else {
                pm.getInstalledPackages(PackageManager.GET_SIGNING_CERTIFICATES)
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    @Suppress("DEPRECATION")
    private fun getPackageInfoWithSignatures(packageName: String): PackageInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
                )
            } else {
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: Throwable) {
            null
        }
    }
}
