package com.netcontrol.data.backup

import com.netcontrol.data.local.entity.GroupEntity
import com.netcontrol.data.local.entity.ManualOverride
import com.netcontrol.data.local.entity.ProfileScope
import com.netcontrol.data.local.entity.ScheduleRuleEntity
import com.netcontrol.data.local.entity.TrafficBucketType
import com.netcontrol.data.local.entity.TrafficNetworkBucket
import com.netcontrol.data.local.entity.TrafficUsageBucketEntity
import com.netcontrol.data.local.entity.WifiBssidMappingEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class BackupCryptoManagerTest {

    private val samplePayload = BackupPayload(
        schemaVersion = 2,
        backupType = BackupType.FULL,
        createdAtMs = 1764000000000L,
        sourceDevice = "samsung SM-S9360 (API 36)",
        includeAuditLogs = false,
        groups = listOf(
            GroupEntity(groupId = 1, name = "夜间防熬夜", colorHex = "#3949AB", iconKey = "night", description = "23点断网", sortOrder = 0)
        ),
        scheduleRules = listOf(
            ScheduleRuleEntity(ruleId = 1, groupId = 1, profileScope = ProfileScope.ALL, startMinute = 1380, endMinute = 420, daysMask = 127)
        ),
        bssidMappings = listOf(
            WifiBssidMappingEntity("8a:33:2a:1b:05:50", "wifi_home", "DJTU", 123456L)
        ),
        appRules = listOf(
            BackupAppRule("tv.danmaku.bili", "Bilibili", "aabbccdd", ManualOverride.FORCE_ALLOW, true, 3000, 60, false, listOf(1L))
        ),
        trafficBuckets = listOf(
            TrafficUsageBucketEntity("tv.danmaku.bili", TrafficNetworkBucket.WIFI, TrafficBucketType.DAILY, 1000L, 12345L, 6789L)
        )
    )

    @Test
    fun encryptAndDecrypt_roundTripPreservesAllDataAndUsesUniqueSaltNonce() {
        val enc1 = BackupCryptoManager.encryptPayload(samplePayload, "Str0ngPass!".toCharArray(), iterations = 15_000)
        val enc2 = BackupCryptoManager.encryptPayload(samplePayload, "Str0ngPass!".toCharArray(), iterations = 15_000)

        // 独立随机 Salt 与 Nonce 保证同明文同密码两次密文完全不同
        assertFalse(enc1.contentEquals(enc2))

        val decoded = BackupCryptoManager.decryptPayload(enc1, "Str0ngPass!".toCharArray())
        assertEquals(samplePayload.backupType, decoded.backupType)
        assertEquals(samplePayload.sourceDevice, decoded.sourceDevice)
        assertEquals(samplePayload.groups, decoded.groups)
        assertEquals(samplePayload.scheduleRules, decoded.scheduleRules)
        assertEquals(samplePayload.bssidMappings, decoded.bssidMappings)
        assertEquals(samplePayload.appRules, decoded.appRules)
        assertEquals(samplePayload.trafficBuckets, decoded.trafficBuckets)
    }

    @Test
    fun wrongPassword_throwsBackupCryptoException() {
        val enc = BackupCryptoManager.encryptPayload(samplePayload, "CorrectPass123".toCharArray(), iterations = 15_000)
        try {
            BackupCryptoManager.decryptPayload(enc, "WrongPass123".toCharArray())
            fail("Expected BackupCryptoException for wrong password")
        } catch (_: BackupCryptoException) {
            // expected
        }
    }

    @Test
    fun tamperedCiphertextOrHeaderByte_rejectsWithAeadAuthenticationFailure() {
        val enc = BackupCryptoManager.encryptPayload(samplePayload, "CorrectPass123".toCharArray(), iterations = 15_000)

        // 篡改密文最后 1 字节
        val tamperedCipher = enc.copyOf()
        tamperedCipher[tamperedCipher.lastIndex] = (tamperedCipher.last().toInt() xor 0x01).toByte()
        try {
            BackupCryptoManager.decryptPayload(tamperedCipher, "CorrectPass123".toCharArray())
            fail("Expected BackupCryptoException for tampered ciphertext")
        } catch (_: BackupCryptoException) {
            // expected
        }

        // 篡改文件头 Salt 区域（受 GCM AAD 保护）
        val tamperedHeader = enc.copyOf()
        tamperedHeader[12] = (tamperedHeader[12].toInt() xor 0xFF).toByte()
        try {
            BackupCryptoManager.decryptPayload(tamperedHeader, "CorrectPass123".toCharArray())
            fail("Expected BackupCryptoException for tampered header AAD")
        } catch (_: BackupCryptoException) {
            // expected
        }
    }
}
