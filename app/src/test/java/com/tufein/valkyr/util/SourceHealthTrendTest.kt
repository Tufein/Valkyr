package com.tufein.valkyr.util

import com.tufein.valkyr.util.SourceHealth.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceHealthTrendTest {
    private val day = 86_400_000L
    private val now = 100 * day
    private fun ok(daysAgo: Long) = Outcome(now - daysAgo * day, null)
    private fun bad(daysAgo: Long, k: FailureKind = FailureKind.RATE_LIMITED) = Outcome(now - daysAgo * day, k)

    @Test fun `a healthy source has no trend`() = assertNull(SourceHealth.trend(listOf(ok(9), ok(5), ok(1)), now))

    @Test fun `one failure that was followed by an answer is not news`() =
        assertNull(SourceHealth.trend(listOf(ok(9), bad(5), ok(1)), now))

    @Test fun `three failures in a row are a streak with the last reason`() {
        val t = SourceHealth.trend(listOf(ok(20), bad(14, FailureKind.NETWORK), bad(7), bad(1)), now)!!
        assertEquals(3, t.streak); assertEquals(3, t.failures); assertEquals(4, t.scans)
        assertEquals(FailureKind.RATE_LIMITED, t.last)
    }

    @Test fun `an intermittent source is told by its count`() {
        val t = SourceHealth.trend(listOf(bad(20), ok(15), bad(10), ok(2)), now)!!
        assertEquals(0, t.streak); assertEquals(2, t.failures); assertEquals(4, t.scans)
    }

    @Test fun `failures older than a month are forgotten`() =
        assertNull(SourceHealth.trend(listOf(bad(60), bad(45), ok(1)), now))

    @Test fun `history is bounded and survives a round trip`() {
        var h = emptyList<Outcome>()
        for (i in 1L..20L) h = SourceHealth.append(h, Outcome(i, if (i % 2 == 0L) FailureKind.TIMEOUT else null))
        assertEquals(SourceHealth.KEEP, h.size)
        assertEquals(h, SourceHealth.decode(SourceHealth.encode(h)))
        assertEquals(emptyList<Outcome>(), SourceHealth.decode("junk,:,x:y"))
    }
}
