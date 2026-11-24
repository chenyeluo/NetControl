package com.netcontrol.data.stats

import com.netcontrol.data.local.entity.AppEntity
import com.netcontrol.data.local.entity.GroupWithApps
import com.netcontrol.data.local.entity.TrafficNetworkBucket
import com.netcontrol.data.local.entity.TrafficUsageBucketEntity
import java.util.Locale

data class AppTrafficRankItem(
    val packageName: String,
    val appName: String,
    val isUninstalled: Boolean,
    val wifiBytes: Long,
    val mobileBytes: Long,
    val rxBytes: Long,
    val txBytes: Long
) {
    val totalBytes: Long get() = rxBytes + txBytes
}

data class GroupTrafficRankItem(
    val groupId: Long,
    val groupName: String,
    val colorHex: String,
    val iconKey: String,
    val appCount: Int,
    val totalBytes: Long,
    val wifiBytes: Long,
    val mobileBytes: Long
)

data class TimeSeriesBarPoint(
    val bucketStartMs: Long,
    val wifiBytes: Long,
    val mobileBytes: Long
) {
    val totalBytes: Long get() = wifiBytes + mobileBytes
}

data class TrafficSummarySnapshot(
    val totalRxBytes: Long,
    val totalTxBytes: Long,
    val wifiBytes: Long,
    val mobileBytes: Long,
    val appRanking: List<AppTrafficRankItem>,
    val groupRanking: List<GroupTrafficRankItem>,
    val timeSeries: List<TimeSeriesBarPoint>
) {
    val totalBytes: Long get() = totalRxBytes + totalTxBytes
}

/**
 * 纯 Kotlin 流量汇总与分组归因计算器（100% 可 JVM 单元测试）。
 */
object TrafficStatsAggregator {

    fun aggregate(
        buckets: List<TrafficUsageBucketEntity>,
        allApps: List<AppEntity>,
        groupsWithApps: List<GroupWithApps>
    ): TrafficSummarySnapshot {
        val appByPkg = allApps.associateBy { it.packageName }

        var totalRx = 0L
        var totalTx = 0L
        var totalWifi = 0L
        var totalMobile = 0L

        data class MutableAcc(
            var wifi: Long = 0L,
            var mobile: Long = 0L,
            var rx: Long = 0L,
            var tx: Long = 0L
        )

        val byPkg = LinkedHashMap<String, MutableAcc>()
        val byTime = LinkedHashMap<Long, Pair<Long, Long>>() // bucketStartMs -> (wifi, mobile)

        for (b in buckets) {
            val rx = b.rxBytes.coerceAtLeast(0L)
            val tx = b.txBytes.coerceAtLeast(0L)
            val sum = rx + tx
            if (sum == 0L) continue

            totalRx += rx
            totalTx += tx
            val isWifi = b.networkBucket == TrafficNetworkBucket.WIFI
            if (isWifi) totalWifi += sum else totalMobile += sum

            val acc = byPkg.getOrPut(b.packageName) { MutableAcc() }
            acc.rx += rx
            acc.tx += tx
            if (isWifi) acc.wifi += sum else acc.mobile += sum

            val curTime = byTime[b.bucketStartMs] ?: (0L to 0L)
            byTime[b.bucketStartMs] = if (isWifi) {
                (curTime.first + sum) to curTime.second
            } else {
                curTime.first to (curTime.second + sum)
            }
        }

        val appRanking = byPkg.entries
            .map { (pkg, acc) ->
                val entity = appByPkg[pkg]
                AppTrafficRankItem(
                    packageName = pkg,
                    appName = entity?.appName ?: pkg,
                    isUninstalled = entity?.isUninstalled ?: false,
                    wifiBytes = acc.wifi,
                    mobileBytes = acc.mobile,
                    rxBytes = acc.rx,
                    txBytes = acc.tx
                )
            }
            .sortedByDescending { it.totalBytes }

        val appAccByPkg = appRanking.associateBy { it.packageName }

        val groupRanking = groupsWithApps
            .map { gwa ->
                var gWifi = 0L
                var gMobile = 0L
                var gTotal = 0L
                for (member in gwa.apps) {
                    val stat = appAccByPkg[member.packageName] ?: continue
                    gWifi += stat.wifiBytes
                    gMobile += stat.mobileBytes
                    gTotal += stat.totalBytes
                }
                GroupTrafficRankItem(
                    groupId = gwa.group.groupId,
                    groupName = gwa.group.name,
                    colorHex = gwa.group.colorHex,
                    iconKey = gwa.group.iconKey,
                    appCount = gwa.apps.count { !it.isUninstalled },
                    totalBytes = gTotal,
                    wifiBytes = gWifi,
                    mobileBytes = gMobile
                )
            }
            .filter { it.totalBytes > 0L }
            .sortedByDescending { it.totalBytes }

        val timeSeries = byTime.entries
            .sortedBy { it.key }
            .map { (ts, pair) -> TimeSeriesBarPoint(ts, pair.first, pair.second) }

        return TrafficSummarySnapshot(
            totalRxBytes = totalRx,
            totalTxBytes = totalTx,
            wifiBytes = totalWifi,
            mobileBytes = totalMobile,
            appRanking = appRanking,
            groupRanking = groupRanking,
            timeSeries = timeSeries
        )
    }

    fun formatBytes(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        return when {
            safe < 1024L -> "$safe B"
            safe < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", safe / 1024.0)
            safe < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f MB", safe / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB", safe / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
