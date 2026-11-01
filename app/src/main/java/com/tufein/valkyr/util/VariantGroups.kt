package com.tufein.valkyr.util

/**
 * 6.0 "Variants": regional and revision copies of one game ("Super Metroid (USA)", "(Europe)",
 * "(Japan)", "(Rev 1)") listed as ONE entry in the Library, with a "N versions" marker. Pure JVM.
 *
 * No new title heuristic: a library row's `name` is already the file name without its tags and
 * extension, and its `searchKey` ([SearchNormalizer.key]) is exactly what the game page's Versions
 * tab uses to list the versions of a game (same console, same key). Two rows are one group when
 * they have the same console, the same search key and the same disc / side / tape part
 * ([VersionPreference.partKey], so Disc 1 and Disc 2 stay apart). Rows that are not a plain copy of
 * the game are never grouped: updates / DLC / patches and hacks, pirate and bootleg copies
 * ([BetterVersions.parse]).
 *
 * The representative of a group is the version the user's region / language preference ranks best
 * (or the one fixed for the game), picked by the caller's [pick]; it takes the place of the first
 * member in the list, so the sort order of the list is kept.
 */
object VariantGroups {

    /** DataStore key of the "Group versions" switch in the Library filters. */
    const val GROUP_KEY = "var60_group"

    /** Its backup type, for [SettingSchema]. */
    val SETTING_TYPES: Map<String, String> = mapOf(GROUP_KEY to "b")

    /**
     * Off by default: the grouping works on the rows the list has loaded (a page at a time), so a
     * version that is further down the list is only counted once its page is loaded.
     */
    const val DEFAULT_ON = false

    /** A row of the grouped list: the shown item and how many different versions it stands for (1 = not a group). */
    data class Entry<T>(val item: T, val versions: Int)

    /** The key under which copies of one game meet; null when the row is never grouped. */
    fun key(consoleId: String, name: String, searchKey: String, fileName: String): String? {
        val parsed = BetterVersions.parse(VersionPicker.readable(fileName))
        if (parsed.addOn) return null
        if (parsed.problem == BetterVersions.Problem.HACK || parsed.problem == BetterVersions.Problem.PIRATE) return null
        val title = searchKey.ifEmpty { SearchNormalizer.key(name) }
        if (title.isEmpty()) return null
        return consoleId + "|" + title + "|" + VersionPreference.partKey(fileName)
    }

    /**
     * [items] with the copies of one game folded into one entry. [pick] gets the members of a group
     * (at least two different files, first in list order) and returns the one to show. Rows with the
     * very same file name (the same file from several sources) count as one version; a game that
     * has only one file name is left exactly as it is.
     */
    fun <T> group(
        items: List<T>,
        consoleId: (T) -> String,
        name: (T) -> String,
        searchKey: (T) -> String,
        fileName: (T) -> String,
        pick: (List<T>) -> T
    ): List<Entry<T>> {
        if (items.size < 2) return items.map { Entry(it, 1) }
        // The cheap key first (no parsing); only a bucket with two different files is looked at closer.
        val buckets = LinkedHashMap<String, MutableList<Int>>()
        for ((i, item) in items.withIndex()) {
            val title = searchKey(item).ifEmpty { SearchNormalizer.key(name(item)) }
            if (title.isNotEmpty()) buckets.getOrPut(consoleId(item) + "|" + title) { ArrayList() } += i
        }
        val groupOf = HashMap<Int, List<Int>>()
        for (bucket in buckets.values) {
            if (bucket.size < 2) continue
            if (bucket.map { VersionPicker.readable(fileName(items[it])).lowercase() }.distinct().size < 2) continue
            val sub = LinkedHashMap<String, MutableList<Int>>()
            for (i in bucket) {
                val item = items[i]
                val k = key(consoleId(item), name(item), searchKey(item), fileName(item)) ?: continue
                sub.getOrPut(k) { ArrayList() } += i
            }
            for (members in sub.values) {
                if (members.map { VersionPicker.readable(fileName(items[it])).lowercase() }.distinct().size < 2) continue
                for (i in members) groupOf[i] = members
            }
        }
        if (groupOf.isEmpty()) return items.map { Entry(it, 1) }
        val out = ArrayList<Entry<T>>(items.size)
        val done = HashSet<List<Int>>()
        for ((i, item) in items.withIndex()) {
            val members = groupOf[i]
            if (members == null) { out += Entry(item, 1); continue }
            if (!done.add(members)) continue
            val rows = members.map { items[it] }
            val versions = rows.map { VersionPicker.readable(fileName(it)).lowercase() }.distinct().size
            out += Entry(pick(rows), versions)
        }
        return out
    }
}
