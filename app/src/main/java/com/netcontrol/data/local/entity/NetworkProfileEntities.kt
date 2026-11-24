package com.netcontrol.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object NetworkProfileType {
    const val WIFI_TRUSTED = "WIFI_TRUSTED"
    const val WIFI_UNKNOWN = "WIFI_UNKNOWN"
    const val WIFI_GENERIC = "WIFI_GENERIC"
    const val MOBILE_SIM_1 = "MOBILE_SIM_1"
    const val MOBILE_SIM_2 = "MOBILE_SIM_2"
    const val MOBILE_UNKNOWN = "MOBILE_UNKNOWN"
}

object BuiltinProfileId {
    const val WIFI_UNKNOWN = "builtin_wifi_unknown"
    const val WIFI_GENERIC = "builtin_wifi_generic"
    const val MOBILE_SIM_1 = "builtin_mobile_sim_1"
    const val MOBILE_SIM_2 = "builtin_mobile_sim_2"
    const val MOBILE_UNKNOWN = "builtin_mobile_unknown"
}

@Entity(tableName = "network_profiles")
data class NetworkProfileEntity(
    @PrimaryKey
    val profileId: String,
    val name: String,
    val type: String,
    val subscriptionId: Int? = null,
    val defaultAllow: Boolean = true,
    val isSystemBuiltin: Boolean = false
)

@Entity(
    tableName = "wifi_bssid_mappings",
    foreignKeys = [
        ForeignKey(
            entity = NetworkProfileEntity::class,
            parentColumns = ["profileId"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["profileId"])]
)
data class WifiBssidMappingEntity(
    @PrimaryKey
    val bssid: String,
    val profileId: String,
    val ssidHint: String = "",
    val addedAt: Long = 0L
)
