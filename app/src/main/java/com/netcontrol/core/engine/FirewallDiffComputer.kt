package com.netcontrol.core.engine

data class PackageStateChange(
    val packageName: String,
    val uid: Int,
    val blocked: Boolean
)

data class FirewallDiffPlan(
    val changes: List<PackageStateChange>,
    val isFullSync: Boolean
) {
    val isEmpty: Boolean get() = changes.isEmpty()
}

/**
 * 纯 Kotlin 增量比对器：对比上次已成功下发的包状态表与本次裁决结果，
 * 仅产出状态真正发生变化的项，将 Shizuku IPC 指令量与耗电降至最低。
 */
object FirewallDiffComputer {

    fun compute(
        previousApplied: Map<String, Boolean>,
        newDecisions: Map<String, AppDecision>,
        forceFullSync: Boolean
    ): FirewallDiffPlan {
        if (forceFullSync || previousApplied.isEmpty()) {
            // 仅下发目标为阻断的包，以及此前已阻断、本次需要显式恢复放行的包，避免遍历数百个默认放行的系统包
            val changes = newDecisions.values.mapNotNull { decision ->
                val wasBlocked = previousApplied[decision.packageName] == true
                when {
                    decision.blocked -> PackageStateChange(decision.packageName, decision.uid, true)
                    wasBlocked -> PackageStateChange(decision.packageName, decision.uid, false)
                    else -> null
                }
            }
            return FirewallDiffPlan(changes = changes, isFullSync = true)
        }

        val delta = mutableListOf<PackageStateChange>()
        for ((pkg, decision) in newDecisions) {
            val prev = previousApplied[pkg]
            if (prev == null) {
                // 新装应用仅在目标为阻断时才需下发（默认底层为放行）
                if (decision.blocked) {
                    delta.add(PackageStateChange(pkg, decision.uid, true))
                }
            } else if (prev != decision.blocked) {
                delta.add(PackageStateChange(pkg, decision.uid, decision.blocked))
            }
        }
        return FirewallDiffPlan(changes = delta, isFullSync = false)
    }
}
