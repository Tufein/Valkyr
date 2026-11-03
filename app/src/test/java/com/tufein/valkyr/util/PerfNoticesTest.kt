package com.tufein.valkyr.util

import com.tufein.valkyr.util.PerfNotices.Kind
import com.tufein.valkyr.util.PerfNotices.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfNoticesTest {

    private val gb = 1024L * 1024 * 1024

    @Test
    fun nothingWrongGivesNoCondition() {
        assertTrue(PerfNotices.conditions(Reading(thermalStatus = 2, lowMemory = false, freeBytes = 5 * gb)).isEmpty())
        assertTrue(PerfNotices.conditions(Reading()).isEmpty())
    }

    @Test
    fun eachConditionHasItsOwnLine() {
        assertEquals(listOf(Kind.HOT), PerfNotices.conditions(Reading(thermalStatus = 3)))
        assertEquals(listOf(Kind.HOT), PerfNotices.conditions(Reading(thermalStatus = 6)))
        assertEquals(listOf(Kind.LOW_MEMORY), PerfNotices.conditions(Reading(lowMemory = true)))
        assertEquals(listOf(Kind.LOW_STORAGE), PerfNotices.conditions(Reading(freeBytes = gb - 1)))
        assertTrue(PerfNotices.conditions(Reading(freeBytes = gb)).isEmpty())
        assertEquals(listOf(Kind.HOT, Kind.LOW_MEMORY, Kind.LOW_STORAGE), PerfNotices.conditions(Reading(4, true, 0L)))
    }

    @Test
    fun aKindIsShownOncePerGap() {
        val t = PerfNotices.Tracker()
        val hot = Reading(thermalStatus = 3)
        assertEquals(listOf(Kind.HOT), t.due(hot, 1_000L))
        assertTrue(t.due(hot, 1_000L + PerfNotices.GAP_MS - 1).isEmpty())
        assertEquals(listOf(Kind.HOT), t.due(hot, 1_000L + PerfNotices.GAP_MS))
    }

    @Test
    fun kindsAreLimitedIndependently() {
        val t = PerfNotices.Tracker()
        assertEquals(listOf(Kind.HOT), t.due(Reading(thermalStatus = 3), 0L))
        assertEquals(listOf(Kind.LOW_MEMORY), t.due(Reading(thermalStatus = 3, lowMemory = true), 60_000L))
    }

    @Test
    fun aClockThatWentBackDoesNotMuteForever() {
        val t = PerfNotices.Tracker()
        t.due(Reading(lowMemory = true), 10_000_000L)
        assertEquals(listOf(Kind.LOW_MEMORY), t.due(Reading(lowMemory = true), 5L))
    }

    @Test
    fun historyIsNewestFirstAndCapped() {
        val t = PerfNotices.Tracker(gapMs = 1L, capacity = 3)
        for (i in 1..5) t.due(Reading(lowMemory = true), i * 10L)
        assertEquals(listOf(50L, 40L, 30L), t.history().map { it.at })
        assertEquals(Kind.LOW_MEMORY, t.history().first().kind)
    }
}
