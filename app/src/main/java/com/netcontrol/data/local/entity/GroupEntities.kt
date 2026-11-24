package com.netcontrol.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey(autoGenerate = true)
    val groupId: Long = 0L,
    val name: String,
    val colorHex: String = "#0B57D0",
    val iconKey: String = "folder",
    val description: String = "",
    val sortOrder: Int = 0,
    val isEnabled: Boolean = true
)

@Entity(
    tableName = "app_group_cross_ref",
    primaryKeys = ["packageName", "groupId"],
    foreignKeys = [
        ForeignKey(
            entity = AppEntity::class,
            parentColumns = ["packageName"],
            childColumns = ["packageName"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["groupId"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["groupId"])]
)
data class AppGroupCrossRef(
    val packageName: String,
    val groupId: Long
)

object ProfileScope {
    const val ALL = "ALL"
    const val WIFI_ALL = "WIFI_ALL"
    const val MOBILE_ALL = "MOBILE_ALL"
}

@Entity(
    tableName = "group_network_policies",
    primaryKeys = ["groupId", "profileScope"],
    foreignKeys = [
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["groupId"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class GroupNetworkPolicyEntity(
    val groupId: Long,
    val profileScope: String,
    val blockByDefault: Boolean
)
