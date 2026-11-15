package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 7.3: emulator folders found below a chosen root ([EmulatorSaveFolders.propose]). */
class EmulatorFolderProposalTest {
    /** A storage tree as path → folder names. */
    private fun tree(vararg paths: String): (String) -> Set<String>? {
        val dirs = HashMap<String, MutableSet<String>>().apply { put("", mutableSetOf()) }
        paths.forEach { path ->
            var at = ""
            path.split('/').forEach { part ->
                dirs.getOrPut(at) { mutableSetOf() } += part
                at = if (at.isEmpty()) part else "$at/$part"
                dirs.getOrPut(at) { mutableSetOf() }
            }
        }
        return { dirs[it] }
    }

    private fun found(root: String, dirsAt: (String) -> Set<String>?, taken: Set<String> = emptySet()) =
        EmulatorSaveFolders.propose(root, taken, dirsAt).map { it.preset.label to it.path }

    @Test
    fun `three emulators are found from the storage root`() {
        val dirs = tree("DraStic/backup", "DraStic/savestates", "PizzaBoyGBA/saves", "PSP/SAVEDATA", "Download", "ROMs/gba")
        assertEquals(
            listOf("DraStic (standalone)" to "DraStic/backup", "Pizza Boy GBA" to "PizzaBoyGBA/saves", "PPSSPP (standalone)" to "PSP/SAVEDATA"),
            found("", dirs)
        )
    }

    @Test
    fun `folder names are matched whatever their case and reported as they are on disk`() {
        assertEquals(listOf("DraStic (standalone)" to "drastic/Backup"), found("", tree("drastic/Backup")))
    }

    @Test
    fun `a root inside the known path still finds the rest of it`() {
        assertEquals(listOf("DraStic (standalone)" to "backup"), found("DraStic", tree("backup", "savestates")))
        assertEquals(listOf("PPSSPP (standalone)" to "SAVEDATA"), found("PSP", tree("SAVEDATA", "SYSTEM")))
    }

    @Test
    fun `a folder that only shares its last name is no proposal`() {
        assertTrue(found("backup", tree("Game.dsv")).isEmpty())
        assertTrue(found("", tree("saves", "backup", "memcards")).isEmpty())
    }

    @Test
    fun `Dolphin needs its GC folder, and the user folder is what is proposed`() {
        assertTrue(found("", tree("dolphin-emu/Config")).isEmpty())
        assertEquals(listOf("Dolphin (standalone)" to "dolphin-emu"), found("", tree("dolphin-emu/GC", "dolphin-emu/Wii")))
        assertEquals(listOf("Dolphin (standalone)" to ""), found("dolphin-emu", tree("GC", "Wii")))
    }

    @Test
    fun `a folder already added or one that cannot be read is left out`() {
        val dirs = tree("DraStic/backup", "DuckStation/memcards")
        assertEquals(listOf("DuckStation (standalone)" to "DuckStation/memcards"), found("", dirs, taken = setOf("drastic/backup")))
        assertTrue(EmulatorSaveFolders.propose("") { null }.isEmpty())
    }

    @Test
    fun `every known path belongs to a preset that still parses`() {
        EmulatorSaveFolders.presets.filter { it.knownPaths.isNotEmpty() }.forEach { preset ->
            assertTrue(preset.label, preset.knownPaths.all { it.isNotBlank() && !it.startsWith("/") && !it.contains("Android/data", ignoreCase = true) })
        }
    }
}
