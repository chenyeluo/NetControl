package com.netcontrol.core.engine

import com.netcontrol.data.local.entity.GroupEntity
import com.netcontrol.data.local.entity.ProfileScope
import com.netcontrol.data.local.entity.ScheduleRuleEntity
import com.netcontrol.data.local.entity.TempAction
import com.netcontrol.data.local.entity.TempTargetType
import com.netcontrol.data.local.entity.TemporaryRuleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleCalculatorTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val group = GroupEntity(groupId = 1L, name = "夜间断网", isEnabled = true)

    @Test
    fun sameDayRule_beforeStart_picksTodayStart() {
        // 2026-11-23 是周一
        val now = ZonedDateTime.of(2026, 11, 23, 8, 30, 0, 0, zone)
        val rule = ScheduleRuleEntity(
            ruleId = 1,
            groupId = 1,
            startMinute = 9 * 60,
            endMinute = 18 * 60,
            daysMask = 1 shl 0 // 周一
        )

        val next = ScheduleCalculator.computeNextBoundary(now, listOf(group), listOf(rule), emptyList())
        assertNotNull(next)
        assertEquals(BoundarySource.SCHEDULE_START, next!!.source)
        assertEquals(
            ZonedDateTime.of(2026, 11, 23, 9, 0, 0, 0, zone).toInstant().toEpochMilli(),
            next.triggerAtEpochMs
        )
    }

    @Test
    fun sameDayRule_insideInterval_picksTodayEnd() {
        val now = ZonedDateTime.of(2026, 11, 23, 10, 15, 0, 0, zone)
        val rule = ScheduleRuleEntity(
            ruleId = 1,
            groupId = 1,
            startMinute = 9 * 60,
            endMinute = 18 * 60,
            daysMask = 1 shl 0
        )

        val next = ScheduleCalculator.computeNextBoundary(now, listOf(group), listOf(rule), emptyList())
        assertNotNull(next)
        assertEquals(BoundarySource.SCHEDULE_END, next!!.source)
        assertEquals(
            ZonedDateTime.of(2026, 11, 23, 18, 0, 0, 0, zone).toInstant().toEpochMilli(),
            next.triggerAtEpochMs
        )
    }

    @Test
    fun crossMidnightRule_afterMidnightTuesday_picksTuesdayMorningEndFromMondayBit() {
        // 2026-11-24 是周二凌晨 02:00；规则仅勾选周一 (bit 0) 23:00 -> 次日 07:00
        val now = ZonedDateTime.of(2026, 11, 24, 2, 0, 0, 0, zone)
        val rule = ScheduleRuleEntity(
            ruleId = 1,
            groupId = 1,
            startMinute = 23 * 60,
            endMinute = 7 * 60,
            daysMask = 1 shl 0 // 仅周一
        )

        val next = ScheduleCalculator.computeNextBoundary(now, listOf(group), listOf(rule), emptyList())
        assertNotNull(next)
        assertEquals(BoundarySource.SCHEDULE_END, next!!.source)
        assertEquals(
            ZonedDateTime.of(2026, 11, 24, 7, 0, 0, 0, zone).toInstant().toEpochMilli(),
            next.triggerAtEpochMs
        )
    }

    @Test
    fun crossMidnightRule_tuesdayMorningAfterEnd_wrapsToNextMondayStart() {
        // 周二 08:00，规则仅勾选周一 23:00 -> 07:00，下一次触发应为下周一 2026-11-30 23:00
        val now = ZonedDateTime.of(2026, 11, 24, 8, 0, 0, 0, zone)
        val rule = ScheduleRuleEntity(
            ruleId = 1,
            groupId = 1,
            startMinute = 23 * 60,
            endMinute = 7 * 60,
            daysMask = 1 shl 0
        )

        val next = ScheduleCalculator.computeNextBoundary(now, listOf(group), listOf(rule), emptyList())
        assertNotNull(next)
        assertEquals(BoundarySource.SCHEDULE_START, next!!.source)
        assertEquals(
            ZonedDateTime.of(2026, 11, 30, 23, 0, 0, 0, zone).toInstant().toEpochMilli(),
            next.triggerAtEpochMs
        )
    }

    @Test
    fun temporaryRuleExpiration_winsOverLaterScheduleBoundary() {
        val now = ZonedDateTime.of(2026, 11, 23, 14, 0, 0, 0, zone)
        val nowMs = now.toInstant().toEpochMilli()
        val nightRule = ScheduleRuleEntity(
            ruleId = 1,
            groupId = 1,
            startMinute = 23 * 60,
            endMinute = 7 * 60,
            daysMask = 0b1111111
        )
        val tempRule = TemporaryRuleEntity(
            tempId = 1,
            targetType = TempTargetType.APP,
            packageName = "tv.danmaku.bili",
            action = TempAction.BLOCK,
            profileScope = ProfileScope.ALL,
            expiresAtEpochMs = nowMs + 120_000L, // 2 分钟后过期
            createdAt = nowMs
        )

        val next = ScheduleCalculator.computeNextBoundary(now, listOf(group), listOf(nightRule), listOf(tempRule))
        assertNotNull(next)
        assertEquals(BoundarySource.TEMPORARY_RULE_EXPIRE, next!!.source)
        assertEquals(nowMs + 120_000L, next.triggerAtEpochMs)
    }

    @Test
    fun disabledGroupSchedule_isIgnored() {
        val disabledGroup = group.copy(isEnabled = false)
        val now = ZonedDateTime.of(2026, 11, 23, 14, 0, 0, 0, zone)
        val rule = ScheduleRuleEntity(
            ruleId = 1,
            groupId = 1,
            startMinute = 23 * 60,
            endMinute = 7 * 60,
            daysMask = 0b1111111
        )

        val next = ScheduleCalculator.computeNextBoundary(now, listOf(disabledGroup), listOf(rule), emptyList())
        assertNull(next)
    }
}
