package com.netcontrol.core.network

import com.netcontrol.data.local.AppDatabase
import com.netcontrol.data.local.entity.BuiltinProfileId
import com.netcontrol.data.local.entity.NetworkProfileEntity
import com.netcontrol.data.local.entity.NetworkProfileType
import com.netcontrol.data.local.entity.WifiBssidMappingEntity
import com.netcontrol.data.prefs.FirewallSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkProfileResolverTest {

    private val homeProfile = NetworkProfileEntity(
        profileId = "wifi_home",
        name = "家庭 Mesh",
        type = NetworkProfileType.WIFI_TRUSTED,
        defaultAllow = true
    )

    private val allProfiles = AppDatabase.DEFAULT_NETWORK_PROFILES + homeProfile

    private val mappings = listOf(
        WifiBssidMappingEntity("aa:bb:cc:11:22:33", "wifi_home", "HomeMesh"),
        WifiBssidMappingEntity("aa:bb:cc:11:22:44", "wifi_home", "HomeMesh")
    )

    @Test
    fun trustedWifiMultiBssid_resolvesToSameLogicalProfile() {
        val raw = RawNetworkSnapshot(
            transport = NetworkTransportKind.WIFI,
            bssid = "AA:BB:CC:11:22:44",
            ssid = "HomeMesh"
        )
        val ctx = NetworkProfileResolver.resolve(
            raw = raw,
            profiles = allProfiles,
            bssidMappings = mappings,
            settings = FirewallSettings(blockUnknownWifi = true)
        )

        assertEquals(ResolvedProfileKind.WIFI_TRUSTED, ctx.kind)
        assertEquals("wifi_home", ctx.profileId)
        assertFalse(ctx.isUnknownSecurityBoundary)
        assertFalse(ctx.degradedFromUnreadableBssid)
    }

    @Test
    fun unregisteredBssid_withBlockUnknownWifiDisabled_defaultsToAllow() {
        val raw = RawNetworkSnapshot(
            transport = NetworkTransportKind.WIFI,
            bssid = "8a:33:2a:1b:05:50",
            ssid = "DJTU"
        )
        val ctx = NetworkProfileResolver.resolve(
            raw = raw,
            profiles = allProfiles,
            bssidMappings = mappings,
            settings = FirewallSettings(blockUnknownWifi = false)
        )

        assertEquals(ResolvedProfileKind.WIFI_UNKNOWN, ctx.kind)
        assertFalse(ctx.isUnknownSecurityBoundary)
        assertTrue(ctx.defaultAllow)
    }

    @Test
    fun unregisteredBssid_withBlockUnknownWifi_triggersSecurityBoundary() {
        val raw = RawNetworkSnapshot(
            transport = NetworkTransportKind.WIFI,
            bssid = "8a:33:2a:1b:05:50",
            ssid = "CafePublic"
        )
        val ctx = NetworkProfileResolver.resolve(
            raw = raw,
            profiles = allProfiles,
            bssidMappings = mappings,
            settings = FirewallSettings(blockUnknownWifi = true, gracefulDegradeOnUnreadableBssid = true)
        )

        assertEquals(ResolvedProfileKind.WIFI_UNKNOWN, ctx.kind)
        assertEquals(BuiltinProfileId.WIFI_UNKNOWN, ctx.profileId)
        assertTrue(ctx.isUnknownSecurityBoundary)
        assertFalse(ctx.defaultAllow)
    }

    @Test
    fun unreadableBssid_withGracefulDegrade_fallsBackToGenericWifiInsteadOfLockout() {
        val raw = RawNetworkSnapshot(
            transport = NetworkTransportKind.WIFI,
            bssid = "02:00:00:00:00:00",
            ssid = null
        )
        val ctx = NetworkProfileResolver.resolve(
            raw = raw,
            profiles = allProfiles,
            bssidMappings = mappings,
            settings = FirewallSettings(blockUnknownWifi = true, gracefulDegradeOnUnreadableBssid = true)
        )

        assertEquals(ResolvedProfileKind.WIFI_GENERIC_DEGRADED, ctx.kind)
        assertEquals(BuiltinProfileId.WIFI_GENERIC, ctx.profileId)
        assertTrue(ctx.degradedFromUnreadableBssid)
        assertFalse(ctx.isUnknownSecurityBoundary)
    }

    @Test
    fun unreadableBssid_withStrictFailClosed_enforcesUnknownBoundary() {
        val raw = RawNetworkSnapshot(
            transport = NetworkTransportKind.WIFI,
            bssid = null,
            ssid = null
        )
        val ctx = NetworkProfileResolver.resolve(
            raw = raw,
            profiles = allProfiles,
            bssidMappings = mappings,
            settings = FirewallSettings(blockUnknownWifi = true, gracefulDegradeOnUnreadableBssid = false)
        )

        assertEquals(ResolvedProfileKind.WIFI_UNKNOWN, ctx.kind)
        assertTrue(ctx.isUnknownSecurityBoundary)
    }

    @Test
    fun dualSimResolution_distinguishesSim1Sim2AndUnknown() {
        val sim1 = NetworkProfileResolver.resolve(
            RawNetworkSnapshot(NetworkTransportKind.MOBILE, simSlotIndex = 0),
            allProfiles,
            mappings,
            FirewallSettings()
        )
        val sim2 = NetworkProfileResolver.resolve(
            RawNetworkSnapshot(NetworkTransportKind.MOBILE, simSlotIndex = 1),
            allProfiles,
            mappings,
            FirewallSettings()
        )
        val simUnknown = NetworkProfileResolver.resolve(
            RawNetworkSnapshot(NetworkTransportKind.MOBILE, simSlotIndex = null),
            allProfiles,
            mappings,
            FirewallSettings(blockUnknownMobile = true)
        )

        assertEquals(ResolvedProfileKind.MOBILE_SIM_1, sim1.kind)
        assertEquals(BuiltinProfileId.MOBILE_SIM_1, sim1.profileId)
        assertEquals(ResolvedProfileKind.MOBILE_SIM_2, sim2.kind)
        assertEquals(BuiltinProfileId.MOBILE_SIM_2, sim2.profileId)
        assertEquals(ResolvedProfileKind.MOBILE_UNKNOWN, simUnknown.kind)
        assertTrue(simUnknown.isUnknownSecurityBoundary)
    }
}
