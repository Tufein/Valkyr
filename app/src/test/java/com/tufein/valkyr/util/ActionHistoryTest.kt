package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class ActionHistoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val zone = ZoneId.of("UTC")
    private fun at(date: String, hour: Int = 12): Long = LocalDate.parse(date).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    private var next = 0
    private fun entry(
        kind: ActionKind, title: String = "Game", at: Long = 1L, console: String? = null, file: String? = null,
        op: String? = null, undone: Boolean = false, count: Int = 0, bytes: Long = 0L
    ) = ActionEntry("e${next++}", at, kind, title, consoleId = console, fileName = file, opId = op, undone = undone, count = count, bytes = bytes)

    // ---- Format -------------------------------------------------------------------------------

    @Test fun `a line round-trips with every field`() {
        val e = ActionEntry(
            "abc", 1_700_000_000_000L, ActionKind.SYNCED, "Chrono Trigger", ActionTopic.SAVE_SYNC, "snes", "Chrono Trigger.sfc", "op-1",
            ActionReason.BY_USER, mapOf(ActionCount.UPLOADED to 3, ActionCount.CONFLICTS to 1), 2, 4_096L, undone = true
        )
        assertEquals(e, ActionLogFormat.parseLine(ActionLogFormat.line(e)))
    }

    @Test fun `optional fields stay out of the line and come back empty`() {
        val e = ActionEntry("x", 5L, ActionKind.PLAYED, "Zelda")
        val line = ActionLogFormat.line(e)
        assertFalse(line.contains("reason") || line.contains("\"c\"") || line.contains("\"n\"") || line.contains("bytes") || line.contains("undone"))
        assertEquals(e, ActionLogFormat.parseLine(line))
    }

    @Test fun `an unknown kind reads as other and a broken line is skipped`() {
        assertEquals(ActionKind.OTHER, ActionLogFormat.parseLine("""{"id":"a","at":1,"kind":"FROM_THE_FUTURE"}""")!!.kind)
        assertNull(ActionLogFormat.parseLine("not json"))
        assertNull(ActionLogFormat.parseLine("""{"id":"a","at":1,"kind":"PLA"""))
    }

    @Test fun `trim keeps the newest entries and drops old ones`() {
        val now = 200L * 24 * 60 * 60 * 1000
        val old = entry(ActionKind.PLAYED, at = 1L)
        val many = (1..ActionLogFormat.MAX_ENTRIES + 5).map { entry(ActionKind.PLAYED, at = now - 1000 + it) }
        val trimmed = ActionLogFormat.trim(listOf(old) + many, now)
        assertEquals(ActionLogFormat.MAX_ENTRIES, trimmed.size)
        assertEquals(many.last(), trimmed.last())
        assertFalse(old in trimmed)
    }

    @Test fun `cloud reasons keep the code and never the detail or free text`() {
        assertEquals("cloud:dav|NETWORK|0|", ActionReason.cloud("dav|NETWORK|0|https://user@cloud.example/remote.php"))
        assertEquals("cloud:k|NO_PASSPHRASE", ActionReason.cloud("k|NO_PASSPHRASE"))
        assertEquals("cloud:other", ActionReason.cloud("other|/storage/emulated/0/secret failed"))
    }

    // ---- File -----------------------------------------------------------------------------------

    @Test fun `appended lines read back in order and a rewrite replaces the file`() {
        val file = File(tmp.root, "log.jsonl")
        val store = ActionLogFile(file)
        assertTrue(store.load().isEmpty())
        val a = entry(ActionKind.PLAYED, at = 1)
        val b = entry(ActionKind.DOWNLOADED, at = 2)
        store.append(a); store.append(b)
        assertEquals(2, store.lines)
        assertEquals(listOf(a, b), ActionLogFile(file).load())
        store.rewrite(listOf(b.copy(undone = true)))
        assertEquals(listOf(b.copy(undone = true)), ActionLogFile(file).load())
        assertFalse(File(tmp.root, "log.jsonl.tmp").exists())
        store.rewrite(emptyList())
        assertFalse(file.exists())
    }

    @Test fun `a line cut off by a crash is skipped and the next append starts a fresh line`() {
        val file = File(tmp.root, "log.jsonl")
        val a = entry(ActionKind.PLAYED, at = 1)
        file.writeText(ActionLogFormat.line(a) + "\n" + """{"id":"cut","at":2,"ki""")
        File(tmp.root, "log.jsonl.tmp").writeText("half a rewrite")
        val store = ActionLogFile(file)
        assertEquals(listOf(a), store.load())
        assertFalse(File(tmp.root, "log.jsonl.tmp").exists())
        val b = entry(ActionKind.REMOVED, at = 3)
        store.append(b)
        assertEquals(listOf(a, b), ActionLogFile(file).load())
    }

    @Test fun `clear removes private history from a failed rewrite temporary file too`() {
        val file = File(tmp.root, "log.jsonl")
        val store = ActionLogFile(file)
        store.append(entry(ActionKind.PLAYED, "Private game"))
        val pending = File(tmp.root, "log.jsonl.tmp").apply { writeText(file.readText()) }
        store.rewrite(emptyList())
        assertFalse(file.exists())
        assertFalse(pending.exists())
    }

    @Test fun `clear refuses to succeed while a temporary history cannot be removed`() {
        val file = File(tmp.root, "log.jsonl")
        val store = ActionLogFile(file)
        store.append(entry(ActionKind.PLAYED, "Private game"))
        val original = file.readText()
        val pending = File(tmp.root, "log.jsonl.tmp").apply { mkdirs() }
        File(pending, "blocked").writeText("keep")
        var refused = false
        try { store.rewrite(emptyList()) } catch (_: java.io.IOException) { refused = true }
        assertTrue(refused)
        assertEquals(original, file.readText())
    }

    // ---- Filter, search, days -------------------------------------------------------------------

    @Test fun `chips filter by kind and words must all match`() {
        val list = listOf(
            entry(ActionKind.DOWNLOADED, "Super Mario World", console = "snes"),
            entry(ActionKind.REMOVED, "Mario Kart", console = "n64"),
            entry(ActionKind.SYNCED, "")
        )
        assertEquals(1, ActionHistory.filter(list, ActionFilter.DOWNLOADS, "").size)
        assertEquals(2, ActionHistory.filter(list, ActionFilter.ALL, "mario").size)
        assertEquals(1, ActionHistory.filter(list, ActionFilter.ALL, "mario n64").size)
        assertEquals(1, ActionHistory.filter(list, ActionFilter.SYNC, "").size)
        assertEquals(ActionFilter.GAMES, ActionFilter.ALL.step(-1))
        assertEquals(ActionFilter.DOWNLOADS, ActionFilter.ALL.step(1))
    }

    @Test fun `entries are grouped by day newest first`() {
        val now = at("2026-10-07", 18)
        val list = listOf(entry(ActionKind.PLAYED, at = at("2026-10-07", 9)), entry(ActionKind.PLAYED, at = at("2026-10-07", 8)), entry(ActionKind.PLAYED, at = at("2026-10-06")))
        val days = ActionHistory.groupByDay(list, now, zone)
        assertEquals(2, days.size)
        assertEquals(DayLabel.Today, days[0].label)
        assertEquals(2, days[0].entries.size)
        assertEquals(DayLabel.Yesterday, days[1].label)
    }

    @Test fun `only lines naming a library file open the game page`() {
        assertTrue(ActionHistory.openable(entry(ActionKind.DOWNLOADED, console = "snes", file = "a.zip")))
        assertFalse(ActionHistory.openable(entry(ActionKind.REMOVED, console = "snes", file = "a.sfc")))
        assertFalse(ActionHistory.openable(entry(ActionKind.DOWNLOADED, "", console = "snes", file = "a.zip", count = 3)))
    }

    // ---- Ways back ------------------------------------------------------------------------------

    @Test fun `a removal offers restore until it is restored or purged`() {
        val removed = entry(ActionKind.REMOVED, file = "a.sfc", op = "op1")
        assertEquals(UndoAction.RESTORE, ActionHistory.undoCandidates(listOf(removed))[removed.id])
        val restored = entry(ActionKind.RESTORED, op = "op1")
        assertTrue(ActionHistory.undoCandidates(listOf(restored, removed)).isEmpty())
        val purged = entry(ActionKind.PURGED, file = "a.sfc", op = "op1")
        val offers = ActionHistory.undoCandidates(listOf(purged, removed))
        assertEquals(mapOf(purged.id to UndoAction.DOWNLOAD_AGAIN), offers)
    }

    @Test fun `download again is offered once per game and not after it came back`() {
        val failedOld = entry(ActionKind.DOWNLOAD_FAILED, file = "a.zip")
        val failedNew = entry(ActionKind.DOWNLOAD_FAILED, file = "a.zip")
        assertEquals(setOf(failedNew.id), ActionHistory.undoCandidates(listOf(failedNew, failedOld)).keys)
        val downloaded = entry(ActionKind.DOWNLOADED, file = "a.zip")
        assertTrue(ActionHistory.undoCandidates(listOf(downloaded, failedNew, failedOld)).isEmpty())
        // A used way back is not offered again, and a summary line has none.
        assertTrue(ActionHistory.undoCandidates(listOf(entry(ActionKind.PURGED, file = "b.zip", op = "x", undone = true))).isEmpty())
        assertTrue(ActionHistory.undoCandidates(listOf(entry(ActionKind.DOWNLOAD_FAILED, "", count = 4))).isEmpty())
    }

    @Test fun `offers come newest first`() {
        val older = entry(ActionKind.REMOVED, op = "o1", file = "1")
        val newer = entry(ActionKind.REMOVED, op = "o2", file = "2")
        assertEquals(listOf(newer.id, older.id), ActionHistory.undoCandidates(listOf(newer, older)).keys.toList())
    }

    @Test fun `same filename on different consoles keeps both download again offers`() {
        val snes = entry(ActionKind.DOWNLOAD_FAILED, console = "snes", file = "Game.zip")
        val nes = entry(ActionKind.DOWNLOAD_FAILED, console = "nes", file = "Game.zip")
        assertEquals(setOf(snes.id, nes.id), ActionHistory.undoCandidates(listOf(snes, nes)).keys)
    }

    @Test fun `download on another console does not hide a failed game offer`() {
        val failed = entry(ActionKind.DOWNLOAD_FAILED, console = "nes", file = "Game.zip")
        val downloaded = entry(ActionKind.DOWNLOADED, console = "snes", file = "Game.zip")
        assertEquals(mapOf(failed.id to UndoAction.DOWNLOAD_AGAIN), ActionHistory.undoCandidates(listOf(downloaded, failed)))
    }

    // ---- Download runs --------------------------------------------------------------------------

    @Test fun `a run writes its first lines and sums up the rest when it ends`() {
        val tally = DownloadRunTally(perRun = 3, perHour = 100)
        var id = 0
        val written = (1..10).count { tally.accept(entry(ActionKind.DOWNLOADED, bytes = 10), it * 1000L) }
        assertEquals(3, written)
        val summary = tally.endRun(20_000L) { "s${id++}" }
        assertEquals(1, summary.size)
        assertEquals(7, summary[0].count)
        assertEquals(70L, summary[0].bytes)
        assertTrue(summary[0].isSummary)
        // The next run starts fresh.
        assertTrue(tally.accept(entry(ActionKind.DOWNLOADED), 30_000L))
        assertTrue(tally.endRun(31_000L) { "s${id++}" }.isEmpty())
    }

    @Test fun `a slow queue of 500 games stays a handful of lines`() {
        val tally = DownloadRunTally()
        var lines = 0
        var counted = 0
        var now = 0L
        repeat(500) {
            now += 2 * 60 * 1000L // one game every two minutes: a run of more than 16 hours
            if (tally.accept(entry(ActionKind.DOWNLOADED), now)) lines++
            tally.tick(now) { "t" }.forEach { lines++; counted += it.count }
        }
        tally.endRun(now) { "e" }.forEach { lines++; counted += it.count }
        assertEquals(500, counted + DownloadRunTally.PER_RUN)
        assertTrue("lines: $lines", lines < 50)
    }

    @Test fun `separate runs are capped per hour`() {
        val tally = DownloadRunTally(perRun = 8, perHour = 5)
        var written = 0
        repeat(20) { i ->
            if (tally.accept(entry(ActionKind.DOWNLOAD_FAILED), i * 60_000L)) written++
            tally.endRun(i * 60_000L + 10_000L) { "r" }
        }
        assertEquals(5, written)
    }
}
