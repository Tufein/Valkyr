package com.tufein.valkyr

import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.repository.DownloadableFileRepository
import com.tufein.valkyr.data.service.GameJournalService
import com.tufein.valkyr.data.service.GameOverrideService
import com.tufein.valkyr.data.service.JournalGameAccess
import com.tufein.valkyr.data.service.ProfileService
import com.tufein.valkyr.data.service.BackupService
import com.tufein.valkyr.data.repository.SourcesRepository
import com.tufein.valkyr.data.repository.CollectionsRepository
import com.tufein.valkyr.data.local.dao.FavouriteDao
import com.tufein.valkyr.data.local.dao.DownloadHistoryDao
import com.tufein.valkyr.data.local.dao.WishlistDao
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tufein.valkyr.util.*
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test


class GameJournalRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = EntryPointAccessors.fromApplication(context.applicationContext, Journal28TestEntryPoint::class.java)
    private suspend fun fixture(block: suspend (GameJournalService, AppSettings, File) -> Unit) {
        val settings = graph.journalSettings()
        val before = settings.activeProfile.first()
        val profiles = settings.profiles.first()
        val dir = File(context.cacheDir, "journal-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = dir }
        try {
            settings.setActiveProfile("journal-parent")
            val service = GameJournalService(isolated, settings, JournalGameAccess(settings, graph.journalRepository()))
            block(service, settings, dir)
        } finally { settings.setProfiles(profiles); settings.setActiveProfile(before); dir.deleteRecursively() }
    }
    @Test fun delayedNoteCommitCannotWriteToAnotherActiveProfile() = runBlocking { fixture { service, settings, _ ->
        val key = JournalKey("journal-parent", "gba", "Game.gba")
        service.saveNote(key, 0, "parent notes") { true }
        val entry = service.entry(key)
        settings.setActiveProfile("journal-child")
        assertTrue(runCatching { service.saveNote(key, entry.modifiedAt, "leaked child notes") { true } }.isFailure)
        assertTrue(runCatching { service.entry(key) }.isFailure)
        settings.setActiveProfile("journal-parent")
        assertEquals("parent notes", service.entry(key).note)
    } }
    @Test fun hiddenConsoleCannotBeOpenedOrModifiedThroughJournalService() = runBlocking { fixture { service, settings, _ ->
        settings.setProfiles(Profiles.toJson(listOf(Profile("journal-child", "Child", hiddenConsoles = setOf("gba")))))
        settings.setActiveProfile("journal-child")
        val key = JournalKey("journal-child", "gba", "Secret.gba")
        assertTrue(runCatching { service.entry(key) }.isFailure)
        assertTrue(runCatching { service.saveNote(key, 0, "forbidden") { true } }.isFailure)
    } }
    @Test fun duplicateSourceTagsCannotBypassJournalProfileRestrictions() = runBlocking { fixture { _, settings, _ ->
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.tufein.valkyr.data.local.DogmatixDatabase::class.java).build()
        try {
            db.manufacturerDao().insertManufacturer(com.tufein.valkyr.data.local.entity.ManufacturerEntity("fixture", "Fixture"))
            db.consoleDao().insertConsole(com.tufein.valkyr.data.local.entity.ConsoleEntity("gba", "GBA", "fixture", "[]"))
            val game = com.tufein.valkyr.data.local.entity.DownloadableFileEntity(name = "Shared", fileName = "Shared.gba", consoleId = "gba", downloadUrl = "https://first.invalid/Shared.gba")
            val dao = db.downloadableFileDao()
            dao.insertFiles(listOf(game, game.copy(downloadUrl = "https://second.invalid/Shared.gba")))
            val rows = dao.filesByFileNames(listOf(game.fileName))
            assertEquals(2, rows.size)
            dao.insertTags(listOf(com.tufein.valkyr.data.local.entity.FileTagEntity(rows.last().id, "Adult")))
            settings.setProfiles(Profiles.toJson(listOf(Profile("journal-child", "Child", hiddenTags = setOf("Adult")))))
            settings.setActiveProfile("journal-child")
            val access = JournalGameAccess(settings, DownloadableFileRepository(dao, graph.journalProfiles()))
            assertTrue(runCatching { access.check(JournalKey("journal-child", "gba", game.fileName)) }.isFailure)
            dao.insertFiles(listOf(game.copy(fileName = "Visible.gba", downloadUrl = "https://first.invalid/Visible.gba")))
            access.check(JournalKey("journal-child", "gba", "Visible.gba"))
        } finally { db.close() }
    } }
    @Test fun staleEditorOrChangedGamePreservesStoredNotes() = runBlocking { fixture { service, _, _ ->
        val key = JournalKey("journal-parent", "gba", "Game.gba")
        service.saveNote(key, 0, "saved") { true }
        assertTrue(runCatching { service.saveNote(key, 0, "old editor") { true } }.isFailure)
        val current = service.entry(key)
        assertTrue(runCatching { service.saveNote(key, current.modifiedAt, "other game") { false } }.isFailure)
        assertEquals("saved", service.entry(key).note)
    } }
    @Test fun importedAttachmentsRequireAnewPickEvenWithAnExistingLocalPermission() = runBlocking { fixture { service, _, _ ->
        val key = JournalKey("journal-parent", "gba", "Game.gba")
        val attachment = JournalAttachment("one", JournalAttachmentKind.MANUAL, "Guide.pdf", "application/pdf", "content://remote/document/guide")
        service.restore(service.validateRestore(GameJournal.encode(listOf(JournalEntry(key, "manual notes", listOf(attachment))))))
        val restored = service.entry(key)
        assertEquals("manual notes", restored.note)
        assertNull(restored.attachments.single().uri)
        assertFalse(service.accessible(restored.attachments.single()))
    } }
    @Test fun fileAndWebUrisAreRejectedBeforeAnyAttachmentIsStored() = runBlocking { fixture { service, _, _ ->
        val key = JournalKey("journal-parent", "gba", "Game.gba")
        listOf("file:///sdcard/screenshot.png", "https://example.invalid/screenshot.png").forEach {
            assertTrue(runCatching { service.attach(key, 0, Uri.parse(it), JournalAttachmentKind.SCREENSHOT, null) { true } }.isFailure)
        }
        assertTrue(service.entry(key).attachments.isEmpty())
    } }
    @Test fun appBackupIncludesNotesAndRestoresConflictsWithoutReplacingLocalWork() = runBlocking { fixture { service, settings, dir ->
        val key = JournalKey("journal-parent", "gba", "Game.gba")
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = dir }
        val backup = BackupService(isolated, graph.journalSources(), graph.journalFavourites(), graph.journalDownloads(),
            graph.journalWishlist(), graph.journalCollections(), service, settings, GameOverrideService(graph.journalOverrides()), graph.playSessions())
        service.saveNote(key, 0, "backup chapter") { true }
        val exported = JsonParser.parseString(backup.export().first).asJsonObject
        assertEquals("backup chapter", GameJournal.decode(exported.get("gameJournal")).single().note)
        val before = service.entry(key)
        service.saveNote(key, before.modifiedAt, "new local chapter") { true }
        backup.restore(JsonObject().apply { add("gameJournal", exported.get("gameJournal")) })
        val restored = service.entry(key)
        assertEquals("new local chapter", restored.note)
        assertEquals(listOf("backup chapter"), restored.recoveredNotes)
        assertEquals("journal-parent", settings.activeProfile.first())
    } }
    @Test fun documentPickerAttachmentIsValidatedAndGrantIsRetainedOnlyAfterCommit() = runBlocking { fixture { service, _, _ ->
        val tree = DocumentsContract.buildTreeDocumentUri("com.tufein.valkyr.test.journal", "root")
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        testContext.grantUriPermission(context.packageName, tree, flags)
        val folder = requireNotNull(StorageHelper.createDirectory(context, tree.toString(), "journal-attachment-${UUID.randomUUID()}"))
        val key = JournalKey("journal-parent", "gba", "Game.gba")
        var retained: Uri? = null
        var picker: androidx.test.core.app.ActivityScenario<JournalGrantReceiverActivity>? = null
        try {
            val image = requireNotNull(folder.createFile("image/png", "image.png"))
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            context.contentResolver.openOutputStream(image.uri, "wt")!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            // Direct grantUriPermission does not offer persistable modes. Deliver the document
            // through an Android intent, as the real document picker does.
            picker = androidx.test.core.app.ActivityScenario.launch(Intent(context, JournalGrantReceiverActivity::class.java).setData(image.uri))
            kotlinx.coroutines.withTimeout(10_000) { while (JournalGrantReceiverActivity.pickedUri == null) kotlinx.coroutines.delay(20) }
            assertEquals(image.uri, JournalGrantReceiverActivity.pickedUri)
            service.attach(key, 0, image.uri, JournalAttachmentKind.SCREENSHOT, null) { true }
            val attachment = service.entry(key).attachments.single()
            retained = image.uri
            assertEquals("image/png", attachment.mime)
            assertTrue(service.accessible(attachment))
            val fake = requireNotNull(folder.createFile("image/png", "fake.png"))
            context.contentResolver.openOutputStream(fake.uri, "wt")!!.use { it.write("not an image".toByteArray()) }
            val current = service.entry(key)
            assertTrue(runCatching { service.attach(key, current.modifiedAt, fake.uri, JournalAttachmentKind.SCREENSHOT, null) { true } }.isFailure)
            assertEquals(1, service.entry(key).attachments.size)
            assertFalse(context.contentResolver.persistedUriPermissions.any { it.uri == fake.uri })
        } finally {
            picker?.close()
            retained?.let { runCatching { context.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            folder.delete()
            testContext.revokeUriPermission(tree, flags)
        }
    } }
}
