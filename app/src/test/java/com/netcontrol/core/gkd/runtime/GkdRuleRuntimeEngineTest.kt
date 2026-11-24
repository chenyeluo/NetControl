package com.netcontrol.core.gkd.runtime

import com.netcontrol.core.gkd.snapshot.GkdSnapshotNode
import com.netcontrol.core.gkd.snapshot.SnapshotNodeAdapter
import com.netcontrol.core.gkd.subscription.CompiledGkdRegistry
import com.netcontrol.core.gkd.subscription.GkdPosition
import com.netcontrol.core.gkd.subscription.GkdSubscriptionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GkdRuleRuntimeEngineTest {

    @Test
    fun excludeMatches_preventsFalseClickOnSkipStepButton() {
        val sub = GkdSubscriptionParser.parse(
            """
            {
              id: 1,
              name: "Test",
              globalGroups: [{
                key: 0,
                name: "开屏跳过",
                matchTime: 5000,
                rules: [{
                  key: 0,
                  matches: '[text*="跳过"][text.length<=8]',
                  excludeMatches: '[text*="跳过此步"]'
                }]
              }]
            }
            """.trimIndent()
        )
        val reg = CompiledGkdRegistry(listOf(sub), sub.globalGroups, emptyMap())
        val engine = GkdRuleRuntimeEngine()
        engine.onForegroundChanged("com.demo", "com.demo.SetupActivity", 1000L)

        val nodes = listOf(
            GkdSnapshotNode(0, -1, 0, 0, "FrameLayout", null, null, null, false, true, 0, 0, 1080, 2400),
            GkdSnapshotNode(1, 0, 0, 1, "TextView", null, "跳过此步", null, true, true, 100, 100, 300, 200)
        )
        val adapter = SnapshotNodeAdapter(nodes)
        val hit = engine.evaluate(reg, "com.demo", "com.demo.SetupActivity", false, false, adapter.root!!, adapter, 1500L)
        assertNull(hit)
    }

    @Test
    fun preKeys_enforcesMultiStepOrderAndActionMaximum() {
        val sub = GkdSubscriptionParser.parse(
            """
            {
              id: 2,
              name: "MultiStep",
              apps: [{
                id: "com.feed.app",
                name: "Feed",
                groups: [{
                  key: 0,
                  name: "分段关闭广告",
                  actionCd: 100,
                  actionMaximum: 1,
                  rules: [
                    { key: 0, activityIds: ".MainActivity", matches: '[text="关闭广告"]' },
                    { key: 1, preKeys: [0], activityIds: ".MainActivity", matches: '[text="不感兴趣"]' }
                  ]
                }]
              }]
            }
            """.trimIndent()
        )
        val reg = CompiledGkdRegistry(listOf(sub), emptyList(), mapOf("com.feed.app" to sub.apps.getValue("com.feed.app").groups))
        val engine = GkdRuleRuntimeEngine()
        engine.onForegroundChanged("com.feed.app", "com.feed.app.MainActivity", 1000L)

        // 先展示只有“不感兴趣”的树 -> 因为 preKey 0 尚未触发，规则 1 绝不能抢跑！
        val step2OnlyNodes = listOf(
            GkdSnapshotNode(0, -1, 0, 0, "FrameLayout", null, null, null, false, true, 0, 0, 1080, 2400),
            GkdSnapshotNode(1, 0, 0, 1, "TextView", null, "不感兴趣", null, true, true, 10, 10, 200, 100)
        )
        val a2 = SnapshotNodeAdapter(step2OnlyNodes)
        assertNull(engine.evaluate(reg, "com.feed.app", "com.feed.app.MainActivity", false, false, a2.root!!, a2, 1100L))

        // 触发第一步“关闭广告”
        val step1Nodes = listOf(
            GkdSnapshotNode(0, -1, 0, 0, "FrameLayout", null, null, null, false, true, 0, 0, 1080, 2400),
            GkdSnapshotNode(1, 0, 0, 1, "TextView", null, "关闭广告", null, true, true, 10, 10, 200, 100)
        )
        val a1 = SnapshotNodeAdapter(step1Nodes)
        val hit1 = engine.evaluate(reg, "com.feed.app", "com.feed.app.MainActivity", false, false, a1.root!!, a1, 1200L)
        assertNotNull(hit1)
        assertEquals(0, hit1!!.rule.key)

        // 现在 preKey 0 已完成，第二步“不感兴趣”成功触发！
        val hit2 = engine.evaluate(reg, "com.feed.app", "com.feed.app.MainActivity", false, false, a2.root!!, a2, 1400L)
        assertNotNull(hit2)
        assertEquals(1, hit2!!.rule.key)

        // actionMaximum = 1，再次检测不再重复触发
        assertNull(engine.evaluate(reg, "com.feed.app", "com.feed.app.MainActivity", false, false, a2.root!!, a2, 2000L))
    }

    @Test
    fun computeClickPoint_evaluatesGkdPositionExpressions() {
        val (x, y) = GkdRuleRuntimeEngine.computeClickPoint(
            left = 100,
            top = 200,
            right = 300, // width = 200
            bottom = 400, // height = 200
            position = GkdPosition(left = "width * 0.85", bottom = "height * 0.25")
        )
        assertEquals(270f, x, 0.01f)
        assertEquals(350f, y, 0.01f)
    }
}
