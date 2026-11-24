package com.netcontrol.core.engine

import android.content.Context
import com.netcontrol.core.alarm.ExactAlarmScheduler
import com.netcontrol.core.backend.BackendType
import com.netcontrol.core.backend.FirewallApplyResult
import com.netcontrol.core.backend.FirewallCoordinator
import com.netcontrol.core.backend.shizuku.ShizukuManager
import com.netcontrol.core.network.NetworkStateMonitor
import com.netcontrol.core.network.ResolvedNetworkContext
import com.netcontrol.core.notification.FirewallNotificationHelper
import com.netcontrol.data.local.AppDatabase
import com.netcontrol.data.local.entity.FirewallAuditLogEntity
import com.netcontrol.data.prefs.GlobalEmergencyMode
import com.netcontrol.data.prefs.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import kotlin.system.measureTimeMillis

data class RuleRunSummary(
    val timestampMs: Long,
    val triggerReason: String,
    val firewallEnabled: Boolean,
    val networkName: String,
    val blockedAppCount: Int,
    val dispatchedDeltaCount: Int,
    val sharedUidAffectedCount: Int,
    val applyResult: FirewallApplyResult,
    val durationMs: Long
)

/**
 * 防火墙核心编排引擎：
 * 响应四大事件源（网络切换、闹钟到点、开屏切换、用户配置变更），串行执行 P0~P5 裁决、
 * 通过 FirewallCoordinator 执行 Diff 增量下发，并将实际变更写入 7 天审计日志。
 */
class RuleEngine(
    context: Context,
    private val appScope: CoroutineScope,
    private val database: AppDatabase,
    private val settingsDataStore: SettingsDataStore,
    private val networkStateMonitor: NetworkStateMonitor,
    private val shizukuManager: ShizukuManager,
    private val coordinator: FirewallCoordinator,
    private val exactAlarmScheduler: ExactAlarmScheduler
) {
    private val appContext = context.applicationContext
    private val selfPackageName = context.packageName
    private val evalMutex = Mutex()

    private val _activeSplashBlockedPackages = MutableStateFlow<Set<String>>(emptySet())
    val activeSplashBlockedPackages: StateFlow<Set<String>> = _activeSplashBlockedPackages.asStateFlow()

    private val _latestEvaluation = MutableStateFlow<EvaluationResult?>(null)
    val latestEvaluation: StateFlow<EvaluationResult?> = _latestEvaluation.asStateFlow()

    private val _lastRunSummary = MutableStateFlow<RuleRunSummary?>(null)
    val lastRunSummary: StateFlow<RuleRunSummary?> = _lastRunSummary.asStateFlow()

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true

        networkStateMonitor.setOnContextChangedListener { _, reason ->
            evaluateAndApply(triggerReason = reason)
        }

        // 监听 Shizuku 重连/断连事件：重连时撤销提醒并全量重放规则；运行中断连时弹出兜底通知
        appScope.launch {
            var previouslyReady = false
            shizukuManager.isReady.collect { ready ->
                if (ready && !previouslyReady) {
                    FirewallNotificationHelper.cancelShizukuDisconnectedAlert(appContext)
                    val settings = settingsDataStore.getSnapshot()
                    shizukuManager.switchDriver(settings.shizukuDriverName)
                    if (settings.firewallEnabled && settings.preferredBackend == BackendType.SHIZUKU) {
                        coordinator.clearDiffCache()
                        evaluateAndApply(triggerReason = "SHIZUKU_RECONNECTED", forceFullSync = true)
                    }
                } else if (!ready && previouslyReady) {
                    val settings = settingsDataStore.getSnapshot()
                    if (settings.firewallEnabled && settings.preferredBackend == BackendType.SHIZUKU) {
                        FirewallNotificationHelper.showShizukuDisconnectedAlert(appContext)
                    }
                }
                previouslyReady = ready
            }
        }
    }

    suspend fun setFirewallEnabled(enabled: Boolean): RuleRunSummary {
        settingsDataStore.setFirewallEnabled(enabled)
        val summary = evaluateAndApply(
            triggerReason = if (enabled) "USER_ENABLE_FIREWALL" else "USER_DISABLE_FIREWALL",
            forceFullSync = enabled
        )
        exactAlarmScheduler.rescheduleNext(if (enabled) "FIREWALL_ENABLED" else "FIREWALL_DISABLED")
        return summary
    }

    suspend fun switchPreferredBackend(target: BackendType): RuleRunSummary {
        settingsDataStore.setPreferredBackend(target)
        return evaluateAndApply(
            triggerReason = "USER_SWITCH_BACKEND_${target.name}",
            forceFullSync = true
        )
    }

    suspend fun setGlobalEmergencyMode(mode: GlobalEmergencyMode): RuleRunSummary {
        settingsDataStore.setGlobalEmergencyMode(mode)
        return evaluateAndApply(triggerReason = "USER_EMERGENCY_${mode.name}")
    }

    /**
     * 规则/分组/临时规则变更后的统一联动入口：先即时裁决下发，再刷新单点链式闹钟。
     */
    suspend fun notifyRulesChanged(reason: String): RuleRunSummary {
        val summary = evaluateAndApply(triggerReason = reason)
        exactAlarmScheduler.rescheduleNext(reason)
        return summary
    }

    /**
     * 一键急救：原子关闭内核 chain3 防火墙链、停止黑洞 VPN、重置全局紧急模式与总开关，
     * 并清空全量已装应用在 chain3 上的残留 deny 位。
     */
    suspend fun emergencyRestoreAllNetwork(): RuleRunSummary = evalMutex.withLock {
        withContext(Dispatchers.IO) {
            val nowMs = System.currentTimeMillis()
            settingsDataStore.setGlobalEmergencyMode(GlobalEmergencyMode.NORMAL)
            settingsDataStore.setFirewallEnabled(false)
            val allInstalled = database.appDao().getInstalledAppsOnce()
                .associate { it.packageName to it.uid }
            val result = coordinator.disableAll(fallbackPackagesToClear = allInstalled)
            exactAlarmScheduler.cancelPendingAlarm()
            _latestEvaluation.value = null
            val summary = RuleRunSummary(
                timestampMs = nowMs,
                triggerReason = "EMERGENCY_RESTORE_ALL",
                firewallEnabled = false,
                networkName = networkStateMonitor.currentContext.value.profileName,
                blockedAppCount = 0,
                dispatchedDeltaCount = 0,
                sharedUidAffectedCount = 0,
                applyResult = result,
                durationMs = System.currentTimeMillis() - nowMs
            )
            _lastRunSummary.value = summary
            summary
        }
    }

    suspend fun setSplashBlocked(packageName: String, blocked: Boolean): RuleRunSummary {
        val updated = if (blocked) {
            _activeSplashBlockedPackages.value + packageName
        } else {
            _activeSplashBlockedPackages.value - packageName
        }
        _activeSplashBlockedPackages.value = updated
        return evaluateAndApply(
            triggerReason = if (blocked) "SPLASH_BLOCK_START:$packageName" else "SPLASH_BLOCK_END:$packageName"
        )
    }

    /**
     * 开屏断网极速直连通道（<15ms）：
     * 跳过全库 6 表查询与 450 应用遍历，直接通过 Shizuku UserService 单包下发内核阻断；
     * 即使主防火墙总开关未打开，只要 Shizuku 已连接且开启了开屏跳过总开关也能独立生效并在到期后无损恢复。
     */
    suspend fun triggerSplashFastBlock(packageName: String, uid: Int): Boolean = withContext(Dispatchers.IO) {
        _activeSplashBlockedPackages.value = _activeSplashBlockedPackages.value + packageName
        val settings = settingsDataStore.getSnapshot()
        if (shizukuManager.isReady.value && settings.preferredBackend == BackendType.SHIZUKU) {
            val res = shizukuManager.setAppBlocked(packageName, uid, blocked = true)
            database.auditLogDao().insert(
                FirewallAuditLogEntity(
                    timestamp = System.currentTimeMillis(),
                    packageName = packageName,
                    uid = uid,
                    blocked = true,
                    reason = "SPLASH_AD_BLOCK_FAST | ${res.message}",
                    networkProfileId = networkStateMonitor.currentContext.value.profileId,
                    backendType = BackendType.SHIZUKU.name
                )
            )
            return@withContext res.success
        }
        if (settings.firewallEnabled) {
            evaluateAndApply("SPLASH_BLOCK_START:$packageName").applyResult.success
        } else {
            false
        }
    }

    suspend fun releaseSplashFastBlock(packageName: String, uid: Int) = withContext(Dispatchers.IO) {
        _activeSplashBlockedPackages.value = _activeSplashBlockedPackages.value - packageName
        val settings = settingsDataStore.getSnapshot()
        if (!settings.firewallEnabled) {
            if (shizukuManager.isReady.value) {
                shizukuManager.setAppBlocked(packageName, uid, blocked = false)
                database.auditLogDao().insert(
                    FirewallAuditLogEntity(
                        timestamp = System.currentTimeMillis(),
                        packageName = packageName,
                        uid = uid,
                        blocked = false,
                        reason = "SPLASH_AD_RELEASE_FAST",
                        networkProfileId = networkStateMonitor.currentContext.value.profileId,
                        backendType = BackendType.SHIZUKU.name
                    )
                )
            }
            return@withContext
        }
        // 主防火墙开启时，由 RuleEngine 重新裁决应有状态（若规则要求继续断网则保持，否则恢复放行）
        val eval = evaluateAndApply("SPLASH_BLOCK_END:$packageName")
        val shouldStillBlock = eval.applyResult.success &&
            (_latestEvaluation.value?.decisions?.get(packageName)?.blocked == true)
        if (!shouldStillBlock && shizukuManager.isReady.value && settings.preferredBackend == BackendType.SHIZUKU) {
            // 双保险：确保直连通道加上的 deny 位一定被清除
            shizukuManager.setAppBlocked(packageName, uid, blocked = false)
        }
    }

    suspend fun evaluateAndApply(
        triggerReason: String,
        forceFullSync: Boolean = false
    ): RuleRunSummary = evalMutex.withLock {
        withContext(Dispatchers.IO) {
            val nowMs = System.currentTimeMillis()
            val settings = settingsDataStore.getSnapshot()
            val network: ResolvedNetworkContext = networkStateMonitor.currentContext.value

            var summary: RuleRunSummary? = null
            val elapsed = measureTimeMillis {
                if (!settings.firewallEnabled) {
                    val disableResult = coordinator.disableAll()
                    _latestEvaluation.value = null
                    summary = RuleRunSummary(
                        timestampMs = nowMs,
                        triggerReason = triggerReason,
                        firewallEnabled = false,
                        networkName = network.profileName,
                        blockedAppCount = 0,
                        dispatchedDeltaCount = 0,
                        sharedUidAffectedCount = 0,
                        applyResult = disableResult,
                        durationMs = 0L
                    )
                    return@measureTimeMillis
                }

                // 清理已过期临时规则并一次性读取快照
                database.temporaryRuleDao().deleteExpiredRules(nowMs)

                val apps = database.appDao().getInstalledAppsOnce()
                val groups = database.groupDao().getAllGroupsOnce()
                val crossRefs = database.groupDao().getAllCrossRefsOnce()
                val policies = database.groupDao().getAllPoliciesOnce()
                val schedules = database.scheduleRuleDao().getEnabledRulesOnce()
                val tempRules = database.temporaryRuleDao().getActiveRulesOnce(nowMs)

                val nowZoned = ZonedDateTime.now()
                val dayIndex = RuleEvaluator.dayOfWeekToIndex(nowZoned.dayOfWeek)
                val minuteOfDay = nowZoned.hour * 60 + nowZoned.minute

                val evaluation = RuleEvaluator.evaluate(
                    EvaluationInput(
                        selfPackageName = selfPackageName,
                        apps = apps,
                        groups = groups,
                        crossRefs = crossRefs,
                        groupPolicies = policies,
                        scheduleRules = schedules,
                        temporaryRules = tempRules,
                        activeSplashBlockedPackages = _activeSplashBlockedPackages.value,
                        network = network,
                        settings = settings,
                        nowEpochMs = nowMs,
                        dayOfWeekIndex = dayIndex,
                        minuteOfDay = minuteOfDay
                    )
                )
                _latestEvaluation.value = evaluation

                val dispatch = coordinator.applyEvaluation(
                    targetBackend = settings.preferredBackend,
                    evaluation = evaluation,
                    forceFullSync = forceFullSync
                )

                // 将实际下发的状态变更写入 7 天审计日志表
                if (dispatch.dispatchedChanges.isNotEmpty()) {
                    val logs = dispatch.dispatchedChanges.map { change ->
                        val decision = evaluation.decisions[change.packageName]
                        val reasonTag = decision?.let {
                            if (it.matchedDetail.isNotBlank()) "${it.reason.name}(${it.matchedDetail})" else it.reason.name
                        } ?: triggerReason
                        FirewallAuditLogEntity(
                            timestamp = nowMs,
                            packageName = change.packageName,
                            uid = change.uid,
                            blocked = change.blocked,
                            reason = "$reasonTag | via $triggerReason",
                            networkProfileId = network.profileId,
                            backendType = settings.preferredBackend.name
                        )
                    }
                    database.auditLogDao().insertAll(logs)
                }

                summary = RuleRunSummary(
                    timestampMs = nowMs,
                    triggerReason = triggerReason,
                    firewallEnabled = true,
                    networkName = network.profileName,
                    blockedAppCount = evaluation.blockedPackages.size,
                    dispatchedDeltaCount = dispatch.dispatchedChanges.size,
                    sharedUidAffectedCount = evaluation.sharedUidAffectedUids.size,
                    applyResult = dispatch.result,
                    durationMs = 0L
                )
            }

            val finalSummary = summary!!.copy(durationMs = elapsed)
            _lastRunSummary.value = finalSummary
            finalSummary
        }
    }
}
