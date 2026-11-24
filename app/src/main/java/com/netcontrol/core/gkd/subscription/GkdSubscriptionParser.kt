package com.netcontrol.core.gkd.subscription

import com.netcontrol.core.gkd.selector.GkdSelector
import com.netcontrol.core.gkd.selector.GkdSelectorParser

/**
 * 完整 GKD 订阅 JSON5 解析器：
 * 兼容官方 gkd-kit/gkd 订阅结构（含 categories、globalGroups、apps、IArray 多态字段与分组级属性继承）。
 */
object GkdSubscriptionParser {

    fun parse(rawJson5: String, fallbackId: Long = System.currentTimeMillis()): GkdSubscription {
        val root = Json5Parser.parseObject(rawJson5)

        val id = root.longOrNull("id") ?: fallbackId
        val name = root.strOrNull("name")?.ifBlank { null } ?: "未命名 GKD 订阅"
        val version = root.intOrNull("version") ?: 0
        val author = root.strOrNull("author").orEmpty()
        val updateUrl = root.strOrNull("updateUrl")
        val checkUpdateUrl = root.strOrNull("checkUpdateUrl")

        val categories = root.listOfMaps("categories").mapIndexed { idx, catMap ->
            GkdCategory(
                key = catMap.intOrNull("key") ?: idx,
                name = catMap.strOrNull("name").orEmpty(),
                enableByDefault = catMap.boolOrNull("enable")
            )
        }
        val categoryDefaults = categories.associate { it.name to it.enableByDefault }

        val globalGroups = root.listOfMaps("globalGroups").mapIndexed { idx, gMap ->
            parseGroup(
                gMap = gMap,
                subId = id,
                subName = name,
                appId = "*",
                defaultKey = idx,
                isGlobal = true,
                categoryDefaults = categoryDefaults
            )
        }

        val appsMap = LinkedHashMap<String, GkdAppSubscription>()
        for (appMap in root.listOfMaps("apps")) {
            val appId = appMap.strOrNull("id")?.takeIf { it.isNotBlank() } ?: continue
            val appName = appMap.strOrNull("name")?.ifBlank { null } ?: appId
            val groups = appMap.listOfMaps("groups").mapIndexed { idx, gMap ->
                parseGroup(
                    gMap = gMap,
                    subId = id,
                    subName = name,
                    appId = appId,
                    defaultKey = idx,
                    isGlobal = false,
                    categoryDefaults = categoryDefaults
                )
            }
            appsMap[appId] = GkdAppSubscription(appId = appId, name = appName, groups = groups)
        }

        return GkdSubscription(
            id = id,
            name = name,
            version = version,
            author = author,
            updateUrl = updateUrl,
            checkUpdateUrl = checkUpdateUrl,
            categories = categories,
            globalGroups = globalGroups,
            apps = appsMap
        )
    }

    private fun parseGroup(
        gMap: Map<String, Any?>,
        subId: Long,
        subName: String,
        appId: String,
        defaultKey: Int,
        isGlobal: Boolean,
        categoryDefaults: Map<String, Boolean?>
    ): GkdRuleGroup {
        val groupKey = gMap.intOrNull("key") ?: defaultKey
        val groupName = gMap.strOrNull("name").orEmpty()
        val desc = gMap.strOrNull("desc").orEmpty()

        // 按 GKD 规则：若分组名以某分类名开头且未显式指定 enable，则继承分类默认开关
        val inheritedCatEnable = categoryDefaults.entries
            .firstOrNull { (catName, _) -> catName.isNotBlank() && groupName.startsWith(catName) }
            ?.value
        val enableByDefault = gMap.boolOrNull("enable") ?: inheritedCatEnable ?: true

        val groupFastQuery = gMap.boolOrNull("fastQuery") ?: gMap.boolOrNull("quickFind") ?: false
        val groupMatchRoot = gMap.boolOrNull("matchRoot") ?: false
        val groupMatchDelay = gMap.longOrNull("matchDelay") ?: 0L
        val groupMatchTime = gMap.longOrNull("matchTime")
        val groupForcedTime = gMap.longOrNull("forcedTime") ?: 0L
        val groupResetMatch = gMap.strOrNull("resetMatch") ?: "activity"
        val groupActionDelay = gMap.longOrNull("actionDelay") ?: 0L
        val groupActionCd = gMap.longOrNull("actionCd") ?: gMap.longOrNull("cd") ?: 1000L
        val groupActionMax = gMap.intOrNull("actionMaximum")
        val groupActionMaxKey = gMap.intOrNull("actionMaximumKey")
        val groupCdKey = gMap.intOrNull("cdKey")
        val groupPriorityTime = gMap.longOrNull("priorityTime") ?: 0L
        val groupAction = GkdActionType.fromRaw(gMap.strOrNull("action"))
        val groupActivityIds = gMap.stringList("activityIds")
        val groupExcludeActivityIds = gMap.stringList("excludeActivityIds")
        val groupPosition = gMap.mapOrNull("position")?.toGkdPosition()

        // rules 可以是数组，也可以是单个对象或字符串选择器
        val rawRules = when (val r = gMap["rules"]) {
            is List<*> -> r
            null -> emptyList<Any?>()
            else -> listOf(r)
        }

        val parsedRules = rawRules.mapIndexedNotNull { idx, item ->
            when (item) {
                is String -> {
                    val sel = GkdSelectorParser.tryParse(item) ?: return@mapIndexedNotNull null
                    GkdRule(
                        key = idx,
                        name = "$groupName#$idx",
                        matches = listOf(sel),
                        activityIds = groupActivityIds,
                        excludeActivityIds = groupExcludeActivityIds,
                        fastQuery = groupFastQuery,
                        matchRoot = groupMatchRoot,
                        matchDelay = groupMatchDelay,
                        matchTime = groupMatchTime,
                        forcedTime = groupForcedTime,
                        resetMatch = groupResetMatch,
                        actionDelay = groupActionDelay,
                        actionCd = groupActionCd,
                        actionMaximum = groupActionMax,
                        actionMaximumKey = groupActionMaxKey,
                        cdKey = groupCdKey,
                        priorityTime = groupPriorityTime,
                        action = groupAction,
                        position = groupPosition
                    )
                }
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val rMap = item as Map<String, Any?>
                    val matches = rMap.stringList("matches").mapNotNull { GkdSelectorParser.tryParse(it) }
                    val anyMatches = rMap.stringList("anyMatches").mapNotNull { GkdSelectorParser.tryParse(it) }
                    val excludeMatches = rMap.stringList("excludeMatches").mapNotNull { GkdSelectorParser.tryParse(it) }
                    if (matches.isEmpty() && anyMatches.isEmpty()) return@mapIndexedNotNull null

                    GkdRule(
                        key = rMap.intOrNull("key") ?: idx,
                        name = rMap.strOrNull("name") ?: "$groupName#$idx",
                        preKeys = rMap.intList("preKeys").toSet(),
                        matches = matches,
                        anyMatches = anyMatches,
                        excludeMatches = excludeMatches,
                        activityIds = rMap.stringList("activityIds").ifEmpty { groupActivityIds },
                        excludeActivityIds = rMap.stringList("excludeActivityIds").ifEmpty { groupExcludeActivityIds },
                        fastQuery = rMap.boolOrNull("fastQuery") ?: rMap.boolOrNull("quickFind") ?: groupFastQuery,
                        matchRoot = rMap.boolOrNull("matchRoot") ?: groupMatchRoot,
                        matchDelay = rMap.longOrNull("matchDelay") ?: groupMatchDelay,
                        matchTime = rMap.longOrNull("matchTime") ?: groupMatchTime,
                        forcedTime = rMap.longOrNull("forcedTime") ?: groupForcedTime,
                        resetMatch = rMap.strOrNull("resetMatch") ?: groupResetMatch,
                        actionDelay = rMap.longOrNull("actionDelay") ?: groupActionDelay,
                        actionCd = rMap.longOrNull("actionCd") ?: rMap.longOrNull("cd") ?: groupActionCd,
                        actionMaximum = rMap.intOrNull("actionMaximum") ?: groupActionMax,
                        actionMaximumKey = rMap.intOrNull("actionMaximumKey") ?: groupActionMaxKey,
                        cdKey = rMap.intOrNull("cdKey") ?: groupCdKey,
                        priorityTime = rMap.longOrNull("priorityTime") ?: groupPriorityTime,
                        action = rMap.strOrNull("action")?.let { GkdActionType.fromRaw(it) } ?: groupAction,
                        position = rMap.mapOrNull("position")?.toGkdPosition() ?: groupPosition
                    )
                }
                else -> null
            }
        }

        val globalScopes = if (isGlobal) {
            gMap.listOfMaps("apps").mapNotNull { am ->
                val targetId = am.strOrNull("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                targetId to GkdGlobalAppScope(
                    appId = targetId,
                    enable = am.boolOrNull("enable") ?: true,
                    activityIds = am.stringList("activityIds"),
                    excludeActivityIds = am.stringList("excludeActivityIds")
                )
            }.toMap()
        } else {
            emptyMap()
        }

        return GkdRuleGroup(
            subId = subId,
            subName = subName,
            appId = appId,
            key = groupKey,
            name = groupName,
            desc = desc,
            enableByDefault = enableByDefault,
            isGlobal = isGlobal,
            matchAnyApp = gMap.boolOrNull("matchAnyApp") ?: true,
            matchSystemApp = gMap.boolOrNull("matchSystemApp") ?: false,
            matchLauncher = gMap.boolOrNull("matchLauncher") ?: false,
            globalAppScopes = globalScopes,
            rules = parsedRules
        )
    }

    private fun Map<String, Any?>.toGkdPosition(): GkdPosition = GkdPosition(
        left = this["left"]?.toString(),
        right = this["right"]?.toString(),
        top = this["top"]?.toString(),
        bottom = this["bottom"]?.toString()
    )

    private fun Map<String, Any?>.strOrNull(key: String): String? = this[key]?.toString()
    private fun Map<String, Any?>.boolOrNull(key: String): Boolean? = this[key] as? Boolean
    private fun Map<String, Any?>.intOrNull(key: String): Int? = (this[key] as? Number)?.toInt()
    private fun Map<String, Any?>.longOrNull(key: String): Long? = (this[key] as? Number)?.toLong()

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.mapOrNull(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.listOfMaps(key: String): List<Map<String, Any?>> {
        val raw = this[key] as? List<*> ?: return emptyList()
        return raw.mapNotNull { it as? Map<String, Any?> }
    }

    private fun Map<String, Any?>.stringList(key: String): List<String> {
        return when (val v = this[key]) {
            is String -> listOf(v)
            is List<*> -> v.mapNotNull { it?.toString() }
            else -> emptyList()
        }
    }

    private fun Map<String, Any?>.intList(key: String): List<Int> {
        return when (val v = this[key]) {
            is Number -> listOf(v.toInt())
            is List<*> -> v.mapNotNull { (it as? Number)?.toInt() }
            else -> emptyList()
        }
    }
}
