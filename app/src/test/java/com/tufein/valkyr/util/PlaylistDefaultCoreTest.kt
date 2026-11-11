package com.tufein.valkyr.util

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistDefaultCoreTest {
    private val gba = "nintendo_gameboy_advance"
    private val sys = "Nintendo - Game Boy Advance"
    private val core = RetroArchPlaylists.defaultCore("mgba", "com.retroarch.aarch64")
    private fun game(label: String) = RetroArchPlaylists.Game(gba, "$label.gba", label, "/storage/emulated/0/ROMs/gba/$label.gba")
    private fun systemGames(vararg labels: String) = RetroArchPlaylists.SystemGames(sys, listOf(gba), labels.map(::game))
    private fun json(defaultPath: String) =
        RetroArchPlaylists.read("""{"version":"1.5","default_core_path":"$defaultPath","default_core_name":"","items":[{"path":"/storage/emulated/0/ROMs/gba/Old.gba","label":"Old","core_path":"DETECT","core_name":"DETECT","crc32":"00000000|crc","db_name":"x.lpl"}]}""".toByteArray())

    @Test fun corePathPointsIntoRetroArchsCoresFolder() {
        assertEquals("/data/data/com.retroarch.aarch64/cores/mgba_libretro_android.so", core.path)
    }

    @Test fun playlistWithoutDefaultCoreOnlyGetsTheCoreWhenNothingElseToAdd() {
        val plan = RetroArchPlaylists.plan(systemGames("Old"), json(""), core)
        assertEquals(RetroArchPlaylists.State.CORE, plan.state)
        assertTrue(plan.writes)
        val out = JsonParser.parseString(String(RetroArchPlaylists.write(json(""), sys, plan.add, core))).asJsonObject
        assertEquals(core.path, out.get("default_core_path").asString)
        assertEquals(1, out.getAsJsonArray("items").size())
    }

    @Test fun aChoiceMadeInRetroArchStaysAndNoCoreMeansNoChange() {
        val chosen = json("/data/data/com.retroarch/cores/gambatte_libretro_android.so")
        assertEquals(RetroArchPlaylists.State.NOTHING_TO_ADD, RetroArchPlaylists.plan(systemGames("Old"), chosen, core).state)
        val out = JsonParser.parseString(String(RetroArchPlaylists.write(chosen, sys, listOf(game("New")), core))).asJsonObject
        assertEquals("/data/data/com.retroarch/cores/gambatte_libretro_android.so", out.get("default_core_path").asString)
        assertEquals(RetroArchPlaylists.State.NOTHING_TO_ADD, RetroArchPlaylists.plan(systemGames("Old"), json(""), null).state)
    }

    @Test fun newPlaylistStartsWithTheCoreAndChoicesRoundTrip() {
        val out = JsonParser.parseString(String(RetroArchPlaylists.write(RetroArchPlaylists.Read.New, sys, listOf(game("A")), core))).asJsonObject
        assertEquals(core.name, out.get("default_core_name").asString)
        val cores = mapOf(sys to "mgba")
        assertEquals(cores, RetroArchPlaylists.parseCores(RetroArchPlaylists.encodeCores(cores)))
        assertEquals("mgba", RetroArchPlaylists.nextCore(listOf("mgba", "vbam"), null))
        assertEquals("vbam", RetroArchPlaylists.nextCore(listOf("mgba", "vbam"), "mgba"))
        assertNull(RetroArchPlaylists.nextCore(listOf("mgba", "vbam"), "vbam"))
    }
}
