package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The download-side counterpart of SourceHealthTrendTest: outcomes over weeks, not only "now". */
class SourceRankingTrendTest {
    private val day = 86_400_000L
    private val now = 100 * day
    private val mb = 1024L * 1024

    private fun record(vararg outcomes: SourceOutcome) =
        SourceRecord(outcomes = outcomes.toList())

    private fun ok(daysAgo: Long, bytes: Long = 10 * mb) = SourceOutcome(now - daysAgo * day, true, bytes)
    private fun bad(daysAgo: Long, why: FailureClass = FailureClass.SERVER) =
        SourceOutcome(now - daysAgo * day, false, failure = why)

    @Test fun `a healthy source has no trend to report`() {
        assertNull(SourceRanking.trend(null, now))
        assertNull(SourceRanking.trend(record(ok(1), ok(0)), now))
        // One failure followed by a success is not news.
        assertNull(SourceRanking.trend(record(bad(4), ok(0)), now))
    }

    @Test fun `a streak and repeated failures are named`() {
        val t = SourceRanking.trend(record(bad(9, FailureClass.NOT_FOUND), ok(8), bad(2, FailureClass.SERVER), bad(0, FailureClass.SERVER)), now)
        checkNotNull(t)
        assertEquals(3, t.failures)
        assertEquals(4, t.downloads)
        assertEquals(2, t.streak)
        assertEquals(FailureClass.SERVER, t.last)
        assertEquals(now, t.newestAt)
    }

    @Test fun `only the last thirty days count and the future is ignored`() {
        val old = record(bad(60), bad(50), bad(40))
        assertNull(SourceRanking.trend(old, now))
        val t = SourceRanking.trend(record(bad(35), bad(2), bad(1), SourceOutcome(now + day, false, failure = FailureClass.NETWORK)), now)
        checkNotNull(t)
        assertEquals(2, t.failures)
        assertEquals(2, t.downloads)
    }

    @Test fun `a demoted source gets a lower score`() {
        val good = SourceRecord(successes = 9, failures = 1)
        val bad = good.copy(failureStreak = 2, lastFailureAt = now - 1_000)
        assertTrue(SourceRanking.score(good, now) > SourceRanking.score(bad, now))
        assertEquals(50, SourceRanking.score(null, now))
    }

    @Test fun `outcomes are appended and bounded`() {
        var r = SourceRecord()
        repeat(SourceRanking.KEEP_OUTCOMES + 5) { i -> r = SourceRanking.withSuccess(r, 2 * mb, 2_000, now + i) }
        assertEquals(SourceRanking.KEEP_OUTCOMES, r.outcomes.size)
        assertEquals(now + 5L, r.outcomes.first().at)
        assertEquals(now + SourceRanking.KEEP_OUTCOMES + 4L, r.outcomes.last().at)
        val failed = SourceRanking.withFailure(r, FailureClass.FORBIDDEN, now + 100)
        assertEquals(SourceRanking.KEEP_OUTCOMES, failed.outcomes.size)
        assertEquals(FailureClass.FORBIDDEN, failed.outcomes.last().failure)
        assertFalse(failed.outcomes.last().ok)
    }

    @Test fun `old counts fade but the timeline stays`() {
        val withEvents = SourceRecord(successes = 60, failures = 40, outcomes = listOf(ok(1), bad(2)))
        val aged = SourceRanking.withSuccess(withEvents, 0, 0, now)
        assertEquals(31, aged.successes)
        assertEquals(20, aged.failures)
        assertEquals(3, aged.outcomes.size)
    }

    @Test fun `json round trips the timeline and still reads old files`() {
        val records = mapOf("https://a.org/x" to SourceRanking.withFailure(
            SourceRanking.withSuccess(SourceRecord(), 4 * mb, 4_000, now), FailureClass.SERVER, now + 1))
        assertEquals(records, SourceRanking.fromJson(SourceRanking.toJson(records)))
        val legacy = """{"https://a.org/x":{"speed":1.0,"ok":2,"failed":1,"okAt":3,"failedAt":4,"streak":1,"why":"SERVER"}}"""
        val old = SourceRanking.fromJson(legacy).values.single()
        assertEquals(2, old.successes)
        assertTrue(old.outcomes.isEmpty())
        // A broken event must not throw away the whole record.
        val broken = """{"https://a.org/x":{"ok":1,"events":[{"t":"nope"},42]}}"""
        assertEquals(1, SourceRanking.fromJson(broken).values.single().successes)
    }

    @Test fun `the export has numbers but no addresses or file names`() {
        val records = mapOf(
            "https://user:secret@host.example.org/private/path?token=abc123" to record(ok(2), bad(1)),
            "magnet:?xt=urn:btih:deadbeef&dn=Secret%20Set" to record(ok(1))
        )
        val text = SourceRanking.exportJson(records, now)
        assertFalse(text.contains("host.example.org"))
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("token"))
        assertFalse(text.contains("deadbeef"))
        assertFalse(text.contains("Secret Set"))
        assertFalse(text.contains("://"))
        assertTrue(text.contains("\"ok\""))
        assertTrue(text.contains("\"events\""))
        // A caller can supply labels; the export still never writes the keys themselves.
        assertFalse(SourceRanking.exportJson(records, now, labelOf = { "x" }).contains("https://"))
    }
}
