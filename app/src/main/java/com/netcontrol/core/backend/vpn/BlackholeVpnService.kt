package com.netcontrol.core.backend.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.netcontrol.MainActivity
import com.netcontrol.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream

/**
 * 备用引擎：轻量级「反向黑洞」本地 VPN。
 *
 * 核心设计原则：
 * 1. 只通过 Builder.addAllowedApplication(pkg) 将「被禁止联网的 App」引入 TUN 隧道；
 * 2. 所有允许联网的 App 完全不经过本 VPN，直接走系统原生网络栈（零转发开销、极低功耗）；
 * 3. 进入 TUN 的数据包在后台读取后立即丢弃，不解析、不转发；
 * 4. 当阻断集合为空时，绝不建立“全机路由”的空允许列表 TUN，而是关闭 TUN 保持待机，避免全机断网。
 */
class BlackholeVpnService : VpnService() {

    companion object {
        const val ACTION_START_OR_UPDATE = "com.netcontrol.vpn.ACTION_START_OR_UPDATE"
        const val ACTION_STOP = "com.netcontrol.vpn.ACTION_STOP"
        const val EXTRA_BLOCKED_PACKAGES = "extra_blocked_packages"

        private const val CHANNEL_ID = "netcontrol_vpn_status"
        private const val NOTIFICATION_ID = 2001

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        private val _activeBlockedPackages = MutableStateFlow<Set<String>>(emptySet())
        val activeBlockedPackages: StateFlow<Set<String>> = _activeBlockedPackages.asStateFlow()

        private val _lastStatusMessage = MutableStateFlow("未启动")
        val lastStatusMessage: StateFlow<String> = _lastStatusMessage.asStateFlow()

        fun startOrUpdate(context: Context, blockedPackages: Set<String>) {
            val intent = Intent(context, BlackholeVpnService::class.java).apply {
                action = ACTION_START_OR_UPDATE
                putStringArrayListExtra(EXTRA_BLOCKED_PACKAGES, ArrayList(blockedPackages))
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, BlackholeVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunInterface: ParcelFileDescriptor? = null
    private var drainJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                tearDownTun()
                _isServiceRunning.value = false
                _activeBlockedPackages.value = emptySet()
                _lastStatusMessage.value = "黑洞 VPN 已停止"
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START_OR_UPDATE, null -> {
                val blocked = intent?.getStringArrayListExtra(EXTRA_BLOCKED_PACKAGES)
                    ?.filter { it.isNotBlank() && it != packageName }
                    ?.toSet()
                    ?: _activeBlockedPackages.value

                startForegroundCompat(blocked.size)
                _isServiceRunning.value = true
                rebuildTun(blocked)
                return START_STICKY
            }

            else -> return START_NOT_STICKY
        }
    }

    override fun onRevoke() {
        super.onRevoke()
        tearDownTun()
        _isServiceRunning.value = false
        _activeBlockedPackages.value = emptySet()
        _lastStatusMessage.value = "VPN 权限已被系统或其他代理软件接管"
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        tearDownTun()
        serviceScope.cancel()
        _isServiceRunning.value = false
        super.onDestroy()
    }

    private fun rebuildTun(blockedPackages: Set<String>) {
        // 关键保护：如果当前没有任何被阻断的应用，必须关闭 TUN。
        // 否则 VpnService.Builder 在未调用 addAllowedApplication 时会接管全机流量导致全机断网。
        if (blockedPackages.isEmpty()) {
            tearDownTun()
            _activeBlockedPackages.value = emptySet()
            _lastStatusMessage.value = "黑洞 VPN 待机中（0 个阻断应用，全机直连）"
            return
        }

        val builder = Builder()
            .setSession("NetControl Blackhole")
            .setMtu(1500)
            .addAddress("10.215.173.1", 30)
            .addRoute("0.0.0.0", 0)
            .addAddress("fd00:215:173::1", 126)
            .addRoute("::", 0)
            .setBlocking(true)

        var addedCount = 0
        for (pkg in blockedPackages) {
            try {
                builder.addAllowedApplication(pkg)
                addedCount++
            } catch (_: PackageManager.NameNotFoundException) {
                // 跳过已卸载或不可见的包名
            }
        }

        if (addedCount == 0) {
            tearDownTun()
            _activeBlockedPackages.value = emptySet()
            _lastStatusMessage.value = "无有效可拦截包名，保持待机"
            return
        }

        val newTun = try {
            builder.establish()
        } catch (t: Throwable) {
            _lastStatusMessage.value = "建立黑洞 TUN 失败: ${t.message}"
            null
        }

        if (newTun == null) {
            _lastStatusMessage.value = "建立黑洞 TUN 返回空（可能尚未授予 VPN 权限）"
            return
        }

        val oldTun = tunInterface
        drainJob?.cancel()
        tunInterface = newTun
        oldTun?.closeQuietly()

        _activeBlockedPackages.value = blockedPackages
        _lastStatusMessage.value = "黑洞 VPN 运行中（已隔离 $addedCount 个应用，其余直连）"

        // 轻量黑洞丢包协程：复用单块缓冲区读取并直接丢弃，不做任何协议解析或转发
        drainJob = serviceScope.launch {
            val buffer = ByteArray(32768)
            try {
                FileInputStream(newTun.fileDescriptor).use { input ->
                    while (isActive) {
                        val read = input.read(buffer)
                        if (read < 0) break
                    }
                }
            } catch (_: Throwable) {
                // TUN 关闭时正常退出
            }
        }
    }

    private fun tearDownTun() {
        drainJob?.cancel()
        drainJob = null
        tunInterface?.closeQuietly()
        tunInterface = null
    }

    private fun startForegroundCompat(blockedCount: Int) {
        val notification = buildNotification(blockedCount)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(blockedCount: Int): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val contentText = if (blockedCount > 0) {
            "黑洞模式已隔离 $blockedCount 个应用，其余应用直连"
        } else {
            "黑洞模式待机中（当前无阻断应用）"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.vpn_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.vpn_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun ParcelFileDescriptor.closeQuietly() {
        try {
            close()
        } catch (_: Throwable) {
        }
    }
}
