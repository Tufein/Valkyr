package com.tufein.valkyr.util

/**
 * 8.1 "Group by series": the games of one franchise on one console listed as ONE entry in the Library,
 * with a "Mega Man · 6 games" marker, on top of [VariantGroups] (regional copies are already one game
 * by then). A view setting only: no file moves, nothing is stored, and switching it off returns the
 * list exactly as it was. Pure JVM.
 *
 * A franchise from [SeriesKey.FRANCHISES] folds from [MIN_KNOWN] games; the first-two-words fallback
 * needs [MIN_HEURISTIC], so two unrelated "Super …" games stay apart. The shown entry is the first
 * member in list order, so the sort order of the list is kept.
 */
object SeriesGroups {

    /** DataStore key of the "Group by series" switch in the Library filters. */
    const val GROUP_KEY = "var81_series"

    val SETTING_TYPES: Map<String, String> = mapOf(GROUP_KEY to "b")

    /** Off by default, like [VariantGroups.DEFAULT_ON]. */
    const val DEFAULT_ON = false

    const val MIN_KNOWN = 2
    const val MIN_HEURISTIC = 3

    /** What a folded entry stands for: the series [name] and how many different games it holds (at least 2). */
    data class Badge(val name: String, val games: Int)

    /** A row of the grouped list: [badge] is null for a game that stays on its own. */
    data class Entry<T>(val item: T, val badge: Badge?)

    fun <T> group(items: List<T>, consoleId: (T) -> String, name: (T) -> String): List<Entry<T>> {
        if (items.size < MIN_KNOWN) return items.map { Entry(it, null) }
        val seriesOf = arrayOfNulls<SeriesKey.Series>(items.size)
        val buckets = LinkedHashMap<String, MutableList<Int>>()
        for ((i, item) in items.withIndex()) {
            val s = SeriesKey.of(name(item)) ?: continue
            seriesOf[i] = s
            buckets.getOrPut(consoleId(item) + "|" + s.key) { ArrayList() } += i
        }
        val folded = HashMap<Int, List<Int>>()
        for (members in buckets.values) {
            val series = seriesOf[members.first()]!!
            if (members.size < if (series.known) MIN_KNOWN else MIN_HEURISTIC) continue
            // Two rows with the same title (other disc, other source) are one game, not two.
            if (members.map { GameTitleCleaner.clean(name(items[it])).lowercase() }.distinct().size < (if (series.known) MIN_KNOWN else MIN_HEURISTIC)) continue
            for (i in members) folded[i] = members
        }
        if (folded.isEmpty()) return items.map { Entry(it, null) }
        val out = ArrayList<Entry<T>>(items.size)
        val done = HashSet<List<Int>>()
        for ((i, item) in items.withIndex()) {
            val members = folded[i]
            if (members == null) { out += Entry(item, null); continue }
            if (!done.add(members)) continue
            val games = members.map { GameTitleCleaner.clean(name(items[it])).lowercase() }.distinct().size
            out += Entry(items[members.first()], Badge(seriesOf[i]!!.name, games))
        }
        return out
    }
}
