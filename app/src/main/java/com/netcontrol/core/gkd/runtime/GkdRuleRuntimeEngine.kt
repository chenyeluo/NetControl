package com.netcontrol.core.gkd.runtime

import com.netcontrol.core.gkd.selector.GkdNodeAdapter
import com.netcontrol.core.gkd.selector.GkdSelectorMatcher
import com.netcontrol.core.gkd.subscription.CompiledGkdRegistry
import com.netcontrol.core.gkd.subscription.GkdPosition
import com.netcontrol.core.gkd.subscription.GkdRule
import com.netcontrol.core.gkd.subscription.GkdRuleGroup

data class GkdMatchHit<T>(
    val group: GkdRuleGroup,
    val rule: GkdRule,
    val targetNode: T,
    val matchedText: String
)

/**
 * 纯 Kotlin GKD 运行时规则状态机与匹配调度器（100% 可 JVM 单元测试）：
 * 完整支持：
 * - App / Activity 切换追踪与 `resetMatch` (`app` / `activity` / `match`) 重置；
 * - `activityIds` / `excludeActivityIds` 精准与简写前缀匹配（`.ui.MainActivity`）；
 * - `preKeys` 多步顺序规则链；
 * - `matchTime`、`matchDelay`、`actionCd`、`actionMaximum`、`cdKey`、`actionMaximumKey`；
 * - `matches` 全匹配、`anyMatches` 任一匹配、`excludeMatches` 排除防误点；
 * - `position` 自定义相对/绝对坐标表达式求值。
 */
class GkdRuleRuntimeEngine {

    private var currentAppId: String = ""
    private var currentActivityId: String = ""
    private var appEnteredAtMs: Long = 0L
    private var activityEnteredAtMs: Long = 0L

    private val lastTriggerByCdKey = HashMap<String, Long>()
    private val triggerCountByMaxKey = HashMap<String, Int>()
    private val triggeredKeysByGroup = HashMap<String, MutableSet<Int>>()

    fun onForegroundChanged(appId: String, activityId: String?, nowMs: Long) {
        val cleanAct = normalizeActivityId(appId, activityId.orEmpty())
        val appChanged = appId != currentAppId
        val actChanged = appChanged || (cleanAct.isNotBlank() && cleanAct != currentActivityId)

        if (appChanged) {
            currentAppId = appId
            appEnteredAtMs = nowMs
            triggerCountByMaxKey.clear()
            triggeredKeysByGroup.clear()
        }
        if (actChanged) {
            if (cleanAct.isNotBlank()) {
                currentActivityId = cleanAct
            } else if (appChanged) {
                currentActivityId = ""
            }
            activityEnteredAtMs = nowMs
            // 仅清理 resetMatch == activity 的计数与 preKeys
            triggerCountByMaxKey.keys.removeAll { it.endsWith("|activity") }
            triggeredKeysByGroup.keys.removeAll { it.endsWith("|activity") }
        }
    }

    fun reset() {
        currentAppId = ""
        currentActivityId = ""
        appEnteredAtMs = 0L
        activityEnteredAtMs = 0L
        lastTriggerByCdKey.clear()
        triggerCountByMaxKey.clear()
        triggeredKeysByGroup.clear()
    }

    fun <T> evaluate(
        registry: CompiledGkdRegistry,
        appId: String,
        activityId: String?,
        isSystemApp: Boolean,
        isLauncher: Boolean,
        root: T,
        adapter: GkdNodeAdapter<T>,
        nowMs: Long
    ): GkdMatchHit<T>? {
        val normalizedAct = normalizeActivityId(appId, activityId ?: currentActivityId)

        // 优先匹配该 App 的专属规则组，再匹配适用的全局规则组
        val candidateGroups = buildList {
            registry.enabledAppGroupsByPkg[appId]?.let { addAll(it) }
            for (gg in registry.enabledGlobalGroups) {
                if (isGlobalGroupApplicable(gg, appId, isSystemApp, isLauncher)) {
                    add(gg)
                }
            }
        }
        if (candidateGroups.isEmpty()) return null

        for (group in candidateGroups) {
            val globalScope = if (group.isGlobal) group.globalAppScopes[appId] else null
            for (rule in group.rules) {
                val hitNode = tryMatchRule(
                    group = group,
                    rule = rule,
                    appId = appId,
                    activityId = normalizedAct,
                    extraActivityIds = globalScope?.activityIds.orEmpty(),
                    extraExcludeActivityIds = globalScope?.excludeActivityIds.orEmpty(),
                    root = root,
                    adapter = adapter,
                    nowMs = nowMs
                ) ?: continue

                recordRuleTriggered(group, rule, nowMs)
                val label = adapter.getAttr(hitNode, "text")?.toString()
                    ?: adapter.getAttr(hitNode, "desc")?.toString()
                    ?: adapter.getAttr(hitNode, "vid")?.toString()
                    ?: ""
                return GkdMatchHit(group, rule, hitNode, label)
            }
        }
        return null
    }

    private fun <T> tryMatchRule(
        group: GkdRuleGroup,
        rule: GkdRule,
        appId: String,
        activityId: String,
        extraActivityIds: List<String>,
        extraExcludeActivityIds: List<String>,
        root: T,
        adapter: GkdNodeAdapter<T>,
        nowMs: Long
    ): T? {
        // 1. 校验 activityIds / excludeActivityIds
        val allowedActs = (rule.activityIds + extraActivityIds).distinct()
        val excludedActs = (rule.excludeActivityIds + extraExcludeActivityIds).distinct()
        if (excludedActs.isNotEmpty() && activityId.isNotBlank() && matchesAnyActivity(appId, activityId, excludedActs)) {
            return null
        }
        if (allowedActs.isNotEmpty() && !matchesAnyActivity(appId, activityId, allowedActs)) {
            return null
        }

        // 2. 校验时间窗（matchDelay / matchTime）
        val anchorMs = if (rule.resetMatch == "app") appEnteredAtMs else activityEnteredAtMs
        val elapsed = (nowMs - anchorMs).coerceAtLeast(0L)
        if (rule.matchDelay > 0L && elapsed < rule.matchDelay) return null
        val maxTime = rule.matchTime
        if (maxTime != null && maxTime > 0L && elapsed > maxTime) return null

        // 3. 校验 preKeys 多步链
        if (rule.preKeys.isNotEmpty()) {
            val scopeTag = if (rule.resetMatch == "app") "app" else "activity"
            val groupStateKey = "${group.subId}:${group.appId}:${group.key}|$scopeTag"
            val triggered = triggeredKeysByGroup[groupStateKey].orEmpty()
            if (rule.preKeys.none { it in triggered }) return null
        }

        // 4. 校验冷却时间 actionCd 与最大触发次数 actionMaximum
        val cdId = "${group.subId}:${group.appId}:${group.key}:${rule.cdKey ?: rule.key}"
        val lastAt = lastTriggerByCdKey[cdId]
        if (lastAt != null && (nowMs - lastAt) < rule.actionCd) return null

        val maxLimit = rule.actionMaximum
        if (maxLimit != null) {
            val scopeTag = if (rule.resetMatch == "app") "app" else "activity"
            val maxId = "${group.subId}:${group.appId}:${group.key}:${rule.actionMaximumKey ?: rule.key}|$scopeTag"
            val count = triggerCountByMaxKey[maxId] ?: 0
            if (count >= maxLimit) return null
        }

        // 5. 校验 excludeMatches（任一命中即中止）
        for (exSel in rule.excludeMatches) {
            if (GkdSelectorMatcher.matchFirst(exSel, root, adapter, appId, rule.fastQuery) != null) {
                return null
            }
        }

        // 6. 校验 matches（全部必须命中，以最后一个为点击目标）
        var target: T? = null
        if (rule.matches.isNotEmpty()) {
            for (sel in rule.matches) {
                val matched = GkdSelectorMatcher.matchFirst(sel, root, adapter, appId, rule.fastQuery)
                    ?: return null
                target = matched
            }
        }

        // 7. 校验 anyMatches（任一命中即可）
        if (rule.anyMatches.isNotEmpty()) {
            var anyHit: T? = null
            for (sel in rule.anyMatches) {
                val matched = GkdSelectorMatcher.matchFirst(sel, root, adapter, appId, rule.fastQuery)
                if (matched != null) {
                    anyHit = matched
                    break
                }
            }
            if (anyHit == null) return null
            target = anyHit
        }

        return target
    }

    private fun recordRuleTriggered(group: GkdRuleGroup, rule: GkdRule, nowMs: Long) {
        val cdId = "${group.subId}:${group.appId}:${group.key}:${rule.cdKey ?: rule.key}"
        lastTriggerByCdKey[cdId] = nowMs

        val scopeTag = if (rule.resetMatch == "app") "app" else "activity"
        val maxId = "${group.subId}:${group.appId}:${group.key}:${rule.actionMaximumKey ?: rule.key}|$scopeTag"
        triggerCountByMaxKey[maxId] = (triggerCountByMaxKey[maxId] ?: 0) + 1

        val groupStateKey = "${group.subId}:${group.appId}:${group.key}|$scopeTag"
        triggeredKeysByGroup.getOrPut(groupStateKey) { mutableSetOf() }.add(rule.key)
    }

    private fun isGlobalGroupApplicable(
        group: GkdRuleGroup,
        appId: String,
        isSystemApp: Boolean,
        isLauncher: Boolean
    ): Boolean {
        val explicit = group.globalAppScopes[appId]
        if (explicit != null) return explicit.enable
        if (!group.matchAnyApp) return false
        if (isLauncher && !group.matchLauncher) return false
        if (isSystemApp && !group.matchSystemApp) return false
        return true
    }

    companion object {
        fun normalizeActivityId(appId: String, rawActivityId: String): String {
            val trimmed = rawActivityId.trim()
            if (trimmed.isEmpty()) return ""
            return if (trimmed.startsWith('.') && appId.isNotBlank()) {
                appId + trimmed
            } else {
                trimmed
            }
        }

        fun matchesAnyActivity(appId: String, currentActivity: String, patterns: List<String>): Boolean {
            if (currentActivity.isBlank()) return false
            val normCur = normalizeActivityId(appId, currentActivity)
            return patterns.any { p ->
                val normP = normalizeActivityId(appId, p)
                normCur == normP || normCur.endsWith(".$p")
            }
        }

        /**
         * 计算点击坐标：支持 GKD position 表达式（如 `width * 0.85`、`height * 0.5` 或纯数字像素偏移），
         * 未提供时默认返回节点矩形正中心。
         */
        fun computeClickPoint(
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            position: GkdPosition?
        ): Pair<Float, Float> {
            val width = (right - left).coerceAtLeast(1).toFloat()
            val height = (bottom - top).coerceAtLeast(1).toFloat()

            val x = when {
                !position?.left.isNullOrBlank() -> left + evalPosExpr(position!!.left!!, width, height)
                !position?.right.isNullOrBlank() -> right - evalPosExpr(position!!.right!!, width, height)
                else -> left + width / 2f
            }
            val y = when {
                !position?.top.isNullOrBlank() -> top + evalPosExpr(position!!.top!!, width, height)
                !position?.bottom.isNullOrBlank() -> bottom - evalPosExpr(position!!.bottom!!, width, height)
                else -> top + height / 2f
            }
            return x to y
        }

        private fun evalPosExpr(expr: String, width: Float, height: Float): Float {
            val s = expr.trim()
            s.toFloatOrNull()?.let { return it }
            val mulMatch = Regex("""^(width|height)\s*\*\s*([0-9.]+)$""").matchEntire(s)
            if (mulMatch != null) {
                val base = if (mulMatch.groupValues[1] == "width") width else height
                val factor = mulMatch.groupValues[2].toFloatOrNull() ?: 0.5f
                return base * factor
            }
            val divMatch = Regex("""^(width|height)\s*/\s*([0-9.]+)$""").matchEntire(s)
            if (divMatch != null) {
                val base = if (divMatch.groupValues[1] == "width") width else height
                val divisor = divMatch.groupValues[2].toFloatOrNull()?.takeIf { it != 0f } ?: 2f
                return base / divisor
            }
            return width / 2f
        }
    }
}
