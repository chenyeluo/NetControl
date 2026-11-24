package com.netcontrol.core.engine

import com.netcontrol.core.network.ResolvedNetworkContext
import com.netcontrol.core.network.ResolvedProfileKind
import com.netcontrol.data.local.entity.AppEntity
import com.netcontrol.data.local.entity.AppGroupCrossRef
import com.netcontrol.data.local.entity.BuiltinProfileId
import com.netcontrol.data.local.entity.GroupEntity
import com.netcontrol.data.local.entity.GroupNetworkPolicyEntity
import com.netcontrol.data.local.entity.ManualOverride
import com.netcontrol.data.local.entity.ProfileScope
import com.netcontrol.data.local.entity.ScheduleRuleEntity
import com.netcontrol.data.local.entity.TempAction
import com.netcontrol.data.local.entity.TempTargetType
import com.netcontrol.data.local.entity.TemporaryRuleEntity
import com.netcontrol.data.prefs.FirewallSettings
import com.netcontrol.data.prefs.GlobalEmergencyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEvaluatorTest {

    private val trustedWifi = ResolvedNetworkContext(
        profileId = "wifi_home",
        profileName = "家庭 Wi-Fi",
        kind = ResolvedProfileKind.WIFI_TRUSTED,
        isWifi = true,
        isMobile = false,
        isOffline = false,
        isUnknownSecurityBoundary = false,
        degradedFromUnreadableBssid = false,
        defaultAllow = true
    )

    private val unknownWifiStrict = ResolvedNetworkContext(
        profileId = BuiltinProfileId.WIFI_UNKNOWN,
        profileName = "未知 Wi-Fi",
        kind = ResolvedProfileKind.WIFI_UNKNOWN,
        isWifi = true,
        isMobile = false,
        isOffline = false,
        isUnknownSecurityBoundary = true,
        degradedFromUnreadableBssid = false,
        defaultAllow = false
    )

    private val mobileSim1 = ResolvedNetworkContext(
        profileId = BuiltinProfileId.MOBILE_SIM_1,
        profileName = "SIM 1",
        kind = ResolvedProfileKind.MOBILE_SIM_1,
        isWifi = false,
        isMobile = true,
        isOffline = false,
        isUnknownSecurityBoundary = false,
        degradedFromUnreadableBssid = false,
        simSlotIndex = 0,
        defaultAllow = true
    )

    @Test
    fun selfAndShizuku_areAlwaysExemptEvenInGlobalBlockAll() {
        val apps = listOf(
            AppEntity("com.netcontrol.debug", 10667, "NetControl", false),
            AppEntity("moe.shizuku.privileged.api", 10500, "Shizuku", false),
            AppEntity("tv.danmaku.bili", 10250, "Bilibili", false)
        )

        val result = RuleEvaluator.evaluate(
            baseInput(
                apps = apps,
                settings = FirewallSettings(globalEmergencyMode = GlobalEmergencyMode.BLOCK_ALL)
            )
        )

        assertFalse(result.decisions.getValue("com.netcontrol.debug").blocked)
        assertEquals(RuleDecisionReason.SELF_EXEMPT, result.decisions.getValue("com.netcontrol.debug").reason)
        assertFalse(result.decisions.getValue("moe.shizuku.privileged.api").blocked)
        assertTrue(result.decisions.getValue("tv.danmaku.bili").blocked)
        assertEquals(RuleDecisionReason.GLOBAL_EMERGENCY_BLOCK, result.decisions.getValue("tv.danmaku.bili").reason)
    }

    @Test
    fun unknownNetworkBoundary_cannotBeBypassedByNormalManualAllow_onlyByExplicitUnknownTempException() {
        val bili = AppEntity("tv.danmaku.bili", 10250, "Bilibili", false, manualOverride = ManualOverride.FORCE_ALLOW)
        val wechat = AppEntity("com.tencent.mm", 10251, "WeChat", false)

        val explicitException = TemporaryRuleEntity(
            tempId = 1,
            targetType = TempTargetType.APP,
            packageName = "com.tencent.mm",
            action = TempAction.ALLOW,
            profileScope = BuiltinProfileId.WIFI_UNKNOWN,
            expiresAtEpochMs = 999_999L,
            createdAt = 100L
        )

        val result = RuleEvaluator.evaluate(
            baseInput(
                apps = listOf(bili, wechat),
                temporaryRules = listOf(explicitException),
                network = unknownWifiStrict,
                nowEpochMs = 500L
            )
        )

        // 普通手动强放不得绕过未知 Wi-Fi 安全边界
        assertTrue(result.decisions.getValue("tv.danmaku.bili").blocked)
        assertEquals(RuleDecisionReason.UNKNOWN_NETWORK_BOUNDARY, result.decisions.getValue("tv.danmaku.bili").reason)

        // 显式针对未知 Wi-Fi 档案的临时例外可放行
        assertFalse(result.decisions.getValue("com.tencent.mm").blocked)
        assertEquals(RuleDecisionReason.APP_TEMP_ALLOW, result.decisions.getValue("com.tencent.mm").reason)
    }

    @Test
    fun crossMidnightSchedule_checksYesterdaysDayBitAfterMidnight() {
        // 周一 (bit 0) 23:00 (1380) -> 次日 07:00 (420)
        val rule = ScheduleRuleEntity(
            ruleId = 1,
            groupId = 10,
            profileScope = ProfileScope.ALL,
            startMinute = 23 * 60,
            endMinute = 7 * 60,
            daysMask = 1 shl 0, // 仅周一晚触发跨夜
            isEnabled = true
        )

        // 周一 23:30 -> 命中
        assertTrue(RuleEvaluator.isScheduleRuleActiveAt(rule, dayOfWeekIndex = 0, minuteOfDay = 23 * 60 + 30))
        // 周二 02:00 -> 昨日是周一，命中跨夜后半段！
        assertTrue(RuleEvaluator.isScheduleRuleActiveAt(rule, dayOfWeekIndex = 1, minuteOfDay = 2 * 60))
        // 周二 07:00 -> 恰好结束，不命中
        assertFalse(RuleEvaluator.isScheduleRuleActiveAt(rule, dayOfWeekIndex = 1, minuteOfDay = 7 * 60))
        // 周三 02:00 -> 昨日是周二未勾选，不命中
        assertFalse(RuleEvaluator.isScheduleRuleActiveAt(rule, dayOfWeekIndex = 2, minuteOfDay = 2 * 60))
    }

    @Test
    fun multiGroupUnion_andPriorityOrder_manualOverridesSplashAndGroups() {
        val bili = AppEntity("tv.danmaku.bili", 10250, "Bilibili", false, manualOverride = ManualOverride.FOLLOW)
        val douyin = AppEntity("com.ss.android.ugc.aweme", 10251, "Douyin", false, manualOverride = ManualOverride.FORCE_ALLOW)

        val gVideo = GroupEntity(groupId = 1, name = "影音", isEnabled = true)
        val gNight = GroupEntity(groupId = 2, name = "夜间断网", isEnabled = true)

        val crossRefs = listOf(
            AppGroupCrossRef("tv.danmaku.bili", 1),
            AppGroupCrossRef("tv.danmaku.bili", 2),
            AppGroupCrossRef("com.ss.android.ugc.aweme", 1)
        )
        // 影音组仅在移动数据断网；夜间组在任意网络 23:00-07:00 断网
        val policies = listOf(
            GroupNetworkPolicyEntity(groupId = 1, profileScope = ProfileScope.MOBILE_ALL, blockByDefault = true)
        )
        val schedules = listOf(
            ScheduleRuleEntity(ruleId = 1, groupId = 2, profileScope = ProfileScope.ALL, startMinute = 23 * 60, endMinute = 7 * 60, daysMask = 0b1111111)
        )

        // 场景 1：家庭 Wi-Fi 晚上 23:30 -> B站命中夜间组定时断网
        val nightWifi = RuleEvaluator.evaluate(
            baseInput(
                apps = listOf(bili, douyin),
                groups = listOf(gVideo, gNight),
                crossRefs = crossRefs,
                groupPolicies = policies,
                scheduleRules = schedules,
                network = trustedWifi,
                minuteOfDay = 23 * 60 + 30
            )
        )
        assertTrue(nightWifi.decisions.getValue("tv.danmaku.bili").blocked)
        assertEquals(RuleDecisionReason.GROUP_SCHEDULE_BLOCK, nightWifi.decisions.getValue("tv.danmaku.bili").reason)

        // 场景 2：SIM 1 下午 14:00 -> B站命中影音组移动数据常驻断网；抖音因 P2 手动强放胜过 P4 分组断网
        val dayMobile = RuleEvaluator.evaluate(
            baseInput(
                apps = listOf(bili, douyin),
                groups = listOf(gVideo, gNight),
                crossRefs = crossRefs,
                groupPolicies = policies,
                scheduleRules = schedules,
                network = mobileSim1,
                minuteOfDay = 14 * 60
            )
        )
        assertTrue(dayMobile.decisions.getValue("tv.danmaku.bili").blocked)
        assertEquals(RuleDecisionReason.GROUP_STATIC_BLOCK, dayMobile.decisions.getValue("tv.danmaku.bili").reason)
        assertFalse(dayMobile.decisions.getValue("com.ss.android.ugc.aweme").blocked)
        assertEquals(RuleDecisionReason.APP_MANUAL_ALLOW, dayMobile.decisions.getValue("com.ss.android.ugc.aweme").reason)

        // 场景 3：家庭 Wi-Fi 下午 14:00 + 开屏断网窗口激活 -> P3 开屏阻断生效
        val splashWifi = RuleEvaluator.evaluate(
            baseInput(
                apps = listOf(bili),
                groups = listOf(gVideo, gNight),
                crossRefs = crossRefs,
                groupPolicies = policies,
                scheduleRules = schedules,
                activeSplashBlockedPackages = setOf("tv.danmaku.bili"),
                network = trustedWifi,
                settings = FirewallSettings(splashBlockGlobalEnabled = true),
                minuteOfDay = 14 * 60
            )
        )
        assertTrue(splashWifi.decisions.getValue("tv.danmaku.bili").blocked)
        assertEquals(RuleDecisionReason.SPLASH_AD_BLOCK, splashWifi.decisions.getValue("tv.danmaku.bili").reason)
    }

    @Test
    fun sharedUidAggregation_recordsAffectedSharedUid() {
        val a = AppEntity("com.sys.a", 10500, "SysA", false, isSharedUid = true, manualOverride = ManualOverride.FORCE_BLOCK)
        val b = AppEntity("com.sys.b", 10500, "SysB", false, isSharedUid = true, manualOverride = ManualOverride.FOLLOW)

        val result = RuleEvaluator.evaluate(baseInput(apps = listOf(a, b)))

        assertEquals(true, result.uidTargetStates[10500])
        assertTrue(10500 in result.sharedUidAffectedUids)
    }

    @Test
    fun coreSystemUidsAndSystemApps_areProtectedDuringGlobalBlockAndUnknownWifi() {
        val sysServer = AppEntity("android", 1000, "Android System", true, manualOverride = ManualOverride.FORCE_BLOCK)
        val netStack = AppEntity("com.google.android.networkstack", 1073, "NetworkStack", true)
        val sysBrowser = AppEntity("com.sec.android.app.sbrowser", 10200, "Samsung Internet", true)
        val userApp = AppEntity("tv.danmaku.bili", 10250, "Bilibili", false)

        val globalBlock = RuleEvaluator.evaluate(
            baseInput(
                apps = listOf(sysServer, netStack, sysBrowser, userApp),
                settings = FirewallSettings(globalEmergencyMode = GlobalEmergencyMode.BLOCK_ALL)
            )
        )
        assertFalse(globalBlock.decisions.getValue("android").blocked)
        assertEquals(RuleDecisionReason.SYSTEM_ESSENTIAL_EXEMPT, globalBlock.decisions.getValue("android").reason)
        assertFalse(globalBlock.decisions.getValue("com.google.android.networkstack").blocked)
        assertFalse(globalBlock.decisions.getValue("com.sec.android.app.sbrowser").blocked)
        assertTrue(globalBlock.decisions.getValue("tv.danmaku.bili").blocked)

        val unknownWifi = RuleEvaluator.evaluate(
            baseInput(
                apps = listOf(sysServer, netStack, sysBrowser, userApp),
                network = unknownWifiStrict
            )
        )
        assertFalse(unknownWifi.decisions.getValue("android").blocked)
        assertFalse(unknownWifi.decisions.getValue("com.sec.android.app.sbrowser").blocked)
        assertTrue(unknownWifi.decisions.getValue("tv.danmaku.bili").blocked)
    }

    private fun baseInput(
        apps: List<AppEntity>,
        groups: List<GroupEntity> = emptyList(),
        crossRefs: List<AppGroupCrossRef> = emptyList(),
        groupPolicies: List<GroupNetworkPolicyEntity> = emptyList(),
        scheduleRules: List<ScheduleRuleEntity> = emptyList(),
        temporaryRules: List<TemporaryRuleEntity> = emptyList(),
        activeSplashBlockedPackages: Set<String> = emptySet(),
        network: ResolvedNetworkContext = trustedWifi,
        settings: FirewallSettings = FirewallSettings(),
        nowEpochMs: Long = 1_000L,
        dayOfWeekIndex: Int = 0,
        minuteOfDay: Int = 12 * 60
    ) = EvaluationInput(
        selfPackageName = "com.netcontrol.debug",
        apps = apps,
        groups = groups,
        crossRefs = crossRefs,
        groupPolicies = groupPolicies,
        scheduleRules = scheduleRules,
        temporaryRules = temporaryRules,
        activeSplashBlockedPackages = activeSplashBlockedPackages,
        network = network,
        settings = settings,
        nowEpochMs = nowEpochMs,
        dayOfWeekIndex = dayOfWeekIndex,
        minuteOfDay = minuteOfDay
    )
}
