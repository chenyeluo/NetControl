package com.netcontrol.data.backup

import com.netcontrol.data.local.entity.AppEntity
import com.netcontrol.data.local.entity.ManualOverride
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupRestorePlannerTest {

    @Test
    fun buildPreview_separatesMatchedUninstalledAndSignatureMismatchApps() {
        val deviceApps = listOf(
            AppEntity("tv.danmaku.bili", 10250, "Bilibili", false, signatureSha256 = "valid_sig_bili"),
            AppEntity("com.bank.app", 10300, "MyBank", false, signatureSha256 = "new_tampered_sig")
        )

        val payload = BackupPayload(
            backupType = BackupType.RULES_ONLY,
            createdAtMs = 1000L,
            sourceDevice = "Test",
            includeAuditLogs = false,
            appRules = listOf(
                BackupAppRule("tv.danmaku.bili", "Bilibili", "valid_sig_bili", ManualOverride.FORCE_ALLOW, true, 3000, 60, false, listOf(1L)),
                BackupAppRule("com.bank.app", "MyBank", "original_bank_sig", ManualOverride.FORCE_ALLOW, false, 3000, 60, false, listOf(1L)),
                BackupAppRule("com.missing.app", "MissingApp", "some_sig", ManualOverride.FORCE_BLOCK, false, 3000, 60, false, emptyList())
            )
        )

        val preview = BackupRestorePlanner.buildPreview(payload, deviceApps)

        assertEquals(1, preview.matchedInstalledApps.size)
        assertEquals("tv.danmaku.bili", preview.matchedInstalledApps.single().packageName)

        assertEquals(1, preview.signatureMismatchApps.size)
        assertEquals("com.bank.app", preview.signatureMismatchApps.single().packageName)

        assertEquals(1, preview.uninstalledBackupApps.size)
        assertEquals("com.missing.app", preview.uninstalledBackupApps.single().packageName)
    }
}
