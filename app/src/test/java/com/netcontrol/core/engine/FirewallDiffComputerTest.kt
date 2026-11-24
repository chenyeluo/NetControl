package com.netcontrol.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirewallDiffComputerTest {

    @Test
    fun initialSync_onlyDispatchesBlockedApps() {
        val decisions = mapOf(
            "com.a" to AppDecision("com.a", 101, true, RuleDecisionReason.APP_MANUAL_BLOCK),
            "com.b" to AppDecision("com.b", 102, false, RuleDecisionReason.PROFILE_DEFAULT_ALLOW),
            "com.c" to AppDecision("com.c", 103, false, RuleDecisionReason.PROFILE_DEFAULT_ALLOW)
        )

        val diff = FirewallDiffComputer.compute(emptyMap(), decisions, forceFullSync = false)
        assertTrue(diff.isFullSync)
        assertEquals(1, diff.changes.size)
        assertEquals("com.a", diff.changes.single().packageName)
        assertTrue(diff.changes.single().blocked)
    }

    @Test
    fun incrementalDiff_emitsOnlyChangedPackages() {
        val prev = mapOf("com.a" to true, "com.b" to false, "com.c" to false)
        val next = mapOf(
            "com.a" to AppDecision("com.a", 101, false, RuleDecisionReason.PROFILE_DEFAULT_ALLOW), // true -> false
            "com.b" to AppDecision("com.b", 102, true, RuleDecisionReason.GROUP_STATIC_BLOCK),     // false -> true
            "com.c" to AppDecision("com.c", 103, false, RuleDecisionReason.PROFILE_DEFAULT_ALLOW)  // unchanged
        )

        val diff = FirewallDiffComputer.compute(prev, next, forceFullSync = false)
        assertFalse(diff.isFullSync)
        assertEquals(2, diff.changes.size)
        val byPkg = diff.changes.associateBy { it.packageName }
        assertFalse(byPkg.getValue("com.a").blocked)
        assertTrue(byPkg.getValue("com.b").blocked)
    }

    @Test
    fun noChange_producesEmptyDiffPlan() {
        val prev = mapOf("com.a" to true, "com.b" to false)
        val next = mapOf(
            "com.a" to AppDecision("com.a", 101, true, RuleDecisionReason.APP_MANUAL_BLOCK),
            "com.b" to AppDecision("com.b", 102, false, RuleDecisionReason.PROFILE_DEFAULT_ALLOW)
        )

        val diff = FirewallDiffComputer.compute(prev, next, forceFullSync = false)
        assertTrue(diff.isEmpty)
    }
}
