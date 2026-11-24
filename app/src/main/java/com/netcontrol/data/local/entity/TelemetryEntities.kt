package com.netcontrol.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "firewall_audit_logs",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["packageName"])
    ]
)
data class FirewallAuditLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val timestamp: Long,
    val packageName: String,
    val uid: Int,
    val blocked: Boolean,
    val reason: String,
    val networkProfileId: String = "",
    val backendType: String = ""
)

object TrafficNetworkBucket {
    const val WIFI = "WIFI"
    const val SIM_1 = "SIM_1"
    const val SIM_2 = "SIM_2"
    const val MOBILE_OTHER = "MOBILE_OTHER"
}

object TrafficBucketType {
    const val HOURLY = "HOURLY"
    const val DAILY = "DAILY"
    const val MONTHLY = "MONTHLY"
}

@Entity(
    tableName = "traffic_usage_buckets",
    primaryKeys = ["packageName", "networkBucket", "bucketType", "bucketStartMs"],
    indices = [Index(value = ["bucketType", "bucketStartMs"])]
)
data class TrafficUsageBucketEntity(
    val packageName: String,
    val networkBucket: String,
    val bucketType: String,
    val bucketStartMs: Long,
    val rxBytes: Long,
    val txBytes: Long
)
