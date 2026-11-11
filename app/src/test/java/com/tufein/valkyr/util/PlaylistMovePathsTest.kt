package com.tufein.valkyr.util

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistMovePathsTest {
    private val json = RetroArchPlaylists.read(
        """{"version":"1.5","default_core_path":"","items":[
        {"path":"/storage/emulated/0/Roms/gba/A.gba","label":"A","core_path":"DETECT"},
        {"path":"/storage/emulated/0/Roms/gba/B.zip#B.gba","label":"B","core_path":"DETECT"},
        {"path":"/storage/emulated/0/RomsOther/gba/C.gba","label":"C","core_path":"DETECT"}]}""".toByteArray()
    )

    @Test fun onlyPathsUnderTheOldFolderMoveAndNothingElseChanges() {
        val (bytes, n) = RetroArchPlaylists.movePaths(json, "/storage/emulated/0/Roms/", "/storage/ABCD-1234/Roms")!!
        assertEquals(2, n)
        val items = JsonParser.parseString(String(bytes)).asJsonObject.getAsJsonArray("items").map { it.asJsonObject }
        assertEquals("/storage/ABCD-1234/Roms/gba/A.gba", items[0].get("path").asString)
        assertEquals("/storage/ABCD-1234/Roms/gba/B.zip#B.gba", items[1].get("path").asString)
        assertEquals("/storage/emulated/0/RomsOther/gba/C.gba", items[2].get("path").asString)
        assertEquals("A", items[0].get("label").asString)
    }

    @Test fun badFoldersAndNoMatchGiveNothing() {
        assertNull(RetroArchPlaylists.movePaths(json, "Roms", "/x"))
        assertNull(RetroArchPlaylists.movePaths(json, "/a/../b", "/x"))
        assertNull(RetroArchPlaylists.movePaths(json, "/same", "/same/"))
        assertNull(RetroArchPlaylists.movePaths(json, "/nowhere", "/x"))
        assertNull(RetroArchPlaylists.movePaths(RetroArchPlaylists.Read.New, "/a", "/b"))
    }
}
