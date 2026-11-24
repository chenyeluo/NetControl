package com.netcontrol.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.netcontrol.data.local.entity.NetworkProfileEntity
import com.netcontrol.data.local.entity.NetworkProfileWithBssids
import com.netcontrol.data.local.entity.WifiBssidMappingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NetworkProfileDao {

    @Transaction
    @Query("SELECT * FROM network_profiles ORDER BY isSystemBuiltin DESC, name COLLATE NOCASE ASC")
    fun observeProfilesWithBssids(): Flow<List<NetworkProfileWithBssids>>

    @Query("SELECT * FROM network_profiles")
    suspend fun getAllProfilesOnce(): List<NetworkProfileEntity>

    @Query("SELECT * FROM network_profiles WHERE profileId = :profileId LIMIT 1")
    suspend fun getProfileById(profileId: String): NetworkProfileEntity?

    @Upsert
    suspend fun upsertProfile(profile: NetworkProfileEntity)

    @Upsert
    suspend fun upsertProfiles(profiles: List<NetworkProfileEntity>)

    @Query("DELETE FROM network_profiles WHERE profileId = :profileId AND isSystemBuiltin = 0")
    suspend fun deleteCustomProfile(profileId: String)

    @Upsert
    suspend fun upsertBssidMapping(mapping: WifiBssidMappingEntity)

    @Query("DELETE FROM wifi_bssid_mappings WHERE bssid = :bssid")
    suspend fun deleteBssidMapping(bssid: String)

    @Query("SELECT profileId FROM wifi_bssid_mappings WHERE LOWER(bssid) = LOWER(:bssid) LIMIT 1")
    suspend fun findProfileIdByBssid(bssid: String): String?

    @Query("SELECT * FROM wifi_bssid_mappings")
    suspend fun getAllBssidMappingsOnce(): List<WifiBssidMappingEntity>
}
