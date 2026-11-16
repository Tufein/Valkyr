package com.tufein.valkyr.util

/**
 * 8.4: what a rescan of one source has to write. The old way deleted every row of the source and wrote
 * the whole listing again, which for one changed line cost tens of thousands of inserts, a full
 * rewrite of the search index and new row ids for every game. Now only the rows that differ move:
 * a row that is in the listing again with the same content stays exactly as it is (id, first-seen
 * time, search index entry and all). Pure JVM.
 *
 * Rows are matched as a multiset by [K], the content of a row without its id and first-seen time,
 * so two rows that really are identical (the same file listed twice) stay two rows.
 */
object SourceDiff {

    /** A row as the diff compares it: everything but its id and first-seen time, and its tags in order. */
    data class Content(val row: com.tufein.valkyr.data.local.entity.DownloadableFileEntity, val tags: List<String>)

    /** [deleteIds]: stored rows that are no longer listed. [insertIndexes]: positions in the new listing that are not stored yet. */
    data class Plan(val deleteIds: List<Long>, val insertIndexes: List<Int>, val unchanged: Int)

    fun <K> plan(stored: List<Pair<Long, K>>, listed: List<K>): Plan {
        val waiting = HashMap<K, ArrayDeque<Long>>()
        for ((id, key) in stored) waiting.getOrPut(key) { ArrayDeque() }.addLast(id)
        val insert = ArrayList<Int>()
        var unchanged = 0
        for ((i, key) in listed.withIndex()) {
            val ids = waiting[key]
            if (ids != null && ids.isNotEmpty()) { ids.removeFirst(); unchanged++ } else insert += i
        }
        val delete = ArrayList<Long>()
        waiting.values.forEach { delete.addAll(it) }
        return Plan(delete.sorted(), insert, unchanged)
    }
}
