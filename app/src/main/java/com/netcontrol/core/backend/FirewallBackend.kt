package com.netcontrol.core.backend

import kotlinx.coroutines.flow.StateFlow

enum class BackendType {
    SHIZUKU,
    BLACKHOLE_VPN
}

data class FirewallApplyResult(
    val success: Boolean,
    val backendType: BackendType,
    val driverName: String,
    val appliedCount: Int,
    val message: String
)

data class WifiShellInfo(
    val connected: Boolean,
    val ssid: String?,
    val bssid: String?,
    val rawSnippet: String
)

data class DiagnosticProbeReport(
    val shellUid: Int,
    val androidRelease: String,
    val sdkInt: Int,
    val displayId: String,
    val chain3Supported: Boolean,
    val chain3Detail: String,
    val netPolicySupported: Boolean,
    val netPolicyDetail: String,
    val wifiInfo: WifiShellInfo,
    val recommendedDriver: String
)

/**
 * 统一的可插拔防火墙后端接口。
 * 上层规则引擎仅面向此接口编程，彻底解耦 Shizuku 内核防火墙与黑洞 VPN 兜底实现。
 */
interface FirewallBackend {
    val type: BackendType
    val isReady: StateFlow<Boolean>

    /**
     * 单个应用断网/放行（供 Shizuku 毫秒级开屏断网或单点手动开关调用）。
     */
    suspend fun setAppBlocked(packageName: String, uid: Int, blocked: Boolean): FirewallApplyResult

    /**
     * 全量应用目标阻断集合同步（供 Diff 增量同步、重连全量重放及黑洞 VPN 重建路由调用）。
     */
    suspend fun syncBlockedApps(
        blockedPackages: Set<String>,
        allKnownPackages: Map<String, Int>
    ): FirewallApplyResult

    /**
     * 撤销本后端施加的全部拦截（用于切换引擎或全局关闭防火墙）。
     */
    suspend fun resetAll(allKnownPackages: Map<String, Int>): FirewallApplyResult
}
