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
}
