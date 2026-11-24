package com.netcontrol.core.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.netcontrol.NetControlApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 精准定时边界广播接收器：
 * 触发瞬间调用 goAsync() 并持有带 8 秒硬超时保护的 PARTIAL_WAKE_LOCK，
 * 确保深度休眠（Doze）唤醒期间完成 Room 查询、规则下发与下一闹钟链式注册后再释放 CPU。
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val WAKE_LOCK_TAG = "NetControl:ExactAlarmBoundary"
        private const val WAKE_LOCK_TIMEOUT_MS = 8_000L
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext as? NetControlApp ?: return
        val desc = intent?.getStringExtra(ExactAlarmScheduler.EXTRA_BOUNDARY_DESC).orEmpty()
        val pendingResult = goAsync()

        val pm = context.getSystemService(PowerManager::class.java)
        val wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                app.container.ruleEngine.evaluateAndApply(
                    triggerReason = if (desc.isNotBlank()) "EXACT_ALARM:$desc" else "EXACT_ALARM_BOUNDARY"
                )
                app.container.exactAlarmScheduler.rescheduleNext("AFTER_ALARM_TRIGGER")
                app.container.repository.pruneExpiredAuditLogs()
            } finally {
                try {
                    if (wakeLock?.isHeld == true) {
                        wakeLock.release()
                    }
                } catch (_: Throwable) {
                }
                pendingResult.finish()
            }
        }
    }
}
