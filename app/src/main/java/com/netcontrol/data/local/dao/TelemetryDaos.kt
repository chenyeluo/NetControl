package com.netcontrol.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.netcontrol.data.local.entity.FirewallAuditLogEntity
import com.netcontrol.data.local.entity.TrafficUsageBucketEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AuditLogDao {

    @Query("SELECT * FROM firewall_audit_logs ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecentLogs(limit: Int = 300): Flow<List<FirewallAuditLogEntity>>

    @Query("SELECT * FROM firewall_audit_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentLogsOnce(limit: Int = 1000): List<FirewallAuditLogEntity>

    @Query("SELECT * FROM firewall_audit_logs WHERE packageName = :packageName ORDER BY timestamp DESC LIMIT :limit")
    fun observeLogsForPackage(packageName: String, limit: Int = 100): Flow<List<FirewallAuditLogEntity>>

    @Insert
    suspend fun insert(log: FirewallAuditLogEntity)

    @Insert
    suspend fun insertAll(logs: List<FirewallAuditLogEntity>)

    @Query("DELETE FROM firewall_audit_logs WHERE timestamp < :cutoffEpochMs")
    suspend fun deleteOlderThan(cutoffEpochMs: Long): Int
}

@Dao
interface TrafficUsageDao {

    @Query(
        """
        SELECT * FROM traffic_usage_buckets
        WHERE bucketType = :bucketType AND bucketStartMs >= :fromEpochMs
        ORDER BY bucketStartMs DESC
        """
    )
    fun observeBuckets(bucketType: String, fromEpochMs: Long): Flow<List<TrafficUsageBucketEntity>>

    @Query(
        """
        SELECT * FROM traffic_usage_buckets
        WHERE packageName = :packageName AND bucketType = :bucketType AND bucketStartMs >= :fromEpochMs
        ORDER BY bucketStartMs DESC
        """
    )
    fun observeBucketsForPackage(
        packageName: String,
        bucketType: String,
        fromEpochMs: Long
    ): Flow<List<TrafficUsageBucketEntity>>

    @Upsert
    suspend fun upsertAll(buckets: List<TrafficUsageBucketEntity>)

    @Query("SELECT * FROM traffic_usage_buckets")
    suspend fun getAllBucketsOnce(): List<TrafficUsageBucketEntity>

    @Query("DELETE FROM traffic_usage_buckets")
    suspend fun deleteAll()
}
