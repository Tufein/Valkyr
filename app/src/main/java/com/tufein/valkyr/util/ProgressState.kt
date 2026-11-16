package com.tufein.valkyr.util

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 8.3: what one profile keeps on this device: its [entries], the [base] both handhelds last agreed on
 * (for the three-way merge) and the [conflicts] waiting for a decision. One small JSON file per
 * profile; never read from the network. Pure JVM.
 */
data class ProgressState(
    val entries: Map<String, ProgressEntry> = emptyMap(),
    val base: Map<String, ProgressEntry> = emptyMap(),
    val conflicts: List<ProgressConflict> = emptyList()
) {
    fun fileName(profileId: String): String = "progress_" + profileId.ifEmpty { "default" }.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64) + ".json"

    /** [key] set to [status] and [rating] at [now]; an empty entry (nothing set) is kept so that clearing a game travels as well. */
    fun with(key: String, status: PlayStatus?, rating: Int, now: Long): ProgressState =
        copy(entries = entries + (key to ProgressEntry(key, status, rating.coerceIn(0, 5), now)))

    companion object {
        const val MAX_CONFLICTS = 200

        fun toJson(state: ProgressState): String = JsonObject().apply {
            addProperty("version", 1)
            add("entries", array(state.entries.values))
            add("base", array(state.base.values))
            add("conflicts", JsonArray().also { arr ->
                state.conflicts.take(MAX_CONFLICTS).forEach { c ->
                    arr.add(JsonObject().apply {
                        add("mine", ProgressSync.entryJson(c.mine)); add("theirs", ProgressSync.entryJson(c.theirs)); addProperty("keptMine", c.keptMine)
                    })
                }
            })
        }.toString()

        fun fromJson(text: String?): ProgressState = runCatching {
            val o = JsonParser.parseString(text ?: return@runCatching ProgressState()).asJsonObject
            fun read(name: String) = o.getAsJsonArray(name)?.mapNotNull { ProgressSync.entryFrom(it.asJsonObject) }?.take(ProgressSync.MAX_ENTRIES)?.associateBy { it.key }.orEmpty()
            val conflicts = o.getAsJsonArray("conflicts")?.mapNotNull { el ->
                val c = el.asJsonObject
                val mine = ProgressSync.entryFrom(c.getAsJsonObject("mine")) ?: return@mapNotNull null
                val theirs = ProgressSync.entryFrom(c.getAsJsonObject("theirs")) ?: return@mapNotNull null
                ProgressConflict(mine.key, mine, theirs, c.get("keptMine")?.asBoolean ?: true)
            }?.take(MAX_CONFLICTS).orEmpty()
            ProgressState(read("entries"), read("base"), conflicts)
        }.getOrDefault(ProgressState())

        private fun array(entries: Collection<ProgressEntry>) = JsonArray().also { arr -> entries.sortedBy { it.key }.forEach { arr.add(ProgressSync.entryJson(it)) } }
    }
}
