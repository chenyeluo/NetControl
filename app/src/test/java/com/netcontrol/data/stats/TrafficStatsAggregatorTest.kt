package com.netcontrol.data.stats

import com.netcontrol.data.local.entity.AppEntity
import com.netcontrol.data.local.entity.GroupEntity
import com.netcontrol.data.local.entity.GroupWithApps
import com.netcontrol.data.local.entity.TrafficBucketType
import com.netcontrol.data.local.entity.TrafficNetworkBucket
import com.netcontrol.data.local.entity.TrafficUsageBucketEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficStatsAggregatorTest {

    @Test
    fun aggregate_computesAppGroupAndTimeSeriesBreakdownCorrectly() {
        val bili = AppEntity("tv.danmaku.bili", 10250, "Bilibili", false)
        val oldApp = AppEntity("com.old.game", 10251, "OldGame", false, isUninstalled = true)
        val videoGroup = GroupWithApps(
            group = GroupEntity(groupId = 1, name = "影音", colorHex = "#C2185B", iconKey = "video"),
            apps = listOf(bili)
        )

        val buckets = listOf(
            TrafficUsageBucketEntity("tv.danmaku.bili", TrafficNetworkBucket.WIFI, TrafficBucketType.DAILY, 1000L, rxBytes = 5_000_000L, txBytes = 1_000_000L),
            TrafficUsageBucketEntity("tv.danmaku.bili", TrafficNetworkBucket.MOBILE_OTHER, TrafficBucketType.DAILY, 2000L, rxBytes = 2_000_000L, txBytes = 500_000L),
            TrafficUsageBucketEntity("com.old.game", TrafficNetworkBucket.WIFI, TrafficBucketType.DAILY, 1000L, rxBytes = 1_000_000L, txBytes = 200_000L)
        )

        val summary = TrafficStatsAggregator.aggregate(buckets, listOf(bili, oldApp), listOf(videoGroup))

        assertEquals(8_000_000L, summary.totalRxBytes)
        assertEquals(1_700_000L, summary.totalTxBytes)
        assertEquals(7_200_000L, summary.wifiBytes)
        assertEquals(2_500_000L, summary.mobileBytes)

        assertEquals(2, summary.appRanking.size)
        assertEquals("tv.danmaku.bili", summary.appRanking[0].packageName)
        assertEquals(8_500_000L, summary.appRanking[0].totalBytes)
        assertTrue(summary.appRanking[1].isUninstalled)

        assertEquals(1, summary.groupRanking.size)
        assertEquals("影音", summary.groupRanking.single().groupName)
        assertEquals(8_500_000L, summary.groupRanking.single().totalBytes)

        assertEquals(2, summary.timeSeries.size)
        assertEquals(1000L, summary.timeSeries[0].bucketStartMs)
        assertEquals(7_200_000L, summary.timeSeries[0].wifiBytes)
    }

    @Test
    fun formatBytes_formatsUnitsCleanly() {
        assertEquals("512 B", TrafficStatsAggregator.formatBytes(512))
        assertEquals("1.5 KB", TrafficStatsAggregator.formatBytes(1536))
        assertEquals("2.00 MB", TrafficStatsAggregator.formatBytes(2L * 1024 * 1024))
        assertEquals("1.50 GB", TrafficStatsAggregator.formatBytes(1536L * 1024 * 1024))
    }
}
