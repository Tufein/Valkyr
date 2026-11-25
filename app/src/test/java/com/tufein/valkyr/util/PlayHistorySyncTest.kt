package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayHistorySyncTest {
    private fun s(game: String, start: Long, end: Long, device: String = "") = PlaySessions.Session(game, "p1", start, end, device)
    private val h = 3_600_000L

    @Test fun snapshotKeepsFinishedSessionsOfTheProfileAndNamesThem() {
        val doc = PlayHistorySync.snapshot(
            listOf(s("gba|A.gba", 1_000, 1_000 + h), s("gba|B.gba", 5_000, 0L), PlaySessions.Session("gba|C.gba", "other", 2_000, 2_000 + h), s("gba|D.gba", 3_000, 3_000 + h, "Thor")),
            "p1", " Pocket "
        )
        assertEquals(listOf("gba|A.gba" to "Pocket", "gba|D.gba" to "Thor"), doc.map { it.game to it.device })
    }

    @Test fun mergeIsAUnionAndNeverCountsASessionTwice() {
        val a = listOf(SyncSession("gba|A.gba", 1_000, 1_000 + h, "Pocket"), SyncSession("gba|B.gba", 9_000, 9_000 + h, "Pocket"))
        val b = listOf(SyncSession("gba|A.gba", 1_000, 1_000 + h, "Thor"), SyncSession("nes|C.nes", 5_000, 5_000 + h, "Thor"))
        val merged = PlayHistorySync.merge(a, b)
        assertEquals(3, merged.size)
        assertEquals(merged, PlayHistorySync.merge(b, a).let { m -> m.map { if (it.game == "gba|A.gba") it.copy(device = "Pocket") else it } })
        assertEquals(merged, PlayHistorySync.merge(merged, merged))
    }

    @Test fun anUnnamedCopyTakesTheNameOfTheNamedOne() {
        val merged = PlayHistorySync.merge(listOf(SyncSession("gba|A.gba", 1_000, 1_000 + h, "")), listOf(SyncSession("gba|A.gba", 1_000, 1_000 + h, "Thor")))
        assertEquals("Thor", merged.single().device)
    }

    @Test fun encodeThenDecodeGivesTheSameDocument() {
        val doc = PlayHistorySync.merge(listOf(SyncSession("gba|A.gba", 1_000, 1_000 + h, "Pocket"), SyncSession("nes|C.nes", 5_000, 5_000 + h, "")), null)
        val parsed = PlayHistorySync.decode(PlayHistorySync.encode(doc, "rev1", 7L)) as DocParsed.Ok
        assertEquals(doc, parsed.value)
        assertEquals("rev1", parsed.rev)
    }

    @Test fun decodeSkipsImplausibleRowsAndRefusesNewerOrForeignFiles() {
        val text = """{"format":"valkyr-play-history","version":1,"sessions":[
            {"g":"gba|A.gba","s":1000,"e":2000},{"g":"nogame","s":1000,"e":2000},{"g":"gba|B.gba","s":5000,"e":4000},
            {"g":"gba|C.gba","s":1000,"e":99999999999},{"g":"gba|D.gba","s":-1,"e":5},"junk"]}"""
        assertEquals(listOf("gba|A.gba"), (PlayHistorySync.decode(text) as DocParsed.Ok).value.map { it.game })
        assertTrue(PlayHistorySync.decode("""{"format":"valkyr-play-history","version":2,"sessions":[]}""") is DocParsed.Newer)
        assertTrue(PlayHistorySync.decode("""{"format":"other","version":1}""") is DocParsed.Invalid)
        assertTrue(PlayHistorySync.decode("not json") is DocParsed.Invalid)
    }

    @Test fun theDocumentIsBoundedAndKeepsTheNewestSessions() {
        val many = (1..PlaySessions.MAX_SESSIONS + 50).map { SyncSession("gba|G$it.gba", it * 10L, it * 10L + 5, "") }
        val merged = PlayHistorySync.merge(many, emptyList())
        assertEquals(PlaySessions.MAX_SESSIONS, merged.size)
        assertEquals(51L * 10, merged.first().startMs)
    }

    @Test fun backupJsonKeepsTheHandheldName() {
        val back = PlaySessions.fromJson(PlaySessions.toJson(listOf(s("gba|A.gba", 1_000, 1_000 + h, "Thor"))))
        assertEquals("Thor", back.single().device)
        assertEquals(listOf("Thor"), PlaySessions.totals(back, "gba|A.gba", "p1").devices)
    }

    @Test fun mergeInSessionsPrefersTheNamedCopy() {
        val m = PlaySessions.merge(listOf(s("gba|A.gba", 1_000, 1_000 + h, "Thor")), listOf(s("gba|A.gba", 1_000, 1_000 + h, "")))
        assertEquals(1, m.size); assertEquals("Thor", m.single().device)
        val m2 = PlaySessions.merge(listOf(s("gba|A.gba", 1_000, 1_000 + h, "")), listOf(s("gba|A.gba", 1_000, 1_000 + h, "Thor")))
        assertEquals("Thor", m2.single().device)
    }
}
