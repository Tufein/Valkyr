package com.tufein.valkyr.util

import com.tufein.valkyr.data.model.SortOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleSortMemoryTest {

    @Test
    fun roundTripKeepsOrder() {
        val map = linkedMapOf("snes" to SortOption.NEWEST, "gba" to SortOption.SIZE_DESC)
        assertEquals("snes=NEWEST;gba=SIZE_DESC", ConsoleSortMemory.encode(map))
        assertEquals(map, ConsoleSortMemory.parse(ConsoleSortMemory.encode(map)))
    }

    @Test
    fun damagedPartsAndDefaultsAreSkipped() {
        val map = ConsoleSortMemory.parse("snes=NEWEST;;=NEWEST;gba=BOGUS;nes;psx=NAME_ASC; n64 = SIZE_ASC ")
        assertEquals(mapOf("snes" to SortOption.NEWEST, "n64" to SortOption.SIZE_ASC), map)
        assertTrue(ConsoleSortMemory.parse(null).isEmpty())
    }

    @Test
    fun onlyASingleConsoleReadsAndWrites() {
        val map = mapOf("snes" to SortOption.NEWEST)
        assertEquals(SortOption.NEWEST, ConsoleSortMemory.sortFor(map, setOf("snes")))
        assertNull(ConsoleSortMemory.sortFor(map, setOf("snes", "gba")))
        assertNull(ConsoleSortMemory.sortFor(map, emptySet()))
        assertNull(ConsoleSortMemory.sortFor(map, setOf("gba")))
        assertEquals(map, ConsoleSortMemory.remember(map, setOf("snes", "gba"), SortOption.SIZE_ASC))
        assertEquals(map, ConsoleSortMemory.remember(map, emptySet(), SortOption.SIZE_ASC))
    }

    @Test
    fun choosingTheDefaultForgetsTheConsole() {
        val map = mapOf("snes" to SortOption.NEWEST)
        assertTrue(ConsoleSortMemory.remember(map, setOf("snes"), SortOption.NAME_ASC).isEmpty())
        assertEquals(SortOption.SIZE_ASC, ConsoleSortMemory.remember(map, setOf("snes"), SortOption.SIZE_ASC)["snes"])
    }

    @Test
    fun oldestEntriesGoWhenFull() {
        var map: Map<String, SortOption> = emptyMap()
        for (i in 0..ConsoleSortMemory.MAX_ENTRIES) map = ConsoleSortMemory.remember(map, setOf("c$i"), SortOption.NEWEST)
        assertEquals(ConsoleSortMemory.MAX_ENTRIES, map.size)
        assertNull(map["c0"])
        assertEquals(SortOption.NEWEST, map["c${ConsoleSortMemory.MAX_ENTRIES}"])
    }

    @Test
    fun unsafeIdsAreNeverStored() {
        assertTrue(ConsoleSortMemory.remember(emptyMap(), setOf("a;b"), SortOption.NEWEST).isEmpty())
        assertEquals("", ConsoleSortMemory.encode(mapOf("a=b" to SortOption.NEWEST)))
    }
}
