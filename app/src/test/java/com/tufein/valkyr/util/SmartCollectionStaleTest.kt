package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartCollectionStaleTest {
    private val day = SmartCollectionRule.DAY_MS
    private val now = 1_000 * day
    private fun rule(value: String) = SmartCollectionRule(played = value)
    private fun hit(rule: SmartCollectionRule, last: Long?) =
        rule.matches("gba", emptyList(), null, null, wasPlayed = last != null, lastPlayedAt = last, now = now)

    @Test fun staleYearKeepsNeverPlayedAndOldGamesOnly() {
        val r = rule("STALE_365")
        assertTrue(r.valid)
        assertTrue(hit(r, null))
        assertTrue(hit(r, now - 400 * day))
        assertFalse(hit(r, now - 364 * day))
        assertFalse(hit(r, now - 1))
    }

    @Test fun staleThresholdsAre90_180_365() {
        assertEquals(listOf(90, 180, 365), SmartCollectionRule.STALE_VALUES.map { SmartCollectionRule.staleDays(it) })
        assertTrue(hit(rule("STALE_90"), now - 100 * day))
        assertFalse(hit(rule("STALE_180"), now - 100 * day))
    }

    @Test fun otherValuesStillWorkAndUnknownIsInvalid() {
        assertTrue(hit(rule("PLAYED"), now - day))
        assertFalse(hit(rule("PLAYED"), null))
        assertTrue(hit(rule("NEVER"), null))
        assertFalse(rule("STALE_7").valid)
    }
}
