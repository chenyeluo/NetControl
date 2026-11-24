package com.netcontrol.core.engine

import com.netcontrol.data.local.entity.GroupEntity
import com.netcontrol.data.local.entity.ScheduleRuleEntity
import com.netcontrol.data.local.entity.TemporaryRuleEntity
import java.time.ZonedDateTime

enum class BoundarySource {
    SCHEDULE_START,
    SCHEDULE_END,
    TEMPORARY_RULE_EXPIRE
}

data class NextScheduleBoundary(
    val triggerAtEpochMs: Long,
    val source: BoundarySource,
    val description: String
)

/**
 * 纯 Kotlin 全局最近状态变更边界计算器（单点链式调度核心）：
 * 遍历全部已启用分组的定时规则（完整支持跨午夜与 7 位星期掩码）及所有活跃临时规则，
 * 算出严格晚于当前时间 now 的唯一最近一次触发时间戳。
 */
object ScheduleCalculator {

    fun computeNextBoundary(
        now: ZonedDateTime,
        groups: List<GroupEntity>,
        scheduleRules: List<ScheduleRuleEntity>,
        temporaryRules: List<TemporaryRuleEntity>
    ): NextScheduleBoundary? {
        val nowEpochMs = now.toInstant().toEpochMilli()
        val zone = now.zone
        val enabledGroupMap = groups.filter { it.isEnabled }.associateBy { it.groupId }

        var earliest: NextScheduleBoundary? = null

        fun consider(candidateMs: Long, source: BoundarySource, desc: String) {
            if (candidateMs <= nowEpochMs) return
            val cur = earliest
            if (cur == null || candidateMs < cur.triggerAtEpochMs) {
                earliest = NextScheduleBoundary(candidateMs, source, desc)
            }
        }

        // 1. 临时规则到期时间点（与定时规则统一归并进同一条单点闹钟链）
        for (temp in temporaryRules) {
            if (temp.expiresAtEpochMs > nowEpochMs) {
                val targetLabel = temp.packageName ?: "全局"
                consider(
                    candidateMs = temp.expiresAtEpochMs,
                    source = BoundarySource.TEMPORARY_RULE_EXPIRE,
                    desc = "临时规则到期($targetLabel:${temp.action})"
                )
            }
        }

        // 2. 分组定时规则边界点（扫描昨日跨夜尾段 + 未来 7 天完整星期循环）
        val today = now.toLocalDate()
        for (rule in scheduleRules) {
            if (!rule.isEnabled || rule.daysMask == 0) continue
            val group = enabledGroupMap[rule.groupId] ?: continue

            val startHour = (rule.startMinute.coerceIn(0, 1439)) / 60
            val startMin = (rule.startMinute.coerceIn(0, 1439)) % 60
            val endHour = (rule.endMinute.coerceIn(0, 1439)) / 60
            val endMin = (rule.endMinute.coerceIn(0, 1439)) % 60

            // 关键：若规则跨午夜，需先检查「昨天」触发的跨夜区间在今天凌晨的结束时间点
            if (rule.startMinute > rule.endMinute) {
                val yesterday = today.minusDays(1)
                val yesterdayBit = 1 shl (yesterday.dayOfWeek.value - 1)
                if ((rule.daysMask and yesterdayBit) != 0) {
                    val endMs = today.atTime(endHour, endMin).atZone(zone).toInstant().toEpochMilli()
                    consider(
                        candidateMs = endMs,
                        source = BoundarySource.SCHEDULE_END,
                        desc = "${group.name} 跨夜结束(${formatMinute(rule.endMinute)})"
                    )
                }
            }

            // 扫描今天起未来 8 天（0..7），确保覆盖下周同一天
            for (dayOffset in 0L..7L) {
                val date = today.plusDays(dayOffset)
                val dayBit = 1 shl (date.dayOfWeek.value - 1)
                if ((rule.daysMask and dayBit) == 0) continue

                when {
                    rule.startMinute == rule.endMinute -> {
                        val dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
                        val nextDayStartMs = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                        consider(dayStartMs, BoundarySource.SCHEDULE_START, "${group.name} 全天开始")
                        consider(nextDayStartMs, BoundarySource.SCHEDULE_END, "${group.name} 全天结束")
                    }
                    rule.startMinute < rule.endMinute -> {
                        val startMs = date.atTime(startHour, startMin).atZone(zone).toInstant().toEpochMilli()
                        val endMs = date.atTime(endHour, endMin).atZone(zone).toInstant().toEpochMilli()
                        consider(
                            startMs,
                            BoundarySource.SCHEDULE_START,
                            "${group.name} 定时断网开始(${formatMinute(rule.startMinute)})"
                        )
                        consider(
                            endMs,
                            BoundarySource.SCHEDULE_END,
                            "${group.name} 定时断网结束(${formatMinute(rule.endMinute)})"
                        )
                    }
                    else -> {
                        val startMs = date.atTime(startHour, startMin).atZone(zone).toInstant().toEpochMilli()
                        val endMs = date.plusDays(1).atTime(endHour, endMin).atZone(zone).toInstant().toEpochMilli()
                        consider(
                            startMs,
                            BoundarySource.SCHEDULE_START,
                            "${group.name} 跨夜断网开始(${formatMinute(rule.startMinute)})"
                        )
                        consider(
                            endMs,
                            BoundarySource.SCHEDULE_END,
                            "${group.name} 跨夜断网结束(${formatMinute(rule.endMinute)})"
                        )
                    }
                }
            }
        }

        return earliest
    }

    fun formatMinute(minuteOfDay: Int): String {
        val clamped = minuteOfDay.coerceIn(0, 1439)
        return "%02d:%02d".format(clamped / 60, clamped % 60)
    }
}
