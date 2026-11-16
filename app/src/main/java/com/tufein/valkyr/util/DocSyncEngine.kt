package com.tufein.valkyr.util

import java.util.UUID

/**
 * 8.3: one careful sync of one small shared document (`<root>/sync/<file>`): read, merge with this
 * device, write only when the merge changed what the server holds, with the same conditional write
 * and read-back checks as the other sync files, then apply the merge here. [D] is the document,
 * [M] what a merge produces (the document plus whatever the device keeps beside it).
 */
class DocSyncEngine<D, M>(
    private val store: DavStore,
    serverUrl: String,
    rootUrl: String,
    fileName: String,
    private val local: Local<D, M>,
    private val codec: Codec<D>,
    private val nonce: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis
) {
    interface Local<D, M> {
        suspend fun snapshot(): D
        fun merge(mine: D, remote: D?): M
        fun docOf(merged: M): D
        /** Saves what the merge decided on this device; false when it could not be saved. */
        suspend fun commit(merged: M, mine: D): Boolean
    }

    interface Codec<D> {
        fun decode(text: String): DocParsed<D>
        fun encode(doc: D, rev: String, now: Long): String
        fun isEmpty(doc: D): Boolean
    }

    data class Outcome(val changedHere: Boolean, val sent: Boolean)

    class NewerFileException(val version: Int) : IllegalStateException("Sync document format $version is newer than this app")
    class UnreadableFileException : IllegalStateException("The sync document cannot be read")

    private val serverRoot = WebDavPaths.normalizeServer(serverUrl)
    private val folderUrl = WebDavPaths.join(rootUrl, listOf(CloudBackupEngine.SYNC), asCollection = true)
    val fileUrl: String = WebDavPaths.child(folderUrl, fileName)

    suspend fun sync(): Outcome {
        val writer = DavConditionalWriter(store, fileUrl, CONTENT_TYPE, MAX_FILE_BYTES)
        while (true) {
            val mine = local.snapshot()
            val file = store.get(fileUrl, MAX_FILE_BYTES)
            val remote: D? = file?.takeIf { it.bytes.isNotEmpty() }?.let {
                when (val parsed = codec.decode(it.bytes.toString(Charsets.UTF_8))) {
                    is DocParsed.Ok -> parsed.value
                    is DocParsed.Newer -> throw NewerFileException(parsed.version)
                    DocParsed.Invalid -> throw UnreadableFileException()
                }
            }
            val merged = local.merge(mine, remote)
            val doc = local.docOf(merged)
            var sent = false
            if (!codec.isEmpty(doc) && doc != remote) {
                val rev = nonce()
                val bytes = codec.encode(doc, rev, clock()).toByteArray(Charsets.UTF_8)
                if (file == null) store.ensureCollection(folderUrl, serverRoot ?: folderUrl)
                if (!writer.attempt(file, bytes) { stored -> isOurs(stored, bytes, rev) }) continue
                sent = true
            }
            val changedHere = local.commit(merged, mine)
            return Outcome(changedHere, sent)
        }
    }

    private fun isOurs(stored: ByteArray, written: ByteArray, rev: String): Boolean {
        if (stored.contentEquals(written)) return true
        val parsed = codec.decode(stored.toString(Charsets.UTF_8))
        return parsed is DocParsed.Ok && parsed.rev == rev
    }

    companion object {
        const val CONTENT_TYPE = "application/json; charset=utf-8"
        const val MAX_FILE_BYTES = 2L * 1024 * 1024
    }
}

/** The Up next-style documents of 8.3 as [DocSyncEngine.Codec]s. */
object ProgressCodec : DocSyncEngine.Codec<Map<String, ProgressEntry>> {
    override fun decode(text: String) = ProgressSync.decode(text)
    override fun encode(doc: Map<String, ProgressEntry>, rev: String, now: Long) = ProgressSync.encode(doc, rev, now)
    override fun isEmpty(doc: Map<String, ProgressEntry>) = doc.isEmpty()
}

object NotesCodec : DocSyncEngine.Codec<Map<String, NoteDoc>> {
    override fun decode(text: String) = NotesSync.decode(text)
    override fun encode(doc: Map<String, NoteDoc>, rev: String, now: Long) = NotesSync.encode(doc, rev, now)
    override fun isEmpty(doc: Map<String, NoteDoc>) = doc.isEmpty()
}
