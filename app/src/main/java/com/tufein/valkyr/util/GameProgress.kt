package com.tufein.valkyr.util

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** 8.3: how far a player is with a game, set by hand; null = not set. */
enum class PlayStatus { PLAYING, FINISHED, COMPLETED, DROPPED }

/**
 * 8.3: a game's own play status and star rating on this device ([rating] 0 = none, 1–5), with the
 * time of the last change. Kept per profile, local and without any server; RomM keeps its own.
 * [key] is `console|file` like [UpNext.key].
 */
data class ProgressEntry(val key: String, val status: PlayStatus?, val rating: Int, val changedAt: Long) {
    /** Whether two entries say the same thing (the time of the change does not count). */
    fun sameValue(other: ProgressEntry?): Boolean = other != null && status == other.status && rating == other.rating
    val isEmpty: Boolean get() = status == null && rating == 0
}

/** Both sides changed a game since they last agreed: the newer was kept, the other is waiting for a decision. */
data class ProgressConflict(val key: String, val mine: ProgressEntry, val theirs: ProgressEntry, val keptMine: Boolean)

/**
 * 8.3: play status and rating travelling between your own handhelds as their own document
 * (`<root>/sync/progress_<profile>.json`). A three-way merge against what both sides last agreed on:
 * a game changed on one side only takes that change, a game on one side only is added, and a game
 * changed differently on both keeps the newer value *and* lists the other as a [ProgressConflict], so
 * nothing is overwritten without a way back. Clearing a game is a change like any other. Pure JVM.
 */
object ProgressSync {
    const val FORMAT = "valkyr-progress"
    const val VERSION = 1
    const val MAX_ENTRIES = 5_000

    data class Merged(val entries: Map<String, ProgressEntry>, val conflicts: List<ProgressConflict>)

    fun merge(base: Map<String, ProgressEntry>, local: Map<String, ProgressEntry>, remote: Map<String, ProgressEntry>?): Merged {
        if (remote == null) return Merged(local, emptyList())
        val out = LinkedHashMap<String, ProgressEntry>()
        val conflicts = ArrayList<ProgressConflict>()
        for (key in (local.keys + remote.keys).toSortedSet()) {
            val l = local[key]; val r = remote[key]; val b = base[key]
            val pick: ProgressEntry = when {
                l == null -> r!!
                r == null -> l
                l.sameValue(r) -> if (l.changedAt >= r.changedAt) l else r
                l.sameValue(b) -> r
                r.sameValue(b) -> l
                else -> {
                    val mineWins = l.changedAt >= r.changedAt
                    conflicts += ProgressConflict(key, l, r, mineWins)
                    if (mineWins) l else r
                }
            }
            out[key] = pick
        }
        return Merged(out, conflicts)
    }

    /** Settles [conflict]: [keepMine] or the other side, written as a new change so it travels to the other handheld. */
    fun resolve(entries: Map<String, ProgressEntry>, conflict: ProgressConflict, keepMine: Boolean, now: Long): Map<String, ProgressEntry> {
        val chosen = if (keepMine) conflict.mine else conflict.theirs
        return entries + (conflict.key to chosen.copy(changedAt = maxOf(now, chosen.changedAt + 1)))
    }

    /** The profile part of the file name: the name, which every device shares (a device's own id is not shared). */
    fun fileName(profileName: String): String = DocFiles.name("progress", profileName)

    fun encode(entries: Map<String, ProgressEntry>, rev: String, now: Long): String = JsonObject().apply {
        addProperty("format", FORMAT); addProperty("version", VERSION); addProperty("updatedAt", now); addProperty("rev", rev)
        add("entries", JsonArray().also { arr ->
            entries.values.sortedBy { it.key }.take(MAX_ENTRIES).forEach { arr.add(entryJson(it)) }
        })
    }.toString()

    fun entryJson(e: ProgressEntry): JsonObject = JsonObject().apply {
        addProperty("k", e.key); e.status?.let { addProperty("s", it.name) }
        addProperty("r", e.rating); addProperty("t", e.changedAt)
    }

    /** One entry as written by [entryJson]; null when its key is unusable. Values are clamped, never trusted. */
    fun entryFrom(e: JsonObject): ProgressEntry? {
        val key = e.get("k")?.asString?.takeIf { it.length in 3..1_200 && '|' in it } ?: return null
        val status = e.get("s")?.asString?.let { s -> PlayStatus.entries.firstOrNull { it.name == s } }
        return ProgressEntry(key, status, (e.get("r")?.asInt ?: 0).coerceIn(0, 5), (e.get("t")?.asLong ?: 0L).coerceAtLeast(0))
    }

    fun decode(text: String): DocParsed<Map<String, ProgressEntry>> = runCatching {
        val o = JsonParser.parseString(text).asJsonObject
        if (o.get("format")?.asString != FORMAT) return@runCatching DocParsed.Invalid
        val version = o.get("version")?.asInt ?: return@runCatching DocParsed.Invalid
        if (version > VERSION) return@runCatching DocParsed.Newer(version)
        val map = LinkedHashMap<String, ProgressEntry>()
        for (el in o.getAsJsonArray("entries")) {
            val entry = entryFrom(el.asJsonObject) ?: continue
            map[entry.key] = entry
            if (map.size >= MAX_ENTRIES) break
        }
        DocParsed.Ok(map, o.get("rev")?.asString.orEmpty())
    }.getOrDefault(DocParsed.Invalid)
}

/** A sync document read from the server. */
sealed class DocParsed<out T> {
    data class Ok<T>(val value: T, val rev: String) : DocParsed<T>()
    data class Newer(val version: Int) : DocParsed<Nothing>()
    data object Invalid : DocParsed<Nothing>()
}

object DocFiles {
    /** `progress_<name>.json`, the name lower-cased and cleaned the way the Up next file is. */
    fun name(kind: String, profileName: String): String {
        val clean = profileName.trim().lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(40)
        return "${kind}_${clean.ifEmpty { "default" }}.json"
    }
}

/** 8.3: one game's note on the shared document: every distinct text ever seen, and the one to show. */
data class NoteDoc(val key: String, val current: String, val texts: Set<String>, val changedAt: Long)

/**
 * 8.3: notes travelling between handhelds (`<root>/sync/notes_<profile>.json`, text only: screenshots
 * and manuals are references to files on one device and stay there). Nothing is ever lost: the note
 * of the newer edit is shown, every other text stays as a recovered note, exactly as the journal
 * already does for a restored backup. Both devices reach the same document, so it settles after
 * one round. Pure JVM.
 */
object NotesSync {
    const val FORMAT = "valkyr-notes"
    const val VERSION = 1
    const val MAX_ENTRIES = 500
    const val MAX_TEXTS = 5

    fun merge(local: Map<String, NoteDoc>, remote: Map<String, NoteDoc>?): Map<String, NoteDoc> {
        if (remote == null) return local
        val out = LinkedHashMap<String, NoteDoc>()
        for (key in (local.keys + remote.keys).toSortedSet()) {
            val l = local[key]; val r = remote[key]
            if (l == null || r == null) { out[key] = (l ?: r)!!; continue }
            val newest = when {
                l.changedAt != r.changedAt -> if (l.changedAt > r.changedAt) l else r
                else -> if (l.current >= r.current) l else r
            }
            val current = newest.current
            val others = (l.texts + r.texts).filter { it.isNotBlank() && it != current }.toSortedSet().take(MAX_TEXTS - 1)
            out[key] = NoteDoc(key, current, (listOf(current).filter { it.isNotBlank() } + others).toSet(), maxOf(l.changedAt, r.changedAt))
        }
        return out
    }

    fun same(a: Map<String, NoteDoc>, b: Map<String, NoteDoc>) = a == b

    fun fileName(profileName: String): String = DocFiles.name("notes", profileName)

    fun encode(docs: Map<String, NoteDoc>, rev: String, now: Long): String = JsonObject().apply {
        addProperty("format", FORMAT); addProperty("version", VERSION); addProperty("updatedAt", now); addProperty("rev", rev)
        add("entries", JsonArray().also { arr ->
            docs.values.sortedBy { it.key }.take(MAX_ENTRIES).forEach { d ->
                arr.add(JsonObject().apply {
                    addProperty("k", d.key); addProperty("c", d.current); addProperty("t", d.changedAt)
                    add("n", JsonArray().also { n -> d.texts.sorted().forEach { n.add(it) } })
                })
            }
        })
    }.toString()

    fun decode(text: String): DocParsed<Map<String, NoteDoc>> = runCatching {
        val o = JsonParser.parseString(text).asJsonObject
        if (o.get("format")?.asString != FORMAT) return@runCatching DocParsed.Invalid
        val version = o.get("version")?.asInt ?: return@runCatching DocParsed.Invalid
        if (version > VERSION) return@runCatching DocParsed.Newer(version)
        val map = LinkedHashMap<String, NoteDoc>()
        for (el in o.getAsJsonArray("entries")) {
            val e = el.asJsonObject
            val key = e.get("k")?.asString?.takeIf { it.length in 3..1_200 && '|' in it } ?: continue
            val current = e.get("c")?.asString.orEmpty().take(GameJournal.MAX_NOTE)
            val texts = e.getAsJsonArray("n")?.map { it.asString.take(GameJournal.MAX_NOTE) }?.filter { it.isNotBlank() }?.toSet().orEmpty()
            map[key] = NoteDoc(key, current, if (current.isBlank()) texts else texts + current, (e.get("t")?.asLong ?: 0L).coerceAtLeast(0))
            if (map.size >= MAX_ENTRIES) break
        }
        DocParsed.Ok(map, o.get("rev")?.asString.orEmpty())
    }.getOrDefault(DocParsed.Invalid)
}
