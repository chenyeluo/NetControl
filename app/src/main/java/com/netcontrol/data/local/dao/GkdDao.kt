package com.netcontrol.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.netcontrol.data.local.entity.GkdActionLogEntity
import com.netcontrol.data.local.entity.GkdGroupConfigEntity
import com.netcontrol.data.local.entity.GkdSubscriptionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GkdDao {

    @Query("SELECT * FROM gkd_subscriptions ORDER BY subId ASC")
    fun observeSubscriptions(): Flow<List<GkdSubscriptionEntity>>

    @Query("SELECT * FROM gkd_subscriptions")
    suspend fun getAllSubscriptionsOnce(): List<GkdSubscriptionEntity>

    @Query("SELECT COUNT(*) FROM gkd_subscriptions")
    suspend fun countSubscriptions(): Int

    @Upsert
    suspend fun upsertSubscription(entity: GkdSubscriptionEntity)

    @Query("UPDATE gkd_subscriptions SET isEnabled = :enabled WHERE subId = :subId")
    suspend fun setSubscriptionEnabled(subId: Long, enabled: Boolean)

    @Query("DELETE FROM gkd_subscriptions WHERE subId = :subId")
    suspend fun deleteSubscription(subId: Long)

    @Query("SELECT * FROM gkd_group_configs")
    fun observeGroupConfigs(): Flow<List<GkdGroupConfigEntity>>

    @Query("SELECT * FROM gkd_group_configs")
    suspend fun getAllGroupConfigsOnce(): List<GkdGroupConfigEntity>

    @Upsert
    suspend fun upsertGroupConfig(config: GkdGroupConfigEntity)

    @Query("DELETE FROM gkd_group_configs WHERE subId = :subId")
    suspend fun deleteGroupConfigsForSub(subId: Long)

    @Query("SELECT * FROM gkd_action_logs ORDER BY timestamp DESC LIMIT :limit")
    fun observeActionLogs(limit: Int = 200): Flow<List<GkdActionLogEntity>>

    @Insert
    suspend fun insertActionLog(log: GkdActionLogEntity)

    @Query("DELETE FROM gkd_action_logs WHERE timestamp < :cutoffMs")
    suspend fun deleteOldActionLogs(cutoffMs: Long): Int

    @Query("DELETE FROM gkd_action_logs")
    suspend fun clearAllActionLogs()
}
