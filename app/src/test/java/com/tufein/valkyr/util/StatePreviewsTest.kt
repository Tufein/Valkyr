package com.tufein.valkyr.util

import com.tufein.valkyr.util.StatePreviews.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatePreviewsTest {
    private val files = listOf(
        Candidate("Advance Wars (USA).state.png", 10),
        Candidate("Advance Wars (USA).state1.png", 30),
        Candidate("Advance Wars (USA).state.auto.png", 20),
        Candidate("Advance Wars 2 (USA).state.png", 99),
        Candidate("Advance Wars (USA).state", 50),
        Candidate("Advance Wars (USA).srm", 50)
    )

    @Test fun newestPreviewOfThatGameWins() {
        assertEquals("Advance Wars (USA).state1.png", StatePreviews.pick(files, "Advance Wars (USA).gba")?.name)
    }

    @Test fun otherGamesAndNonPicturesAreIgnored() {
        assertNull(StatePreviews.pick(files, "Metroid (USA).gba"))
        assertNull(StatePreviews.pick(listOf(Candidate("Advance Wars (USA).state", 1)), "Advance Wars (USA).gba"))
    }

    @Test fun nameMatchIsCaseInsensitiveAndAcceptsTheFullFileName() {
        assertEquals("game.gba.state.png", StatePreviews.pick(listOf(Candidate("game.gba.state.png", 1)), "GAME.GBA")?.name)
    }

    private fun files(vararg names: Pair<String, Long>) = names.map { StatePreviews.Candidate(it.first, it.second) }

    @Test
    fun `the newest save is found whatever wrote it`() {
        val all = files("Zelda (USA).srm" to 10, "Zelda (USA).state1" to 30, "Zelda (USA).state1.png" to 40, "Zelda (USA).dsv" to 20, "Other.srm" to 99)
        val line = StatePreviews.newestSave(all, "Zelda (USA).gba")!!
        assertEquals("Zelda (USA).state1", line.name)
        assertEquals(true, line.state)
        assertEquals("Zelda (USA).dsv", StatePreviews.newestSave(all.filterNot { it.name.contains("state") }, "Zelda (USA).gba")!!.name)
    }

    @Test
    fun `a battery save without any picture still gives a line`() {
        val line = StatePreviews.newestSave(files("Crash (Europe).gba.sav" to 5), "Crash (Europe).gba")!!
        assertEquals(false, line.state)
        assertEquals(5L, line.modified)
    }

    @Test
    fun `another game with a longer name and the game file itself are no saves`() {
        assertNull(StatePreviews.newestSave(files("Zelda (USA).gba" to 5, "Zelda (USA) (Rev 1).srm" to 9, "Zelda (USA).some long note.txt" to 9), "Zelda (USA).gba"))
        assertNull(StatePreviews.newestSave(emptyList(), "Zelda (USA).gba"))
    }

    @Test fun `every picture of the game is listed newest first and nothing else`() {
        val files = listOf(
            Candidate("Sonic.state.png", 100), Candidate("Sonic.state1.png", 300), Candidate("sonic.state.auto.png", 200),
            Candidate("Sonic.state1", 400), Candidate("Sonic Two.state.png", 500), Candidate("Sonic.srm", 600)
        )
        assertEquals(listOf("Sonic.state1.png", "sonic.state.auto.png", "Sonic.state.png"), StatePreviews.pictures(files, "Sonic.md").map { it.name })
        assertEquals(StatePreviews.pick(files, "Sonic.md"), StatePreviews.pictures(files, "Sonic.md").first())
        assertEquals(2, StatePreviews.pictures(files, "Sonic.md", limit = 2).size)
        assertEquals(emptyList<Candidate>(), StatePreviews.pictures(files, "Other.md"))
    }

    @Test fun `the gallery is bounded`() {
        val many = (1..100).map { Candidate("G.state$it.png", it.toLong()) }
        assertEquals(StatePreviews.MAX_PICTURES, StatePreviews.pictures(many, "G.gba").size)
        assertEquals("G.state100.png", StatePreviews.pictures(many, "G.gba").first().name)
    }

    @Test fun `pictures are named by slot, automatic or plain state`() {
        assertEquals(StatePreviews.Label(StatePreviews.Kind.SLOT, 3), StatePreviews.label("Game.state3.png", "Game.gba"))
        assertEquals(StatePreviews.Label(StatePreviews.Kind.SLOT, 12), StatePreviews.label("Game.gba.state.12.png", "Game.gba"))
        assertEquals(StatePreviews.Label(StatePreviews.Kind.AUTO), StatePreviews.label("game.state.auto.png", "Game.gba"))
        assertEquals(StatePreviews.Label(StatePreviews.Kind.STATE), StatePreviews.label("Game.state.png", "Game.gba"))
        assertEquals(StatePreviews.Label(StatePreviews.Kind.OTHER, raw = "quick"), StatePreviews.label("Game.state.quick.png", "Game.gba"))
        assertEquals(StatePreviews.Label(StatePreviews.Kind.OTHER, raw = "weird"), StatePreviews.label("weird.png", "Game.gba"))
    }
}
