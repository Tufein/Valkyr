package com.tufein.valkyr.util

/**
 * "More like this": ranks other library games against one game with local data only — shared title
 * words (the "Super Mario ..." family), same developer, shared genres, same console. Pure.
 */
object SimilarGames {

    data class Candidate(
        val id: Long,
        val consoleId: String,
        /** The ROM name; cleaned inside. */
        val name: String,
        val genres: Set<String> = emptySet(),
        val developer: String = ""
    )

    private val weakWords = setOf("the", "a", "an", "of", "and", "in", "to", "for", "on", "vs", "edition", "version", "collection", "game")

    /** Words that identify a series: lower-case, no filler, no pure numbers or roman numerals. */
    fun seriesWords(name: String): List<String> =
        GameTitleCleaner.clean(name).lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 1 && it !in weakWords && !it.all { c -> c.isDigit() } && !isRoman(it) }

    private fun isRoman(w: String) = w.length <= 4 && w.all { it in "ivxlc" }

    /**
     * A search text that finds the target's family in the library: its first two series words
     * ("super mario"), or the single one when there is only one.
     */
    fun familyQuery(name: String): String = seriesWords(name).take(2).joinToString(" ")

    /** Score of [other] against [target]; 0 = unrelated. */
    fun score(target: Candidate, other: Candidate): Int {
        val tw = seriesWords(target.name)
        val ow = seriesWords(other.name).toSet()
        if (tw.isEmpty()) return 0
        val shared = tw.count { it in ow }
        var s = 0
        if (shared > 0) {
            s += shared * 3
            if (tw.first() in ow) s += 2 // same lead word: Super Mario ..., not Mario ... Super
        }
        val sharedGenres = target.genres.count { it in other.genres }
        s += minOf(sharedGenres, 2) * 2
        val dev = target.developer.trim()
        if (dev.isNotEmpty() && dev.equals(other.developer.trim(), ignoreCase = true)) s += 3
        if (target.consoleId == other.consoleId) s += 1
        return s
    }

    /** 7.9: why a game is suggested, strongest first. */
    enum class Why { SAME_SERIES, SAME_DEVELOPER, SHARED_GENRE, SAME_CONSOLE }

    /** The reasons [other] matches [target], strongest first; empty when nothing matches. */
    fun reasons(target: Candidate, other: Candidate): List<Why> = buildList {
        val ow = seriesWords(other.name).toSet()
        if (seriesWords(target.name).any { it in ow }) add(Why.SAME_SERIES)
        val dev = target.developer.trim()
        if (dev.isNotEmpty() && dev.equals(other.developer.trim(), ignoreCase = true)) add(Why.SAME_DEVELOPER)
        if (target.genres.any { it in other.genres }) add(Why.SHARED_GENRE)
        if (target.consoleId == other.consoleId) add(Why.SAME_CONSOLE)
    }

    /** Minimum score worth showing: a genre + console match alone (3) is too loose, family or developer + genre is not. */
    const val MIN_SCORE = 4

    /**
     * The best [limit] games of [pool] for [target]: best score first, one entry per cleaned title
     * (other regions and versions of the same game are skipped, as is the target itself).
     */
    fun rank(target: Candidate, pool: List<Candidate>, limit: Int = 6): List<Candidate> {
        val targetTitle = GameTitleCleaner.clean(target.name).lowercase()
        val seen = HashSet<String>().apply { add("${target.consoleId}|$targetTitle") }
        return pool.asSequence()
            .filter { it.id != target.id }
            .map { it to score(target, it) }
            .filter { it.second >= MIN_SCORE }
            .sortedWith(compareByDescending<Pair<Candidate, Int>> { it.second }.thenBy { GameTitleCleaner.clean(it.first.name).lowercase() })
            .filter { seen.add("${it.first.consoleId}|${GameTitleCleaner.clean(it.first.name).lowercase()}") }
            .take(limit)
            .map { it.first }
            .toList()
    }
}
