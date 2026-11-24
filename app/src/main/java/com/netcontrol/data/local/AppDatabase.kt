package com.netcontrol.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.netcontrol.data.local.dao.AppDao
import com.netcontrol.data.local.dao.AuditLogDao
import com.netcontrol.data.local.dao.GkdDao
import com.netcontrol.data.local.dao.GroupDao
import com.netcontrol.data.local.dao.NetworkProfileDao
import com.netcontrol.data.local.dao.SchedulePresetDao
import com.netcontrol.data.local.dao.ScheduleRuleDao
import com.netcontrol.data.local.dao.TemporaryRuleDao
import com.netcontrol.data.local.dao.TrafficUsageDao
import com.netcontrol.data.local.entity.AppEntity
import com.netcontrol.data.local.entity.AppGroupCrossRef
import com.netcontrol.data.local.entity.BuiltinProfileId
import com.netcontrol.data.local.entity.FirewallAuditLogEntity
import com.netcontrol.data.local.entity.GkdActionLogEntity
import com.netcontrol.data.local.entity.GkdGroupConfigEntity
import com.netcontrol.data.local.entity.GkdSubscriptionEntity
import com.netcontrol.data.local.entity.GroupEntity
import com.netcontrol.data.local.entity.GroupNetworkPolicyEntity
import com.netcontrol.data.local.entity.NetworkProfileEntity
import com.netcontrol.data.local.entity.NetworkProfileType
import com.netcontrol.data.local.entity.ProfileScope
import com.netcontrol.data.local.entity.SchedulePresetEntity
import com.netcontrol.data.local.entity.ScheduleRuleEntity
import com.netcontrol.data.local.entity.TemporaryRuleEntity
import com.netcontrol.data.local.entity.TrafficUsageBucketEntity
import com.netcontrol.data.local.entity.WifiBssidMappingEntity

@Database(
    entities = [
        AppEntity::class,
        GroupEntity::class,
        AppGroupCrossRef::class,
        NetworkProfileEntity::class,
        WifiBssidMappingEntity::class,
        GroupNetworkPolicyEntity::class,
        ScheduleRuleEntity::class,
        SchedulePresetEntity::class,
        TemporaryRuleEntity::class,
        FirewallAuditLogEntity::class,
        TrafficUsageBucketEntity::class,
        GkdSubscriptionEntity::class,
        GkdGroupConfigEntity::class,
        GkdActionLogEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun appDao(): AppDao
    abstract fun groupDao(): GroupDao
    abstract fun networkProfileDao(): NetworkProfileDao
    abstract fun scheduleRuleDao(): ScheduleRuleDao
    abstract fun schedulePresetDao(): SchedulePresetDao
    abstract fun temporaryRuleDao(): TemporaryRuleDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun trafficUsageDao(): TrafficUsageDao
    abstract fun gkdDao(): GkdDao

    companion object {
        private const val DB_NAME = "netcontrol.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE groups ADD COLUMN iconKey TEXT NOT NULL DEFAULT 'folder'")
                db.execSQL("ALTER TABLE groups ADD COLUMN description TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `gkd_subscriptions` (
                        `subId` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `version` INTEGER NOT NULL,
                        `author` TEXT NOT NULL,
                        `updateUrl` TEXT,
                        `rawJson5` TEXT NOT NULL,
                        `isEnabled` INTEGER NOT NULL,
                        `appCount` INTEGER NOT NULL,
                        `globalGroupCount` INTEGER NOT NULL,
                        `ruleCount` INTEGER NOT NULL,
                        `updatedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`subId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `gkd_group_configs` (
                        `subId` INTEGER NOT NULL,
                        `appId` TEXT NOT NULL,
                        `groupKey` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        PRIMARY KEY(`subId`, `appId`, `groupKey`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `gkd_action_logs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `packageName` TEXT NOT NULL,
                        `activityId` TEXT NOT NULL,
                        `subName` TEXT NOT NULL,
                        `groupName` TEXT NOT NULL,
                        `ruleKey` INTEGER NOT NULL,
                        `action` TEXT NOT NULL,
                        `matchedText` TEXT NOT NULL,
                        `costMs` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_gkd_action_logs_timestamp` ON `gkd_action_logs` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_gkd_action_logs_packageName` ON `gkd_action_logs` (`packageName`)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `schedule_presets` (
                        `presetId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `startMinute` INTEGER NOT NULL,
                        `endMinute` INTEGER NOT NULL,
                        `daysMask` INTEGER NOT NULL,
                        `profileScope` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `schedule_presets` (`name`, `startMinute`, `endMinute`, `daysMask`, `profileScope`, `sortOrder`)
                    VALUES
                        ('夜间防熬夜', 1380, 420, 127, 'ALL', 0),
                        ('工作日专注', 540, 1080, 31, 'ALL', 1),
                        ('午休静默', 750, 840, 127, 'ALL', 2)
                    """.trimIndent()
                )
            }
        }

        fun create(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DB_NAME
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
        }

        val DEFAULT_NETWORK_PROFILES: List<NetworkProfileEntity> = listOf(
            NetworkProfileEntity(
                profileId = BuiltinProfileId.WIFI_UNKNOWN,
                name = "未知 Wi-Fi",
                type = NetworkProfileType.WIFI_UNKNOWN,
                defaultAllow = false,
                isSystemBuiltin = true
            ),
            NetworkProfileEntity(
                profileId = BuiltinProfileId.WIFI_GENERIC,
                name = "通用 Wi-Fi（优雅降级）",
                type = NetworkProfileType.WIFI_GENERIC,
                defaultAllow = true,
                isSystemBuiltin = true
            ),
            NetworkProfileEntity(
                profileId = BuiltinProfileId.MOBILE_SIM_1,
                name = "移动数据 · SIM 1",
                type = NetworkProfileType.MOBILE_SIM_1,
                defaultAllow = true,
                isSystemBuiltin = true
            ),
            NetworkProfileEntity(
                profileId = BuiltinProfileId.MOBILE_SIM_2,
                name = "移动数据 · SIM 2",
                type = NetworkProfileType.MOBILE_SIM_2,
                defaultAllow = true,
                isSystemBuiltin = true
            ),
            NetworkProfileEntity(
                profileId = BuiltinProfileId.MOBILE_UNKNOWN,
                name = "未知移动网络",
                type = NetworkProfileType.MOBILE_UNKNOWN,
                defaultAllow = true,
                isSystemBuiltin = true
            )
        )

        val DEFAULT_STARTER_GROUPS: List<GroupEntity> = listOf(
            GroupEntity(name = "社交聊天", colorHex = "#0B57D0", iconKey = "chat", description = "微信、QQ、Telegram 等即时通讯", sortOrder = 0),
            GroupEntity(name = "影音娱乐", colorHex = "#C2185B", iconKey = "video", description = "短视频、长视频与直播（适合免流/夜间断网）", sortOrder = 1),
            GroupEntity(name = "游戏", colorHex = "#7B1FA2", iconKey = "game", description = "手游与游戏社区", sortOrder = 2),
            GroupEntity(name = "办公效率", colorHex = "#00695C", iconKey = "work", description = "邮件、文档、远程协作", sortOrder = 3),
            GroupEntity(name = "夜间睡眠断网", colorHex = "#3949AB", iconKey = "night", description = "睡前自动断网防熬夜刷手机", sortOrder = 4),
            GroupEntity(name = "购物与生活", colorHex = "#EF6C00", iconKey = "shop", description = "电商、外卖与本地生活", sortOrder = 5),
            GroupEntity(name = "系统与工具", colorHex = "#455A64", iconKey = "system", description = "系统组件与实用工具", sortOrder = 6)
        )

        val DEFAULT_SCHEDULE_PRESETS: List<SchedulePresetEntity> = listOf(
            SchedulePresetEntity(
                name = "夜间防熬夜",
                startMinute = 23 * 60,
                endMinute = 7 * 60,
                daysMask = 0b1111111,
                profileScope = ProfileScope.ALL,
                sortOrder = 0
            ),
            SchedulePresetEntity(
                name = "工作日专注",
                startMinute = 9 * 60,
                endMinute = 18 * 60,
                daysMask = 0b0011111,
                profileScope = ProfileScope.ALL,
                sortOrder = 1
            ),
            SchedulePresetEntity(
                name = "午休静默",
                startMinute = 12 * 60 + 30,
                endMinute = 14 * 60,
                daysMask = 0b1111111,
                profileScope = ProfileScope.ALL,
                sortOrder = 2
            )
        )
    }
}
