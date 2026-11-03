package com.tufein.valkyr.util

import com.tufein.valkyr.data.model.SortOption

/**
 * 6.3: the Library remembers the sort order per console. Sorting while exactly one console is
 * filtered stores that order for the console, and filtering to that console again brings it back.
 * The default order (name A-Z) is not stored, so choosing it forgets the console. Several consoles,
 * or none, never read or write an entry. Stored as one string, `console=ORDER;console=ORDER`, under [KEY]
 * (so backups carry it). Pure JVM for the tests.
 */
object ConsoleSortMemory {

    const val KEY = "lib63_console_sorts"

    /** Their backup types, for [SettingSchema]. */
    val SETTING_TYPES: Map<String, String> = mapOf(KEY to "s")

    /** The order a console falls back to when nothing is remembered. */
    val DEFAULT = SortOption.NAME_ASC

    /** Most consoles remembered; the oldest entries go first. */
    const val MAX_ENTRIES = 80

    /** The entries in [raw] (oldest first); damaged parts and unknown orders are skipped. */
    fun parse(raw: String?): LinkedHashMap<String, SortOption> {
        val out = LinkedHashMap<String, SortOption>()
        raw.orEmpty().split(';').forEach { part ->
            val at = part.indexOf('=')
            if (at <= 0) return@forEach
            val console = part.substring(0, at).trim()
            val sort = SortOption.entries.firstOrNull { it.name == part.substring(at + 1).trim() } ?: return@forEach
            if (console.isNotEmpty() && sort != DEFAULT) out[console] = sort
        }
        return out
    }

    fun encode(map: Map<String, SortOption>): String =
        map.entries.filter { it.value != DEFAULT && isValidId(it.key) }.joinToString(";") { "${it.key}=${it.value.name}" }

    /** The order to show for the current console filter, or null when it names no single console or nothing is remembered. */
    fun sortFor(map: Map<String, SortOption>, selected: Set<String>): SortOption? =
        selected.singleOrNull()?.let { map[it] }

    /** [map] after [sort] was chosen for [selected]; unchanged unless exactly one console is selected. */
    fun remember(map: Map<String, SortOption>, selected: Set<String>, sort: SortOption): LinkedHashMap<String, SortOption> {
        val out = LinkedHashMap(map)
        val console = selected.singleOrNull()?.takeIf(::isValidId) ?: return out
        out.remove(console)
        if (sort != DEFAULT) out[console] = sort
        while (out.size > MAX_ENTRIES) out.remove(out.keys.first())
        return out
    }

    private fun isValidId(id: String): Boolean = id.isNotBlank() && '=' !in id && ';' !in id
}
