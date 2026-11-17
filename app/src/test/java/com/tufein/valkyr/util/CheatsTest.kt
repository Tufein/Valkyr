package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheatsTest {

    @Test fun `import parses a cheat file`() {
        val text = """
            cheat0_desc = "Infinite lives"
            cheat0_code = "00C0:09"
            cheat0_enable = false
            cheat1_desc = "Invincible"
            cheat1_code = "00C1:01"
            cheat1_enable = true
        """.trimIndent()
        val cheats = Cheats.parse(text)
        assertEquals(2, cheats.size)
        assertEquals("Infinite lives", cheats[0].name)
        assertEquals("00C0:09", cheats[0].code)
        assertEquals(false, cheats[0].enabled)
        assertEquals(true, cheats[1].enabled)
    }

    @Test fun `a file for another game does not match`() {
        assertTrue(Cheats.matchesFile("mario.cht", "mario.nes"))
        org.junit.Assert.assertFalse(Cheats.matchesFile("zelda.cht", "mario.nes"))
    }

    @Test fun `a broken file yields no cheats`() {
        assertEquals(0, Cheats.parse("this is not a cheat file").size)
        assertEquals(0, Cheats.parse("").size)
        assertEquals(0, Cheats.parse("cheat0_desc = \"\"").size)
    }

    @Test fun `hardcore refuses an enabled cheat`() {
        assertEquals(Cheats.Verdict.REFUSED_HARDCORE, Cheats.verdict(raHardcore = true, anyEnabled = true))
        org.junit.Assert.assertNotNull(Cheats.refusalReason(Cheats.Verdict.REFUSED_HARDCORE))
        assertEquals(Cheats.Verdict.ALLOWED, Cheats.verdict(raHardcore = true, anyEnabled = false))
        assertEquals(Cheats.Verdict.ALLOWED, Cheats.verdict(raHardcore = false, anyEnabled = true))
    }
}