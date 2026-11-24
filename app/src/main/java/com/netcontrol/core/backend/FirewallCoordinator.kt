package com.netcontrol.core.backend

import com.netcontrol.core.backend.shizuku.ShizukuManager
import com.netcontrol.core.backend.vpn.BlackholeVpnBackend
import com.netcontrol.core.engine.EvaluationResult
import com.netcontrol.core.engine.FirewallDiffComputer
import com.netcontrol.core.engine.PackageStateChange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CoordinatorDispatchOutcome(
    val result: FirewallApplyResult,
    val dispatchedChanges: List<PackageStateChange>
)

/**
 * 双引擎协调器：
 * 1. 维护当前激活后端（Shizuku 内核防火墙 / 轻量黑洞 VPN）与 Diff 增量状态缓存；
 * 2. 切换后端时自动清空旧后端拦截并重置缓存，确保不残留幽灵规则；
 * 3. Shizuku 仅下发真正发生变化的包列表；黑洞 VPN 仅在阻断集合变化时才重建 TUN。
 */
class FirewallCoordinator(
    private val shizukuManager: ShizukuManager,
    private val blackholeVpnBackend: BlackholeVpnBackend
) {
    private val _activeBackend = MutableStateFlow<BackendType?>(null)
    val activeBackend: StateFlow<BackendType?> = _activeBackend.asStateFlow()

    private val _lastApplyResult = MutableStateFlow<FirewallApplyResult?>(null)
    val lastApplyResult: StateFlow<FirewallApplyResult?> = _lastApplyResult.asStateFlow()

    // 仅在 Coordinator 串行互斥区内访问
    private val shizukuAppliedStates = LinkedHashMap<String, Boolean>()
    private val shizukuKnownUids = LinkedHashMap<String, Int>()
    private var lastVpnBlockedSet: Set<String>? = null

    fun clearDiffCache() {
        shizukuAppliedStates.clear()
        lastVpnBlockedSet = null
    }

    suspend fun applyEvaluation(
        targetBackend: BackendType,
        evaluation: EvaluationResult,
        forceFullSync: Boolean
    ): CoordinatorDispatchOutcome {
        // 如果正在热切换引擎，先撤销旧引擎的拦截
        val current = _activeBackend.value
        if (current != null && current != targetBackend) {
            resetBackendInternal(current)
            clearDiffCache()
        }

        val outcome = when (targetBackend) {
            BackendType.SHIZUKU -> applyToShizuku(evaluation, forceFullSync || current != targetBackend)
            BackendType.BLACKHOLE_VPN -> applyToBlackholeVpn(evaluation, forceFullSync || current != targetBackend)
        }

        if (outcome.result.success) {
            _activeBackend.value = targetBackend
        }
        _lastApplyResult.value = outcome.result
        return outcome
    }

    suspend fun disableAll(fallbackPackagesToClear: Map<String, Int> = emptyMap()): FirewallApplyResult {
        val current = _activeBackend.value
        // 双保险：无论内存缓存状态如何，只要 Shizuku 已连接就原子关闭 chain3 并清理已知阻断项；
        // 同时若黑洞 VPN 在运行也一并停止，确保绝不残留阻断。
        val shizukuRes = if (shizukuManager.isReady.value) {
            val blockedMap = shizukuAppliedStates
                .filterValues { it }
                .keys
                .associateWith { shizukuKnownUids[it] ?: -1 }
                .ifEmpty { fallbackPackagesToClear }
            shizukuManager.resetAll(blockedMap)
        } else {
            null
        }
        val vpnRes = if (blackholeVpnBackend.isReady.value || current == BackendType.BLACKHOLE_VPN) {
            blackholeVpnBackend.resetAll(emptyMap())
        } else {
            null
        }

        val result = shizukuRes ?: vpnRes ?: FirewallApplyResult(
            success = true,
            backendType = BackendType.SHIZUKU,
            driverName = "idle",
            appliedCount = 0,
            message = "防火墙已关闭"
        )
        _activeBackend.value = null
        clearDiffCache()
        _lastApplyResult.value = result
        return result
    }

    private suspend fun applyToShizuku(
        evaluation: EvaluationResult,
        forceFullSync: Boolean
    ): CoordinatorDispatchOutcome {
        if (!shizukuManager.isReady.value) {
            return CoordinatorDispatchOutcome(
                result = FirewallApplyResult(
                    success = false,
                    backendType = BackendType.SHIZUKU,
                    driverName = "disconnected",
                    appliedCount = 0,
                    message = "Shizuku 未连接，无法下发内核规则（可一键切换至黑洞 VPN 兜底）"
                ),
                dispatchedChanges = emptyList()
            )
        }

        val diffPlan = FirewallDiffComputer.compute(
            previousApplied = shizukuAppliedStates,
            newDecisions = evaluation.decisions,
            forceFullSync = forceFullSync
        )

        if (diffPlan.isEmpty) {
            // 同步更新内存基线，并自愈确保内核 chain3 链处于开启态（防止系统或 One UI 静默关闭）
            if (evaluation.blockedPackages.isNotEmpty()) {
                shizukuManager.ensureFirewallChainActive()
            }
            evaluation.decisions.forEach { (pkg, decision) ->
                shizukuAppliedStates[pkg] = decision.blocked
                shizukuKnownUids[pkg] = decision.uid
            }
            return CoordinatorDispatchOutcome(
                result = FirewallApplyResult(
                    success = true,
                    backendType = BackendType.SHIZUKU,
                    driverName = "cached",
                    appliedCount = 0,
                    message = "Shizuku 状态已是最新（0 项变更）"
                ),
                dispatchedChanges = emptyList()
            )
        }

        val packages = diffPlan.changes.map { it.packageName }
        val uids = diffPlan.changes.map { it.uid }
        val flags = diffPlan.changes.map { it.blocked }
        val res = shizukuManager.applyDeltaChanges(packages, uids, flags)

        if (res.success) {
            evaluation.decisions.forEach { (pkg, decision) ->
                shizukuAppliedStates[pkg] = decision.blocked
                shizukuKnownUids[pkg] = decision.uid
            }
        }
        return CoordinatorDispatchOutcome(result = res, dispatchedChanges = if (res.success) diffPlan.changes else emptyList())
    }

    private suspend fun applyToBlackholeVpn(
        evaluation: EvaluationResult,
        forceFullSync: Boolean
    ): CoordinatorDispatchOutcome {
        val targetBlocked = evaluation.blockedPackages
        val prev = lastVpnBlockedSet
        if (!forceFullSync && prev != null && prev == targetBlocked && blackholeVpnBackend.isReady.value) {
            return CoordinatorDispatchOutcome(
                result = FirewallApplyResult(
                    success = true,
                    backendType = BackendType.BLACKHOLE_VPN,
                    driverName = "blackhole_vpn",
                    appliedCount = 0,
                    message = "黑洞 VPN 隔离列表未变化（${targetBlocked.size} 项）"
                ),
                dispatchedChanges = emptyList()
            )
        }

        val knownMap = evaluation.decisions.mapValues { it.value.uid }
        val res = blackholeVpnBackend.syncBlockedApps(targetBlocked, knownMap)
        val changes = if (res.success) {
            val prevSet = prev ?: emptySet()
            val added = (targetBlocked - prevSet).mapNotNull { pkg ->
                evaluation.decisions[pkg]?.let { PackageStateChange(pkg, it.uid, true) }
            }
            val removed = (prevSet - targetBlocked).mapNotNull { pkg ->
                evaluation.decisions[pkg]?.let { PackageStateChange(pkg, it.uid, false) }
            }
            lastVpnBlockedSet = targetBlocked
            added + removed
        } else {
            emptyList()
        }
        return CoordinatorDispatchOutcome(result = res, dispatchedChanges = changes)
    }

    private suspend fun resetBackendInternal(backend: BackendType): FirewallApplyResult {
        return when (backend) {
            BackendType.SHIZUKU -> {
                val blockedMap = shizukuAppliedStates
                    .filterValues { it }
                    .keys
                    .associateWith { shizukuKnownUids[it] ?: -1 }
                // 即使 blockedMap 为空也调用 resetAll，确保内核 chain3 被原子关闭
                shizukuManager.resetAll(blockedMap)
            }
            BackendType.BLACKHOLE_VPN -> blackholeVpnBackend.resetAll(emptyMap())
        }
    }
}
