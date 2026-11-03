package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistAutoRefreshTest {
    private val gba = "nintendo_gameboy_advance"
    private val sys = "Nintendo - Game Boy Advance"
    private val existing = """{"version":"1.5","items":[{"path":"/storage/emulated/0/ROMs/gba/Old.gba","label":"Old","core_path":"DETECT","core_name":"DETECT","crc32":"00000000|crc","db_name":"x.lpl"}]}"""

    private fun game(label: String) = RetroArchPlaylists.Game(gba, "$label.gba", label, "/storage/emulated/0/ROMs/gba/$label.gba")
    private fun plan(system: String, games: List<RetroArchPlaylists.Game>, read: RetroArchPlaylists.Read) =
        RetroArchPlaylists.plan(RetroArchPlaylists.SystemGames(system, listOf(gba), games), read)

    @Test fun onlyExistingPlaylistsWithNewGamesAreTargets() {
        val json = RetroArchPlaylists.read(existing.toByteArray())
        val add = plan(sys, listOf(game("Old"), game("New")), json)
        val fresh = plan("Sony - PlayStation", listOf(game("A")), RetroArchPlaylists.Read.New)
        val upToDate = plan("Sega - Mega Drive", listOf(game("Old")), json)
        val leftAlone = plan("Atari - 2600", listOf(game("A")), RetroArchPlaylists.Read.Kept(RetroArchPlaylists.LeftAlone.OLD_FORMAT))
        val targets = RetroArchPlaylists.autoTargets(listOf(add, fresh, upToDate, leftAlone), emptySet())
        assertEquals(listOf(sys), targets.map { it.system })
        // Only additions: the new game, never the existing entry.
        assertEquals(listOf("New"), targets.single().add.map { it.label })
    }

    @Test fun switchedOffSystemsAndMissingPlansGiveNothing() {
        val json = RetroArchPlaylists.read(existing.toByteArray())
        val add = plan(sys, listOf(game("New")), json)
        assertTrue(RetroArchPlaylists.autoTargets(listOf(add), setOf(sys)).isEmpty())
        assertTrue(RetroArchPlaylists.autoTargets(null, emptySet()).isEmpty())
    }

    @Test fun downloadsTriggerOnceTheQueueIsIdle() {
        assertTrue(RetroArchPlaylists.refreshAfterDownloads(2, 3, anyActive = false))
        assertFalse(RetroArchPlaylists.refreshAfterDownloads(2, 3, anyActive = true))
        assertFalse(RetroArchPlaylists.refreshAfterDownloads(3, 3, anyActive = false))
        assertFalse(RetroArchPlaylists.refreshAfterDownloads(5, 2, anyActive = false))
    }

    @Test fun settingIsRegisteredAsBoolean() {
        assertEquals("b", RetroArchPlaylists.SETTING_TYPES[RetroArchPlaylists.AUTO_KEY])
    }
}
