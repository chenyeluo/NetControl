package com.netcontrol.core.gkd.subscription

import com.netcontrol.core.gkd.selector.GkdSelector

enum class GkdActionType {
    CLICK,
    CLICK_NODE,
    CLICK_CENTER,
    BACK,
    LONG_CLICK,
    LONG_CLICK_NODE,
    LONG_CLICK_CENTER,
    NONE;

    companion object {
        fun fromRaw(raw: String?): GkdActionType = when (raw?.trim()?.lowercase()) {
            "clicknode" -> CLICK_NODE
            "clickcenter" -> CLICK_CENTER
            "back" -> BACK
            "longclick" -> LONG_CLICK
            "longclicknode" -> LONG_CLICK_NODE
            "longclickcenter" -> LONG_CLICK_CENTER
            "none" -> NONE
            else -> CLICK
        }
    }
}

data class GkdPosition(
    val left: String? = null,
    val right: String? = null,
    val top: String? = null,
    val bottom: String? = null
)

data class GkdRule(
    val key: Int,
    val name: String,
    val preKeys: Set<Int> = emptySet(),
    val matches: List<GkdSelector> = emptyList(),
    val anyMatches: List<GkdSelector> = emptyList(),
    val excludeMatches: List<GkdSelector> = emptyList(),
    val activityIds: List<String> = emptyList(),
    val excludeActivityIds: List<String> = emptyList(),
    val fastQuery: Boolean = false,
    val matchRoot: Boolean = false,
    val matchDelay: Long = 0L,
    val matchTime: Long? = null,
    val forcedTime: Long = 0L,
    val resetMatch: String = "activity", // "activity" | "app" | "match"
    val actionDelay: Long = 0L,
    val actionCd: Long = 1000L,
    val actionMaximum: Int? = null,
    val actionMaximumKey: Int? = null,
    val cdKey: Int? = null,
    val priorityTime: Long = 0L,
    val action: GkdActionType = GkdActionType.CLICK,
    val position: GkdPosition? = null
)

data class GkdGlobalAppScope(
    val appId: String,
    val enable: Boolean = true,
    val activityIds: List<String> = emptyList(),
    val excludeActivityIds: List<String> = emptyList()
)

data class GkdRuleGroup(
    val subId: Long,
    val subName: String,
    val appId: String, // "*" 表示 globalGroup
    val key: Int,
    val name: String,
    val desc: String = "",
    val enableByDefault: Boolean = true,
    val isGlobal: Boolean = false,
    val matchAnyApp: Boolean = true,
    val matchSystemApp: Boolean = false,
    val matchLauncher: Boolean = false,
    val globalAppScopes: Map<String, GkdGlobalAppScope> = emptyMap(),
    val rules: List<GkdRule> = emptyList()
)

data class GkdCategory(
    val key: Int,
    val name: String,
    val enableByDefault: Boolean? = null
)

data class GkdAppSubscription(
    val appId: String,
    val name: String,
    val groups: List<GkdRuleGroup>
)

data class GkdSubscription(
    val id: Long,
    val name: String,
    val version: Int,
    val author: String,
    val updateUrl: String?,
    val checkUpdateUrl: String?,
    val categories: List<GkdCategory>,
    val globalGroups: List<GkdRuleGroup>,
    val apps: Map<String, GkdAppSubscription>
) {
    val totalRuleCount: Int
        get() = globalGroups.sumOf { it.rules.size } +
            apps.values.sumOf { app -> app.groups.sumOf { it.rules.size } }
}
