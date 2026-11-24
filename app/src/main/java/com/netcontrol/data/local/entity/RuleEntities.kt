package com.netcontrol.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "schedule_rules",
    foreignKeys = [
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["groupId"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["groupId"])]
)
data class ScheduleRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val ruleId: Long = 0L,
    val groupId: Long,
    val profileScope: String = ProfileScope.ALL,
    /** 距离当日 00:00 的分钟数 [0..1439]；startMinute > endMinute 表示跨午夜区间 */
    val startMinute: Int,
    val endMinute: Int,
    /** 7 位星期掩码：bit 0 = 周一 .. bit 6 = 周日，默认 0b1111111 = 127 */
    val daysMask: Int = 0b1111111,
    val isEnabled: Boolean = true
)

@Entity(tableName = "schedule_presets")
data class SchedulePresetEntity(
    @PrimaryKey(autoGenerate = true)
    val presetId: Long = 0L,
    val name: String,
    /** 距离当日 00:00 的分钟数 [0..1439]；startMinute > endMinute 表示跨午夜区间 */
    val startMinute: Int,
    val endMinute: Int,
    /** 7 位星期掩码：bit 0 = 周一 .. bit 6 = 周日，默认 0b1111111 = 127 */
    val daysMask: Int = 0b1111111,
    val profileScope: String = ProfileScope.ALL,
    val sortOrder: Int = 0
)

object TempTargetType {
    const val GLOBAL = "GLOBAL"
    const val APP = "APP"
}

object TempAction {
    const val ALLOW = "ALLOW"
    const val BLOCK = "BLOCK"
}

@Entity(
    tableName = "temporary_rules",
    indices = [
        Index(value = ["expiresAtEpochMs"]),
        Index(value = ["packageName"])
    ]
)
data class TemporaryRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val tempId: Long = 0L,
    val targetType: String,
    val packageName: String? = null,
    val action: String,
    val profileScope: String = ProfileScope.ALL,
    val expiresAtEpochMs: Long,
    val createdAt: Long
)
