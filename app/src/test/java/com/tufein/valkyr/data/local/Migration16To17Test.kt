package com.tufein.valkyr.data.local

import org.junit.Assert.assertTrue
import org.junit.Test

class Migration16To17Test {

    @Test fun `creates the game_overrides table`() {
        val sql = Migration16To17.plan()
        assertTrue(sql.any { it.contains("CREATE TABLE IF NOT EXISTS `game_overrides`") })
    }

    @Test fun `the table is keyed by console and stem`() {
        val sql = Migration16To17.plan().joinToString("\n")
        assertTrue(sql.contains("`key` TEXT NOT NULL PRIMARY KEY"))
    }

    @Test fun `nullable fields mean left automatic`() {
        val sql = Migration16To17.plan().joinToString("\n")
        assertTrue(sql.contains("`title` TEXT"))
        assertTrue(sql.contains("`year` INTEGER"))
        assertTrue(sql.contains("`genre` TEXT"))
        assertTrue(sql.contains("`pinnedCoverUrl` TEXT"))
    }
}