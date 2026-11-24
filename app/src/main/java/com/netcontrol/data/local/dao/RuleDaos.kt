package com.netcontrol.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.netcontrol.data.local.entity.SchedulePresetEntity
import com.netcontrol.data.local.entity.ScheduleRuleEntity
import com.netcontrol.data.local.entity.TemporaryRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleRuleDao {

    @Query("SELECT * FROM schedule_rules ORDER BY groupId ASC, startMinute ASC")
    fun observeAllRules(): Flow<List<ScheduleRuleEntity>>

    @Query("SELECT * FROM schedule_rules WHERE groupId = :groupId ORDER BY startMinute ASC")
    fun observeRulesForGroup(groupId: Long): Flow<List<ScheduleRuleEntity>>

    @Query("SELECT * FROM schedule_rules WHERE isEnabled = 1")
    suspend fun getEnabledRulesOnce(): List<ScheduleRuleEntity>

    @Upsert
    suspend fun upsertRule(rule: ScheduleRuleEntity): Long

    @Query("DELETE FROM schedule_rules WHERE ruleId = :ruleId")
    suspend fun deleteRule(ruleId: Long)

    @Query("UPDATE schedule_rules SET isEnabled = :enabled WHERE ruleId = :ruleId")
    suspend fun setRuleEnabled(ruleId: Long, enabled: Boolean)
}

@Dao
interface SchedulePresetDao {

    @Query("SELECT * FROM schedule_presets ORDER BY sortOrder ASC, presetId ASC")
    fun observeAllPresets(): Flow<List<SchedulePresetEntity>>

    @Query("SELECT COUNT(*) FROM schedule_presets")
    suspend fun countPresets(): Int

    @Upsert
    suspend fun upsertPreset(preset: SchedulePresetEntity): Long

    @Upsert
    suspend fun upsertPresets(presets: List<SchedulePresetEntity>)

    @Query("DELETE FROM schedule_presets WHERE presetId = :presetId")
    suspend fun deletePreset(presetId: Long)
}

@Dao
interface TemporaryRuleDao {

    @Query("SELECT * FROM temporary_rules WHERE expiresAtEpochMs > :nowEpochMs ORDER BY expiresAtEpochMs ASC")
    fun observeActiveRules(nowEpochMs: Long): Flow<List<TemporaryRuleEntity>>

    @Query("SELECT * FROM temporary_rules WHERE expiresAtEpochMs > :nowEpochMs ORDER BY expiresAtEpochMs ASC")
    suspend fun getActiveRulesOnce(nowEpochMs: Long): List<TemporaryRuleEntity>

    @Query("SELECT MIN(expiresAtEpochMs) FROM temporary_rules WHERE expiresAtEpochMs > :nowEpochMs")
    suspend fun getNextExpirationAfter(nowEpochMs: Long): Long?

    @Upsert
    suspend fun upsertRule(rule: TemporaryRuleEntity): Long

    @Query("DELETE FROM temporary_rules WHERE tempId = :tempId")
    suspend fun deleteRule(tempId: Long)

    @Query("DELETE FROM temporary_rules WHERE targetType = :targetType AND (:packageName IS NULL OR packageName = :packageName)")
    suspend fun clearRulesForTarget(targetType: String, packageName: String?)

    @Query("DELETE FROM temporary_rules WHERE expiresAtEpochMs <= :nowEpochMs")
    suspend fun deleteExpiredRules(nowEpochMs: Long): Int
}
