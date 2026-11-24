package com.netcontrol.core.backend.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import com.netcontrol.core.backend.BackendType
import com.netcontrol.core.backend.FirewallApplyResult
import com.netcontrol.core.backend.FirewallBackend
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * 备用引擎适配器：封装黑洞 VPN 权限检查、第三方 VPN 冲突检测与全量阻断集合下发。
 */
class BlackholeVpnBackend(private val context: Context) : FirewallBackend {

    override val type: BackendType = BackendType.BLACKHOLE_VPN

    override val isReady: StateFlow<Boolean> = BlackholeVpnService.isServiceRunning

    val activeBlockedPackages: StateFlow<Set<String>> = BlackholeVpnService.activeBlockedPackages
    val statusMessage: StateFlow<String> = BlackholeVpnService.lastStatusMessage

    private val trackedBlocked = ConcurrentHashMap.newKeySet<String>()

    /**
     * 检查系统当前是否需要先弹出 VPN 授权弹窗。返回 null 表示已授权。
     */
    fun prepareVpnIntent(): Intent? = VpnService.prepare(context)

    /**
     * 检测是否有其他第三方 VPN（如 Clash/v2ray）正在运行，避免静默抢占。
     */
    fun isAnotherVpnActive(): Boolean {
        if (BlackholeVpnService.isServiceRunning.value) return false
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    fun startService(initialBlocked: Set<String> = trackedBlocked.toSet()) {
        trackedBlocked.clear()
        trackedBlocked.addAll(initialBlocked)
        BlackholeVpnService.startOrUpdate(context, trackedBlocked.toSet())
    }

    fun stopService() {
        trackedBlocked.clear()
        BlackholeVpnService.stop(context)
    }

    override suspend fun setAppBlocked(
        packageName: String,
        uid: Int,
        blocked: Boolean
    ): FirewallApplyResult {
        if (VpnService.prepare(context) != null) {
            return FirewallApplyResult(
                success = false,
                backendType = BackendType.BLACKHOLE_VPN,
                driverName = "blackhole_vpn",
                appliedCount = 0,
                message = "尚未授予系统 VPN 权限"
            )
        }
        if (blocked) {
            trackedBlocked.add(packageName)
        } else {
            trackedBlocked.remove(packageName)
        }
        BlackholeVpnService.startOrUpdate(context, trackedBlocked.toSet())
        return FirewallApplyResult(
            success = true,
            backendType = BackendType.BLACKHOLE_VPN,
            driverName = "blackhole_vpn",
            appliedCount = trackedBlocked.size,
            message = "${if (blocked) "已拉入黑洞" else "已移出黑洞"} $packageName（当前共 ${trackedBlocked.size} 项）"
        )
    }

    override suspend fun syncBlockedApps(
        blockedPackages: Set<String>,
        allKnownPackages: Map<String, Int>
    ): FirewallApplyResult {
        if (VpnService.prepare(context) != null) {
            return FirewallApplyResult(
                success = false,
                backendType = BackendType.BLACKHOLE_VPN,
                driverName = "blackhole_vpn",
                appliedCount = 0,
                message = "尚未授予系统 VPN 权限"
            )
        }
        trackedBlocked.clear()
        trackedBlocked.addAll(blockedPackages)
        BlackholeVpnService.startOrUpdate(context, blockedPackages)
        return FirewallApplyResult(
            success = true,
            backendType = BackendType.BLACKHOLE_VPN,
            driverName = "blackhole_vpn",
            appliedCount = blockedPackages.size,
            message = "黑洞 VPN 路由已更新：隔离 ${blockedPackages.size} 个应用"
        )
    }

    override suspend fun resetAll(allKnownPackages: Map<String, Int>): FirewallApplyResult {
        stopService()
        return FirewallApplyResult(
            success = true,
            backendType = BackendType.BLACKHOLE_VPN,
            driverName = "blackhole_vpn",
            appliedCount = 0,
            message = "黑洞 VPN 已关闭，所有应用恢复直连"
        )
    }
}
