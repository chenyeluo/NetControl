package com.netcontrol.data.local.entity

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

data class AppWithGroups(
    @Embedded val app: AppEntity,
    @Relation(
        parentColumn = "packageName",
        entityColumn = "groupId",
        associateBy = Junction(AppGroupCrossRef::class)
    )
    val groups: List<GroupEntity>
)

data class GroupWithApps(
    @Embedded val group: GroupEntity,
    @Relation(
        parentColumn = "groupId",
        entityColumn = "packageName",
        associateBy = Junction(AppGroupCrossRef::class)
    )
    val apps: List<AppEntity>
)

data class NetworkProfileWithBssids(
    @Embedded val profile: NetworkProfileEntity,
    @Relation(
        parentColumn = "profileId",
        entityColumn = "profileId"
    )
    val bssids: List<WifiBssidMappingEntity>
)
