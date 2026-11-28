package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryOperatorsTest {
    @Test fun plainTextIsLeftAlone() {
        val p = QueryOperators.parse("super mario")
        assertEquals("super mario", p.text)
        assertFalse(p.hasOperators)
    }

    @Test fun operatorsAreTakenOutOfTheText() {
        val p = QueryOperators.parse("mario console:snes tag:Hack is:fav")
        assertEquals("mario", p.text)
        assertEquals(listOf("snes"), p.consoleWords)
        assertEquals(listOf("Hack"), p.tags)
        assertTrue(p.favouritesOnly)
    }

    @Test fun shortNamesAndQuotes() {
        val p = QueryOperators.parse("c:\"game boy\" c:nes t:fan")
        assertEquals("", p.text)
        assertEquals(listOf("game boy", "nes"), p.consoleWords)
        assertEquals(listOf("fan"), p.tags)
    }

    @Test fun titlesWithAColonAndEmptyValuesStayText() {
        assertEquals("zelda: link", QueryOperators.parse("zelda: link").text)
        assertEquals("console: snes", QueryOperators.parse("console: snes").text)
        assertEquals("is:hard", QueryOperators.parse("is:hard").text)
        assertFalse(QueryOperators.parse("star wars: rogue").hasOperators)
    }

    @Test fun consoleWordsFindConsoles() {
        val names = mapOf("snes" to "Super Nintendo", "genesis" to "Sega Mega Drive", "gb" to "Game Boy", "gba" to "Game Boy Advance")
        val ids = names.keys.toList()
        fun m(w: String) = QueryOperators.matchConsoles(w, ids) { names.getValue(it) }
        assertEquals(setOf("snes"), m("snes"))
        assertEquals(setOf("snes"), m("Super Nintendo"))
        assertEquals(setOf("genesis"), m("mega"))
        assertEquals(setOf("gb"), m("game boy"))
        assertEquals(setOf("gb", "gba"), m("game"))
        assertEquals(emptySet<String>(), m("xyz"))
        assertEquals(emptySet<String>(), m("g"))
    }
}
