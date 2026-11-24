package com.netcontrol.data.stats

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.net.NetworkCapabilities
import android.os.Process
import android.provider.Settings
import com.netcontrol.data.local.AppDatabase
import com.netcontrol.data.local.entity.TrafficBucketType
import com.netcontrol.data.local.entity.TrafficNetworkBucket
import com.netcontrol.data.local.entity.TrafficUsageBucketEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

data class TrafficSyncStatus(
    val lastSyncedAtMs: Long = 0L,
    val bucketsWritten: Int = 0,
    val message: String = "尚未同步"
)

/**
 * 零耗电系统原生流量采集器：
 * 通过 Android NetworkStatsManager 直接读取内核级按 UID 统计的 Wi-Fi 与移动数据流量，
 * 按小时（近 24 小时）、按日（近 30 天）、按月（当月）批量落盘至 Room，完全不接管或转发任何用户态数据包。
 */
class TrafficStatsCollector(
    private val context: Context,
    private val database: AppDatabase
) {
    private val networkStatsManager = context.getSystemService(NetworkStatsManager::class.java)

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncStatus = MutableStateFlow(TrafficSyncStatus())
    val syncStatus: StateFlow<TrafficSyncStatus> = _syncStatus.asStateFlow()

    @Suppress("DEPRECATION")
    fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun buildUsageAccessSettingsIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun syncTrafficStats(): TrafficSyncStatus = withContext(Dispatchers.IO) {
        if (!hasUsageStatsPermission()) {
            val status = TrafficSyncStatus(message = "尚未授予「使用情况访问权限」，请先前往系统设置开启")
            _syncStatus.value = status
            return@withContext status
        }
        val nsm = networkStatsManager ?: run {
            val status = TrafficSyncStatus(message = "当前设备不支持 NetworkStatsManager")
            _syncStatus.value = status
            return@withContext status
        }

        _isSyncing.value = true
        try {
            val allApps = database.appDao().getAllAppsOnce()
            // 为每个 UID 建立稳定主包名映射（共享 UID 取非系统优先或字典序首个，避免同一 UID 流量被重复计算）
            val uidToPrimaryPackage = allApps
                .groupBy { it.uid }
                .mapValues { (_, list) ->
                    list.sortedWith(compareBy<com.netcontrol.data.local.entity.AppEntity> { it.isSystemApp }.thenBy { it.packageName })
                        .first()
                        .packageName
                }

            val now = ZonedDateTime.now()
            val bucketsToUpsert = mutableListOf<TrafficUsageBucketEntity>()

            // 1. 最近 24 小时按小时采集
            val currentHourStart = now.truncatedTo(ChronoUnit.HOURS)
            for (h in 0L until 24L) {
                val start = currentHourStart.minusHours(h)
                val end = if (h == 0L) now else start.plusHours(1)
                collectWindow(
                    nsm = nsm,
                    startMs = start.toInstant().toEpochMilli(),
                    endMs = end.toInstant().toEpochMilli(),
                    bucketType = TrafficBucketType.HOURLY,
                    uidToPkg = uidToPrimaryPackage,
                    out = bucketsToUpsert
                )
            }

            // 2. 最近 14 天按天采集（日常轻量快速同步，历史按天数据长期留存）
            val todayStart = now.toLocalDate().atStartOfDay(now.zone)
            for (d in 0L until 14L) {
                val start = todayStart.minusDays(d)
                val end = if (d == 0L) now else start.plusDays(1)
                collectWindow(
                    nsm = nsm,
                    startMs = start.toInstant().toEpochMilli(),
                    endMs = end.toInstant().toEpochMilli(),
                    bucketType = TrafficBucketType.DAILY,
                    uidToPkg = uidToPrimaryPackage,
                    out = bucketsToUpsert
                )
            }

            // 3. 本月汇总采集
            val monthStart = now.toLocalDate().withDayOfMonth(1).atStartOfDay(now.zone)
            collectWindow(
                nsm = nsm,
                startMs = monthStart.toInstant().toEpochMilli(),
                endMs = now.toInstant().toEpochMilli(),
                bucketType = TrafficBucketType.MONTHLY,
                uidToPkg = uidToPrimaryPackage,
                out = bucketsToUpsert
            )

            if (bucketsToUpsert.isNotEmpty()) {
                database.trafficUsageDao().upsertAll(bucketsToUpsert)
            }

            val status = TrafficSyncStatus(
                lastSyncedAtMs = System.currentTimeMillis(),
                bucketsWritten = bucketsToUpsert.size,
                message = "同步完成：更新 ${bucketsToUpsert.size} 条系统流量分桶记录"
            )
            _syncStatus.value = status
            status
        } catch (t: Throwable) {
            val status = TrafficSyncStatus(message = "采集失败：${t.message}")
            _syncStatus.value = status
            status
        } finally {
            _isSyncing.value = false
        }
    }

    @Suppress("DEPRECATION")
    private fun collectWindow(
        nsm: NetworkStatsManager,
        startMs: Long,
        endMs: Long,
        bucketType: String,
        uidToPkg: Map<Int, String>,
        out: MutableList<TrafficUsageBucketEntity>
    ) {
        if (endMs <= startMs) return
        val transports = listOf(
            NetworkCapabilities.TRANSPORT_WIFI to TrafficNetworkBucket.WIFI,
            NetworkCapabilities.TRANSPORT_CELLULAR to TrafficNetworkBucket.MOBILE_OTHER
        )
        for ((transport, netBucket) in transports) {
            val perUid = HashMap<Int, Pair<Long, Long>>()
            try {
                // Android 10+ 传入 subscriberId = null 可无需特权 IMSI 权限即可稳定读取汇总流量
                nsm.querySummary(transport, null, startMs, endMs).use { stats ->
                    val bucket = NetworkStats.Bucket()
                    while (stats.hasNextBucket()) {
                        stats.getNextBucket(bucket)
                        val uid = bucket.uid
                        if (uid < 0) continue
                        val prev = perUid[uid] ?: (0L to 0L)
                        perUid[uid] = (prev.first + bucket.rxBytes) to (prev.second + bucket.txBytes)
                    }
                }
            } catch (_: Throwable) {
                continue
            }

            for ((uid, pair) in perUid) {
                if (pair.first + pair.second <= 0L) continue
                val pkg = uidToPkg[uid] ?: "uid:$uid"
                out.add(
                    TrafficUsageBucketEntity(
                        packageName = pkg,
                        networkBucket = netBucket,
                        bucketType = bucketType,
                        bucketStartMs = startMs,
                        rxBytes = pair.first,
                        txBytes = pair.second
                    )
                )
            }
        }
    }
}
