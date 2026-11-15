package com.tufein.valkyr.util

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID

/**
 * 8.0: "Up next" travelling between your own handhelds through the WebDAV folder, as its own
 * document (`<root>/sync/upnext_<profile>.json`) so an older app that reads the folder is not
 * disturbed. Nothing is ever overwritten: the two lists are merged. A game on either list stays;
 * for a game on both, the side that changed it last decides (started or finished beats queued
 * from an hour earlier). Taking a game off a list does not travel, and that is the price of a
 * merge that can never lose a week of changes. Pure JVM for the tests.
 */
object UpNextSync {
    const val FORMAT = "valkyr-upnext"
    const val VERSION = 1

    /** Both lists merged: local order first, then what only the other side had. */
    fun merge(local: List<UpNextEntry>, remote: List<UpNextEntry>): List<UpNextEntry> {
        val remoteByKey = remote.associateBy { it.key }
        val localKeys = local.mapTo(HashSet()) { it.key }
        val merged = local.map { mine ->
            val theirs = remoteByKey[mine.key] ?: return@map mine
            newer(mine, theirs)
        } + remote.filter { it.key !in localKeys }
        return UpNext.normalized(merged).take(UpNext.MAX_ENTRIES)
    }

    /** The entry that changed last; the same time keeps the further state (finished > started > queued). */
    private fun newer(a: UpNextEntry, b: UpNextEntry): UpNextEntry = when {
        a.changedAt != b.changedAt -> if (a.changedAt > b.changedAt) a else b
        a.state != b.state -> if (a.state.ordinal >= b.state.ordinal) a else b
        else -> a.copy(addedAt = minOf(a.addedAt, b.addedAt), title = a.title.ifBlank { b.title })
    }

    /** The profile part of the file name: the name (not the device's own id, which no other device shares). */
    fun fileName(profileName: String): String {
        val clean = profileName.trim().lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(40)
        return "upnext_${clean.ifEmpty { "default" }}.json"
    }

    sealed class Parsed {
        data class Ok(val list: List<UpNextEntry>, val rev: String) : Parsed()
        data class Newer(val version: Int) : Parsed()
        data object Invalid : Parsed()
    }

    fun write(list: List<UpNextEntry>, rev: String, now: Long): String = JsonObject().apply {
        addProperty("format", FORMAT)
        addProperty("version", VERSION)
        addProperty("updatedAt", now)
        addProperty("rev", rev)
        add("entries", JsonParser.parseString(UpNext.toJson(list)))
    }.toString()

    fun read(text: String): Parsed = runCatching {
        val o = JsonParser.parseString(text).asJsonObject
        if (o.get("format")?.asString != FORMAT) return@runCatching Parsed.Invalid
        val version = o.get("version")?.asInt ?: return@runCatching Parsed.Invalid
        if (version > VERSION) return@runCatching Parsed.Newer(version)
        Parsed.Ok(UpNext.fromJson(o.get("entries")?.toString()), o.get("rev")?.asString.orEmpty())
    }.getOrDefault(Parsed.Invalid)
}

/** One sync of this device's list against the shared document, with the same careful write as the other sync files. */
class UpNextSyncEngine(
    private val store: DavStore,
    private val serverUrl: String,
    rootUrl: String,
    profileName: String,
    private val local: Local,
    private val nonce: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis
) {
    interface Local {
        suspend fun snapshot(): List<UpNextEntry>
        /** Replaces the device's list by [merged]; returns false when it could not be saved. */
        suspend fun replace(merged: List<UpNextEntry>): Boolean
    }

    data class Outcome(val gained: Int, val sent: Boolean)

    class NewerFileException(val version: Int) : IllegalStateException("Up next format $version is newer than ${UpNextSync.VERSION}")
    class UnreadableFileException : IllegalStateException("The Up next file cannot be read")

    private val folderUrl = WebDavPaths.join(rootUrl, listOf(SYNC), asCollection = true)
    val fileUrl: String = WebDavPaths.child(folderUrl, UpNextSync.fileName(profileName))

    suspend fun sync(): Outcome {
        val writer = DavConditionalWriter(store, fileUrl, CONTENT_TYPE, MAX_FILE_BYTES)
        while (true) {
            val mine = local.snapshot()
            val file = store.get(fileUrl, MAX_FILE_BYTES)
            val remote = file?.takeIf { it.bytes.isNotEmpty() }?.let {
                when (val parsed = UpNextSync.read(it.bytes.toString(Charsets.UTF_8))) {
                    is UpNextSync.Parsed.Ok -> parsed.list
                    is UpNextSync.Parsed.Newer -> throw NewerFileException(parsed.version)
                    UpNextSync.Parsed.Invalid -> throw UnreadableFileException()
                }
            }
            val merged = UpNextSync.merge(mine, remote.orEmpty())
            var sent = false
            if (merged.isNotEmpty() && !sameGames(merged, remote.orEmpty())) {
                val rev = nonce()
                val bytes = UpNextSync.write(merged, rev, clock()).toByteArray(Charsets.UTF_8)
                if (file == null) store.ensureCollection(folderUrl, WebDavPaths.normalizeServer(serverUrl) ?: folderUrl)
                if (!writer.attempt(file, bytes) { stored -> isOurs(stored, bytes, rev) }) continue
                sent = true
            }
            val gained = merged.count { it.key !in mine.mapTo(HashSet()) { m -> m.key } }
            if (!sameGames(merged, mine)) local.replace(merged)
            return Outcome(gained, sent)
        }
    }

    /** Same games in the same state: the order is each device's own and does not count as a change. */
    private fun sameGames(a: List<UpNextEntry>, b: List<UpNextEntry>): Boolean =
        a.size == b.size && a.sortedBy { it.key } == b.sortedBy { it.key }

    private fun isOurs(stored: ByteArray, written: ByteArray, rev: String): Boolean {
        if (stored.contentEquals(written)) return true
        val parsed = UpNextSync.read(stored.toString(Charsets.UTF_8))
        return parsed is UpNextSync.Parsed.Ok && parsed.rev == rev
    }

    companion object {
        const val SYNC = "sync"
        const val CONTENT_TYPE = "application/json; charset=utf-8"
        const val MAX_FILE_BYTES = 1L * 1024 * 1024
    }
}
