package com.netcontrol.core.engine

import com.netcontrol.core.network.ResolvedNetworkContext
import com.netcontrol.data.local.entity.AppEntity
import com.netcontrol.data.local.entity.AppGroupCrossRef
import com.netcontrol.data.local.entity.GroupEntity
import com.netcontrol.data.local.entity.GroupNetworkPolicyEntity
import com.netcontrol.data.local.entity.ScheduleRuleEntity
import com.netcontrol.data.local.entity.TemporaryRuleEntity
import com.netcontrol.data.prefs.FirewallSettings

enum class RuleDecisionReason {
    SELF_EXEMPT,
    SYSTEM_ESSENTIAL_EXEMPT,
    GLOBAL_EMERGENCY_BLOCK,
    GLOBAL_EMERGENCY_ALLOW,
    GLOBAL_TEMP_BLOCK,
    GLOBAL_TEMP_ALLOW,
    FAIL_SAFE_TRANSITION,
    UNKNOWN_NETWORK_BOUNDARY,
    APP_TEMP_BLOCK,
    APP_TEMP_ALLOW,
    APP_MANUAL_BLOCK,
    APP_MANUAL_ALLOW,
    SPLASH_AD_BLOCK,
    GROUP_STATIC_BLOCK,
    GROUP_SCHEDULE_BLOCK,
    PROFILE_DEFAULT_BLOCK,
    PROFILE_DEFAULT_ALLOW
}

data class AppDecision(
    val packageName: String,
    val uid: Int,
    val blocked: Boolean,
    val reason: RuleDecisionReason,
    val matchedDetail: String = ""
)

data class EvaluationInput(
    val selfPackageName: String,
    val apps: List<AppEntity>,
    val groups: List<GroupEntity>,
    val crossRefs: List<AppGroupCrossRef>,
    val groupPolicies: List<GroupNetworkPolicyEntity>,
    val scheduleRules: List<ScheduleRuleEntity>,
    val temporaryRules: List<TemporaryRuleEntity>,
    val activeSplashBlockedPackages: Set<String>,
    val network: ResolvedNetworkContext,
    val settings: FirewallSettings,
    val nowEpochMs: Long,
    /** 0 = 周一 .. 6 = 周日 */
    val dayOfWeekIndex: Int,
    /** 当日距离 00:00 的分钟数 [0..1439] */
    val minuteOfDay: Int
)

data class EvaluationResult(
    val decisions: Map<String, AppDecision>,
    val blockedPackages: Set<String>,
    val uidTargetStates: Map<Int, Boolean>,
    val sharedUidAffectedUids: Set<Int>
)
