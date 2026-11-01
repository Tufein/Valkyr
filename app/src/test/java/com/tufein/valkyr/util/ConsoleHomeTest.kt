package com.tufein.valkyr.util

import com.tufein.valkyr.util.ConsoleHome.Tile
import org.junit.Assert.assertEquals
import org.junit.Test

class ConsoleHomeTest {

    private val names = mapOf("snes" to "Super Nintendo", "gba" to "Game Boy Advance", "psx" to "PlayStation", "nes" to "NES")
    private val nameOf = { id: String -> names[id] ?: id }

    @Test
    fun `tiles carry total and on-device counts, empty consoles are left out`() {
        val tiles = ConsoleHome.tiles(listOf("snes" to 120, "gba" to 0, "nes" to 40), mapOf("snes" to 7), nameOf)
        assertEquals(listOf(Tile("snes", 120, 7), Tile("nes", 40, 0)), tiles)
    }

    @Test
    fun `consoles with games on the device come first, then the bigger ones, then by name`() {
        val tiles = ConsoleHome.tiles(
            listOf("psx" to 500, "snes" to 100, "gba" to 100, "nes" to 40),
            mapOf("nes" to 3, "gba" to 3), nameOf
        )
        assertEquals(listOf("gba", "nes", "psx", "snes"), tiles.map { it.consoleId })
    }

    @Test
    fun `the on-device count never exceeds the total and a console is listed once`() {
        val tiles = ConsoleHome.tiles(listOf("snes" to 5, "snes" to 9), mapOf("snes" to 12), nameOf)
        assertEquals(listOf(Tile("snes", 5, 5)), tiles)
    }

    @Test
    fun `the all games tile sums the others`() {
        val all = ConsoleHome.overall(listOf(Tile("a", 10, 2), Tile("b", 5, 0)))
        assertEquals(15, all.total)
        assertEquals(2, all.here)
        assertEquals(Tile("", 0, 0), ConsoleHome.overall(emptyList()))
    }

    @Test
    fun `the counts line leaves out here when nothing is on the device`() {
        val games = { n: Int -> "$n games" }
        val here = { n: Int -> "$n here" }
        assertEquals("12 games · 3 here", ConsoleHome.countsLine(12, 3, games, here))
        assertEquals("12 games", ConsoleHome.countsLine(12, 0, games, here))
        assertEquals("1 games", ConsoleHome.countsLine(1, -2, games, here))
    }
}
