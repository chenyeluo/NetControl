package com.netcontrol.core.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.netcontrol.NetControlApp
import com.netcontrol.core.backend.BackendType
import com.netcontrol.core.notification.FirewallNotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 监听系统开机、手动改时间、切时区与本应用覆盖升级事件：
 * 自动重算当前网络与规则状态、重建单点链式闹钟；
 * 若设备刚重启且 Shizuku 尚未激活，弹出低打扰通知引导一键激活或切换黑洞 VPN 兜底。
 */
class SystemEventReceiver : BroadcastReceiver() {

    companion object {
        private const val WAKE_LOCK_TAG = "NetControl:SystemEventRecovery"
        private const val WAKE_LOCK_TIMEOUT_MS = 8_000L
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val app = context.applicationContext as? NetControlApp ?: return
        val pendingResult = goAsync()

        val pm = context.getSystemService(PowerManager::class.java)
        val wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val container = app.container
                container.networkStateMonitor.refreshNow("SYSTEM_EVENT:$action")
                container.ruleEngine.evaluateAndApply(
                    triggerReason = "SYSTEM_EVENT:$action",
                    forceFullSync = true
                )
                container.exactAlarmScheduler.rescheduleNext("SYSTEM_EVENT:$action")

                if (action == Intent.ACTION_BOOT_COMPLETED) {
                    val settings = container.settingsDataStore.getSnapshot()
                    if (settings.firewallEnabled &&
                        settings.preferredBackend == BackendType.SHIZUKU &&
                        !container.shizukuManager.isReady.value
                    ) {
                        FirewallNotificationHelper.showShizukuDisconnectedAlert(
                            context = context,
                            title = "手机已重启，Shizuku 尚未激活",
                            content = "点击打开 Shizuku 激活，或一键切换黑洞 VPN 临时兜底"
                        )
                    }
                }
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
