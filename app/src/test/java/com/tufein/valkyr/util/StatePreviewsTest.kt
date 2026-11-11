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
}
