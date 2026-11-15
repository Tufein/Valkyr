package com.tufein.valkyr.util

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** One choice for a whole batch of save conflicts ([SaveSyncEngine.resolveAll]). */
class SaveBatchResolveTest {
    private val roms = (1..10).map { SaveSyncPlanner.RomCandidate(it, "Game $it (USA).gba", "gba", "gba") }
    private val clock = TestClock()
    private val server = FakeSaveServer(clock, roms)
    private val store = FakeSaveStore(clock)

    private class CountingStore(private val inner: SaveStore) : SaveStore by inner {
        var listings = 0
        override suspend fun list(): SaveStore.Listing { listings++; return inner.list() }
        override suspend fun keepCopy(kind: SaveKind, path: String, bytes: ByteArray) = inner.keepCopy(kind, path, bytes)
    }
    private class CountingServer(private val inner: SaveServer) : SaveServer by inner {
        var listings = 0
        override suspend fun list(kind: SaveKind): List<RemoteSaveFile> { listings++; return inner.list(kind) }
    }

    private val countingStore = CountingStore(store)
    private val countingServer = CountingServer(server)
    private val engine = SaveSyncEngine(countingServer, countingStore, clock = { clock.now })
    private val records = mutableMapOf<String, SaveSyncRecord>()

    private fun name(i: Int) = "Game $i (USA).srm"

    /** Ten saves that changed on both sides; with [deviceLast] the device copy is the newer one. */
    private fun tenConflicts(deviceLast: Boolean): List<SaveConflict> {
        (1..10).forEach { store.put(SaveKind.SAVE, name(it), "v1") }
        runBlocking { engine.sync(records) }
        (1..10).forEach {
            // Minutes apart: times within two seconds count as the same moment.
            if (deviceLast) { server.put(SaveKind.SAVE, it, name(it), "server $it"); clock.now += 60_000; store.put(SaveKind.SAVE, name(it), "device $it") }
            else { store.put(SaveKind.SAVE, name(it), "device $it"); clock.now += 60_000; server.put(SaveKind.SAVE, it, name(it), "server $it") }
        }
        val conflicts = runBlocking { engine.sync(records) }.second
        assertEquals(10, conflicts.size)
        countingStore.listings = 0; countingServer.listings = 0
        return conflicts
    }

    @Test
    fun `ten conflicts take one listing of each side`() {
        val conflicts = tenConflicts(deviceLast = false)
        val outcome = runBlocking { engine.resolveAll(conflicts, BatchChoice.SERVER, records) }
        assertEquals(10, outcome.resolved.size)
        assertEquals(1, countingStore.listings)
        assertEquals(1, countingServer.listings)
        (1..10).forEach { assertEquals("server $it", store.text(SaveKind.SAVE, name(it))) }
        // Every replaced device save was backed up first.
        assertEquals(10, store.backups.size)
        assertEquals(10, runBlocking { engine.sync(records) }.first.unchanged)
    }

    @Test
    fun `keeping the device everywhere keeps a copy of each server version first`() {
        val conflicts = tenConflicts(deviceLast = false)
        val outcome = runBlocking { engine.resolveAll(conflicts, BatchChoice.DEVICE, records) }
        assertEquals(10, outcome.resolved.size)
        (1..10).forEach { assertEquals("device $it", server.text(SaveKind.SAVE, name(it))) }
        assertEquals((1..10).map { "server $it" }.toSet(), store.copies.map { it.second }.toSet())
    }

    @Test
    fun `newest takes the newer side per file`() {
        val conflicts = tenConflicts(deviceLast = true)
        val outcome = runBlocking { engine.resolveAll(conflicts, BatchChoice.NEWER, records) }
        assertEquals(10, outcome.resolved.size)
        (1..10).forEach { assertEquals("device $it", server.text(SaveKind.SAVE, name(it))) }
    }

    @Test
    fun `keep both replaces nothing and names each copy by its own path`() {
        val conflicts = tenConflicts(deviceLast = false)
        val outcome = runBlocking { engine.resolveAll(conflicts, BatchChoice.KEEP_BOTH, records) }
        assertEquals(10, outcome.resolved.size)
        (1..10).forEach {
            assertEquals("device $it", store.text(SaveKind.SAVE, name(it)))
            assertEquals("server $it", server.text(SaveKind.SAVE, name(it)))
        }
        assertEquals((1..10).map { name(it) to "server $it" }.toSet(), store.copies.toSet())
        assertTrue(store.backups.isEmpty())
    }

    @Test
    fun `a file that fails stays a conflict and the rest go on`() {
        val conflicts = tenConflicts(deviceLast = false)
        store.remove(SaveKind.SAVE, name(4))
        val outcome = runBlocking { engine.resolveAll(conflicts, BatchChoice.SERVER, records) }
        assertEquals(9, outcome.resolved.size)
        assertEquals(listOf(name(4)), outcome.skipped.map { it.local.name })
        assertEquals(1, outcome.errors.size)
    }

    @Test
    fun `newest leaves saves written at the same moment for the user`() {
        (1..2).forEach { store.put(SaveKind.SAVE, name(it), "v1") }
        runBlocking { engine.sync(records) }
        (1..2).forEach { store.put(SaveKind.SAVE, name(it), "device $it"); server.put(SaveKind.SAVE, it, name(it), "server $it") }
        val conflicts = runBlocking { engine.sync(records) }.second
        val outcome = runBlocking { engine.resolveAll(conflicts, BatchChoice.NEWER, records) }
        assertTrue(outcome.resolved.isEmpty())
        assertEquals(2, outcome.skipped.size)
        assertEquals("device 1", store.text(SaveKind.SAVE, name(1)))
    }
}
