package com.netcontrol.core.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.netcontrol.MainActivity
import com.netcontrol.core.engine.NextScheduleBoundary
import com.netcontrol.core.engine.ScheduleCalculator
import com.netcontrol.data.local.AppDatabase
import com.netcontrol.data.prefs.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

data class AlarmScheduleStatus(
    val nextBoundary: NextScheduleBoundary? = null,
    val modeLabel: String = "未调度",
    val canScheduleExact: Boolean = true,
    val degradedToInexact: Boolean = false,
    val lastReason: String = ""
)

/**
 * 单点链式精准闹钟调度器：
 * 无论用户建了多少分组、定时规则或临时规则，系统内永远只注册「距离当前最近的唯一一个」闹钟；
 * 到点触发后执行 RuleEngine，再自动计算并注册下一个时间点，平时息屏零 CPU 唤醒。
 */
class ExactAlarmScheduler(
    private val context: Context,
    private val database: AppDatabase,
    private val settingsDataStore: SettingsDataStore
) {
    companion object {
        const val ACTION_EXACT_BOUNDARY = "com.netcontrol.alarm.ACTION_EXACT_BOUNDARY"
        const val EXTRA_BOUNDARY_DESC = "extra_boundary_desc"
        private const val REQUEST_CODE_BOUNDARY = 3001
        private const val REQUEST_CODE_SHOW_APP = 3002
    }

    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val scheduleMutex = Mutex()

    private val _status = MutableStateFlow(AlarmScheduleStatus())
    val status: StateFlow<AlarmScheduleStatus> = _status.asStateFlow()

    fun canScheduleExactAlarms(): Boolean {
        val am = alarmManager ?: return false
        return try {
            am.canScheduleExactAlarms()
        } catch (_: Throwable) {
            true
        }
    }

    suspend fun rescheduleNext(reason: String): AlarmScheduleStatus = scheduleMutex.withLock {
        withContext(Dispatchers.IO) {
            val am = alarmManager
            val canExact = canScheduleExactAlarms()
            val settings = settingsDataStore.getSnapshot()

            if (!settings.firewallEnabled || am == null) {
                cancelPendingAlarm()
                val idle = AlarmScheduleStatus(
                    nextBoundary = null,
                    modeLabel = if (am == null) "AlarmManager 不可用" else "防火墙未开启，已暂停调度",
                    canScheduleExact = canExact,
                    degradedToInexact = false,
                    lastReason = reason
                )
                _status.value = idle
                return@withContext idle
            }

            val now = ZonedDateTime.now()
            val nowMs = now.toInstant().toEpochMilli()
            val groups = database.groupDao().getAllGroupsOnce()
            val schedules = database.scheduleRuleDao().getEnabledRulesOnce()
            val tempRules = database.temporaryRuleDao().getActiveRulesOnce(nowMs)

            val next = ScheduleCalculator.computeNextBoundary(
                now = now,
                groups = groups,
                scheduleRules = schedules,
                temporaryRules = tempRules
            )

            if (next == null) {
                cancelPendingAlarm()
                val emptyStatus = AlarmScheduleStatus(
                    nextBoundary = null,
                    modeLabel = "无待触发的定时或临时规则",
                    canScheduleExact = canExact,
                    degradedToInexact = false,
                    lastReason = reason
                )
                _status.value = emptyStatus
                return@withContext emptyStatus
            }

            val pendingIntent = buildBoundaryPendingIntent(next.description)
            var modeLabel: String
            var degraded = false

            if (settings.strongAlarmClockMode) {
                val showIntent = PendingIntent.getActivity(
                    context,
                    REQUEST_CODE_SHOW_APP,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                try {
                    am.setAlarmClock(
                        AlarmManager.AlarmClockInfo(next.triggerAtEpochMs, showIntent),
                        pendingIntent
                    )
                    modeLabel = "强力精准模式 (setAlarmClock)"
                } catch (_: SecurityException) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.triggerAtEpochMs, pendingIntent)
                    modeLabel = "降级模式 (无精确闹钟权限)"
                    degraded = true
                }
            } else if (canExact) {
                try {
                    am.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        next.triggerAtEpochMs,
                        pendingIntent
                    )
                    modeLabel = "标准精准穿透 Doze (setExactAndAllowWhileIdle)"
                } catch (_: SecurityException) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.triggerAtEpochMs, pendingIntent)
                    modeLabel = "降级模式 (无精确闹钟权限)"
                    degraded = true
                }
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.triggerAtEpochMs, pendingIntent)
                modeLabel = "降级模式 (请在系统设置允许精确闹钟)"
                degraded = true
            }

            val result = AlarmScheduleStatus(
                nextBoundary = next,
                modeLabel = modeLabel,
                canScheduleExact = canExact,
                degradedToInexact = degraded,
                lastReason = reason
            )
            _status.value = result
            result
        }
    }

    fun cancelPendingAlarm() {
        val am = alarmManager ?: return
        val pi = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_BOUNDARY,
            Intent(context, AlarmReceiver::class.java).apply { action = ACTION_EXACT_BOUNDARY },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) {
            am.cancel(pi)
            pi.cancel()
        }
    }

    private fun buildBoundaryPendingIntent(description: String): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_EXACT_BOUNDARY
            putExtra(EXTRA_BOUNDARY_DESC, description)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_BOUNDARY,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
