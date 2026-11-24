package com.netcontrol.core.accessibility

data class SplashAppConfig(
    val packageName: String,
    val uid: Int = -1,
    val launcherActivity: String?,
    val durationMs: Int = 3000,
    val cooldownSec: Int = 60,
    val relaxActivityCheck: Boolean = false
)

data class WindowEventInput(
    val packageName: String,
    val className: String?,
    val nowMs: Long,
    val activeImePackages: Set<String> = emptySet(),
    val homeLauncherPackages: Set<String> = emptySet()
)

sealed interface SplashDecision {
    data class TriggerBlock(
        val packageName: String,
        val uid: Int,
        val durationMs: Int,
        val matchedBy: String
    ) : SplashDecision

    data class Ignore(
        val packageName: String,
        val reason: String
    ) : SplashDecision
}

data class SplashTraceEntry(
    val timestampMs: Long,
    val packageName: String,
    val className: String?,
    val outcome: String
)

/**
 * 纯 Kotlin 开屏广告断网防误触状态机（纯内存 O(1) 判定，零 I/O）：
 * 1. 防线 1：忽略系统浮层（SystemUI/音量条/通知栏/权限弹窗/安装器/三星侧屏）与输入法，绝不污染前台记录；
 * 2. 防线 2：同包名去重——但若刚进入该应用 2.5 秒内尚未触发且弹出了独立 Splash/Ad Activity（如百度网盘 MainActivity -> SplashAdActivity），允许补触发；
 * 3. 防线 3：支付/授权过渡白名单（从微信支付、支付宝收银台、云闪付等跳回原 App 时，即使超过冷却也不误杀）；
 * 4. 防线 4：后台驻留冷却阈值（默认 60 秒）；
 * 5. 防线 5：桌面启动识别 + 入口 Activity 校验：
 *    - 从系统桌面 Launcher 直接启动该应用时，即使该应用使用了 <activity-alias>（导致实际窗口是 MainActivity 而非别名）也能准确命中；
 *    - 从其他应用（如微信）跳转过来时严格校验 launcherActivity / Splash 关键词，零误杀外部 Deep Link 详情页。
 */
class SplashBlockStateMachine(
    private val selfPackageName: String,
    private val extraIgnoredPackages: Set<String> = emptySet(),
    private val extraTransitionPackages: Set<String> = emptySet()
) {
    companion object {
        private const val SECONDARY_SPLASH_WINDOW_MS = 2_500L

        private val SYSTEM_OVERLAY_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.samsung.android.app.cocktailbarservice",
            "com.samsung.android.app.smartcapture",
            "com.samsung.android.honeyboard",
            "com.sec.android.app.launcher.overlay"
        )

        private val KNOWN_HOME_LAUNCHERS = setOf(
            "com.sec.android.app.launcher",
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher3",
            "com.miui.home",
            "com.huawei.android.launcher",
            "com.oppo.launcher",
            "com.bbk.launcher2",
            "com.teslacoilsw.launcher",
            "ch.deletescape.lawnchair.plah"
        )

        private val TRANSIENT_CLASS_PREFIXES = listOf(
            "android.widget.",
            "android.inputmethodservice.",
            "androidx.appcompat.app.AlertDialog",
            "android.app.AlertDialog",
            "android.app.Dialog"
        )

        private val SPLASH_CLASS_KEYWORDS = listOf(
            "splash",
            "launch",
            "welcome",
            "startup",
            "startactivity",
            "adactivity",
            "advert",
            "guide"
        )

        private val PAYMENT_OR_AUTH_PACKAGES = setOf(
            "com.eg.android.AlipayGphone",
            "com.unionpay",
            "com.samsung.android.spay"
        )

        private val WECHAT_PAYMENT_OR_AUTH_KEYWORDS = listOf(
            "wallet",
            "pay",
            "oauth",
            "auth",
            "wxentry",
            "wxpayentry",
            "openapi",
            "sendauth"
        )
    }

    private var lastForegroundPackage: String? = null
    private var lastExitTimeByPackage = HashMap<String, Long>()
    private var currentPackageEnterTimeMs: Long = 0L
    private var currentPackageTriggeredSplash: Boolean = false
    private var currentPackageReturnedFromTransition: Boolean = false
    private var currentPackageWasInCooldown: Boolean = false
    private var appBeforeTransition: String? = null
    private var inPaymentOrAuthTransition: Boolean = false

    fun reset() {
        lastForegroundPackage = null
        lastExitTimeByPackage.clear()
        currentPackageEnterTimeMs = 0L
        currentPackageTriggeredSplash = false
        currentPackageReturnedFromTransition = false
        currentPackageWasInCooldown = false
        appBeforeTransition = null
        inPaymentOrAuthTransition = false
    }

    fun onWindowStateChanged(
        event: WindowEventInput,
        enabledConfigs: Map<String, SplashAppConfig>
    ): SplashDecision {
        val pkg = event.packageName
        val cls = event.className

        // 防线 1：系统浮层 / 输入法 / 临时弹窗 / 本应用自身 -> 忽略且绝不更新 lastForegroundPackage
        if (pkg.isBlank() ||
            pkg == selfPackageName ||
            pkg in SYSTEM_OVERLAY_PACKAGES ||
            pkg in extraIgnoredPackages ||
            pkg in event.activeImePackages ||
            isTransientOverlayClass(cls)
        ) {
            return SplashDecision.Ignore(pkg, "SYSTEM_OVERLAY_OR_IME")
        }

        // 防线 2：同应用内部页面切换
        if (pkg == lastForegroundPackage) {
            if (isPaymentOrAuthTransition(pkg, cls)) {
                inPaymentOrAuthTransition = true
                return SplashDecision.Ignore(pkg, "SAME_PACKAGE")
            }
            // 特殊补捕获：若刚切入本应用 <= 2.5 秒且尚未触发、非冷却/非支付返回，此时弹出了独立 Splash/Ad 页（如百度网盘）
            val config = enabledConfigs[pkg]
            val withinSecondaryWindow = (event.nowMs - currentPackageEnterTimeMs) in 0..SECONDARY_SPLASH_WINDOW_MS
            if (config != null &&
                !currentPackageTriggeredSplash &&
                !currentPackageReturnedFromTransition &&
                !currentPackageWasInCooldown &&
                withinSecondaryWindow
            ) {
                val splashKeyword = findSplashKeyword(cls)
                if (splashKeyword != null) {
                    currentPackageTriggeredSplash = true
                    return SplashDecision.TriggerBlock(
                        packageName = pkg,
                        uid = config.uid,
                        durationMs = config.durationMs.coerceIn(500, 15_000),
                        matchedBy = "SECONDARY_SPLASH($splashKeyword)"
                    )
                }
            }
            return SplashDecision.Ignore(pkg, "SAME_PACKAGE")
        }

        val prevPkg = lastForegroundPackage
        val launchedFromDesktop = isHomeLauncher(prevPkg, event.homeLauncherPackages)
        val returningFromTransition = inPaymentOrAuthTransition && pkg == appBeforeTransition

        if (prevPkg != null) {
            lastExitTimeByPackage[prevPkg] = event.nowMs
        }

        val enteringTransition = isPaymentOrAuthTransition(pkg, cls)
        if (enteringTransition) {
            if (!inPaymentOrAuthTransition) {
                appBeforeTransition = prevPkg
            }
            inPaymentOrAuthTransition = true
        } else {
            inPaymentOrAuthTransition = false
            appBeforeTransition = null
        }

        lastForegroundPackage = pkg
        currentPackageEnterTimeMs = event.nowMs
        currentPackageTriggeredSplash = false
        currentPackageReturnedFromTransition = returningFromTransition
        currentPackageWasInCooldown = false

        // 防线 3：从微信支付/支付宝收银台等过渡页直接返回原 App -> 强制放行
        if (returningFromTransition) {
            return SplashDecision.Ignore(pkg, "PAYMENT_OR_AUTH_TRANSITION_RETURN")
        }

        val config = enabledConfigs[pkg]
            ?: return SplashDecision.Ignore(pkg, "APP_NOT_ENABLED")

        // 防线 4：后台冷却时间阈值（默认 60 秒）
        val lastExit = lastExitTimeByPackage[pkg]
        if (lastExit != null) {
            val elapsedMs = event.nowMs - lastExit
            val cooldownMs = config.cooldownSec.coerceAtLeast(0) * 1000L
            if (elapsedMs in 0 until cooldownMs) {
                currentPackageWasInCooldown = true
                return SplashDecision.Ignore(pkg, "IN_COOLDOWN(${elapsedMs / 1000}s/${config.cooldownSec}s)")
            }
        }

        // 防线 5：桌面启动直达识别 + 入口 Activity / Splash 关键词校验
        val matchResult = matchEntryActivity(config, cls, launchedFromDesktop)
        if (matchResult == null) {
            return SplashDecision.Ignore(pkg, "NON_LAUNCHER_DEEP_LINK(${cls?.substringAfterLast('.') ?: "null"})")
        }

        currentPackageTriggeredSplash = true
        return SplashDecision.TriggerBlock(
            packageName = pkg,
            uid = config.uid,
            durationMs = config.durationMs.coerceIn(500, 15_000),
            matchedBy = matchResult
        )
    }

    private fun isHomeLauncher(pkg: String?, dynamicLaunchers: Set<String>): Boolean {
        if (pkg.isNullOrBlank()) return false
        return pkg in KNOWN_HOME_LAUNCHERS || pkg in dynamicLaunchers
    }

    private fun isTransientOverlayClass(className: String?): Boolean {
        if (className.isNullOrBlank()) return false
        return TRANSIENT_CLASS_PREFIXES.any { className.startsWith(it) }
    }

    private fun isPaymentOrAuthTransition(packageName: String, className: String?): Boolean {
        if (packageName in PAYMENT_OR_AUTH_PACKAGES || packageName in extraTransitionPackages) {
            return true
        }
        if (packageName == "com.tencent.mm" && !className.isNullOrBlank()) {
            val lower = className.lowercase()
            return WECHAT_PAYMENT_OR_AUTH_KEYWORDS.any { lower.contains(it) }
        }
        return false
    }

    private fun findSplashKeyword(className: String?): String? {
        if (className.isNullOrBlank()) return null
        val simpleLower = className.substringAfterLast('.').lowercase()
        return SPLASH_CLASS_KEYWORDS.firstOrNull { simpleLower.contains(it) }
    }

    private fun matchEntryActivity(
        config: SplashAppConfig,
        className: String?,
        launchedFromDesktop: Boolean
    ): String? {
        if (config.relaxActivityCheck) {
            return "RELAXED_MODE"
        }
        if (className.isNullOrBlank()) {
            return if (launchedFromDesktop) "DESKTOP_LAUNCH" else null
        }

        val launcher = config.launcherActivity
        if (!launcher.isNullOrBlank()) {
            if (className.equals(launcher, ignoreCase = true) ||
                className.substringAfterLast('.').equals(launcher.substringAfterLast('.'), ignoreCase = true)
            ) {
                return "LAUNCHER_ACTIVITY"
            }
        }

        val hitKeyword = findSplashKeyword(className)
        if (hitKeyword != null) {
            return "SPLASH_KEYWORD($hitKeyword)"
        }

        // 从系统桌面直接点击图标启动：兼容使用 <activity-alias>（如百度网盘 SvipLauncherActivity -> MainActivity）的应用
        if (launchedFromDesktop) {
            return "DESKTOP_LAUNCH_ALIAS_SAFE"
        }

        return null
    }
}
