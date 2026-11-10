package com.tufein.valkyr.util

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadConditionsTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val none = DownloadConditions.State(onUnmeteredNetwork = false, charging = false)
    private val both = DownloadConditions.State(onUnmeteredNetwork = true, charging = true)

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: ZoneId = berlin) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    private fun eval(c: DownloadCondition?, s: DownloadConditions.State, now: Long) = DownloadConditions.evaluate(c, s, now, berlin)

    @Test
    fun `no condition is always ready`() {
        assertEquals(DownloadConditions.Verdict.Ready, eval(null, none, 0L))
    }

    @Test
    fun `wifi waits for an unmetered network`() {
        val c = DownloadCondition(ConditionKind.WIFI)
        assertEquals(DownloadConditions.Verdict.Waiting(listOf(ItemWait.WIFI), null), eval(c, none, 0L))
        assertEquals(DownloadConditions.Verdict.Ready, eval(c, DownloadConditions.State(true, false), 0L))
    }

    @Test
    fun `charging waits for the charger`() {
        val c = DownloadCondition(ConditionKind.CHARGING)
        assertEquals(DownloadConditions.Verdict.Waiting(listOf(ItemWait.CHARGER), null), eval(c, DownloadConditions.State(true, false), 0L))
        assertEquals(DownloadConditions.Verdict.Ready, eval(c, DownloadConditions.State(false, true), 0L))
    }

    @Test
    fun `wifi and charging lists only what is missing`() {
        val c = DownloadCondition(ConditionKind.WIFI_AND_CHARGING)
        assertEquals(listOf(ItemWait.WIFI, ItemWait.CHARGER), (eval(c, none, 0L) as DownloadConditions.Verdict.Waiting).reasons)
        assertEquals(listOf(ItemWait.CHARGER), (eval(c, DownloadConditions.State(true, false), 0L) as DownloadConditions.Verdict.Waiting).reasons)
        assertEquals(listOf(ItemWait.WIFI), (eval(c, DownloadConditions.State(false, true), 0L) as DownloadConditions.Verdict.Waiting).reasons)
        assertEquals(DownloadConditions.Verdict.Ready, eval(c, both, 0L))
    }

    @Test
    fun `tonight is ready from 23 until 07 across midnight`() {
        val c = DownloadCondition(ConditionKind.TONIGHT)
        assertEquals(DownloadConditions.Verdict.Ready, eval(c, none, at(2026, 10, 5, 23, 0)))
        assertEquals(DownloadConditions.Verdict.Ready, eval(c, none, at(2026, 10, 5, 23, 59)))
        assertEquals(DownloadConditions.Verdict.Ready, eval(c, none, at(2026, 10, 6, 0, 0)))
        assertEquals(DownloadConditions.Verdict.Ready, eval(c, none, at(2026, 10, 6, 6, 59)))
        assertTrue(eval(c, none, at(2026, 10, 6, 7, 0)) is DownloadConditions.Verdict.Waiting)
        assertTrue(eval(c, none, at(2026, 10, 5, 22, 59)) is DownloadConditions.Verdict.Waiting)
    }

    @Test
    fun `tonight outside the window reports the next 23 00`() {
        val c = DownloadCondition(ConditionKind.TONIGHT)
        val v = eval(c, none, at(2026, 10, 5, 12, 0)) as DownloadConditions.Verdict.Waiting
        assertEquals(listOf(ItemWait.NIGHT), v.reasons)
        assertEquals(at(2026, 10, 5, 23, 0), v.nextCheckAt)
        val morning = eval(c, none, at(2026, 10, 6, 7, 30)) as DownloadConditions.Verdict.Waiting
        assertEquals(at(2026, 10, 6, 23, 0), morning.nextCheckAt)
    }

    @Test
    fun `tonight does not need wifi or charger`() {
        assertEquals(DownloadConditions.Verdict.Ready, eval(DownloadCondition(ConditionKind.TONIGHT), none, at(2026, 10, 5, 2, 0)))
    }

    @Test
    fun `next occurrence is today when still ahead`() {
        assertEquals(at(2026, 10, 5, 14, 30), DownloadConditions.nextOccurrence(14 * 60 + 30, at(2026, 10, 5, 9, 0), berlin))
    }

    @Test
    fun `next occurrence is tomorrow when passed or exactly now`() {
        assertEquals(at(2026, 10, 6, 8, 0), DownloadConditions.nextOccurrence(8 * 60, at(2026, 10, 5, 9, 0), berlin))
        assertEquals(at(2026, 10, 6, 9, 0), DownloadConditions.nextOccurrence(9 * 60, at(2026, 10, 5, 9, 0), berlin))
    }

    @Test
    fun `next occurrence of 00 30 asked at 23 50 is after midnight`() {
        assertEquals(at(2026, 10, 6, 0, 30), DownloadConditions.nextOccurrence(30, at(2026, 10, 5, 23, 50), berlin))
    }

    @Test
    fun `next occurrence survives the spring forward gap`() {
        // 2026-03-29 02:30 does not exist in Berlin: the clock jumps from 02:00 to 03:00.
        val now = at(2026, 3, 29, 1, 0)
        val next = DownloadConditions.nextOccurrence(2 * 60 + 30, now, berlin)
        assertTrue(next > now)
        assertTrue(next - now < 3 * 3_600_000L)
        assertEquals(3, ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(next), berlin).hour)
    }

    @Test
    fun `next occurrence on the fall back day is one real day later plus the extra hour`() {
        // 2026-10-25: 03:00 CEST goes back to 02:00 CET (25 hour day).
        val now = at(2026, 10, 24, 12, 0)
        val next = DownloadConditions.nextOccurrence(12 * 60, now, berlin)
        assertEquals(at(2026, 10, 25, 12, 0), next)
        val midnight = at(2026, 10, 25, 0, 0)
        assertEquals(25 * 3_600_000L, DownloadConditions.nextOccurrence(0, midnight, berlin) - midnight)
    }

    @Test
    fun `night window uses the local zone at that instant`() {
        val c = DownloadCondition(ConditionKind.TONIGHT)
        val instant = at(2026, 10, 5, 23, 30, berlin)
        val tokyo = ZoneId.of("Asia/Tokyo") // 06:30 next day
        assertEquals(DownloadConditions.Verdict.Ready, DownloadConditions.evaluate(c, none, instant, berlin))
        assertEquals(DownloadConditions.Verdict.Ready, DownloadConditions.evaluate(c, none, instant, tokyo))
        val noonBerlin = at(2026, 10, 5, 12, 0, berlin) // 19:00 Tokyo
        assertTrue(DownloadConditions.evaluate(c, none, noonBerlin, tokyo) is DownloadConditions.Verdict.Waiting)
    }

    @Test
    fun `at time waits until the resolved moment`() {
        val now = at(2026, 10, 5, 9, 0)
        val c = DownloadConditions.atTime(10 * 60, now, berlin)
        assertEquals(at(2026, 10, 5, 10, 0), c.atMillis)
        val before = eval(c, none, now) as DownloadConditions.Verdict.Waiting
        assertEquals(listOf(ItemWait.TIME), before.reasons)
        assertEquals(c.atMillis, before.nextCheckAt)
        assertEquals(DownloadConditions.Verdict.Ready, eval(c, none, c.atMillis))
        assertEquals(DownloadConditions.Verdict.Ready, eval(c, none, c.atMillis + 1))
    }

    @Test
    fun `at time ignores wifi and charger`() {
        val c = DownloadCondition(ConditionKind.AT_TIME, 600, 1_000L)
        assertEquals(DownloadConditions.Verdict.Ready, eval(c, none, 2_000L))
    }

    @Test
    fun `shiftQuarter wraps both ways`() {
        assertEquals(15, DownloadConditions.shiftQuarter(0, 1))
        assertEquals(23 * 60 + 45, DownloadConditions.shiftQuarter(0, -1))
        assertEquals(0, DownloadConditions.shiftQuarter(23 * 60 + 45, 1))
    }

    @Test
    fun `earliestCheck picks the nearest future moment`() {
        val list = listOf(
            DownloadConditions.Verdict.Waiting(listOf(ItemWait.TIME), 500L),
            DownloadConditions.Verdict.Waiting(listOf(ItemWait.WIFI), null),
            DownloadConditions.Verdict.Waiting(listOf(ItemWait.NIGHT), 300L),
            DownloadConditions.Verdict.Ready
        )
        assertEquals(300L, DownloadConditions.earliestCheck(list, 100L))
        assertEquals(500L, DownloadConditions.earliestCheck(list, 300L))
        assertNull(DownloadConditions.earliestCheck(list, 500L))
    }

    @Test
    fun `encode and decode round trip with awkward names`() {
        val map = linkedMapOf(
            "Super Game (USA) | v1.zip" to DownloadCondition(ConditionKind.WIFI_AND_CHARGING),
            "a\nb.7z" to DownloadCondition(ConditionKind.AT_TIME, 870, 1_788_000_000_000L),
            "plain.chd" to DownloadCondition(ConditionKind.TONIGHT)
        )
        assertEquals(map, DownloadConditions.decode(DownloadConditions.encode(map)))
    }

    @Test
    fun `decode skips damaged lines`() {
        val text = "WIFI|0|0|ok.zip\ngarbage\nNOPE|0|0|x\nCHARGING|x|0|y\nTONIGHT|0|0|"
        assertEquals(mapOf("ok.zip" to DownloadCondition(ConditionKind.WIFI)), DownloadConditions.decode(text))
        assertEquals(emptyMap<String, DownloadCondition>(), DownloadConditions.decode(""))
    }
}
