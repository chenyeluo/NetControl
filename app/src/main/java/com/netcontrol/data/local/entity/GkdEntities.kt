package com.netcontrol.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "gkd_subscriptions")
data class GkdSubscriptionEntity(
    @PrimaryKey
    val subId: Long,
    val name: String,
    val version: Int = 0,
    val author: String = "",
    val updateUrl: String? = null,
    val rawJson5: String,
    val isEnabled: Boolean = true,
    val appCount: Int = 0,
    val globalGroupCount: Int = 0,
    val ruleCount: Int = 0,
    val updatedAtMs: Long = 0L
)

@Entity(
    tableName = "gkd_group_configs",
    primaryKeys = ["subId", "appId", "groupKey"]
)
data class GkdGroupConfigEntity(
    val subId: Long,
    val appId: String,
    val groupKey: Int,
    val enabled: Boolean
)

@Entity(
    tableName = "gkd_action_logs",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["packageName"])
    ]
)
data class GkdActionLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val timestamp: Long,
    val packageName: String,
    val activityId: String,
    val subName: String,
    val groupName: String,
    val ruleKey: Int,
    val action: String,
    val matchedText: String = "",
    val costMs: Long = 0L
)
