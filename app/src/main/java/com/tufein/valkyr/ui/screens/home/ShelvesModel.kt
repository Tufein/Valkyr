package com.tufein.valkyr.ui.screens.home

import com.tufein.valkyr.util.UpNext

/**
 * What the 3.8.0 Home shelves show while nothing narrows the library: the game to continue (the
 * hero), "New for you" and one shelf per console. Which game goes where, in which order and how
 * many is decided here, on plain values, so it can be tested on the JVM; [ShelvesViewModel] reads
 * the data and the screen only draws the result.
 */
object ShelvesModel {

    /** Games per console shelf before its "See all" card. */
    const val SHELF_CAP = 15

    /** Games on "New for you" before its "See all" card. */
    const val FOR_YOU_CAP = 15

    /** Consoles shown when nothing is on the device yet (newest additions per console). */
    const val MAX_NEWEST_SHELVES = 6

    /** Recent plays and downloads looked at for the hero and the shelf order. */
    const val RECENT_LOOK_BACK = 40

    /** One game on a shelf. [owned] = on this device, as far as the library index knows. */
    data class Game(val consoleId: String, val fileName: String, val title: String, val owned: Boolean = false) {
        val key: String get() = UpNext.key(consoleId, fileName)
    }

    enum class RecentKind { PLAYED, DOWNLOADED }

    /** Something the player did with a game: started it (in Dogmatix+ or ES-DE) or downloaded it, at [at]. */
    data class Recent(val game: Game, val at: Long, val kind: RecentKind)

    enum class ShelfKind {
        /** The console's games on this device. */
        ON_DEVICE,
        /** Nothing is on the device yet: the console's newest games in the sources. */
        NEWEST
    }

    /** A console's shelf: at most [SHELF_CAP] [games] of [total]. */
    data class Shelf(val consoleId: String, val games: List<Game>, val total: Int, val kind: ShelfKind) {
        val hasMore: Boolean get() = total > games.size
        val key: String get() = "console:$consoleId"
    }

    /** Everything the shelves show. [tiles] only feed the 6.0 Consoles start page. */
    data class State(
        val hero: Recent?,
        val forYou: List<Game>,
        val shelves: List<Shelf>,
        val tiles: List<com.tufein.valkyr.util.ConsoleHome.Tile> = emptyList()
    ) {
        val isEmpty: Boolean get() = hero == null && forYou.isEmpty() && shelves.isEmpty()

        companion object {
            val EMPTY = State(null, emptyList(), emptyList())
        }
    }

    /**
     * The shelves replace the result list only in the start state: no search text, no filter and
     * the default order. Anything else is the power-user list, unchanged.
     */
    fun isStartState(query: String, activeFilters: Int, defaultSort: Boolean): Boolean =
        query.isBlank() && activeFilters == 0 && defaultSort

    /**
     * [events] newest first, one per game (its newest event). At the same moment a start counts
     * before a download. Events without a time are dropped: they cannot be placed.
     */
    fun recents(events: List<Recent>, limit: Int = RECENT_LOOK_BACK): List<Recent> =
        events.asSequence()
            .filter { it.at > 0L }
            .sortedWith(compareByDescending<Recent> { it.at }.thenBy { it.kind.ordinal })
            .distinctBy { it.game.key }
            .take(limit.coerceAtLeast(0))
            .toList()

    /**
     * The game for "Continue playing": the newest of [recents] that is on the device; when none of
     * them is (or the device was not looked at yet), simply the newest. Null without any.
     */
    fun hero(recents: List<Recent>): Recent? =
        recents.firstOrNull { it.game.owned } ?: recents.firstOrNull()

    /**
     * "New for you": the player's own Up next list first (in their order), then the suggestions,
     * each game once, without [exclude] (the hero), at most [cap].
     */
    fun forYou(upNext: List<Game>, suggestions: List<Game>, exclude: Set<String> = emptySet(), cap: Int = FOR_YOU_CAP): List<Game> =
        (upNext + suggestions).asSequence()
            .filter { it.key !in exclude }
            .distinctBy { it.key }
            .take(cap.coerceAtLeast(0))
            .toList()

    /**
     * One [ShelfKind.ON_DEVICE] shelf per console of [owned] that has games: per shelf the games
     * played or downloaded most recently first ([recentAt] by [Game.key]), then A–Z; at most [cap].
     * Shelves follow the newest activity on their console, then the number of games, then the
     * console's name ([nameOf]).
     */
    fun consoleShelves(
        owned: Map<String, List<Game>>,
        recentAt: Map<String, Long>,
        nameOf: (String) -> String,
        cap: Int = SHELF_CAP
    ): List<Shelf> {
        val shelves = owned.mapNotNull { (consoleId, games) ->
            val unique = games.distinctBy { it.key }
            if (unique.isEmpty()) return@mapNotNull null
            val sorted = unique.sortedWith(
                compareByDescending<Game> { recentAt[it.key] ?: 0L }.thenBy { it.title.lowercase() }.thenBy { it.fileName }
            )
            Shelf(consoleId, sorted.take(cap.coerceAtLeast(0)), unique.size, ShelfKind.ON_DEVICE) to
                (unique.maxOfOrNull { recentAt[it.key] ?: 0L } ?: 0L)
        }
        return shelves.sortedWith(
            compareByDescending<Pair<Shelf, Long>> { it.second }
                .thenByDescending { it.first.total }
                .thenBy { nameOf(it.first.consoleId).lowercase() }
        ).map { it.first }
    }

    /**
     * The consoles for [ShelfKind.NEWEST] shelves (nothing is on the device yet): the ones with the
     * most games in the sources, then by name, at most [max]. Empty consoles are left out.
     */
    fun newestConsoles(counts: Map<String, Int>, nameOf: (String) -> String, max: Int = MAX_NEWEST_SHELVES): List<String> =
        counts.filter { it.value > 0 }.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { nameOf(it.key).lowercase() })
            .take(max.coerceAtLeast(0))
            .map { it.key }

    /** A [ShelfKind.NEWEST] shelf: [games] as the sources list them (newest first), each once, at most [cap]. */
    fun newestShelf(consoleId: String, games: List<Game>, total: Int, cap: Int = SHELF_CAP): Shelf? {
        val unique = games.distinctBy { it.key }.take(cap.coerceAtLeast(0))
        if (unique.isEmpty()) return null
        return Shelf(consoleId, unique, maxOf(total, unique.size), ShelfKind.NEWEST)
    }
}
