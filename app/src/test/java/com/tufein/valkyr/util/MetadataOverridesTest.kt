package com.tufein.valkyr.util

import com.tufein.valkyr.data.local.entity.GameOverrideEntity
import com.tufein.valkyr.data.model.GameDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetadataOverridesTest {
    private val base = GameDetails("Mario Land", "Description", listOf("Platform", "Family"), "1990", "Nintendo", "https://art", "RAWG")

    @Test fun `only changed fields are stored`() {
        val f = MetadataOverrides.fields("  Mario Land 2 ", "1992", "  ", "http://insecure")
        assertEquals("Mario Land 2", f.title)
        assertEquals(1992, f.year)
        assertNull(f.genre)
        assertNull(f.pinnedCoverUrl)
    }

    @Test fun `a pinned cover must use a safe scheme`() {
        assertEquals("https://img", MetadataOverrides.fields(null, null, null, " https://img ").pinnedCoverUrl)
        assertNull(MetadataOverrides.fields(null, null, null, "javascript:alert(1)").pinnedCoverUrl)
    }

    @Test fun `years outside the range are ignored`() {
        assertNull(MetadataOverrides.year("1234"))
        assertNull(MetadataOverrides.year("22"))
        assertEquals(2001, MetadataOverrides.year("2001"))
    }

    @Test fun `an override replaces only the fields it sets`() {
        val o = GameOverrideEntity(key = "k", title = "Correct title", genre = "Puzzle", year = 1991)
        val result = MetadataOverrides.apply(base, o)
        assertEquals("Correct title", result.title)
        assertEquals(listOf("Puzzle"), result.genres)
        assertEquals("1991", result.released)
        assertEquals("Description", result.description)
        assertEquals("https://art", result.imageUrl)
        assertEquals("Nintendo", result.developer)
    }

    @Test fun `without an override nothing changes`() {
        assertEquals(base, MetadataOverrides.apply(base, null))
    }

    @Test fun `the key matches the cover key`() {
        assertEquals(RommMarks.key("nintendo_game_boy", "Mario (USA).gb"), MetadataOverrides.key("nintendo_game_boy", "Mario (USA).gb"))
    }
}
