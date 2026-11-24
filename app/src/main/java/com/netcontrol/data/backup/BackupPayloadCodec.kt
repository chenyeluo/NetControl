package com.netcontrol.data.backup

import com.netcontrol.data.local.entity.FirewallAuditLogEntity
import com.netcontrol.data.local.entity.GroupEntity
import com.netcontrol.data.local.entity.GroupNetworkPolicyEntity
import com.netcontrol.data.local.entity.NetworkProfileEntity
import com.netcontrol.data.local.entity.ScheduleRuleEntity
import com.netcontrol.data.local.entity.TrafficUsageBucketEntity
import com.netcontrol.data.local.entity.WifiBssidMappingEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 零第三方依赖、带 GZIP 压缩的强类型结构化二进制编解码器（完全兼容 Android 与纯 JVM 单测）。
 */
object BackupPayloadCodec {

    private const val PAYLOAD_FORMAT_VERSION = 1

    fun encode(payload: BackupPayload): ByteArray {
        val rawOut = ByteArrayOutputStream()
        GZIPOutputStream(rawOut).use { gz ->
            DataOutputStream(gz).use { out ->
                out.writeInt(PAYLOAD_FORMAT_VERSION)
                out.writeInt(payload.schemaVersion)
                out.writeUTF(payload.backupType.name)
                out.writeLong(payload.createdAtMs)
                out.writeUTF(payload.sourceDevice)
                out.writeBoolean(payload.includeAuditLogs)

                out.writeInt(payload.groups.size)
                payload.groups.forEach { g ->
                    out.writeLong(g.groupId)
                    out.writeUTF(g.name)
                    out.writeUTF(g.colorHex)
                    out.writeUTF(g.iconKey)
                    out.writeUTF(g.description)
                    out.writeInt(g.sortOrder)
                    out.writeBoolean(g.isEnabled)
                }

                out.writeInt(payload.groupPolicies.size)
                payload.groupPolicies.forEach { p ->
                    out.writeLong(p.groupId)
                    out.writeUTF(p.profileScope)
                    out.writeBoolean(p.blockByDefault)
                }

                out.writeInt(payload.scheduleRules.size)
                payload.scheduleRules.forEach { r ->
                    out.writeLong(r.ruleId)
                    out.writeLong(r.groupId)
                    out.writeUTF(r.profileScope)
                    out.writeInt(r.startMinute)
                    out.writeInt(r.endMinute)
                    out.writeInt(r.daysMask)
                    out.writeBoolean(r.isEnabled)
                }

                out.writeInt(payload.networkProfiles.size)
                payload.networkProfiles.forEach { p ->
                    out.writeUTF(p.profileId)
                    out.writeUTF(p.name)
                    out.writeUTF(p.type)
                    out.writeBoolean(p.subscriptionId != null)
                    if (p.subscriptionId != null) out.writeInt(p.subscriptionId)
                    out.writeBoolean(p.defaultAllow)
                    out.writeBoolean(p.isSystemBuiltin)
                }

                out.writeInt(payload.bssidMappings.size)
                payload.bssidMappings.forEach { m ->
                    out.writeUTF(m.bssid)
                    out.writeUTF(m.profileId)
                    out.writeUTF(m.ssidHint)
                    out.writeLong(m.addedAt)
                }

                out.writeInt(payload.appRules.size)
                payload.appRules.forEach { a ->
                    out.writeUTF(a.packageName)
                    out.writeUTF(a.appName)
                    out.writeUTF(a.signatureSha256)
                    out.writeInt(a.manualOverride)
                    out.writeBoolean(a.splashBlockEnabled)
                    out.writeInt(a.splashDurationMs)
                    out.writeInt(a.splashCooldownSec)
                    out.writeBoolean(a.splashRelaxActivityCheck)
                    out.writeInt(a.groupIds.size)
                    a.groupIds.forEach { gid -> out.writeLong(gid) }
                }

                out.writeInt(payload.trafficBuckets.size)
                payload.trafficBuckets.forEach { t ->
                    out.writeUTF(t.packageName)
                    out.writeUTF(t.networkBucket)
                    out.writeUTF(t.bucketType)
                    out.writeLong(t.bucketStartMs)
                    out.writeLong(t.rxBytes)
                    out.writeLong(t.txBytes)
                }

                out.writeInt(payload.auditLogs.size)
                payload.auditLogs.forEach { l ->
                    out.writeLong(l.timestamp)
                    out.writeUTF(l.packageName)
                    out.writeInt(l.uid)
                    out.writeBoolean(l.blocked)
                    out.writeUTF(l.reason)
                    out.writeUTF(l.networkProfileId)
                    out.writeUTF(l.backendType)
                }
            }
        }
        return rawOut.toByteArray()
    }

    fun decode(bytes: ByteArray): BackupPayload {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { gz ->
            DataInputStream(gz).use { inp ->
                val formatVersion = inp.readInt()
                require(formatVersion == PAYLOAD_FORMAT_VERSION) {
                    "不支持的备份载荷版本: $formatVersion"
                }
                val schemaVersion = inp.readInt()
                val backupType = BackupType.valueOf(inp.readUTF())
                val createdAtMs = inp.readLong()
                val sourceDevice = inp.readUTF()
                val includeAuditLogs = inp.readBoolean()

                val groups = List(inp.readInt()) {
                    GroupEntity(
                        groupId = inp.readLong(),
                        name = inp.readUTF(),
                        colorHex = inp.readUTF(),
                        iconKey = inp.readUTF(),
                        description = inp.readUTF(),
                        sortOrder = inp.readInt(),
                        isEnabled = inp.readBoolean()
                    )
                }

                val policies = List(inp.readInt()) {
                    GroupNetworkPolicyEntity(
                        groupId = inp.readLong(),
                        profileScope = inp.readUTF(),
                        blockByDefault = inp.readBoolean()
                    )
                }

                val schedules = List(inp.readInt()) {
                    ScheduleRuleEntity(
                        ruleId = inp.readLong(),
                        groupId = inp.readLong(),
                        profileScope = inp.readUTF(),
                        startMinute = inp.readInt(),
                        endMinute = inp.readInt(),
                        daysMask = inp.readInt(),
                        isEnabled = inp.readBoolean()
                    )
                }

                val profiles = List(inp.readInt()) {
                    val profileId = inp.readUTF()
                    val name = inp.readUTF()
                    val type = inp.readUTF()
                    val hasSub = inp.readBoolean()
                    val subId = if (hasSub) inp.readInt() else null
                    val defaultAllow = inp.readBoolean()
                    val isBuiltin = inp.readBoolean()
                    NetworkProfileEntity(profileId, name, type, subId, defaultAllow, isBuiltin)
                }

                val bssids = List(inp.readInt()) {
                    WifiBssidMappingEntity(
                        bssid = inp.readUTF(),
                        profileId = inp.readUTF(),
                        ssidHint = inp.readUTF(),
                        addedAt = inp.readLong()
                    )
                }

                val appRules = List(inp.readInt()) {
                    val pkg = inp.readUTF()
                    val name = inp.readUTF()
                    val sig = inp.readUTF()
                    val overrideMode = inp.readInt()
                    val splashEnabled = inp.readBoolean()
                    val splashDur = inp.readInt()
                    val splashCool = inp.readInt()
                    val splashRelax = inp.readBoolean()
                    val gids = List(inp.readInt()) { inp.readLong() }
                    BackupAppRule(pkg, name, sig, overrideMode, splashEnabled, splashDur, splashCool, splashRelax, gids)
                }

                val buckets = List(inp.readInt()) {
                    TrafficUsageBucketEntity(
                        packageName = inp.readUTF(),
                        networkBucket = inp.readUTF(),
                        bucketType = inp.readUTF(),
                        bucketStartMs = inp.readLong(),
                        rxBytes = inp.readLong(),
                        txBytes = inp.readLong()
                    )
                }

                val logs = List(inp.readInt()) {
                    FirewallAuditLogEntity(
                        timestamp = inp.readLong(),
                        packageName = inp.readUTF(),
                        uid = inp.readInt(),
                        blocked = inp.readBoolean(),
                        reason = inp.readUTF(),
                        networkProfileId = inp.readUTF(),
                        backendType = inp.readUTF()
                    )
                }

                return BackupPayload(
                    schemaVersion = schemaVersion,
                    backupType = backupType,
                    createdAtMs = createdAtMs,
                    sourceDevice = sourceDevice,
                    includeAuditLogs = includeAuditLogs,
                    groups = groups,
                    groupPolicies = policies,
                    scheduleRules = schedules,
                    networkProfiles = profiles,
                    bssidMappings = bssids,
                    appRules = appRules,
                    trafficBuckets = buckets,
                    auditLogs = logs
                )
            }
        }
    }
}
