package com.netcontrol.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.netcontrol.data.local.entity.AppEntity
import com.netcontrol.data.local.entity.AppWithGroups
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {

    @Query("SELECT * FROM apps WHERE isUninstalled = 0 ORDER BY appName COLLATE NOCASE ASC")
    fun observeInstalledApps(): Flow<List<AppEntity>>

    @Query("SELECT * FROM apps ORDER BY isUninstalled ASC, appName COLLATE NOCASE ASC")
    fun observeAllApps(): Flow<List<AppEntity>>

    @Transaction
    @Query("SELECT * FROM apps WHERE isUninstalled = 0 ORDER BY appName COLLATE NOCASE ASC")
    fun observeInstalledAppsWithGroups(): Flow<List<AppWithGroups>>

    @Query("SELECT * FROM apps WHERE signatureChanged = 1 AND isUninstalled = 0")
    fun observeSignatureAlertApps(): Flow<List<AppEntity>>

    @Query("SELECT * FROM apps")
    suspend fun getAllAppsOnce(): List<AppEntity>

    @Query("SELECT * FROM apps WHERE isUninstalled = 0")
    suspend fun getInstalledAppsOnce(): List<AppEntity>

    @Query("SELECT * FROM apps WHERE packageName = :packageName LIMIT 1")
    suspend fun getByPackage(packageName: String): AppEntity?

    @Query("SELECT packageName FROM apps WHERE uid = :uid AND isUninstalled = 0")
    suspend fun getInstalledPackagesByUid(uid: Int): List<String>

    @Upsert
    suspend fun upsertAll(apps: List<AppEntity>)

    @Upsert
    suspend fun upsert(app: AppEntity)

    @Query("UPDATE apps SET isUninstalled = 1, updatedAt = :updatedAt WHERE packageName = :packageName")
    suspend fun markUninstalled(packageName: String, updatedAt: Long)

    @Query("UPDATE apps SET manualOverride = :overrideMode, updatedAt = :updatedAt WHERE packageName = :packageName")
    suspend fun updateManualOverride(packageName: String, overrideMode: Int, updatedAt: Long)

    @Query(
        """
        UPDATE apps
        SET splashBlockEnabled = :enabled,
            splashDurationMs = :durationMs,
            splashCooldownSec = :cooldownSec,
            splashRelaxActivityCheck = :relaxActivityCheck,
            updatedAt = :updatedAt
        WHERE packageName = :packageName
        """
    )
    suspend fun updateSplashConfig(
        packageName: String,
        enabled: Boolean,
        durationMs: Int,
        cooldownSec: Int,
        relaxActivityCheck: Boolean,
        updatedAt: Long
    )

    @Query("UPDATE apps SET signatureChanged = 0, updatedAt = :updatedAt WHERE packageName = :packageName")
    suspend fun acknowledgeSignatureChange(packageName: String, updatedAt: Long)
}
