package com.netcontrol.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.netcontrol.data.local.entity.AppGroupCrossRef
import com.netcontrol.data.local.entity.GroupEntity
import com.netcontrol.data.local.entity.GroupNetworkPolicyEntity
import com.netcontrol.data.local.entity.GroupWithApps
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {

    @Query("SELECT * FROM groups ORDER BY sortOrder ASC, groupId ASC")
    fun observeGroups(): Flow<List<GroupEntity>>

    @Transaction
    @Query("SELECT * FROM groups ORDER BY sortOrder ASC, groupId ASC")
    fun observeGroupsWithApps(): Flow<List<GroupWithApps>>

    @Query("SELECT * FROM groups ORDER BY sortOrder ASC, groupId ASC")
    suspend fun getAllGroupsOnce(): List<GroupEntity>

    @Query("SELECT COUNT(*) FROM groups")
    suspend fun countGroups(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: GroupEntity): Long

    @Upsert
    suspend fun upsertGroups(groups: List<GroupEntity>)

    @Delete
    suspend fun deleteGroup(group: GroupEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addAppToGroup(crossRef: AppGroupCrossRef)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addAppsToGroup(crossRefs: List<AppGroupCrossRef>)

    @Query("DELETE FROM app_group_cross_ref WHERE packageName = :packageName AND groupId = :groupId")
    suspend fun removeAppFromGroup(packageName: String, groupId: Long)

    @Query("DELETE FROM app_group_cross_ref WHERE packageName = :packageName")
    suspend fun removeAppFromAllGroups(packageName: String)

    @Query("DELETE FROM app_group_cross_ref WHERE packageName IN (:packageNames)")
    suspend fun removeAppsFromAllGroups(packageNames: List<String>)

    @Query("DELETE FROM app_group_cross_ref WHERE groupId = :groupId")
    suspend fun clearGroupMembers(groupId: Long)

    @Transaction
    suspend fun replaceGroupMembers(groupId: Long, packageNames: Collection<String>) {
        clearGroupMembers(groupId)
        if (packageNames.isNotEmpty()) {
            addAppsToGroup(packageNames.map { AppGroupCrossRef(it, groupId) })
        }
    }

    @Query("SELECT * FROM app_group_cross_ref")
    suspend fun getAllCrossRefsOnce(): List<AppGroupCrossRef>

    @Query("SELECT * FROM group_network_policies WHERE groupId = :groupId")
    fun observePoliciesForGroup(groupId: Long): Flow<List<GroupNetworkPolicyEntity>>

    @Query("SELECT * FROM group_network_policies")
    fun observeAllPolicies(): Flow<List<GroupNetworkPolicyEntity>>

    @Query("SELECT * FROM group_network_policies")
    suspend fun getAllPoliciesOnce(): List<GroupNetworkPolicyEntity>

    @Upsert
    suspend fun upsertPolicy(policy: GroupNetworkPolicyEntity)

    @Query("DELETE FROM group_network_policies WHERE groupId = :groupId AND profileScope = :profileScope")
    suspend fun deletePolicy(groupId: Long, profileScope: String)
}
