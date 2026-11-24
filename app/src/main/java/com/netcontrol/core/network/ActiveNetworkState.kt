package com.netcontrol.core.network

enum class NetworkTransportKind {
    WIFI,
    MOBILE,
    OFFLINE
}

enum class ResolvedProfileKind {
    WIFI_TRUSTED,
    WIFI_UNKNOWN,
    WIFI_GENERIC_DEGRADED,
    MOBILE_SIM_1,
    MOBILE_SIM_2,
    MOBILE_UNKNOWN,
    OFFLINE
}

data class RawNetworkSnapshot(
    val transport: NetworkTransportKind,
    val bssid: String? = null,
    val ssid: String? = null,
    val dataSubId: Int? = null,
    val simSlotIndex: Int? = null,
    val bssidSource: String = "none"
)

data class ResolvedNetworkContext(
    val profileId: String,
    val profileName: String,
    val kind: ResolvedProfileKind,
    val isWifi: Boolean,
    val isMobile: Boolean,
    val isOffline: Boolean,
    val isUnknownSecurityBoundary: Boolean,
    val degradedFromUnreadableBssid: Boolean,
    val bssid: String? = null,
    val ssid: String? = null,
    val simSlotIndex: Int? = null,
    val defaultAllow: Boolean = true,
    val transitionFailSafe: Boolean = false,
    val bssidSource: String = "none"
) {
    companion object {
        val OFFLINE = ResolvedNetworkContext(
            profileId = "offline",
            profileName = "离线 / 无可用网络",
            kind = ResolvedProfileKind.OFFLINE,
            isWifi = false,
            isMobile = false,
            isOffline = true,
            isUnknownSecurityBoundary = false,
            degradedFromUnreadableBssid = false,
            defaultAllow = true
        )
    }
}
