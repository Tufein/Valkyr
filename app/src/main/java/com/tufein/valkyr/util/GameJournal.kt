package com.tufein.valkyr.util

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.security.MessageDigest

data class JournalKey(val profileId: String, val consoleId: String, val fileName: String)
enum class JournalAttachmentKind { SCREENSHOT, MANUAL }
data class JournalAttachment(val id: String, val kind: JournalAttachmentKind, val name: String, val mime: String, val uri: String? = null)
data class JournalEntry(
    val key: JournalKey,
    val note: String = "",
    val attachments: List<JournalAttachment> = emptyList(),
    val modifiedAt: Long = 0,
    /** Different notes in an imported backup remain available instead of replacing local work. */
    val recoveredNotes: List<String> = emptyList()
)

/** Fixed JSON fields, bounded data and no file paths derived from game/profile names. */
object GameJournal {
    const val MAX_NOTE = 4_000
    const val MAX_ENTRIES = 500
    const val MAX_ATTACHMENTS = 8
    const val MAX_BYTES = 2 * 1024 * 1024
    val screenshotMimes = setOf("image/png", "image/jpeg", "image/webp")

    fun validContentUri(value: String): Boolean = runCatching {
        val uri = URI(value)
        value.length <= 2_048 && uri.scheme == "content" && !uri.rawAuthority.isNullOrBlank() && uri.rawFragment == null
    }.getOrDefault(false)

    fun validate(entry: JournalEntry) {
        require(entry.key.profileId.length <= 128 && entry.key.consoleId.length in 1..128 && entry.key.fileName.length in 1..1_024)
        require(entry.note.length <= MAX_NOTE && entry.modifiedAt >= 0)
        require(entry.recoveredNotes.size <= 4 && entry.recoveredNotes.all { it.length <= MAX_NOTE })
        require(entry.attachments.size <= MAX_ATTACHMENTS && entry.attachments.map { it.id }.distinct().size == entry.attachments.size)
        entry.attachments.forEach {
            require(it.id.length in 1..128 && it.name.length in 1..256)
            require(if (it.kind == JournalAttachmentKind.MANUAL) it.mime == "application/pdf" else it.mime in screenshotMimes)
            require(it.uri == null || validContentUri(it.uri))
        }
    }

    fun encode(entries: Collection<JournalEntry>): JsonObject {
        require(entries.size <= MAX_ENTRIES && entries.map { it.key }.distinct().size == entries.size)
        val rows = JsonArray()
        entries.forEach { entry ->
            validate(entry)
            rows.add(JsonObject().apply {
                addProperty("profile", entry.key.profileId); addProperty("console", entry.key.consoleId); addProperty("file", entry.key.fileName)
                addProperty("note", entry.note); addProperty("modifiedAt", entry.modifiedAt)
                add("recoveredNotes", JsonArray().apply { entry.recoveredNotes.forEach { add(it) } })
                add("attachments", JsonArray().apply { entry.attachments.forEach { attachment -> add(JsonObject().apply {
                    addProperty("id", attachment.id); addProperty("kind", attachment.kind.name)
                    addProperty("name", attachment.name); addProperty("mime", attachment.mime)
                    attachment.uri?.let { addProperty("uri", it) }
                }) } })
            })
        }
        return JsonObject().apply { addProperty("version", 1); add("entries", rows) }.also {
            require(it.toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Journal is full; export your notes before adding more" }
        }
    }

    fun decode(element: JsonElement, importing: Boolean = false): List<JournalEntry> {
        require(element.toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val root = element.asJsonObject
        require(root.get("version")?.toString() == "1") { "Unsupported journal backup" }
        val rows = root.getAsJsonArray("entries") ?: error("Missing journal entries")
        require(rows.size() <= MAX_ENTRIES)
        val entries = rows.map { row ->
            val o = row.asJsonObject
            val attachments = o.getAsJsonArray("attachments") ?: error("Missing journal attachments")
            require(attachments.size() <= MAX_ATTACHMENTS)
            val recovered = o.getAsJsonArray("recoveredNotes") ?: JsonArray()
            JournalEntry(
                JournalKey(o.string("profile"), o.string("console"), o.string("file")), o.string("note"),
                attachments.map { item -> item.asJsonObject.let { a ->
                    val uri = a.get("uri")?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString }
                    require(uri == null || validContentUri(uri))
                    JournalAttachment(a.string("id"), JournalAttachmentKind.valueOf(a.string("kind")), a.string("name"), a.string("mime"), if (importing) null else uri)
                } },
                o.get("modifiedAt")?.toString()?.toLongOrNull() ?: error("Invalid journal date"),
                recovered.map { require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString }
            ).also(::validate)
        }
        require(entries.map { it.key }.distinct().size == entries.size) { "Duplicate journal game" }
        return entries
    }

    /** A restore preserves both local and backed-up notes and attachment descriptions. */
    fun merge(current: List<JournalEntry>, incoming: List<JournalEntry>): List<JournalEntry> {
        val all = current.associateBy { it.key }.toMutableMap()
        incoming.forEach { restored ->
            val local = all[restored.key]
            all[restored.key] = if (local == null) restored else {
                val alternatives = (local.recoveredNotes + restored.recoveredNotes + restored.note)
                    .filter { it.isNotBlank() && it != local.note }.distinct()
                val attachments = local.attachments + restored.attachments.mapNotNull { r ->
                    val existing = local.attachments.firstOrNull { it.id == r.id }
                    when {
                        existing == null -> r
                        existing.kind == r.kind && existing.name == r.name && existing.mime == r.mime -> null
                        else -> {
                            // A backup from an edited attachment must not hide its older description.
                            val fingerprint = MessageDigest.getInstance("SHA-256")
                                .digest("${r.id}|${r.kind}|${r.name}|${r.mime}".toByteArray(Charsets.UTF_8))
                                .joinToString("") { "%02x".format(it) }
                            r.copy(id = "recovered-$fingerprint").takeUnless { recovered -> local.attachments.any { it.id == recovered.id } }
                        }
                    }
                }
                local.copy(attachments = attachments, recoveredNotes = alternatives, modifiedAt = maxOf(local.modifiedAt, restored.modifiedAt))
            }
        }
        return all.values.toList().also { encode(it) }
    }

    private fun JsonObject.string(name: String): String = get(name).let {
        require(it != null && it.isJsonPrimitive && it.asJsonPrimitive.isString) { "Invalid journal $name" }; it.asString
    }
}

/** Flush and verify staging before an atomic replacement; a corrupt journal never becomes empty. */
class GameJournalStore(private val file: File, private val commit: (File, File) -> Boolean = { staging, target -> staging.renameTo(target) }) {
    fun read(): List<JournalEntry> {
        if (!file.exists()) return emptyList()
        if (file.length() > GameJournal.MAX_BYTES) throw IOException("Journal is too large")
        return file.inputStream().use { input ->
            val bytes = BoundedStreams.read(input, GameJournal.MAX_BYTES + 1)
            require(bytes.size <= GameJournal.MAX_BYTES)
            GameJournal.decode(JsonParser.parseString(bytes.toString(Charsets.UTF_8)))
        }
    }

    fun write(entries: List<JournalEntry>) {
        val bytes = GameJournal.encode(entries).toString().toByteArray(Charsets.UTF_8)
        val parent = file.parentFile ?: throw IOException("Missing journal directory")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create journal directory")
        val staging = File(parent, ".journal-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(staging).use { it.write(bytes); it.flush(); it.fd.sync() }
            if (!staging.readBytes().contentEquals(bytes)) throw IOException("Journal verification failed")
            if (!commit(staging, file)) throw IOException("Cannot save journal")
        } finally { staging.delete() }
    }
}
