package com.tufein.valkyr.util

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UpNextSyncTest {
    private fun e(file: String, state: UpNextState = UpNextState.QUEUED, added: Long = 1, changed: Long = 0) =
        UpNextEntry("snes", file, file, added, state, changed)

    @Test fun `a game on either list stays and a week offline loses nothing`() {
        val merged = UpNextSync.merge(listOf(e("a"), e("b")), listOf(e("c"), e("a")))
        assertEquals(listOf("a", "b", "c"), merged.map { it.fileName })
    }

    @Test fun `the side that changed a game last decides`() {
        val merged = UpNextSync.merge(
            listOf(e("a", UpNextState.STARTED, changed = 50)),
            listOf(e("a", UpNextState.FINISHED, changed = 90))
        )
        assertEquals(UpNextState.FINISHED, merged.single().state)
        val other = UpNextSync.merge(listOf(e("a", UpNextState.FINISHED, changed = 90)), listOf(e("a", UpNextState.QUEUED)))
        assertEquals(UpNextState.FINISHED, other.single().state)
    }

    @Test fun `file names follow the profile name and stay harmless`() {
        assertEquals("upnext_default.json", UpNextSync.fileName(""))
        assertEquals("upnext_stefan-s-list.json", UpNextSync.fileName("  Stefan's List "))
        assertEquals("upnext_default.json", UpNextSync.fileName("../.."))
    }

    @Test fun `a newer or foreign document is refused, not overwritten`() {
        assertTrue(UpNextSync.read("""{"format":"valkyr-upnext","version":9,"entries":[]}""") is UpNextSync.Parsed.Newer)
        assertTrue(UpNextSync.read("""{"format":"other","version":1}""") is UpNextSync.Parsed.Invalid)
        assertTrue(UpNextSync.read("nonsense") is UpNextSync.Parsed.Invalid)
        val ok = UpNextSync.read(UpNextSync.write(listOf(e("a")), "r1", 5)) as UpNextSync.Parsed.Ok
        assertEquals(listOf("a"), ok.list.map { it.fileName })
        assertEquals("r1", ok.rev)
    }

    private class Side(var list: List<UpNextEntry>) : UpNextSyncEngine.Local {
        override suspend fun snapshot() = list
        override suspend fun replace(merged: List<UpNextEntry>): Boolean { list = merged; return true }
    }

    private val server = "https://nas.local/dav/"
    private val root = "https://nas.local/dav/Valkyr/"
    private var n = 0
    private fun engine(store: DavStore, side: Side, name: String = "Stefan") =
        UpNextSyncEngine(store, server, root, name, side, nonce = { "rev${n++}" }, clock = { 100L + n })

    @Test fun `two handhelds end up with the same list`() = runBlocking {
        val store = FakeDavStore("/dav")
        val thor = Side(listOf(e("a"), e("b")))
        val odin = Side(listOf(e("c")))
        assertEquals(UpNextSyncEngine.Outcome(0, true), engine(store, thor).sync())
        assertEquals(UpNextSyncEngine.Outcome(2, true), engine(store, odin).sync())
        assertEquals(UpNextSyncEngine.Outcome(1, false), engine(store, thor).sync())
        assertEquals(thor.list.map { it.key }.toSet(), odin.list.map { it.key }.toSet())
        assertEquals(3, thor.list.size)
    }

    @Test fun `nothing is written when nothing changed`() = runBlocking {
        val store = FakeDavStore("/dav")
        val side = Side(listOf(e("a")))
        engine(store, side).sync()
        val puts = store.requests.count { it.startsWith("PUT") }
        assertEquals(UpNextSyncEngine.Outcome(0, false), engine(store, side).sync())
        assertEquals(puts, store.requests.count { it.startsWith("PUT") })
    }

    @Test fun `a newer file on the server is left alone`() = runBlocking {
        val store = FakeDavStore("/dav")
        val side = Side(listOf(e("a")))
        val eng = engine(store, side)
        store.collections.add("/dav/Valkyr/sync")
        store.put(eng.fileUrl, """{"format":"valkyr-upnext","version":9,"entries":[]}""".toByteArray(), "application/json")
        try { eng.sync(); fail() } catch (_: UpNextSyncEngine.NewerFileException) { }
        assertEquals(listOf("a"), side.list.map { it.fileName })
    }
}
