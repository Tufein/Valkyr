package com.tufein.valkyr.util

/**
 * 6.0: the pure parts of the Library tab's "Consoles" start page: one tile per console with how many games
 * it has and how many of them are on this device, plus the "All games" tile. [ShelvesViewModel][com.tufein.valkyr.ui.screens.home.ShelvesViewModel]
 * fills the numbers from the per-console counts it already keeps for the shelves (one grouped query per
 * rescan, no scan per tile); the screen only draws the result.
 */
object ConsoleHome {

    /** One console tile. [here] = games of it that are on this device, as far as the library index knows. */
    data class Tile(val consoleId: String, val total: Int, val here: Int)

    /** The tiles of [totals] (console id to number of games), each console once, empty consoles left out. */
    fun tiles(totals: List<Pair<String, Int>>, onDevice: Map<String, Int>, nameOf: (String) -> String): List<Tile> =
        totals.asSequence()
            .filter { it.second > 0 }
            .distinctBy { it.first }
            .map { (id, total) -> Tile(id, total, (onDevice[id] ?: 0).coerceIn(0, total)) }
            .sortedWith(compareByDescending<Tile> { it.here }.thenByDescending { it.total }.thenBy { nameOf(it.consoleId).lowercase() })
            .toList()

    /** The numbers of the "All games" tile: the sum over [tiles]. */
    fun overall(tiles: List<Tile>): Tile = Tile("", tiles.sumOf { it.total }, tiles.sumOf { it.here })

    /**
     * The counts line of a tile, "N games · M here": the "here" part is left out when nothing of the console is on
     * the device, so a tile never claims "0 here". [games] and [here] format their number (plural forms).
     */
    fun countsLine(total: Int, here: Int, games: (Int) -> String, hereText: (Int) -> String, separator: String = " · "): String =
        if (here <= 0) games(total) else games(total) + separator + hereText(here)
}
