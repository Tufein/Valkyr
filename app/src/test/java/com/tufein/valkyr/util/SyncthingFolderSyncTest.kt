package com.tufein.valkyr.util

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SyncthingFolderSyncTest {

    private class FakeFolder(private val clock: TestClock) : SyncFolder {
        val files = linkedMapOf<String, Pair<ByteArray, Long>>()
        var listFailure: Exception? = null
        fun put(path: String, text: String) { files[path] = text.toByteArray() to clock.tick() }
        fun text(path: String) = files[path]?.first?.toString(Charsets.UTF_8)
        override suspend fun list(): List<FolderFile> {
            listFailure?.let { throw it }
            return files.map { (p, v) -> FolderFile(p, v.first.size.toLong(), v.second) }
        }
        override suspend fun read(file: FolderFile) = files[file.path]?.first ?: throw IOException("gone")
        override suspend fun write(path: String, bytes: ByteArray): FolderFile {
            files[path] = bytes to clock.tick()
            return FolderFile(path, bytes.size.toLong(), files.getValue(path).second)
        }
    }

    private val clock = TestClock()
    private val store = FakeSaveStore(clock)
    private val folder = FakeFolder(clock)
    private val records = mutableMapOf<String, SaveSyncRecord>()
    private var options = SaveSyncOptions()
    private val engine = FolderSaveSync(store, folder, clock::tick, { options })

    private fun sync(handled: Set<String> = emptySet()) = runBlocking { engine.sync(records, handled) }

    @Test fun `conflict file names are recognised and their original found`() {
        val c = SyncthingNames.conflictOf("Game.sync-conflict-20190101-120000-ABCDEFG.srm")!!
        assertEquals("Game.srm", c.original)
        assertEquals("2019-01-01 12:00:00", SyncthingNames.stamp(c))
        assertEquals("ABCDEFG", c.device)
        assertEquals("Game (USA).srm", SyncthingNames.conflictOf("Game (USA).sync-conflict-20190101-120000-ABCDEFG.srm")!!.original)
        assertEquals("noext", SyncthingNames.conflictOf("noext.sync-conflict-20190101-120000-AB")!!.original)
        assertNull(SyncthingNames.conflictOf("Game.srm"))
        assertNull(SyncthingNames.conflictOf(".sync-conflict-20190101-120000-AB.srm"))
        assertTrue(SyncthingNames.isTemporary(".syncthing.Game.srm.tmp"))
        assertTrue(SyncthingNames.isTemporary("~syncthing~Game.srm.tmp"))
        assertTrue(SyncthingNames.isMarker(".stfolder"))
    }

    @Test fun `new files go both ways and nothing is overwritten`() {
        store.put(SaveKind.SAVE, "mGBA/a.srm", "device")
        folder.put("saves/mGBA/b.srm", "folder")
        folder.put("states/mGBA/c.state1", "state")
        val (result, conflicts) = sync()
        assertEquals(1, result.toFolder); assertEquals(2, result.toDevice); assertTrue(conflicts.isEmpty())
        assertEquals("device", folder.text("saves/mGBA/a.srm"))
        assertEquals("folder", store.text(SaveKind.SAVE, "mGBA/b.srm"))
        assertEquals("state", store.text(SaveKind.STATE, "mGBA/c.state1"))
        assertTrue(store.backups.isEmpty() && store.copies.isEmpty())
        assertEquals(3, sync().first.unchanged)
    }

    @Test fun `a change on one side replaces the other after a backup`() {
        store.put(SaveKind.SAVE, "a.srm", "v1"); sync()
        store.put(SaveKind.SAVE, "a.srm", "v2")
        val push = sync().first
        assertEquals(1, push.toFolder); assertEquals("v2", folder.text("saves/a.srm"))
        assertEquals(listOf("a.srm" to "v1"), store.copies)
        folder.put("saves/a.srm", "v3")
        val pull = sync().first
        assertEquals(1, pull.toDevice); assertEquals("v3", store.text(SaveKind.SAVE, "a.srm"))
        assertEquals(listOf("a.srm"), store.backups)
    }

    @Test fun `a file changed on both sides is a conflict and nothing is touched`() {
        store.put(SaveKind.SAVE, "a.srm", "v1"); sync()
        store.put(SaveKind.SAVE, "a.srm", "device"); folder.put("saves/a.srm", "folder")
        val (result, conflicts) = sync()
        assertEquals(1, result.conflicts)
        assertEquals(FolderConflict.Type.DEVICE_VS_FOLDER, conflicts.single().type)
        assertEquals("device", store.text(SaveKind.SAVE, "a.srm")); assertEquals("folder", folder.text("saves/a.srm"))
        assertTrue(store.backups.isEmpty() && store.copies.isEmpty())
    }

    @Test fun `different files never synced before are a conflict, equal ones are recorded`() {
        store.put(SaveKind.SAVE, "same.srm", "x"); folder.put("saves/same.srm", "x")
        store.put(SaveKind.SAVE, "diff.srm", "a"); folder.put("saves/diff.srm", "b")
        val (result, conflicts) = sync()
        assertEquals(1, result.unchanged); assertEquals(listOf("diff.srm"), conflicts.map { it.path })
    }

    @Test fun `resolving a device conflict backs up what is replaced`() {
        store.put(SaveKind.SAVE, "a.srm", "v1"); sync()
        store.put(SaveKind.SAVE, "a.srm", "device"); folder.put("saves/a.srm", "folder")
        val c = sync().second.single()
        runBlocking { engine.resolve(c, FolderChoice.KEEP_THIS, records) }
        assertEquals("device", folder.text("saves/a.srm")); assertEquals(listOf("a.srm" to "folder"), store.copies)
        assertEquals(0, sync().second.size)
        // keep other
        folder.put("saves/a.srm", "folder2"); store.put(SaveKind.SAVE, "a.srm", "device2")
        val c2 = sync().second.single()
        runBlocking { engine.resolve(c2, FolderChoice.KEEP_OTHER, records) }
        assertEquals("folder2", store.text(SaveKind.SAVE, "a.srm")); assertEquals(listOf("a.srm"), store.backups)
        // keep both
        folder.put("saves/a.srm", "folder3"); store.put(SaveKind.SAVE, "a.srm", "device3")
        val c3 = sync().second.single()
        runBlocking { engine.resolve(c3, FolderChoice.KEEP_BOTH, records) }
        assertEquals("device3", store.text(SaveKind.SAVE, "a.srm")); assertEquals("folder3", folder.text("saves/a.srm"))
        assertEquals("folder3", store.copies.last().second)
        assertEquals(0, sync().second.size)
    }

    @Test fun `a failed safety copy changes nothing`() {
        store.put(SaveKind.SAVE, "a.srm", "v1"); sync()
        store.put(SaveKind.SAVE, "a.srm", "v2")
        store.copyFailure = IOException("disk full")
        val result = sync().first
        assertEquals(1, result.failed); assertEquals("v1", folder.text("saves/a.srm"))
    }

    @Test fun `never overwrite only adds`() {
        store.put(SaveKind.SAVE, "a.srm", "v1"); sync()
        options = SaveSyncOptions(nonDestructive = true)
        store.put(SaveKind.SAVE, "a.srm", "v2")
        val result = sync().first
        assertEquals(1, result.keptAsIs); assertEquals("v1", folder.text("saves/a.srm"))
    }

    @Test fun `deletions are never mirrored`() {
        store.put(SaveKind.SAVE, "a.srm", "v1"); sync()
        folder.files.remove("saves/a.srm")
        sync()
        assertEquals("v1", store.text(SaveKind.SAVE, "a.srm"))
        assertEquals("v1", folder.text("saves/a.srm"))
        store.remove(SaveKind.SAVE, "a.srm"); folder.files.remove("saves/a.srm"); folder.put("saves/b.srm", "b")
        sync()
        assertEquals("b", store.text(SaveKind.SAVE, "b.srm"))
    }

    @Test fun `a listing that fails stops the sync before anything is written`() {
        store.put(SaveKind.SAVE, "a.srm", "v1")
        folder.listFailure = IOException("unreadable")
        assertTrue(runCatching { sync() }.isFailure)
        assertTrue(folder.files.isEmpty())
    }

    @Test fun `syncthing temporaries markers and unknown folders are ignored`() {
        folder.put("saves/.syncthing.a.srm.tmp", "x"); folder.put("saves/~syncthing~a.srm.tmp", "x")
        folder.put("saves/.stversions/a.srm", "x"); folder.put("other/a.srm", "x"); folder.put("saves/.dogmatix-write-1.part", "x")
        folder.put("saves/cover.png", "x")
        sync()
        assertTrue(store.files.isEmpty())
    }

    @Test fun `syncthing conflict copies are listed and resolved without deleting`() {
        folder.put("saves/mGBA/a.srm", "current")
        folder.put("saves/mGBA/a.sync-conflict-20190101-120000-ABCDEFG.srm", "other")
        val (_, conflicts) = sync()
        val c = conflicts.single()
        assertEquals(FolderConflict.Type.SYNCTHING_COPY, c.type); assertEquals("mGBA/a.srm", c.path)
        assertEquals("current", store.text(SaveKind.SAVE, "mGBA/a.srm"))
        // decided ones are not listed again
        assertTrue(sync(setOf(c.id)).second.isEmpty())
        // keep other: nothing changes but a safety copy of the conflict copy
        runBlocking { engine.resolve(c, FolderChoice.KEEP_OTHER, records) }
        assertEquals("current", folder.text("saves/mGBA/a.srm")); assertEquals("other", store.copies.last().second)
        // keep this: the file in place is copied away first, then holds the conflict copy's content
        runBlocking { engine.resolve(c, FolderChoice.KEEP_THIS, records) }
        assertEquals("other", folder.text("saves/mGBA/a.srm")); assertEquals("current", store.copies.last().second)
        // keep both: nothing replaced
        runBlocking { engine.resolve(c, FolderChoice.KEEP_BOTH, records) }
        assertNotNull(folder.text("saves/mGBA/a.sync-conflict-20190101-120000-ABCDEFG.srm"))
        assertFalse(folder.files.keys.any { it.contains("deleted") })
    }
}
