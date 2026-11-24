package com.netcontrol.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object ManualOverride {
    const val FOLLOW = 0
    const val FORCE_BLOCK = 1
    const val FORCE_ALLOW = 2
}

@Entity(
    tableName = "apps",
    indices = [
        Index(value = ["uid"]),
        Index(value = ["isUninstalled"])
    ]
)
data class AppEntity(
    @PrimaryKey
    val packageName: String,
    val uid: Int,
    val appName: String,
    val isSystemApp: Boolean,
    val isSharedUid: Boolean = false,
    val signatureSha256: String = "",
    val signatureChanged: Boolean = false,
    val isUninstalled: Boolean = false,
    val launcherActivity: String? = null,
    val manualOverride: Int = ManualOverride.FOLLOW,
    val splashBlockEnabled: Boolean = false,
    val splashDurationMs: Int = 3000,
    val splashCooldownSec: Int = 60,
    val splashRelaxActivityCheck: Boolean = false,
    val installedAt: Long = 0L,
    val updatedAt: Long = 0L
)
