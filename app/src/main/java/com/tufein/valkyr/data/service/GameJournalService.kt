package com.tufein.valkyr.data.service

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.util.BoundedStreams
import com.tufein.valkyr.util.GameJournal
import com.tufein.valkyr.util.GameJournalStore
import com.tufein.valkyr.util.JournalAttachment
import com.tufein.valkyr.util.JournalAttachmentKind
import com.tufein.valkyr.util.JournalEntry
import com.tufein.valkyr.util.JournalKey
import com.tufein.valkyr.util.NoteDoc
import com.tufein.valkyr.util.UpNext
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class GameJournalService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val profiles: AppSettings,
    private val access: JournalGameAccess
) {
    private val store = GameJournalStore(File(context.filesDir, "game-journal.json"))
    private val lock = Mutex()
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()

    suspend fun entry(key: JournalKey): JournalEntry = withContext(Dispatchers.IO) {
        profiles.withActiveProfile(key.profileId) {
            access.check(key)
            lock.withLock { store.read().firstOrNull { it.key == key } ?: JournalEntry(key) }
        }
    }

    fun accessible(attachment: JournalAttachment): Boolean = attachment.uri?.let { uri ->
        GameJournal.validContentUri(uri) && context.contentResolver.persistedUriPermissions.any { it.isReadPermission && it.uri.toString() == uri }
    } == true

    suspend fun saveNote(key: JournalKey, expectedDate: Long, note: String, stillCurrent: () -> Boolean) =
        mutate(key, expectedDate, stillCurrent) { it.copy(note = note) }

    suspend fun remove(key: JournalKey, expectedDate: Long, id: String, stillCurrent: () -> Boolean) =
        mutate(key, expectedDate, stillCurrent) { it.copy(attachments = it.attachments.filterNot { attachment -> attachment.id == id }) }

    /** OpenDocument grants only; read and bound the actual contents before retaining a reference. */
    suspend fun attach(key: JournalKey, expectedDate: Long, uri: Uri, kind: JournalAttachmentKind, replaceId: String?, stillCurrent: () -> Boolean) = withContext(Dispatchers.IO) {
        require(GameJournal.validContentUri(uri.toString())) { "Choose a document from the file picker" }
        val resolver = context.contentResolver
        val mime = resolver.getType(uri).orEmpty().lowercase()
        require(if (kind == JournalAttachmentKind.MANUAL) mime == "application/pdf" else mime in GameJournal.screenshotMimes) { "Choose a PNG, JPEG, WebP image or PDF manual" }
        val limit = if (kind == JournalAttachmentKind.MANUAL) 32L * 1024 * 1024 else 12L * 1024 * 1024
        var name = "attachment"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                name = cursor.getString(0).orEmpty().take(256).ifBlank { name }
                if (!cursor.isNull(1)) require(cursor.getLong(1) in 0..limit) { "Attachment is too large" }
            }
        }
        val bytes = resolver.openInputStream(uri)?.use { BoundedStreams.read(it, limit.toInt() + 1) } ?: throw IOException("Cannot read attachment")
        require(bytes.size.toLong() in 1..limit) { "Attachment is empty or too large" }
        if (kind == JournalAttachmentKind.MANUAL) require(bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray())) { "Invalid PDF manual" }
        else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth in 1..12_000 && bounds.outHeight in 1..12_000 && bounds.outWidth.toLong() * bounds.outHeight <= 50_000_000) { "Invalid or oversized image" }
        }
        currentCoroutineContext().ensureActive()
        val attachment = JournalAttachment(replaceId ?: UUID.randomUUID().toString(), kind, name, mime, uri.toString())
        val hadGrant = resolver.persistedUriPermissions.any { it.isReadPermission && it.uri == uri }
        var retained = false
        var committed = false
        try {
            mutate(key, expectedDate, stillCurrent, onCommitted = { committed = true }) { old ->
                check(replaceId == null || old.attachments.any { it.id == replaceId }) { "Attachment changed; open it again" }
                val changed = old.copy(attachments = old.attachments.filterNot { it.id == replaceId } + attachment)
                GameJournal.validate(changed)
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                retained = true
                changed
            }
        } finally {
            if (retained && !hadGrant && !committed) runCatching { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    private suspend fun mutate(key: JournalKey, expectedDate: Long, stillCurrent: () -> Boolean, onCommitted: () -> Unit = {}, transform: (JournalEntry) -> JournalEntry) = withContext(Dispatchers.IO) {
        profiles.withActiveProfile(key.profileId) { lock.withLock {
            currentCoroutineContext().ensureActive()
            access.check(key)
            check(stillCurrent()) { "Game changed; open it again" }
            val all = store.read()
            val old = all.firstOrNull { it.key == key } ?: JournalEntry(key)
            check(old.modifiedAt == expectedDate) { "Journal changed; reload before saving" }
            val changed = transform(old).copy(modifiedAt = maxOf(System.currentTimeMillis(), old.modifiedAt + 1))
            store.write(all.filterNot { it.key == key } + changed)
            onCommitted()
            _revision.value++
        } }
    }

    /** Export fails loudly on an unreadable journal; notes never silently disappear from a backup. */
    /** 8.3: the notes of [profileId] as shared documents (text only: attachments are references to files on this device). */
    suspend fun notesForSync(profileId: String): Map<String, NoteDoc> = withContext(Dispatchers.IO) {
        lock.withLock {
            store.read().filter { it.key.profileId == profileId && (it.note.isNotBlank() || it.recoveredNotes.isNotEmpty()) }.associate {
                val key = UpNext.key(it.key.consoleId, it.key.fileName)
                key to NoteDoc(key, it.note, (listOf(it.note) + it.recoveredNotes).filter { t -> t.isNotBlank() }.toSet(), it.modifiedAt)
            }
        }
    }

    /**
     * 8.3: saves what a notes sync decided for [profileId]: the note of the newer edit is shown, every
     * other text stays as a recovered note, and attachments are never touched. True when something changed here.
     */
    suspend fun applyNotes(profileId: String, merged: Map<String, NoteDoc>): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = store.read()
            val byKey = current.associateBy { it.key }.toMutableMap()
            var changed = false
            for (doc in merged.values) {
                val console = doc.key.substringBefore('|'); val file = doc.key.substringAfter('|')
                val key = JournalKey(profileId, console, file)
                val existing = byKey[key]
                val recovered = doc.texts.filter { it != doc.current && it.isNotBlank() }.take(4)
                val next = (existing ?: JournalEntry(key)).copy(note = doc.current, recoveredNotes = recovered, modifiedAt = maxOf(existing?.modifiedAt ?: 0L, doc.changedAt))
                if (existing?.note == next.note && existing.recoveredNotes == next.recoveredNotes) continue
                runCatching { GameJournal.validate(next) }.onSuccess { byKey[key] = next; changed = true }
            }
            if (!changed) return@withLock false
            val saved = runCatching { GameJournal.encode(byKey.values.toList()) }.isSuccess
            if (!saved) return@withLock false
            store.write(byKey.values.toList())
            _revision.value++
            true
        }
    }

    suspend fun export(): JsonObject = withContext(Dispatchers.IO) { lock.withLock { GameJournal.encode(store.read()) } }
    fun validateRestore(element: JsonElement): List<JournalEntry> = GameJournal.decode(element, importing = true)
    suspend fun preflightRestore(incoming: List<JournalEntry>) = withContext(Dispatchers.IO) { lock.withLock { GameJournal.merge(store.read(), incoming); Unit } }
    suspend fun restore(incoming: List<JournalEntry>) = withContext(Dispatchers.IO) { lock.withLock {
        store.write(GameJournal.merge(store.read(), incoming))
        _revision.value++
    } }
}
