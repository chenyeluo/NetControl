package com.netcontrol.core.network

import com.netcontrol.data.local.entity.BuiltinProfileId
import com.netcontrol.data.local.entity.NetworkProfileEntity
import com.netcontrol.data.local.entity.WifiBssidMappingEntity
import com.netcontrol.data.prefs.FirewallSettings

/**
 * 纯 Kotlin 网络档案裁决器：
 * 将底层探测到的 Wi-Fi BSSID / 双 SIM 槽位信息映射为逻辑网络档案，
 * 并严格处理「未知网络默认拒绝」与「读不到 BSSID 时优雅降级为通用 Wi-Fi」两种策略。
 */
object NetworkProfileResolver {

    private val INVALID_BSSIDS = setOf("", "02:00:00:00:00:00", "00:00:00:00:00:00")

    fun normalizeBssid(raw: String?): String? {
        val clean = raw?.trim()?.lowercase() ?: return null
        return clean.takeUnless { it in INVALID_BSSIDS }
    }

    fun resolve(
        raw: RawNetworkSnapshot,
        profiles: List<NetworkProfileEntity>,
        bssidMappings: List<WifiBssidMappingEntity>,
        settings: FirewallSettings,
        transitionFailSafe: Boolean = false
    ): ResolvedNetworkContext {
        val profileById = profiles.associateBy { it.profileId }

        return when (raw.transport) {
            NetworkTransportKind.OFFLINE -> ResolvedNetworkContext.OFFLINE.copy(
                transitionFailSafe = transitionFailSafe
            )

            NetworkTransportKind.WIFI -> resolveWifi(
                raw = raw,
                profileById = profileById,
                bssidMappings = bssidMappings,
                settings = settings,
                transitionFailSafe = transitionFailSafe
            )

            NetworkTransportKind.MOBILE -> resolveMobile(
                raw = raw,
                profileById = profileById,
                settings = settings,
                transitionFailSafe = transitionFailSafe
            )
        }
    }

    private fun resolveWifi(
        raw: RawNetworkSnapshot,
        profileById: Map<String, NetworkProfileEntity>,
        bssidMappings: List<WifiBssidMappingEntity>,
        settings: FirewallSettings,
        transitionFailSafe: Boolean
    ): ResolvedNetworkContext {
        val normalizedBssid = normalizeBssid(raw.bssid)

        // 1. 优先匹配已登记的可信 Wi-Fi（支持同一逻辑档案关联多个 Mesh / AP 的 BSSID）
        if (normalizedBssid != null) {
            val mapping = bssidMappings.firstOrNull {
                it.bssid.equals(normalizedBssid, ignoreCase = true)
            }
            val trustedProfile = mapping?.let { profileById[it.profileId] }
            if (trustedProfile != null) {
                return ResolvedNetworkContext(
                    profileId = trustedProfile.profileId,
                    profileName = trustedProfile.name,
                    kind = ResolvedProfileKind.WIFI_TRUSTED,
                    isWifi = true,
                    isMobile = false,
                    isOffline = false,
                    isUnknownSecurityBoundary = false,
                    degradedFromUnreadableBssid = false,
                    bssid = normalizedBssid,
                    ssid = raw.ssid,
                    defaultAllow = trustedProfile.defaultAllow,
                    transitionFailSafe = transitionFailSafe,
                    bssidSource = raw.bssidSource
                )
            }

            // 读到了真实 BSSID 但尚未登记 -> 未知 Wi-Fi
            // 只有显式开启「未知 Wi-Fi 默认拒绝」时 defaultAllow 才为 false，防止误锁全机
            val unknownProfile = profileById[BuiltinProfileId.WIFI_UNKNOWN]
            return ResolvedNetworkContext(
                profileId = BuiltinProfileId.WIFI_UNKNOWN,
                profileName = unknownProfile?.name ?: "未知 Wi-Fi",
                kind = ResolvedProfileKind.WIFI_UNKNOWN,
                isWifi = true,
                isMobile = false,
                isOffline = false,
                isUnknownSecurityBoundary = settings.blockUnknownWifi,
                degradedFromUnreadableBssid = false,
                bssid = normalizedBssid,
                ssid = raw.ssid,
                defaultAllow = !settings.blockUnknownWifi,
                transitionFailSafe = transitionFailSafe,
                bssidSource = raw.bssidSource
            )
        }

        // 2. 无法读取 BSSID（例如未激活 Shizuku 且定位关闭）：按配置执行优雅降级或严格未知策略
        if (settings.gracefulDegradeOnUnreadableBssid) {
            val genericProfile = profileById[BuiltinProfileId.WIFI_GENERIC]
            return ResolvedNetworkContext(
                profileId = BuiltinProfileId.WIFI_GENERIC,
                profileName = genericProfile?.name ?: "通用 Wi-Fi（优雅降级）",
                kind = ResolvedProfileKind.WIFI_GENERIC_DEGRADED,
                isWifi = true,
                isMobile = false,
                isOffline = false,
                isUnknownSecurityBoundary = false,
                degradedFromUnreadableBssid = true,
                bssid = null,
                ssid = raw.ssid,
                defaultAllow = genericProfile?.defaultAllow ?: true,
                transitionFailSafe = transitionFailSafe,
                bssidSource = raw.bssidSource
            )
        }

        val unknownProfile = profileById[BuiltinProfileId.WIFI_UNKNOWN]
        return ResolvedNetworkContext(
            profileId = BuiltinProfileId.WIFI_UNKNOWN,
            profileName = unknownProfile?.name ?: "未知 Wi-Fi（无法识别 BSSID）",
            kind = ResolvedProfileKind.WIFI_UNKNOWN,
            isWifi = true,
            isMobile = false,
            isOffline = false,
            isUnknownSecurityBoundary = settings.blockUnknownWifi,
            degradedFromUnreadableBssid = false,
            bssid = null,
            ssid = raw.ssid,
            defaultAllow = !settings.blockUnknownWifi,
            transitionFailSafe = transitionFailSafe,
            bssidSource = raw.bssidSource
        )
    }

    private fun resolveMobile(
        raw: RawNetworkSnapshot,
        profileById: Map<String, NetworkProfileEntity>,
        settings: FirewallSettings,
        transitionFailSafe: Boolean
    ): ResolvedNetworkContext {
        return when (raw.simSlotIndex) {
            0 -> {
                val p = profileById[BuiltinProfileId.MOBILE_SIM_1]
                ResolvedNetworkContext(
                    profileId = BuiltinProfileId.MOBILE_SIM_1,
                    profileName = p?.name ?: "移动数据 · SIM 1",
                    kind = ResolvedProfileKind.MOBILE_SIM_1,
                    isWifi = false,
                    isMobile = true,
                    isOffline = false,
                    isUnknownSecurityBoundary = false,
                    degradedFromUnreadableBssid = false,
                    simSlotIndex = 0,
                    defaultAllow = p?.defaultAllow ?: true,
                    transitionFailSafe = transitionFailSafe
                )
            }
            1 -> {
                val p = profileById[BuiltinProfileId.MOBILE_SIM_2]
                ResolvedNetworkContext(
                    profileId = BuiltinProfileId.MOBILE_SIM_2,
                    profileName = p?.name ?: "移动数据 · SIM 2",
                    kind = ResolvedProfileKind.MOBILE_SIM_2,
                    isWifi = false,
                    isMobile = true,
                    isOffline = false,
                    isUnknownSecurityBoundary = false,
                    degradedFromUnreadableBssid = false,
                    simSlotIndex = 1,
                    defaultAllow = p?.defaultAllow ?: true,
                    transitionFailSafe = transitionFailSafe
                )
            }
            else -> {
                val p = profileById[BuiltinProfileId.MOBILE_UNKNOWN]
                ResolvedNetworkContext(
                    profileId = BuiltinProfileId.MOBILE_UNKNOWN,
                    profileName = p?.name ?: "未知移动网络",
                    kind = ResolvedProfileKind.MOBILE_UNKNOWN,
                    isWifi = false,
                    isMobile = true,
                    isOffline = false,
                    isUnknownSecurityBoundary = settings.blockUnknownMobile,
                    degradedFromUnreadableBssid = false,
                    simSlotIndex = raw.simSlotIndex,
                    defaultAllow = if (settings.blockUnknownMobile) false else (p?.defaultAllow ?: true),
                    transitionFailSafe = transitionFailSafe
                )
            }
        }
    }
}
