package com.netcontrol.core.engine

import com.netcontrol.core.network.ResolvedNetworkContext
import com.netcontrol.data.local.entity.AppEntity
import com.netcontrol.data.local.entity.ManualOverride
import com.netcontrol.data.local.entity.ProfileScope
import com.netcontrol.data.local.entity.ScheduleRuleEntity
import com.netcontrol.data.local.entity.TempAction
import com.netcontrol.data.local.entity.TempTargetType
import com.netcontrol.data.prefs.GlobalEmergencyMode
import java.time.DayOfWeek

/**
 * 纯 Kotlin 确定性优先级规则裁决器（P0 ~ P5）：
 * 给定任意快照输入，产生 100% 可复现、无副作用的逐应用决策与 UID 聚合结果。
 */
object RuleEvaluator {

    private const val FIRST_APPLICATION_UID = 10000

    private val CORE_EXEMPT_PACKAGES = setOf(
        "moe.shizuku.privileged.api"
    )

    fun dayOfWeekToIndex(dayOfWeek: DayOfWeek): Int = dayOfWeek.value - 1

    fun matchesProfileScope(scope: String, network: ResolvedNetworkContext): Boolean {
        return when (scope) {
            ProfileScope.ALL -> true
            ProfileScope.WIFI_ALL -> network.isWifi
            ProfileScope.MOBILE_ALL -> network.isMobile
            else -> scope == network.profileId
        }
    }

    /**
     * 判断一条定时规则在指定星期（0=周一..6=周日）与当日分钟数（0..1439）是否生效。
     * 完整支持跨午夜区间（例如周一 23:00 -> 次日 07:00，在周二 02:00 时回查周一的掩码位）。
     */
    fun isScheduleRuleActiveAt(
        rule: ScheduleRuleEntity,
        dayOfWeekIndex: Int,
        minuteOfDay: Int
    ): Boolean {
        if (!rule.isEnabled) return false
        if (rule.daysMask == 0) return false
        val safeDay = ((dayOfWeekIndex % 7) + 7) % 7
        val todayBit = 1 shl safeDay

        return when {
            rule.startMinute == rule.endMinute -> {
                // 起止相同视为选中星期全天生效
                (rule.daysMask and todayBit) != 0
            }
            rule.startMinute < rule.endMinute -> {
                // 当日时段：[startMinute, endMinute)
                (rule.daysMask and todayBit) != 0 &&
                    minuteOfDay in rule.startMinute until rule.endMinute
            }
            else -> {
                // 跨午夜时段：当日 [startMinute, 1440) 或 次日 [0, endMinute)（需检查昨日掩码位）
                val inTonightPart = (rule.daysMask and todayBit) != 0 && minuteOfDay >= rule.startMinute
                val yesterdayBit = 1 shl ((safeDay + 6) % 7)
                val inAfterMidnightPart = (rule.daysMask and yesterdayBit) != 0 && minuteOfDay < rule.endMinute
                inTonightPart || inAfterMidnightPart
            }
        }
    }

    fun evaluate(input: EvaluationInput): EvaluationResult {
        val installedApps = input.apps.filter { !it.isUninstalled }
        val enabledGroupById = input.groups.filter { it.isEnabled }.associateBy { it.groupId }
        val groupsByPackage = input.crossRefs
            .filter { it.groupId in enabledGroupById }
            .groupBy({ it.packageName }, { it.groupId })

        val policiesByGroup = input.groupPolicies.groupBy { it.groupId }
        val schedulesByGroup = input.scheduleRules.filter { it.isEnabled }.groupBy { it.groupId }

        val activeTempRules = input.temporaryRules.filter { it.expiresAtEpochMs > input.nowEpochMs }
        val globalTempRules = activeTempRules.filter {
            it.targetType == TempTargetType.GLOBAL && matchesProfileScope(it.profileScope, input.network)
        }
        val appTempRulesByPkg = activeTempRules
            .filter { it.targetType == TempTargetType.APP && !it.packageName.isNullOrBlank() }
            .groupBy { it.packageName!! }

        val decisions = LinkedHashMap<String, AppDecision>(installedApps.size)
        val blockedPackages = LinkedHashSet<String>()

        for (app in installedApps) {
            val decision = evaluateSingleApp(
                app = app,
                input = input,
                enabledGroupIds = groupsByPackage[app.packageName].orEmpty(),
                enabledGroupNames = enabledGroupById.mapValues { it.value.name },
                policiesByGroup = policiesByGroup,
                schedulesByGroup = schedulesByGroup,
                globalTempRules = globalTempRules,
                appTempRules = appTempRulesByPkg[app.packageName].orEmpty()
            )
            decisions[app.packageName] = decision
            if (decision.blocked) {
                blockedPackages.add(app.packageName)
            }
        }

        // 聚合 UID 级目标状态（内核 chain3 按 UID 生效，确保永不阻断本应用自身 UID）
        val selfUid = installedApps.firstOrNull { it.packageName == input.selfPackageName }?.uid
        val uidGroups = installedApps.groupBy { it.uid }
        val uidTargetStates = LinkedHashMap<Int, Boolean>(uidGroups.size)
        val sharedUidAffected = LinkedHashSet<Int>()

        for ((uid, appsWithUid) in uidGroups) {
            if (uid == selfUid) {
                uidTargetStates[uid] = false
                continue
            }
            val anyBlocked = appsWithUid.any { decisions[it.packageName]?.blocked == true }
            uidTargetStates[uid] = anyBlocked
            if (anyBlocked && appsWithUid.size > 1) {
                sharedUidAffected.add(uid)
            }
        }

        return EvaluationResult(
            decisions = decisions,
            blockedPackages = blockedPackages,
            uidTargetStates = uidTargetStates,
            sharedUidAffectedUids = sharedUidAffected
        )
    }

    private fun evaluateSingleApp(
        app: AppEntity,
        input: EvaluationInput,
        enabledGroupIds: List<Long>,
        enabledGroupNames: Map<Long, String>,
        policiesByGroup: Map<Long, List<com.netcontrol.data.local.entity.GroupNetworkPolicyEntity>>,
        schedulesByGroup: Map<Long, List<ScheduleRuleEntity>>,
        globalTempRules: List<com.netcontrol.data.local.entity.TemporaryRuleEntity>,
        appTempRules: List<com.netcontrol.data.local.entity.TemporaryRuleEntity>
    ): AppDecision {
        // 自身与 Shizuku 永久豁免，防止自锁
        if (app.packageName == input.selfPackageName || app.packageName in CORE_EXEMPT_PACKAGES) {
            return AppDecision(app.packageName, app.uid, false, RuleDecisionReason.SELF_EXEMPT)
        }

        // 核心系统 UID（< 10000：android/system_server、networkstack、phone、dns、shell 等）永久豁免，防止全机断网瘫痪
        if (app.uid in 0 until FIRST_APPLICATION_UID) {
            return AppDecision(app.packageName, app.uid, false, RuleDecisionReason.SYSTEM_ESSENTIAL_EXEMPT)
        }

        // P0: 全局紧急控制 > 全局临时规则
        when (input.settings.globalEmergencyMode) {
            GlobalEmergencyMode.BLOCK_ALL -> {
                // 全局断网默认仅阻断普通用户应用，避免误杀系统网络栈与关键系统服务
                if (app.isSystemApp) {
                    return AppDecision(app.packageName, app.uid, false, RuleDecisionReason.SYSTEM_ESSENTIAL_EXEMPT)
                }
                return AppDecision(app.packageName, app.uid, true, RuleDecisionReason.GLOBAL_EMERGENCY_BLOCK)
            }
            GlobalEmergencyMode.ALLOW_ALL -> {
                return AppDecision(app.packageName, app.uid, false, RuleDecisionReason.GLOBAL_EMERGENCY_ALLOW)
            }
            GlobalEmergencyMode.NORMAL -> Unit
        }
        if (globalTempRules.isNotEmpty()) {
            val hasGlobalBlock = globalTempRules.any { it.action == TempAction.BLOCK }
            return if (hasGlobalBlock) {
                if (app.isSystemApp) {
                    AppDecision(app.packageName, app.uid, false, RuleDecisionReason.SYSTEM_ESSENTIAL_EXEMPT)
                } else {
                    AppDecision(app.packageName, app.uid, true, RuleDecisionReason.GLOBAL_TEMP_BLOCK)
                }
            } else {
                AppDecision(app.packageName, app.uid, false, RuleDecisionReason.GLOBAL_TEMP_ALLOW)
            }
        }

        // P1: 网络安全边界（切换 Fail-Safe 过渡 & 未知网络默认拒绝）
        if (input.network.transitionFailSafe && input.settings.blockUnknownWifi) {
            if (app.isSystemApp) {
                return AppDecision(app.packageName, app.uid, false, RuleDecisionReason.SYSTEM_ESSENTIAL_EXEMPT)
            }
            return AppDecision(app.packageName, app.uid, true, RuleDecisionReason.FAIL_SAFE_TRANSITION)
        }
        if (input.network.isUnknownSecurityBoundary) {
            if (app.isSystemApp) {
                return AppDecision(app.packageName, app.uid, false, RuleDecisionReason.SYSTEM_ESSENTIAL_EXEMPT)
            }
            // 仅显式针对该未知网络档案创建的单应用临时放行例外可穿透未知网络边界
            val explicitUnknownException = appTempRules.any {
                it.action == TempAction.ALLOW && it.profileScope == input.network.profileId
            }
            return if (explicitUnknownException) {
                AppDecision(
                    app.packageName,
                    app.uid,
                    false,
                    RuleDecisionReason.APP_TEMP_ALLOW,
                    matchedDetail = "未知网络显式临时例外"
                )
            } else {
                AppDecision(
                    app.packageName,
                    app.uid,
                    true,
                    RuleDecisionReason.UNKNOWN_NETWORK_BOUNDARY,
                    matchedDetail = input.network.profileName
                )
            }
        }

        // P2: 单应用覆盖（带到期时间的临时规则 > 永久手动覆盖）
        val matchingAppTempRules = appTempRules.filter { matchesProfileScope(it.profileScope, input.network) }
        if (matchingAppTempRules.isNotEmpty()) {
            val hasBlock = matchingAppTempRules.any { it.action == TempAction.BLOCK }
            return if (hasBlock) {
                AppDecision(app.packageName, app.uid, true, RuleDecisionReason.APP_TEMP_BLOCK)
            } else {
                AppDecision(app.packageName, app.uid, false, RuleDecisionReason.APP_TEMP_ALLOW)
            }
        }
        when (app.manualOverride) {
            ManualOverride.FORCE_BLOCK -> {
                return AppDecision(app.packageName, app.uid, true, RuleDecisionReason.APP_MANUAL_BLOCK)
            }
            ManualOverride.FORCE_ALLOW -> {
                return AppDecision(app.packageName, app.uid, false, RuleDecisionReason.APP_MANUAL_ALLOW)
            }
        }

        // P3: 开屏广告临时断网倒计时窗口
        if (input.settings.splashBlockGlobalEnabled && app.packageName in input.activeSplashBlockedPackages) {
            return AppDecision(app.packageName, app.uid, true, RuleDecisionReason.SPLASH_AD_BLOCK)
        }

        // P4: 多对多分组常驻策略与定时规则（取并集：任一所属启用分组命中禁止即断网）
        for (groupId in enabledGroupIds) {
            val groupName = enabledGroupNames[groupId] ?: "分组#$groupId"

            val hitStaticBlock = policiesByGroup[groupId].orEmpty().any { policy ->
                policy.blockByDefault && matchesProfileScope(policy.profileScope, input.network)
            }
            if (hitStaticBlock) {
                return AppDecision(
                    app.packageName,
                    app.uid,
                    true,
                    RuleDecisionReason.GROUP_STATIC_BLOCK,
                    matchedDetail = groupName
                )
            }

            val hitScheduleBlock = schedulesByGroup[groupId].orEmpty().any { rule ->
                matchesProfileScope(rule.profileScope, input.network) &&
                    isScheduleRuleActiveAt(rule, input.dayOfWeekIndex, input.minuteOfDay)
            }
            if (hitScheduleBlock) {
                return AppDecision(
                    app.packageName,
                    app.uid,
                    true,
                    RuleDecisionReason.GROUP_SCHEDULE_BLOCK,
                    matchedDetail = groupName
                )
            }
        }

        // P5: 当前网络档案默认策略（默认拒绝同样不误杀系统应用）
        return if (input.network.defaultAllow || app.isSystemApp) {
            AppDecision(app.packageName, app.uid, false, RuleDecisionReason.PROFILE_DEFAULT_ALLOW)
        } else {
            AppDecision(app.packageName, app.uid, true, RuleDecisionReason.PROFILE_DEFAULT_BLOCK)
        }
    }
}
