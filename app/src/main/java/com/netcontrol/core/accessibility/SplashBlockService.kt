package com.netcontrol.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import com.netcontrol.NetControlApp
import com.netcontrol.core.gkd.runtime.AccessibilityNodeAdapter
import com.netcontrol.core.gkd.runtime.GkdActionExecutor
import com.netcontrol.core.gkd.runtime.GkdRuleRuntimeEngine
import com.netcontrol.core.gkd.snapshot.GkdSnapshotDocument
import com.netcontrol.core.gkd.subscription.CompiledGkdRegistry
import com.netcontrol.data.local.entity.GkdActionLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.measureTimeMillis

/**
 * NetControl v2.0 双擎合一无障碍服务：
 * 1. 毫秒级开屏断网（拦截在线广告请求，与 Shizuku 直连快通道协同）；
 * 2. 完整 GKD 跳过引擎（全量高级选择器 + fastQuery + 订阅规则 + 节点/手势坐标自动点击，搞定本地缓存开屏与 App 内弹窗）；
 * 3. 一键/倒计时屏幕节点快照抓拍（输出 GKD 兼容 JSON）。
 */
class SplashBlockService : AccessibilityService() {

    companion object {
        private const val IME_CACHE_TTL_MS = 60_000L
        private const val MAX_TRACE_SIZE = 30
        private const val CONTENT_THROTTLE_MS = 65L

        @Volatile
        private var instanceRef: WeakReference<SplashBlockService>? = null

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _recentTraces = MutableStateFlow<List<SplashTraceEntry>>(emptyList())
        val recentTraces: StateFlow<List<SplashTraceEntry>> = _recentTraces.asStateFlow()

        private val _currentTopInfo = MutableStateFlow<Pair<String, String>>("" to "")
        val currentTopInfo: StateFlow<Pair<String, String>> = _currentTopInfo.asStateFlow()

        fun clearTraces() {
            _recentTraces.value = emptyList()
        }

        fun isEnabledInSettings(context: Context): Boolean {
            val expected = ComponentName(context, SplashBlockService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }

        fun captureSnapshotNow(): GkdSnapshotDocument? {
            return instanceRef?.get()?.captureActiveWindowSnapshotInternal()
        }

        fun scheduleDelayedSnapshotCapture(delaySec: Int = 3, onCaptured: (GkdSnapshotDocument?) -> Unit) {
            val svc = instanceRef?.get()
            if (svc == null) {
                onCaptured(null)
                return
            }
            svc.serviceScope.launch(Dispatchers.Main) {
                delay(delaySec * 1000L)
                val doc = svc.captureActiveWindowSnapshotInternal()
                onCaptured(doc)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var stateMachine: SplashBlockStateMachine
    private val gkdEngine = GkdRuleRuntimeEngine()
    private val gkdEvaluating = AtomicBoolean(false)

    @Volatile
    private var enabledConfigs: Map<String, SplashAppConfig> = emptyMap()

    @Volatile
    private var systemAppPackages: Set<String> = emptySet()

    @Volatile
    private var globalSplashActive: Boolean = false

    @Volatile
    private var gkdEngineActive: Boolean = true

    @Volatile
    private var compiledGkd: CompiledGkdRegistry = CompiledGkdRegistry(emptyList(), emptyList(), emptyMap())

    @Volatile
    private var cachedImePackages: Set<String> = emptySet()

    @Volatile
    private var cachedHomeLaunchers: Set<String> = emptySet()

    @Volatile
    private var lastImeRefreshMs: Long = 0L

    @Volatile
    private var lastGkdScanMs: Long = 0L

    @Volatile
    private var currentForegroundPkg: String = ""

    @Volatile
    private var currentForegroundActivity: String = ""

    private val activeRecoveryJobs = ConcurrentHashMap<String, Pair<Int, Job>>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
        stateMachine = SplashBlockStateMachine(selfPackageName = packageName)
        _isConnected.value = true
        refreshImeAndLauncherCacheIfNeeded(System.currentTimeMillis())

        val app = applicationContext as? NetControlApp ?: return

        serviceScope.launch {
            app.container.repository.installedAppsFlow.collect { apps ->
                systemAppPackages = apps.asSequence().filter { it.isSystemApp }.map { it.packageName }.toSet()
                enabledConfigs = apps
                    .asSequence()
                    .filter { it.splashBlockEnabled && !it.isUninstalled }
                    .associate { entity ->
                        entity.packageName to SplashAppConfig(
                            packageName = entity.packageName,
                            uid = entity.uid,
                            launcherActivity = entity.launcherActivity,
                            durationMs = entity.splashDurationMs,
                            cooldownSec = entity.splashCooldownSec,
                            relaxActivityCheck = entity.splashRelaxActivityCheck
                        )
                    }
            }
        }

        serviceScope.launch {
            app.container.settingsDataStore.settingsFlow.collect { settings ->
                globalSplashActive = settings.splashBlockGlobalEnabled
                gkdEngineActive = settings.gkdEngineEnabled
            }
        }

        serviceScope.launch {
            app.container.gkdSubscriptionRepository.compiledRegistry.collect { reg ->
                compiledGkd = reg
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventType = event.eventType
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString()
        val now = System.currentTimeMillis()

        refreshImeAndLauncherCacheIfNeeded(now)

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // 1. 追踪前台包名与 Activity（过滤非 Activity 系统浮层）
            val isOverlay = pkg == "com.android.systemui" || pkg in cachedImePackages ||
                (cls != null && (cls.startsWith("android.widget.") || cls.startsWith("android.inputmethodservice.")))
            if (!isOverlay) {
                val activityName = if (cls != null && !cls.startsWith("android.app.Dialog")) cls else null
                currentForegroundPkg = pkg
                if (activityName != null) {
                    currentForegroundActivity = GkdRuleRuntimeEngine.normalizeActivityId(pkg, activityName)
                }
                _currentTopInfo.value = currentForegroundPkg to currentForegroundActivity
                gkdEngine.onForegroundChanged(pkg, activityName, now)
            }

            // 2. 执行毫秒级开屏断网（拦截在线广告）
            if (globalSplashActive && enabledConfigs.isNotEmpty()) {
                val decision = stateMachine.onWindowStateChanged(
                    event = WindowEventInput(
                        packageName = pkg,
                        className = cls,
                        nowMs = now,
                        activeImePackages = cachedImePackages,
                        homeLauncherPackages = cachedHomeLaunchers
                    ),
                    enabledConfigs = enabledConfigs
                )
                when (decision) {
                    is SplashDecision.TriggerBlock -> {
                        triggerTemporarySplashBlock(
                            decision.packageName,
                            decision.uid,
                            decision.durationMs,
                            cls,
                            decision.matchedBy
                        )
                    }
                    is SplashDecision.Ignore -> {
                        if (decision.reason != "SAME_PACKAGE" && decision.reason != "SYSTEM_OVERLAY_OR_IME") {
                            recordTrace(now, pkg, cls, "NET_SKIP: ${decision.reason}")
                        }
                    }
                }
            }

            // 3. 立即触发一次 GKD 规则检查（零延迟响应开屏页）
            if (gkdEngineActive && pkg != packageName) {
                triggerGkdEvaluation(pkg, forceImmediate = true)
            }
        } else if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (!gkdEngineActive || pkg == packageName) return
            if (pkg == "com.android.systemui" || pkg in cachedImePackages) return
            // 节流保护：刷列表时最多每 65ms 评估一次，避免耗电卡顿
            if (now - lastGkdScanMs < CONTENT_THROTTLE_MS) return
            triggerGkdEvaluation(pkg, forceImmediate = false)
        }
    }

    private fun triggerGkdEvaluation(eventPkg: String, forceImmediate: Boolean) {
        val reg = compiledGkd
        if (reg.enabledGlobalGroups.isEmpty() && eventPkg !in reg.enabledAppGroupsByPkg) return
        if (!gkdEvaluating.compareAndSet(false, true)) return

        val now = System.currentTimeMillis()
        lastGkdScanMs = now
        val targetPkg = currentForegroundPkg.ifBlank { eventPkg }
        val targetAct = currentForegroundActivity

        serviceScope.launch(Dispatchers.Default) {
            try {
                val root = rootInActiveWindow ?: return@launch
                val rootPkg = root.packageName?.toString()?.takeIf { it.isNotBlank() } ?: targetPkg
                if (rootPkg == packageName) return@launch

                val adapter = AccessibilityNodeAdapter(root)
                val isSys = rootPkg in systemAppPackages
                val isLauncher = rootPkg in cachedHomeLaunchers

                var hitResult: com.netcontrol.core.gkd.runtime.GkdMatchHit<android.view.accessibility.AccessibilityNodeInfo>? = null
                val costMs = measureTimeMillis {
                    hitResult = gkdEngine.evaluate(
                        registry = reg,
                        appId = rootPkg,
                        activityId = targetAct,
                        isSystemApp = isSys,
                        isLauncher = isLauncher,
                        root = root,
                        adapter = adapter,
                        nowMs = System.currentTimeMillis()
                    )
                }

                val hit = hitResult ?: return@launch
                if (hit.rule.actionDelay > 0L) {
                    delay(hit.rule.actionDelay)
                }

                val bounds = adapter.getBoundsRect(hit.targetNode)
                val executed = withContext(Dispatchers.Main) {
                    GkdActionExecutor.execute(
                        service = this@SplashBlockService,
                        node = hit.targetNode,
                        bounds = bounds,
                        action = hit.rule.action,
                        position = hit.rule.position
                    )
                }

                if (executed) {
                    recordTrace(
                        System.currentTimeMillis(),
                        rootPkg,
                        targetAct,
                        "GKD_HIT [${hit.group.name}] ${hit.rule.action.name} (${costMs}ms) ${hit.matchedText}"
                    )
                    val app = applicationContext as? NetControlApp
                    app?.container?.gkdSubscriptionRepository?.recordActionLog(
                        GkdActionLogEntity(
                            timestamp = System.currentTimeMillis(),
                            packageName = rootPkg,
                            activityId = targetAct,
                            subName = hit.group.subName,
                            groupName = hit.group.name,
                            ruleKey = hit.rule.key,
                            action = hit.rule.action.name,
                            matchedText = hit.matchedText.take(40),
                            costMs = costMs
                        )
                    )
                }
            } catch (_: Throwable) {
            } finally {
                gkdEvaluating.set(false)
            }
        }
    }

    private fun captureActiveWindowSnapshotInternal(): GkdSnapshotDocument? {
        val root = rootInActiveWindow ?: return null
        val app = applicationContext as? NetControlApp ?: return null
        val pkg = root.packageName?.toString().orEmpty().ifBlank { currentForegroundPkg }
        val act = currentForegroundActivity
        return runCatching {
            app.container.gkdSnapshotManager.captureFromRootNode(root, pkg, act)
        }.getOrNull()
    }

    override fun onInterrupt() {
        releaseAllActiveBlocks()
    }

    override fun onDestroy() {
        instanceRef = null
        _isConnected.value = false
        releaseAllActiveBlocks()
        gkdEngine.reset()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun triggerTemporarySplashBlock(
        pkg: String,
        uid: Int,
        durationMs: Int,
        cls: String?,
        matchedBy: String
    ) {
        val app = applicationContext as? NetControlApp ?: return
        activeRecoveryJobs.remove(pkg)?.second?.cancel()

        val job = serviceScope.launch(Dispatchers.IO) {
            try {
                var ok = false
                val costMs = measureTimeMillis {
                    ok = app.container.ruleEngine.triggerSplashFastBlock(pkg, uid)
                }
                recordTrace(
                    System.currentTimeMillis(),
                    pkg,
                    cls,
                    "NET_BLOCK ${durationMs}ms (IPC ${costMs}ms, ok=$ok, $matchedBy)"
                )
                delay(durationMs.toLong())
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    activeRecoveryJobs.remove(pkg)
                    app.container.ruleEngine.releaseSplashFastBlock(pkg, uid)
                }
            }
        }
        activeRecoveryJobs[pkg] = uid to job
    }

    private fun releaseAllActiveBlocks() {
        val entries = activeRecoveryJobs.entries.map { it.key to it.value.first }
        activeRecoveryJobs.values.forEach { it.second.cancel() }
        activeRecoveryJobs.clear()
        val app = applicationContext as? NetControlApp ?: return
        if (entries.isNotEmpty()) {
            app.container.appScope.launch(Dispatchers.IO) {
                entries.forEach { (pkg, uid) ->
                    app.container.ruleEngine.releaseSplashFastBlock(pkg, uid)
                }
            }
        }
    }

    private fun refreshImeAndLauncherCacheIfNeeded(nowMs: Long) {
        if (nowMs - lastImeRefreshMs < IME_CACHE_TTL_MS && cachedImePackages.isNotEmpty()) return
        lastImeRefreshMs = nowMs
        try {
            val imm = getSystemService(InputMethodManager::class.java)
            if (imm != null) {
                cachedImePackages = imm.enabledInputMethodList.mapNotNull { it.packageName }.toSet()
            }
        } catch (_: Throwable) {
        }
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolved = packageManager.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            cachedHomeLaunchers = resolved.mapNotNull { it.activityInfo?.packageName }.toSet()
        } catch (_: Throwable) {
        }
    }

    private fun recordTrace(nowMs: Long, pkg: String, cls: String?, outcome: String) {
        val entry = SplashTraceEntry(nowMs, pkg, cls, outcome)
        val updated = (listOf(entry) + _recentTraces.value).take(MAX_TRACE_SIZE)
        _recentTraces.value = updated
    }
}
