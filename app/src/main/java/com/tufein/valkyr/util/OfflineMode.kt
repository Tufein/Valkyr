package com.tufein.valkyr.util

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Rules for the "Offline" chip and the actions that wait for a connection (6.0). Pure. */
object OfflineMode {

    /** A network counts as online when Android says it reaches the internet and has checked it. */
    fun isOnline(hasInternet: Boolean, validated: Boolean): Boolean = hasInternet && validated

    enum class Screen { HOME, DOWNLOADS, OTHER }

    /** The chip shows on Home and Downloads, only while offline. */
    fun chipVisible(online: Boolean, screen: Screen): Boolean = !online && screen != Screen.OTHER

    /** What still works without a connection, in the order the dialog lists it. */
    enum class Works { PLAY, LIBRARY, OFFLINE_COLLECTIONS, MANUALS }

    val worksOffline: List<Works> = listOf(Works.PLAY, Works.LIBRARY, Works.OFFLINE_COLLECTIONS, Works.MANUALS)

    /** One change that waits for a connection: a RomM play-status write. */
    data class Pending(val kind: String, val romId: Int, val changes: Map<String, Any?>, val at: Long) {
        companion object { const val KIND_ROMM_PROPS = "romm_props" }
    }

    /** The newest change to the same game wins field by field; entries of other games stay in order. */
    fun merge(list: List<Pending>, add: Pending): List<Pending> {
        val same = list.firstOrNull { it.kind == add.kind && it.romId == add.romId }
        if (same == null) return (list + add).takeLast(MAX_PENDING)
        val merged = same.copy(changes = same.changes + add.changes, at = add.at)
        return list.map { if (it === same) merged else it }
    }

    const val MAX_PENDING = 200

    /**
     * Takes the fields in [keys] of [romId] out of the waiting list: an online write of those fields just
     * succeeded, so an older queued value must not overwrite it later. Entries left without fields go.
     */
    fun discard(list: List<Pending>, romId: Int, keys: Set<String>): List<Pending> =
        list.mapNotNull { p ->
            if (p.romId != romId) return@mapNotNull p
            val left = p.changes.filterKeys { it !in keys }
            if (left.isEmpty()) null else if (left.size == p.changes.size) p else p.copy(changes = left)
        }

    /**
     * Whether a waiting change is thrown away after the server refused it with [httpCode]. Only answers
     * that will never succeed on a retry: the item is gone (404) or the data is refused (other 4xx).
     * A wrong token (401/403), a timeout or rate limit (408/429), a server error (5xx), a certificate
     * problem or no network (no code) keep the change for the next run.
     */
    fun dropsEntry(httpCode: Int?): Boolean = httpCode != null && httpCode in 400..499 && httpCode !in KEEP_CODES

    private val KEEP_CODES = setOf(401, 403, 407, 408, 421, 423, 425, 429)

    fun encode(list: List<Pending>): String {
        val arr = JsonArray()
        list.forEach { p ->
            val o = JsonObject()
            o.addProperty("kind", p.kind); o.addProperty("rom", p.romId); o.addProperty("at", p.at)
            val c = JsonObject()
            p.changes.forEach { (k, v) ->
                when (v) {
                    null -> c.add(k, JsonNull.INSTANCE)
                    is Boolean -> c.addProperty(k, v)
                    is Number -> c.addProperty(k, v)
                    else -> c.addProperty(k, v.toString())
                }
            }
            o.add("changes", c)
            arr.add(o)
        }
        return arr.toString()
    }

    /** Unreadable text gives an empty list; entries without an id or changes are dropped. */
    fun decode(text: String?): List<Pending> {
        val arr = runCatching { JsonParser.parseString(text.orEmpty().ifBlank { "[]" }).asJsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val rom = o.get("rom")?.takeIf { it.isJsonPrimitive }?.asInt ?: return@mapNotNull null
            val c = o.getAsJsonObject("changes") ?: return@mapNotNull null
            val changes = LinkedHashMap<String, Any?>()
            c.entrySet().forEach { (k, v) ->
                changes[k] = when {
                    v.isJsonNull -> null
                    v.isJsonPrimitive && v.asJsonPrimitive.isBoolean -> v.asBoolean
                    v.isJsonPrimitive && v.asJsonPrimitive.isNumber -> v.asInt
                    else -> v.asString
                }
            }
            if (changes.isEmpty()) return@mapNotNull null
            Pending(o.get("kind")?.asString ?: Pending.KIND_ROMM_PROPS, rom, changes, o.get("at")?.asLong ?: 0L)
        }
    }
}
