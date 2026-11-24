package com.netcontrol.core.gkd.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Json5AndSubscriptionParserTest {

    @Test
    fun json5Parser_handlesCommentsUnquotedKeysSingleQuotesAndTrailingCommas() {
        val raw = """
            // 顶部单行注释
            {
              id: 233,
              name: '测试订阅',
              /* 多行
                 注释 */
              version: 0x10,
              categories: [
                { key: 0, name: '开屏广告', enable: true, },
              ],
              apps: [
                {
                  id: 'com.example.app',
                  name: "示例应用",
                  groups: [
                    {
                      key: 1,
                      name: '开屏广告-跳过',
                      fastQuery: true,
                      actionCd: 1500,
                      rules: {
                        key: 0,
                        activityIds: '.ui.SplashActivity',
                        matches: '[vid="skip_btn"][visibleToUser=true]',
                        position: { left: 'width * 0.8', top: 'height * 0.5' },
                      },
                    },
                  ],
                },
              ],
            }
        """.trimIndent()

        val sub = GkdSubscriptionParser.parse(raw)
        assertEquals(233L, sub.id)
        assertEquals("测试订阅", sub.name)
        assertEquals(16, sub.version)
        assertEquals(1, sub.apps.size)

        val group = sub.apps.getValue("com.example.app").groups.single()
        assertTrue(group.enableByDefault)
        val rule = group.rules.single()
        assertTrue(rule.fastQuery)
        assertEquals(1500L, rule.actionCd)
        assertEquals(listOf(".ui.SplashActivity"), rule.activityIds)
        assertEquals("width * 0.8", rule.position?.left)
    }

    @Test
    fun builtinDefaultSubscription_parsesCleanlyWithGlobalAndBaiduNetdiskRules() {
        val sub = GkdSubscriptionParser.parse(GkdSubscriptionRepository.BUILTIN_DEFAULT_SUBSCRIPTION_JSON5)
        assertEquals(GkdSubscriptionRepository.BUILTIN_SUB_ID, sub.id)
        assertTrue(sub.globalGroups.isNotEmpty())
        assertTrue("com.baidu.netdisk.samsung" in sub.apps)
        assertTrue("com.baidu.netdisk" in sub.apps)
        assertTrue(sub.totalRuleCount >= 4)
    }
}
