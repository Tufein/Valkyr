package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarGamesTest {

    private fun c(id: Long, name: String, console: String = "snes", genres: Set<String> = emptySet(), dev: String = "") =
        SimilarGames.Candidate(id, console, name, genres, dev)

    @Test
    fun `series words drop tags fillers numbers and roman numerals`() {
        assertEquals(listOf("super", "mario", "world"), SimilarGames.seriesWords("Super Mario World (USA).sfc"))
        assertEquals(listOf("legend", "zelda"), SimilarGames.seriesWords("The Legend of Zelda II"))
        assertEquals("super mario", SimilarGames.familyQuery("Super Mario Kart (Europe)"))
    }

    @Test
    fun `the Mario family ranks above an unrelated game of the same console`() {
        val target = c(1, "Super Mario World (USA)")
        val ranked = SimilarGames.rank(
            target,
            listOf(c(2, "Super Mario Kart (USA)"), c(3, "Street Fighter II"), c(4, "Super Mario All-Stars (USA)"), c(5, "Mario Paint"))
        )
        assertEquals(listOf(4L, 2L, 5L), ranked.map { it.id })
        assertFalse(ranked.any { it.id == 3L })
    }

    @Test
    fun `other versions of the same game and the game itself are not suggested`() {
        val target = c(1, "Sonic the Hedgehog (USA)", "genesis")
        val ranked = SimilarGames.rank(
            target,
            listOf(c(1, "Sonic the Hedgehog (USA)", "genesis"), c(2, "Sonic the Hedgehog (Europe)", "genesis"), c(3, "Sonic the Hedgehog 2 (World)", "genesis"))
        )
        assertEquals(listOf(3L), ranked.map { it.id })
    }

    @Test
    fun `same developer and genres can relate games without shared words`() {
        val target = c(1, "Chrono Trigger", genres = setOf("Role-Playing"), dev = "Square")
        val other = c(2, "Final Fantasy VI", genres = setOf("Role-Playing"), dev = "Square")
        val unrelated = c(3, "Pilotwings", genres = setOf("Simulation"), dev = "Nintendo")
        val ranked = SimilarGames.rank(target, listOf(other, unrelated))
        assertEquals(listOf(2L), ranked.map { it.id })
        assertTrue(SimilarGames.score(target, other) >= SimilarGames.MIN_SCORE)
    }

    @Test
    fun `limit is respected and empty pool gives nothing`() {
        val target = c(1, "Mega Man X")
        val pool = (2L..12L).map { c(it, "Mega Man X$it Special") }
        assertEquals(3, SimilarGames.rank(target, pool, limit = 3).size)
        assertTrue(SimilarGames.rank(target, emptyList()).isEmpty())
    }

    @Test fun `every ranked game carries a reason and the series comes first`() {
        val target = SimilarGames.Candidate(1, "snes", "Super Mario World (USA)", setOf("platform"), "Nintendo")
        val pool = listOf(
            SimilarGames.Candidate(2, "snes", "Super Mario Kart (USA)", setOf("racing"), "Nintendo"),
            SimilarGames.Candidate(3, "gba", "Wario Land 4 (USA)", setOf("platform"), "Nintendo"),
            SimilarGames.Candidate(4, "snes", "Yoshi Island (USA)", setOf("platform"), "Other")
        )
        val ranked = SimilarGames.rank(target, pool)
        assertTrue(ranked.isNotEmpty())
        ranked.forEach { assertTrue(SimilarGames.reasons(target, it).isNotEmpty()) }
        assertEquals(SimilarGames.Why.SAME_SERIES, SimilarGames.reasons(target, pool[0]).first())
        assertEquals(listOf(SimilarGames.Why.SAME_DEVELOPER, SimilarGames.Why.SHARED_GENRE), SimilarGames.reasons(target, pool[1]))
        assertEquals(listOf(SimilarGames.Why.SHARED_GENRE, SimilarGames.Why.SAME_CONSOLE), SimilarGames.reasons(target, pool[2]))
    }
}
