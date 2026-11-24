package com.netcontrol.data.scanner

import com.netcontrol.data.local.entity.AppEntity
import com.netcontrol.data.local.entity.ManualOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSyncMergerTest {

    @Test
    fun mergeFullScan_marksSharedUidsAndDiscoversNewApps() {
        val scanned = listOf(
            ScannedAppInfo("com.sys.a", 1000, "Sys A", true, null, "aaaa", 100L),
            ScannedAppInfo("com.sys.b", 1000, "Sys B", true, null, "aaaa", 100L),
            ScannedAppInfo("com.user.bili", 10250, "Bilibili", false, "tv.danmaku.bili.MainActivityV2", "bbbb", 200L)
        )

        val plan = AppSyncMerger.mergeFullScan(scanned, emptyList(), nowMs = 5000L)

        assertEquals(3, plan.newlyDiscoveredPackages.size)
        assertTrue(plan.signatureAlerts.isEmpty())
        val map = plan.appsToUpsert.associateBy { it.packageName }
        assertTrue(map.getValue("com.sys.a").isSharedUid)
        assertTrue(map.getValue("com.sys.b").isSharedUid)
        assertFalse(map.getValue("com.user.bili").isSharedUid)
        assertEquals("tv.danmaku.bili.MainActivityV2", map.getValue("com.user.bili").launcherActivity)
    }

    @Test
    fun mergeFullScan_preservesUserRulesOnSameSignatureUpdate() {
        val existing = listOf(
            AppEntity(
                packageName = "com.user.bili",
                uid = 10250,
                appName = "Bilibili Old",
                isSystemApp = false,
                signatureSha256 = "abc123",
                manualOverride = ManualOverride.FORCE_ALLOW,
                splashBlockEnabled = true,
                splashDurationMs = 3500,
                splashCooldownSec = 90
            )
        )
        val scanned = listOf(
            ScannedAppInfo("com.user.bili", 10250, "Bilibili New", false, "MainV3", "ABC123", 100L)
        )

        val plan = AppSyncMerger.mergeFullScan(scanned, existing, nowMs = 9000L)

        assertTrue(plan.signatureAlerts.isEmpty())
        assertTrue(plan.packagesToStripGroups.isEmpty())
        val updated = plan.appsToUpsert.single()
        assertEquals("Bilibili New", updated.appName)
        assertEquals(ManualOverride.FORCE_ALLOW, updated.manualOverride)
        assertTrue(updated.splashBlockEnabled)
        assertEquals(3500, updated.splashDurationMs)
        assertFalse(updated.signatureChanged)
    }

    @Test
    fun mergeFullScan_isolatesPermissionsWhenReinstalledWithDifferentSignature() {
        val existing = listOf(
            AppEntity(
                packageName = "com.bank.app",
                uid = 10300,
                appName = "MyBank",
                isSystemApp = false,
                signatureSha256 = "original_valid_hash",
                manualOverride = ManualOverride.FORCE_ALLOW,
                isUninstalled = true
            )
        )
        val scanned = listOf(
            ScannedAppInfo("com.bank.app", 10399, "MyBank", false, "MainActivity", "tampered_new_hash", 999L)
        )

        val plan = AppSyncMerger.mergeFullScan(scanned, existing, nowMs = 10000L)

        assertEquals(1, plan.signatureAlerts.size)
        assertEquals("com.bank.app", plan.signatureAlerts.single().packageName)
        assertTrue("com.bank.app" in plan.packagesToStripGroups)

        val isolated = plan.appsToUpsert.single()
        assertTrue(isolated.signatureChanged)
        assertFalse(isolated.isUninstalled)
        // 严禁继承旧的 FORCE_ALLOW，必须回退为 FOLLOW
        assertEquals(ManualOverride.FOLLOW, isolated.manualOverride)
        assertEquals("tampered_new_hash", isolated.signatureSha256)
    }

    @Test
    fun mergeFullScan_marksMissingAppsUninstalledAndStripsGroupsWithoutDeletingHistory() {
        val existing = listOf(
            AppEntity(
                packageName = "com.removed.game",
                uid = 10400,
                appName = "OldGame",
                isSystemApp = false,
                isSharedUid = true,
                signatureSha256 = "deadbeef",
                isUninstalled = false
            )
        )

        val plan = AppSyncMerger.mergeFullScan(emptyList(), existing, nowMs = 20000L)

        assertEquals(listOf("com.removed.game"), plan.newlyUninstalledPackages)
        assertEquals(listOf("com.removed.game"), plan.packagesToStripGroups)
        val record = plan.appsToUpsert.single()
        assertTrue(record.isUninstalled)
        assertFalse(record.isSharedUid)
    }
}
