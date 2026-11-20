package com.tufein.valkyr.util

import com.tufein.valkyr.data.model.Console
import com.tufein.valkyr.data.model.Manufacturer
import com.tufein.valkyr.data.model.UrlEntry
import com.tufein.valkyr.data.state.SourceScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceTrackerTest {
    private val now = 100 * 86_400_000L
    private val web = "https://host.example.org/files"
    private val romm = "romm://snes"

    private fun manufacturers() = listOf(Manufacturer("nintendo", "Nintendo", listOf(
        Console("snes", "SNES", listOf(UrlEntry(web), UrlEntry(romm, enabled = false)))
    )))

    @Test fun `every configured source gets a row with both records`() {
        val scan = SourceScanResult(files = 42, at = now - 3_600_000L)
        val record = SourceRecord(successes = 8, failures = 2)
        val rows = SourceTracker.rows(manufacturers(), mapOf("snes|$web" to scan), mapOf(web to record), now)
        assertEquals(2, rows.size)
        val webRow = rows.first { it.url == web }
        assertEquals("SNES", webRow.consoleName)
        assertEquals("Nintendo", webRow.manufacturerName)
        assertEquals("host.example.org", webRow.label)
        assertEquals(SourceKind.WEB, webRow.kind)
        assertEquals(42, webRow.games)
        assertEquals(80, webRow.record?.okPercent)
        assertEquals(75, webRow.score)
        assertNotNull(webRow.record)
        val rommRow = rows.first { it.url == romm }
        assertEquals(SourceKind.ROMM, rommRow.kind)
        assertTrue(!rommRow.enabled)
        assertNull(rommRow.games)
    }

    @Test fun `disabled sources go last and failing ones first`() {
        val good = SourceRecord(successes = 10)
        val bad = SourceRecord(failures = 10, failureStreak = 2, lastFailureAt = now - 1_000)
        val both = listOf(UrlEntry(web), UrlEntry(romm))
        val manufacturers = listOf(Manufacturer("n", "N", listOf(Console("snes", "SNES", both))))
        val rows = SourceTracker.rows(manufacturers, emptyMap(), mapOf(web to good, romm to bad), now)
        assertEquals(romm, rows.first().url)
    }

    @Test fun `a scan trend and a download trend are both reported`() {
        val scan = SourceScanResult(
            files = 1, at = now,
            history = listOf(
                SourceHealth.Outcome(now - 4 * 86_400_000L, FailureKind.RATE_LIMITED),
                SourceHealth.Outcome(now - 3 * 86_400_000L, FailureKind.RATE_LIMITED)
            )
        )
        val record = SourceRanking.withFailure(
            SourceRanking.withFailure(SourceRecord(), FailureClass.NETWORK, now - 2 * 86_400_000L),
            FailureClass.SERVER, now
        )
        val rows = SourceTracker.rows(manufacturers(), mapOf("snes|$web" to scan), mapOf(web to record), now)
        val row = rows.first { it.url == web }
        assertEquals(2, row.scanTrend?.failures)
        assertEquals(FailureKind.RATE_LIMITED, row.scanTrend?.last)
        assertNotNull(row.downloadTrend)
        assertEquals(FailureClass.SERVER, row.downloadTrend?.last)
    }

    private val broken = SourceRecord(failures = 10, failureStreak = 3, lastFailureAt = now - 1_000)
    private val mirror = "https://mirror.example.net/files"

    private fun twoOf(vararg entries: UrlEntry) =
        listOf(Manufacturer("n", "N", listOf(Console("snes", "SNES", entries.toList()))))

    @Test fun `a row knows its position in the console list`() {
        val rows = SourceTracker.rows(twoOf(UrlEntry(web), UrlEntry(mirror)), emptyMap(), emptyMap(), now)
        assertEquals(0, rows.first { it.url == web }.index)
        assertEquals(1, rows.first { it.url == mirror }.index)
    }

    @Test fun `a failing source is paused when another one still works`() {
        val rows = SourceTracker.rows(twoOf(UrlEntry(web), UrlEntry(mirror)), emptyMap(), mapOf(web to broken), now)
        assertEquals(listOf(web), SourceTracker.pausable(rows, now).map { it.url })
    }

    @Test fun `the last working source of a console is never paused`() {
        val rows = SourceTracker.rows(twoOf(UrlEntry(web), UrlEntry(mirror)), emptyMap(), mapOf(web to broken, mirror to broken), now)
        assertTrue(SourceTracker.pausable(rows, now).isEmpty())
    }

    @Test fun `a switched off source does not count as a working one`() {
        val rows = SourceTracker.rows(twoOf(UrlEntry(web), UrlEntry(mirror, enabled = false)), emptyMap(), mapOf(web to broken), now)
        assertTrue(SourceTracker.pausable(rows, now).isEmpty())
    }

    @Test fun `a failed scan counts as needing attention`() {
        val scan = SourceScanResult(files = 0, at = now, failure = FailureKind.NOT_FOUND)
        val rows = SourceTracker.rows(twoOf(UrlEntry(web), UrlEntry(mirror)), mapOf("snes|$web" to scan), emptyMap(), now)
        assertTrue(SourceTracker.needsAttention(rows.first { it.url == web }, now))
        assertEquals(listOf(web), SourceTracker.pausable(rows, now).map { it.url })
    }
}
