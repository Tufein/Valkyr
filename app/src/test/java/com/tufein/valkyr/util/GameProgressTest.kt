package com.tufein.valkyr.util

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameProgressTest {

    private fun e(key: String, status: PlayStatus?, rating: Int, at: Long) = ProgressEntry(key, status, rating, at)
    private fun map(vararg entries: ProgressEntry) = entries.associateBy { it.key }

    @Test fun `a game on one side only is added`() {
        val m = ProgressSync.merge(emptyMap(), map(e("snes|a", PlayStatus.PLAYING, 0, 1)), map(e("snes|b", null, 4, 2)))
        assertEquals(setOf("snes|a", "snes|b"), m.entries.keys)
        assertTrue(m.conflicts.isEmpty())
    }

    @Test fun `a change on one side takes it, whichever is newer`() {
        val base = map(e("n|a", PlayStatus.PLAYING, 3, 10))
        val remote = map(e("n|a", PlayStatus.FINISHED, 3, 20))
        assertEquals(PlayStatus.FINISHED, ProgressSync.merge(base, base, remote).entries["n|a"]?.status)
        // The local side changed, the remote one did not: the older local change still wins over an unchanged remote.
        val local = map(e("n|a", PlayStatus.DROPPED, 3, 5))
        assertEquals(PlayStatus.DROPPED, ProgressSync.merge(base, local, base).entries["n|a"]?.status)
    }

    @Test fun `different changes on both sides keep the newer and list the other`() {
        val base = map(e("n|a", PlayStatus.PLAYING, 2, 10))
        val local = map(e("n|a", PlayStatus.FINISHED, 4, 30))
        val remote = map(e("n|a", PlayStatus.DROPPED, 1, 20))
        val m = ProgressSync.merge(base, local, remote)
        assertEquals(PlayStatus.FINISHED, m.entries["n|a"]?.status)
        assertEquals(1, m.conflicts.size)
        assertTrue(m.conflicts[0].keptMine)
        assertEquals(PlayStatus.DROPPED, m.conflicts[0].theirs.status)
        val flipped = ProgressSync.merge(base, remote, local)
        assertEquals(PlayStatus.FINISHED, flipped.entries["n|a"]?.status)
        assertEquals(false, flipped.conflicts[0].keptMine)
    }

    @Test fun `the same change on both sides is no conflict`() {
        val a = map(e("n|a", PlayStatus.FINISHED, 5, 10)); val b = map(e("n|a", PlayStatus.FINISHED, 5, 40))
        val m = ProgressSync.merge(emptyMap(), a, b)
        assertTrue(m.conflicts.isEmpty())
        assertEquals(40L, m.entries["n|a"]?.changedAt)
    }

    @Test fun `without a base two different values are a conflict`() {
        val m = ProgressSync.merge(emptyMap(), map(e("n|a", null, 3, 1)), map(e("n|a", null, 5, 2)))
        assertEquals(1, m.conflicts.size)
        assertEquals(5, m.entries["n|a"]?.rating)
    }

    @Test fun `deciding a conflict writes a newer change so it travels`() {
        val c = ProgressConflict("n|a", e("n|a", PlayStatus.FINISHED, 4, 30), e("n|a", PlayStatus.DROPPED, 1, 20), keptMine = true)
        val r = ProgressSync.resolve(map(c.mine), c, keepMine = false, now = 25)
        assertEquals(PlayStatus.DROPPED, r["n|a"]?.status)
        assertEquals(25L, r["n|a"]?.changedAt)
        assertEquals(21L, ProgressSync.resolve(map(c.mine), c, keepMine = false, now = 5)["n|a"]?.changedAt)
    }

    @Test fun `the document round trips and refuses a newer format`() {
        val m = map(e("snes|Super Metroid.sfc", PlayStatus.COMPLETED, 5, 99), e("nes|x.nes", null, 0, 7))
        val parsed = ProgressSync.decode(ProgressSync.encode(m, "rev1", 123)) as DocParsed.Ok
        assertEquals(m, parsed.value); assertEquals("rev1", parsed.rev)
        assertTrue(ProgressSync.decode("""{"format":"valkyr-progress","version":9,"entries":[]}""") is DocParsed.Newer)
        assertTrue(ProgressSync.decode("not json") is DocParsed.Invalid)
        val clamped = (ProgressSync.decode("""{"format":"valkyr-progress","version":1,"entries":[{"k":"a|b","s":"NOPE","r":99,"t":-5}]}""") as DocParsed.Ok).value["a|b"]!!
        assertEquals(5, clamped.rating); assertEquals(null, clamped.status); assertEquals(0L, clamped.changedAt)
    }

    @Test fun `file names come from the profile name`() {
        assertEquals("progress_kids-room.json", ProgressSync.fileName(" Kids Room "))
        assertEquals("notes_default.json", NotesSync.fileName(""))
    }

    private fun note(key: String, current: String, at: Long, vararg others: String) = NoteDoc(key, current, (listOf(current) + others).filter { it.isNotBlank() }.toSet(), at)

    @Test fun `the newer note is shown and the older one is kept`() {
        val m = NotesSync.merge(mapOf("n|a" to note("n|a", "old", 1)), mapOf("n|a" to note("n|a", "new", 2)))
        assertEquals("new", m["n|a"]?.current)
        assertEquals(setOf("new", "old"), m["n|a"]?.texts)
        assertEquals(m, NotesSync.merge(mapOf("n|a" to note("n|a", "new", 2)), mapOf("n|a" to note("n|a", "old", 1))))
    }

    @Test fun `merging twice changes nothing`() {
        val a = mapOf("n|a" to note("n|a", "x", 5, "w"), "n|b" to note("n|b", "p", 1))
        val b = mapOf("n|a" to note("n|a", "y", 5), "n|c" to note("n|c", "q", 2))
        val once = NotesSync.merge(a, b)
        assertEquals(once, NotesSync.merge(once, b))
        assertEquals(once, NotesSync.merge(b, once))
        assertEquals(setOf("n|a", "n|b", "n|c"), once.keys)
    }

    @Test fun `notes round trip`() {
        val m = mapOf("n|a" to note("n|a", "text \"quoted\"", 9, "older"))
        assertEquals(m, (NotesSync.decode(NotesSync.encode(m, "r", 1)) as DocParsed.Ok).value)
    }

    // --- the engine, on the in-memory server ---

    private class ProgressSide(var entries: Map<String, ProgressEntry>, var base: Map<String, ProgressEntry> = emptyMap()) :
        DocSyncEngine.Local<Map<String, ProgressEntry>, ProgressSync.Merged> {
        var conflicts: List<ProgressConflict> = emptyList()
        override suspend fun snapshot() = entries
        override fun merge(mine: Map<String, ProgressEntry>, remote: Map<String, ProgressEntry>?) = ProgressSync.merge(base, mine, remote)
        override fun docOf(merged: ProgressSync.Merged) = merged.entries
        override suspend fun commit(merged: ProgressSync.Merged, mine: Map<String, ProgressEntry>): Boolean {
            conflicts = merged.conflicts; base = merged.entries
            val changed = merged.entries != mine; entries = merged.entries; return changed
        }
    }

    private fun engine(store: FakeDavStore, side: ProgressSide) =
        DocSyncEngine(store, "https://h/", "https://h/Dogmatix/", ProgressSync.fileName("Me"), side, ProgressCodec)

    @Test fun `two handhelds end with the same progress and a week offline loses nothing`() = runBlocking {
        val store = FakeDavStore("/", "/Dogmatix")
        val a = ProgressSide(mapOf("n|a" to e("n|a", PlayStatus.PLAYING, 0, 1)))
        val b = ProgressSide(mapOf("n|b" to e("n|b", null, 5, 2)))
        engine(store, a).sync(); engine(store, b).sync(); engine(store, a).sync()
        assertEquals(setOf("n|a", "n|b"), a.entries.keys)
        assertEquals(a.entries, b.entries)
        // Both change the same game differently while apart.
        a.entries = a.entries + ("n|a" to e("n|a", PlayStatus.FINISHED, 4, 100))
        b.entries = b.entries + ("n|a" to e("n|a", PlayStatus.DROPPED, 2, 200))
        engine(store, a).sync(); engine(store, b).sync(); engine(store, a).sync()
        assertEquals(a.entries, b.entries)
        assertEquals(PlayStatus.DROPPED, a.entries["n|a"]?.status)
        // Only the handheld that syncs second sees both changes against the same base; the first one just takes the newer value.
        assertEquals(1, b.conflicts.size)
        assertTrue(a.conflicts.isEmpty())
    }

    @Test fun `a second sync with nothing new writes nothing`() = runBlocking {
        val store = FakeDavStore("/", "/Dogmatix")
        val a = ProgressSide(mapOf("n|a" to e("n|a", PlayStatus.PLAYING, 0, 1)))
        engine(store, a).sync()
        val writes = store.requests.count { it.startsWith("PUT") }
        val out = engine(store, a).sync()
        assertEquals(false, out.sent)
        assertEquals(writes, store.requests.count { it.startsWith("PUT") })
    }

    @Test fun `a newer file is refused and a damaged one is reported`() {
        val store = FakeDavStore("/", "/Dogmatix", "/Dogmatix/sync")
        val a = ProgressSide(mapOf("n|a" to e("n|a", null, 1, 1)))
        store.put("https://h/Dogmatix/sync/" + ProgressSync.fileName("Me"), """{"format":"valkyr-progress","version":7,"entries":[]}""".toByteArray(), "application/json")
        try { runBlocking { engine(store, a).sync() }; throw AssertionError("expected") } catch (_: DocSyncEngine.NewerFileException) {}
        store.put("https://h/Dogmatix/sync/" + ProgressSync.fileName("Me"), "garbage".toByteArray(), "application/json")
        try { runBlocking { engine(store, a).sync() }; throw AssertionError("expected") } catch (_: DocSyncEngine.UnreadableFileException) {}
    }
}

class ProgressStateTest {
    private fun e(status: PlayStatus?, rating: Int, at: Long) = ProgressEntry("n|a", status, rating, at)

    @Test fun `the file round trips with its base and conflicts`() {
        val s = ProgressState(mapOf("n|a" to e(PlayStatus.FINISHED, 4, 9)), mapOf("n|a" to e(null, 0, 1)),
            listOf(ProgressConflict("n|a", e(PlayStatus.FINISHED, 4, 9), e(PlayStatus.DROPPED, 1, 8), true)))
        assertEquals(s, ProgressState.fromJson(ProgressState.toJson(s)))
    }

    @Test fun `a damaged file gives an empty state`() {
        assertEquals(ProgressState(), ProgressState.fromJson("{oops"))
        assertEquals(ProgressState(), ProgressState.fromJson(null))
    }

    @Test fun `setting a game keeps an empty entry so that clearing travels`() {
        val s = ProgressState().with("n|a", PlayStatus.PLAYING, 3, 5).with("n|a", null, 0, 6)
        assertTrue(s.entries.getValue("n|a").isEmpty)
        assertEquals(6L, s.entries.getValue("n|a").changedAt)
        assertEquals(5, ProgressState().with("n|a", null, 99, 1).entries.getValue("n|a").rating)
    }

    @Test fun `file names keep only safe characters`() {
        assertEquals("progress__.json".length > 0, true)
        assertEquals("progress_a_b.json", ProgressState().fileName("a/b"))
        assertEquals("progress_default.json", ProgressState().fileName(""))
    }
}
