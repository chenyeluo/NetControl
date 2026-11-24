package com.netcontrol.core.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashBlockStateMachineTest {

    private val biliConfig = SplashAppConfig(
        packageName = "tv.danmaku.bili",
        launcherActivity = "tv.danmaku.bili.MainActivityV2",
        durationMs = 3000,
        cooldownSec = 60,
        relaxActivityCheck = false
    )

    private val configs = mapOf(biliConfig.packageName to biliConfig)

    @Test
    fun coldLaunchFromDesktop_matchingLauncherActivity_triggersSplashBlock() {
        val sm = SplashBlockStateMachine("com.netcontrol.debug")
        sm.onWindowStateChanged(WindowEventInput("com.sec.android.app.launcher", "Launcher", 1_000L), configs)

        val decision = sm.onWindowStateChanged(
            WindowEventInput("tv.danmaku.bili", "tv.danmaku.bili.MainActivityV2", 2_000L),
            configs
        )
        assertTrue(decision is SplashDecision.TriggerBlock)
        val trigger = decision as SplashDecision.TriggerBlock
        assertEquals("tv.danmaku.bili", trigger.packageName)
        assertEquals(3000, trigger.durationMs)
    }

    @Test
    fun notificationShadeAndImeOverlays_doNotPolluteForegroundOrRetrigger() {
        val sm = SplashBlockStateMachine("com.netcontrol.debug")
        sm.onWindowStateChanged(WindowEventInput("com.sec.android.app.launcher", "Launcher", 1_000L), configs)
        assertTrue(
            sm.onWindowStateChanged(
                WindowEventInput("tv.danmaku.bili", "tv.danmaku.bili.MainActivityV2", 2_000L),
                configs
            ) is SplashDecision.TriggerBlock
        )

        // 拉下通知栏、弹出输入法、权限弹窗后再返回 B 站，绝不能误触发
        val sysUi = sm.onWindowStateChanged(
            WindowEventInput("com.android.systemui", "StatusBar", 120_000L),
            configs
        )
        assertTrue(sysUi is SplashDecision.Ignore)

        val ime = sm.onWindowStateChanged(
            WindowEventInput("com.sohu.inputmethod.sogou", "SoftKeyboard", 125_000L, setOf("com.sohu.inputmethod.sogou")),
            configs
        )
        assertTrue(ime is SplashDecision.Ignore)

        val backToBili = sm.onWindowStateChanged(
            WindowEventInput("tv.danmaku.bili", "tv.danmaku.bili.MainActivityV2", 130_000L),
            configs
        )
        assertTrue(backToBili is SplashDecision.Ignore)
        assertEquals("SAME_PACKAGE", (backToBili as SplashDecision.Ignore).reason)
    }

    @Test
    fun shortSwitchToWechatAndBack_withinCooldown_isIgnored_afterCooldown_triggers() {
        val sm = SplashBlockStateMachine("com.netcontrol.debug")
        sm.onWindowStateChanged(WindowEventInput("tv.danmaku.bili", "tv.danmaku.bili.MainActivityV2", 1_000L), configs)

        // 切去微信回消息 10 秒后切回 -> 冷却期内放行
        sm.onWindowStateChanged(WindowEventInput("com.tencent.mm", "LauncherUI", 10_000L), configs)
        val withinCooldown = sm.onWindowStateChanged(
            WindowEventInput("tv.danmaku.bili", "tv.danmaku.bili.MainActivityV2", 20_000L),
            configs
        )
        assertTrue(withinCooldown is SplashDecision.Ignore)
        assertTrue((withinCooldown as SplashDecision.Ignore).reason.startsWith("IN_COOLDOWN"))

        // 切去桌面 90 秒后冷启回来 -> 超过 60s 冷却触发断网
        sm.onWindowStateChanged(WindowEventInput("com.sec.android.app.launcher", "Launcher", 30_000L), configs)
        val afterCooldown = sm.onWindowStateChanged(
            WindowEventInput("tv.danmaku.bili", "tv.danmaku.bili.MainActivityV2", 120_000L),
            configs
        )
        assertTrue(afterCooldown is SplashDecision.TriggerBlock)
    }

    @Test
    fun deepLinkDirectlyIntoVideoDetails_isIgnoredToPreventVideoLoadFailure() {
        val sm = SplashBlockStateMachine("com.netcontrol.debug")
        sm.onWindowStateChanged(WindowEventInput("com.tencent.mm", "LauncherUI", 1_000L), configs)

        val deepLink = sm.onWindowStateChanged(
            WindowEventInput("tv.danmaku.bili", "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity", 5_000L),
            configs
        )
        assertTrue(deepLink is SplashDecision.Ignore)
        assertTrue((deepLink as SplashDecision.Ignore).reason.startsWith("NON_LAUNCHER_DEEP_LINK"))
    }

    @Test
    fun relaxActivityCheck_allowsTriggerOnAnyActivityForStubbornApp() {
        val sm = SplashBlockStateMachine("com.netcontrol.debug")
        val relaxedMap = mapOf(biliConfig.packageName to biliConfig.copy(relaxActivityCheck = true))
        sm.onWindowStateChanged(WindowEventInput("com.sec.android.app.launcher", "Launcher", 1_000L), relaxedMap)

        val decision = sm.onWindowStateChanged(
            WindowEventInput("tv.danmaku.bili", "com.bilibili.SomeObfuscatedActivity", 5_000L),
            relaxedMap
        )
        assertTrue(decision is SplashDecision.TriggerBlock)
        assertEquals("RELAXED_MODE", (decision as SplashDecision.TriggerBlock).matchedBy)
    }

    @Test
    fun paymentTransitionToAlipay_longerThanCooldown_stillBypassesOnReturn() {
        val sm = SplashBlockStateMachine("com.netcontrol.debug")
        // 进入 B 站
        sm.onWindowStateChanged(WindowEventInput("tv.danmaku.bili", "tv.danmaku.bili.MainActivityV2", 1_000L), configs)
        // 跳转支付宝付款耗时 95 秒（大于 60 秒冷却阈值！）
        sm.onWindowStateChanged(
            WindowEventInput("com.eg.android.AlipayGphone", "com.alipay.android.msp.ui.views.MspContainerActivity", 10_000L),
            configs
        )
        // 支付完成自动跳回 B 站 -> 命中支付过渡白名单，绝不断网以免支付回调失败！
        val returned = sm.onWindowStateChanged(
            WindowEventInput("tv.danmaku.bili", "tv.danmaku.bili.MainActivityV2", 105_000L),
            configs
        )
        assertTrue(returned is SplashDecision.Ignore)
        assertEquals("PAYMENT_OR_AUTH_TRANSITION_RETURN", (returned as SplashDecision.Ignore).reason)
    }

    @Test
    fun wechatPayTransition_bypassesOnReturn() {
        val sm = SplashBlockStateMachine("com.netcontrol.debug")
        sm.onWindowStateChanged(WindowEventInput("tv.danmaku.bili", "tv.danmaku.bili.MainActivityV2", 1_000L), configs)
        sm.onWindowStateChanged(
            WindowEventInput("com.tencent.mm", "com.tencent.mm.plugin.wallet.pay.ui.WalletPayUI", 10_000L),
            configs
        )
        val returned = sm.onWindowStateChanged(
            WindowEventInput("tv.danmaku.bili", "tv.danmaku.bili.MainActivityV2", 110_000L),
            configs
        )
        assertTrue(returned is SplashDecision.Ignore)
        assertEquals("PAYMENT_OR_AUTH_TRANSITION_RETURN", (returned as SplashDecision.Ignore).reason)
    }

    @Test
    fun baiduNetdiskAliasLaunch_fromDesktop_andSecondarySplashAdActivity_bothTrigger() {
        val netdiskConfig = SplashAppConfig(
            packageName = "com.baidu.netdisk.samsung",
            uid = 10333,
            launcherActivity = "com.baidu.netdisk.ui.SvipLauncherActivity",
            durationMs = 3000,
            cooldownSec = 60,
            relaxActivityCheck = false
        )
        val map = mapOf(netdiskConfig.packageName to netdiskConfig)

        // 场景 A：从三星桌面点击图标启动，实际首窗是 MainActivity（alias 跳转）
        val sm1 = SplashBlockStateMachine("com.netcontrol.debug")
        sm1.onWindowStateChanged(WindowEventInput("com.sec.android.app.launcher", "Launcher", 1_000L), map)
        val d1 = sm1.onWindowStateChanged(
            WindowEventInput("com.baidu.netdisk.samsung", "com.baidu.netdisk.ui.MainActivity", 2_000L),
            map
        )
        assertTrue(d1 is SplashDecision.TriggerBlock)
        assertEquals("DESKTOP_LAUNCH_ALIAS_SAFE", (d1 as SplashDecision.TriggerBlock).matchedBy)

        // 场景 B：从非桌面切入 MainActivity 后 200ms 内弹出 SplashAdActivity（补捕获）
        val sm2 = SplashBlockStateMachine("com.netcontrol.debug")
        sm2.onWindowStateChanged(WindowEventInput("com.some.other", "OtherActivity", 1_000L), map)
        val d2a = sm2.onWindowStateChanged(
            WindowEventInput("com.baidu.netdisk.samsung", "com.baidu.netdisk.ui.MainActivity", 2_000L),
            map
        )
        assertTrue(d2a is SplashDecision.Ignore)
        val d2b = sm2.onWindowStateChanged(
            WindowEventInput("com.baidu.netdisk.samsung", "com.baidu.netdisk.advertise.ui.SplashAdActivity", 2_200L),
            map
        )
        assertTrue(d2b is SplashDecision.TriggerBlock)
        assertTrue((d2b as SplashDecision.TriggerBlock).matchedBy.startsWith("SECONDARY_SPLASH"))
    }
}
