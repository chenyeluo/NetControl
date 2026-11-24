package com.netcontrol.core.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.netcontrol.MainActivity

/**
 * 统一系统低打扰告警通知管理器：
 * - 手机重启或 Shizuku Binder 断连时，弹出附带「打开 Shizuku」与「切换黑洞 VPN 兜底」双快捷动作的通知；
 * - 一旦 Shizuku 重连成功，立即自动撤销该通知。
 */
object FirewallNotificationHelper {

    const val EXTRA_REQUEST_BLACKHOLE_FALLBACK = "extra_request_blackhole_fallback"
    private const val CHANNEL_ALERTS = "netcontrol_system_alerts"
    private const val NOTIFICATION_SHIZUKU_DISCONNECTED = 2002

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun showShizukuDisconnectedAlert(
        context: Context,
        title: String = "Shizuku 已断开，防火墙暂停保护",
        content: String = "可点此激活 Shizuku，或一键切换至黑洞 VPN 兜底保护"
    ) {
        if (!hasNotificationPermission(context)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        ensureAlertChannel(nm)

        val openAppPi = PendingIntent.getActivity(
            context,
            NOTIFICATION_SHIZUKU_DISCONNECTED,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val fallbackVpnPi = PendingIntent.getActivity(
            context,
            NOTIFICATION_SHIZUKU_DISCONNECTED + 1,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_REQUEST_BLACKHOLE_FALLBACK, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(openAppPi)
            .addAction(0, "切换黑洞 VPN 兜底", fallbackVpnPi)

        val shizukuLaunch = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (shizukuLaunch != null) {
            val openShizukuPi = PendingIntent.getActivity(
                context,
                NOTIFICATION_SHIZUKU_DISCONNECTED + 2,
                shizukuLaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "打开 Shizuku", openShizukuPi)
        }

        nm.notify(NOTIFICATION_SHIZUKU_DISCONNECTED, builder.build())
    }

    fun cancelShizukuDisconnectedAlert(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(NOTIFICATION_SHIZUKU_DISCONNECTED)
    }

    private fun ensureAlertChannel(nm: NotificationManager) {
        val channel = NotificationChannel(
            CHANNEL_ALERTS,
            "防火墙状态与安全提醒",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "手机重启、Shizuku 断连兜底提醒及签名变更告警"
        }
        nm.createNotificationChannel(channel)
    }
}
