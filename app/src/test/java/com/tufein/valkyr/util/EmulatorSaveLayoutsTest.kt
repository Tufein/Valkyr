package com.tufein.valkyr.util

import com.tufein.valkyr.util.SaveSyncPlanner.RomCandidate
import com.tufein.valkyr.util.SaveSyncPlanner.RomMatch
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EmulatorSaveLayoutsTest {
    private val ps1 = setOf("psx")
    private fun card(name: String) = LocalSaveFile(SaveKind.SAVE, "DuckStation (standalone)/$name", 131072, 1, ps1, SaveFolderLayout.MEMCARDS)
    private fun ps2Card(name: String) = LocalSaveFile(SaveKind.SAVE, "AetherSX2 - NetherSX2 (standalone)/$name", 8_650_752, 1, setOf("ps2"), SaveFolderLayout.PS2_MEMCARDS, 7)
    private fun gcCard(name: String) = LocalSaveFile(SaveKind.SAVE, "Dolphin (standalone)/$name", 16_777_216, 1, setOf("ngc", "gc"), SaveFolderLayout.GC_MEMCARDS, 8)
    private fun pack(folder: String) = LocalSaveFile(SaveKind.SAVE, "PPSSPP (standalone)/$folder.savedata.zip", 100, 1, setOf("psp"), SaveFolderLayout.SAVEDATA)

    @Test fun memoryCardNamesAreRead() {
        assertEquals(MemoryCard.Shared(1), EmulatorSaveLayouts.memoryCard("shared_card_1.mcd"))
        assertEquals(MemoryCard.Shared(2), EmulatorSaveLayouts.memoryCard("Shared_Card_2.MCD"))
        assertEquals(MemoryCard.PerGame("Crash Bandicoot", null, 1), EmulatorSaveLayouts.memoryCard("Crash Bandicoot_1.mcd"))
        assertEquals(MemoryCard.PerGame("SLUS-00594", "SLUS-00594", 2), EmulatorSaveLayouts.memoryCard("SLUS-00594_2.mcd"))
        // A title that merely contains a serial is a title.
        assertEquals(MemoryCard.PerGame("Game SLUS-00594 Edition", null, 1), EmulatorSaveLayouts.memoryCard("Game SLUS-00594 Edition_1.mcd"))
        assertEquals(MemoryCard.PerGame("Final Fantasy VII", null, 1), EmulatorSaveLayouts.memoryCard("Final Fantasy VII.mcr"))
        assertNull(EmulatorSaveLayouts.memoryCard("Crash Bandicoot_1.png"))
        assertNull(EmulatorSaveLayouts.memoryCard(".dogmatix-write-1.part"))
        assertTrue(EmulatorSaveLayouts.isSharedCard("shared_card_1.mcd"))
        assertFalse(EmulatorSaveLayouts.isSharedCard("Crash_1.mcd"))
    }

    @Test fun ps2AndGameCubeCardNamesAreRead() {
        // AetherSX2 / NetherSX2: every .ps2 card is shared; the default names give the slot.
        assertEquals(MemoryCard.Shared(1), EmulatorSaveLayouts.ps2Card("Mcd001.ps2"))
        assertEquals(MemoryCard.Shared(2), EmulatorSaveLayouts.ps2Card("MCD002.PS2"))
        assertEquals(MemoryCard.Shared(0), EmulatorSaveLayouts.ps2Card("My Card.ps2"))
        assertNull(EmulatorSaveLayouts.ps2Card(".ps2"))
        assertNull(EmulatorSaveLayouts.ps2Card(".dogmatix-write-1.ps2"))
        assertNull(EmulatorSaveLayouts.ps2Card("Mcd001.ps2.bak"))
        assertNull(EmulatorSaveLayouts.ps2Card("Mcd001.mcd"))
        // Dolphin: MemoryCardA/B per region, also with a block count; nothing else in its GC folder.
        assertEquals(MemoryCard.Shared(1), EmulatorSaveLayouts.gameCubeCard("MemoryCardA.USA.raw"))
        assertEquals(MemoryCard.Shared(2), EmulatorSaveLayouts.gameCubeCard("MemoryCardB.EUR.raw"))
        assertEquals(MemoryCard.Shared(1), EmulatorSaveLayouts.gameCubeCard("MemoryCardA.JAP.251.raw"))
        assertNull(EmulatorSaveLayouts.gameCubeCard("SRAM.raw"))
        assertNull(EmulatorSaveLayouts.gameCubeCard("MemoryCardC.USA.raw"))
        assertNull(EmulatorSaveLayouts.gameCubeCard("MemoryCardA.KOR.raw"))
        assertNull(EmulatorSaveLayouts.gameCubeCard("MemoryCardA.USA.raw.bak"))
        // GCI files (one save each, named by maker and game code) are never taken for a card.
        assertNull(EmulatorSaveLayouts.gameCubeCard("01-GALE-SuperSmashBros0110290334.gci"))
        assertFalse(EmulatorSaveLayouts.isCardImage("01-GALE-SuperSmashBros0110290334.gci"))
        // Each folder reads only its own emulator's cards.
        assertEquals(MemoryCard.Shared(1), EmulatorSaveLayouts.memoryCard(SaveFolderLayout.PS2_MEMCARDS, "Mcd001.ps2"))
        assertNull(EmulatorSaveLayouts.memoryCard(SaveFolderLayout.PS2_MEMCARDS, "shared_card_1.mcd"))
        assertNull(EmulatorSaveLayouts.memoryCard(SaveFolderLayout.GC_MEMCARDS, "Mcd001.ps2"))
        assertEquals(MemoryCard.Shared(2), EmulatorSaveLayouts.memoryCard(SaveFolderLayout.GC_MEMCARDS, "MemoryCardB.USA.raw"))
        assertNull(EmulatorSaveLayouts.memoryCard(SaveFolderLayout.MEMCARDS, "MemoryCardA.USA.raw"))
        assertNull(EmulatorSaveLayouts.memoryCard(SaveFolderLayout.FILES, "Mcd001.ps2"))
        assertEquals(MemoryCard.PerGame("Crash", null, 1), EmulatorSaveLayouts.memoryCard(SaveFolderLayout.MEMCARDS, "Crash_1.mcd"))
    }

    @Test fun ps2AndGameCubeCardsAreAlwaysShared() {
        assertTrue(EmulatorSaveLayouts.isSharedCard("Mcd001.ps2"))
        assertTrue(EmulatorSaveLayouts.isSharedCard("MemoryCardA.USA.raw"))
        assertFalse(EmulatorSaveLayouts.isSharedCard("SRAM.raw"))
        assertTrue(EmulatorSaveLayouts.isSharedCard(ps2Card("Mcd001.ps2")))
        assertTrue(EmulatorSaveLayouts.isSharedCard(gcCard("MemoryCardA.USA.raw")))
        assertTrue(EmulatorSaveLayouts.isSharedCard(card("shared_card_1.mcd")))
        assertFalse(EmulatorSaveLayouts.isSharedCard(card("Crash_1.mcd")))
        // Outside a folder of cards, a file named like a card is an ordinary save.
        assertFalse(EmulatorSaveLayouts.isSharedCard(LocalSaveFile(SaveKind.SAVE, "pcsx2/Mcd001.ps2", 8_650_752, 1)))
        assertTrue(SaveFolderLayout.PS2_MEMCARDS.onlySharedCards && SaveFolderLayout.GC_MEMCARDS.onlySharedCards)
        assertFalse(SaveFolderLayout.MEMCARDS.onlySharedCards)
        assertTrue(SaveFolderLayout.entries.filter { it.holdsSharedCards }.toSet() == setOf(SaveFolderLayout.MEMCARDS, SaveFolderLayout.PS2_MEMCARDS, SaveFolderLayout.GC_MEMCARDS))
        // Never matched or searched by name, never the save of the game on the page.
        val roms = listOf(RomCandidate(1, "Mcd001.ps2", "ps2", "ps2"), RomCandidate(2, "MemoryCardA.USA.raw", "ngc", "gc"))
        assertEquals(RomMatch.NotFound, EmulatorSaveLayouts.matchRom(ps2Card("Mcd001.ps2"), roms))
        assertEquals(RomMatch.NotFound, EmulatorSaveLayouts.matchRom(gcCard("MemoryCardA.USA.raw"), roms))
        assertEquals(emptyList<String>(), EmulatorSaveLayouts.searchTerms(ps2Card("Mcd001.ps2")))
        assertEquals(emptyList<String>(), EmulatorSaveLayouts.searchTerms(gcCard("MemoryCardA.USA.raw")))
        assertFalse(EmulatorSaveLayouts.belongsToGame(ps2Card("Mcd001.ps2"), "Ico (USA).iso"))
        assertFalse(EmulatorSaveLayouts.belongsToGame(gcCard("MemoryCardA.USA.raw"), "Pikmin (USA).rvz"))
    }

    @Test fun largeCardsFitTheSyncButOtherSavesKeepTheirLimit() {
        val ordinary = 64L * 1024 * 1024
        // PCSX2's 8, 16, 32 and 64 MB cards with their error-correction bytes.
        for (size in listOf(8_650_752L, 17_301_504L, 34_603_008L, 69_206_016L)) {
            assertTrue(size <= EmulatorSaveLayouts.sizeLimit("Mcd001.ps2", ordinary))
        }
        assertTrue(16L * 1024 * 1024 <= EmulatorSaveLayouts.sizeLimit("MemoryCardA.USA.raw", ordinary))
        assertEquals(ordinary, EmulatorSaveLayouts.sizeLimit("Game (USA).srm", ordinary))
        assertEquals(ordinary, EmulatorSaveLayouts.sizeLimit("SRAM.raw", ordinary))
        assertEquals(EmulatorSaveLayouts.MAX_CARD_BYTES, EmulatorSaveLayouts.sizeLimit("shared_card_1.mcd", ordinary))
        // A hand-off (16 MiB) never takes a shared card, the sync's own limit only grows.
        assertEquals(100L * 1024 * 1024, EmulatorSaveLayouts.sizeLimit("Mcd001.ps2", 100L * 1024 * 1024))
    }

    @Test fun serialsAndProductIdsAreFoundInNames() {
        assertEquals(listOf("SLUS-00594"), EmulatorSaveLayouts.ps1Serials("Metal Gear Solid (USA) [SLUS-00594].chd"))
        assertEquals(listOf("SCES-02105"), EmulatorSaveLayouts.ps1Serials("SCES_021.05"))
        assertEquals(emptyList<String>(), EmulatorSaveLayouts.ps1Serials("XSLUS-00594"))
        assertEquals(listOf("ULUS10041"), EmulatorSaveLayouts.productIds("GTA LCS (USA) (ULUS-10041).iso"))
        assertEquals(listOf("NPUH10091"), EmulatorSaveLayouts.productIds("Game [NPUH10091].cso"))
        assertEquals("ULUS10041", EmulatorSaveLayouts.folderProductId("ULUS10041DATA00"))
        assertEquals("ULJM05500", EmulatorSaveLayouts.folderProductId("ULJM05500"))
        assertNull(EmulatorSaveLayouts.folderProductId("SYSTEM"))
        assertEquals("ULUS10041DATA00", EmulatorSaveLayouts.folderOfPack("ULUS10041DATA00.savedata.zip"))
        assertNull(EmulatorSaveLayouts.folderOfPack("ULUS10041DATA00.zip"))
        assertNull(EmulatorSaveLayouts.folderOfPack(".savedata.zip"))
    }

    @Test fun perGameCardsMatchByFileTitleGameTitleOrSerial() {
        val roms = listOf(
            RomCandidate(1, "Crash Bandicoot (USA).chd", "psx", "psx", "Crash Bandicoot"),
            RomCandidate(2, "Metal Gear Solid (USA) (Disc 1) [SLUS-00594].chd", "psx", "psx"),
            RomCandidate(3, "Spyro the Dragon (USA).chd", "psx", "psx")
        )
        // DuckStation's "file title" naming.
        assertEquals(RomMatch.Found(1), EmulatorSaveLayouts.matchRom(card("Crash Bandicoot (USA)_1.mcd"), roms))
        // Its "game title" naming, also when the file name lost a character DuckStation cannot write.
        assertEquals(RomMatch.Found(3), EmulatorSaveLayouts.matchRom(card("Spyro the Dragon_1.mcd"), roms))
        assertEquals(RomMatch.Found(1), EmulatorSaveLayouts.matchRom(card("Crash_ Bandicoot_1.mcd"), roms))
        // Serial naming.
        assertEquals(RomMatch.Found(2), EmulatorSaveLayouts.matchRom(card("SLUS-00594_1.mcd"), roms))
        assertEquals(RomMatch.NotFound, EmulatorSaveLayouts.matchRom(card("SLUS-99999_1.mcd"), roms))
        assertEquals(RomMatch.NotFound, EmulatorSaveLayouts.matchRom(card("Tekken 3_1.mcd"), roms))
        // A shared card is never matched by name.
        assertEquals(RomMatch.NotFound, EmulatorSaveLayouts.matchRom(card("shared_card_1.mcd"), roms))
    }

    @Test fun cardsOfAMultiDiscGameGoWithTheFirstDiscButRegionsStayAmbiguous() {
        val discs = listOf(
            RomCandidate(12, "Final Fantasy VII (USA) (Disc 2).chd", "psx"),
            RomCandidate(11, "Final Fantasy VII (USA) (Disc 1).chd", "psx"),
            RomCandidate(13, "Final Fantasy VII (USA) (Disc 3).chd", "psx")
        )
        assertEquals(RomMatch.Found(11), EmulatorSaveLayouts.matchRom(card("Final Fantasy VII_1.mcd"), discs))
        val regions = discs + RomCandidate(21, "Final Fantasy VII (Europe) (Disc 1).chd", "psx")
        assertEquals(RomMatch.Ambiguous, EmulatorSaveLayouts.matchRom(card("Final Fantasy VII_1.mcd"), regions))
        val twoRegions = listOf(RomCandidate(1, "Tekken 3 (USA).chd", "psx"), RomCandidate(2, "Tekken 3 (Europe).chd", "psx"))
        assertEquals(RomMatch.Ambiguous, EmulatorSaveLayouts.matchRom(card("Tekken 3_1.mcd"), twoRegions))
    }

    @Test fun gamesOfAnotherPlatformAreNotTaken() {
        val roms = listOf(RomCandidate(1, "Tetris (World).gb", "gb", "gb"), RomCandidate(2, "Tetris (World).chd", "psx", "psx"))
        assertEquals(RomMatch.Found(2), EmulatorSaveLayouts.matchRom(card("Tetris_1.mcd"), roms))
        assertEquals(RomMatch.NotFound, EmulatorSaveLayouts.matchRom(card("Tetris_1.mcd"), roms.take(1)))
    }

    @Test fun ppssppFoldersMatchOnlyByProductId() {
        val roms = listOf(
            RomCandidate(5, "Grand Theft Auto - Liberty City Stories (USA) (ULUS-10041).iso", "psp", "psp"),
            RomCandidate(6, "Patapon (USA).iso", "psp", "psp")
        )
        assertEquals(RomMatch.Found(5), EmulatorSaveLayouts.matchRom(pack("ULUS10041DATA00"), roms))
        // No product id in any name: unmatched, never a guess by title.
        assertEquals(RomMatch.NotFound, EmulatorSaveLayouts.matchRom(pack("UCUS98711DATA"), roms))
        assertEquals(RomMatch.NotFound, EmulatorSaveLayouts.matchRom(pack("SYSTEMDATA"), roms))
        assertEquals(listOf("ULUS10041", "ULUS-10041", "ULUS 10041"), EmulatorSaveLayouts.searchTerms(pack("ULUS10041DATA00")))
        assertEquals(emptyList<String>(), EmulatorSaveLayouts.searchTerms(pack("SYSTEMDATA")))
        assertEquals(listOf("SLUS-00594", "SLUS 00594", "SLUS00594"), EmulatorSaveLayouts.searchTerms(card("SLUS-00594_1.mcd")))
        assertEquals(SaveSyncPlanner.searchTerms("Crash Bandicoot.mcd"), EmulatorSaveLayouts.searchTerms(card("Crash Bandicoot_1.mcd")))
        assertEquals(emptyList<String>(), EmulatorSaveLayouts.searchTerms(card("shared_card_1.mcd")))
    }

    @Test fun savesBelongToTheGameOnThePageByName() {
        assertTrue(EmulatorSaveLayouts.belongsToGame(card("Crash Bandicoot_1.mcd"), "Crash Bandicoot (USA).chd"))
        assertTrue(EmulatorSaveLayouts.belongsToGame(card("Crash Bandicoot (USA)_1.mcd"), "Crash Bandicoot (USA).chd"))
        assertTrue(EmulatorSaveLayouts.belongsToGame(card("SLUS-00594_1.mcd"), "Metal Gear Solid (USA) [SLUS-00594].chd"))
        assertFalse(EmulatorSaveLayouts.belongsToGame(card("SLUS-00594_1.mcd"), "Metal Gear Solid (USA).chd"))
        assertFalse(EmulatorSaveLayouts.belongsToGame(card("shared_card_1.mcd"), "Crash Bandicoot (USA).chd"))
        assertTrue(EmulatorSaveLayouts.belongsToGame(pack("ULUS10041DATA00"), "GTA (USA) (ULUS-10041).iso"))
        assertFalse(EmulatorSaveLayouts.belongsToGame(pack("ULUS10041DATA00"), "GTA (USA).iso"))
    }

    @Test fun theInnerSavesFolderIsFoundWhenTheParentWasPicked() {
        val tree = mapOf("" to setOf("PSP", "Android"), "PSP" to setOf("SAVEDATA", "PPSSPP_STATE"))
        assertEquals("PSP/SAVEDATA", EmulatorSaveLayouts.innerPath(SaveFolderLayout.SAVEDATA) { tree[it] })
        assertEquals("SAVEDATA", EmulatorSaveLayouts.innerPath(SaveFolderLayout.SAVEDATA) { if (it == "") setOf("SAVEDATA") else null })
        assertEquals("", EmulatorSaveLayouts.innerPath(SaveFolderLayout.SAVEDATA) { if (it == "") setOf("ULUS10041DATA00") else null })
        assertEquals("memcards", EmulatorSaveLayouts.innerPath(SaveFolderLayout.MEMCARDS) { if (it == "") setOf("memcards", "savestates") else null })
        assertEquals("", EmulatorSaveLayouts.innerPath(SaveFolderLayout.MEMCARDS) { emptySet() })
        assertEquals("", EmulatorSaveLayouts.innerPath(SaveFolderLayout.FILES) { tree[it] })
        // The parent is picked before the emulator made its saves folder: refused, never the parent itself.
        assertNull(EmulatorSaveLayouts.innerPath(SaveFolderLayout.MEMCARDS) { if (it == "") setOf("savestates", "bios") else null })
        assertNull(EmulatorSaveLayouts.innerPath(SaveFolderLayout.SAVEDATA) { if (it == "") setOf("PSP") else if (it == "PSP") setOf("SYSTEM") else null })
        assertNull(EmulatorSaveLayouts.innerPath(SaveFolderLayout.SAVEDATA) { if (it == "") setOf("SYSTEM", "PPSSPP_STATE") else null })
        // AetherSX2 / NetherSX2: its data folder or its memcards folder.
        assertEquals("memcards", EmulatorSaveLayouts.innerPath(SaveFolderLayout.PS2_MEMCARDS) { if (it == "") setOf("bios", "memcards", "sstates") else null })
        assertEquals("", EmulatorSaveLayouts.innerPath(SaveFolderLayout.PS2_MEMCARDS) { emptySet() })
        assertNull(EmulatorSaveLayouts.innerPath(SaveFolderLayout.PS2_MEMCARDS) { if (it == "") setOf("bios", "sstates", "covers") else null })
        // Dolphin: its user folder or its GC folder; the region folders inside GC (GCI folders) are not where the cards are.
        assertEquals("GC", EmulatorSaveLayouts.innerPath(SaveFolderLayout.GC_MEMCARDS) { if (it == "") setOf("Config", "GC", "Wii", "StateSaves") else null })
        assertEquals("", EmulatorSaveLayouts.innerPath(SaveFolderLayout.GC_MEMCARDS) { if (it == "") setOf("USA", "EUR") else null })
        assertNull(EmulatorSaveLayouts.innerPath(SaveFolderLayout.GC_MEMCARDS) { if (it == "") setOf("Config", "Wii", "StateSaves") else null })
    }

    @Test fun cardsAndSaveFoldersHoldSeveralSaves() {
        assertTrue(EmulatorSaveLayouts.holdsSeveralSaves("PPSSPP (standalone)/ULUS10041DATA00.savedata.zip"))
        assertTrue(EmulatorSaveLayouts.holdsSeveralSaves("DuckStation/Crash Bandicoot_1.mcd"))
        assertTrue(EmulatorSaveLayouts.holdsSeveralSaves("DuckStation/shared_card_1.mcd"))
        assertFalse(EmulatorSaveLayouts.holdsSeveralSaves("mGBA/Pokemon Emerald.sav"))
        assertTrue(EmulatorSaveLayouts.holdsSeveralSaves("AetherSX2 - NetherSX2 (standalone)/Mcd001.ps2"))
        assertTrue(EmulatorSaveLayouts.holdsSeveralSaves("Dolphin (standalone)/MemoryCardA.USA.raw"))
        assertFalse(EmulatorSaveLayouts.holdsSeveralSaves("Dolphin (standalone)/SRAM.raw"))
    }

    @Test fun packsAreTheSameBytesWhateverTheOrderAndUnpackBackToTheFiles() {
        val files = listOf("PARAM.SFO" to byteArrayOf(1, 2, 3), "DATA.BIN" to ByteArray(1000) { it.toByte() }, "sub/ICON0.PNG" to byteArrayOf(9))
        val a = SavePack.pack(files)
        val b = SavePack.pack(files.reversed())
        assertArrayEquals(a, b)
        assertEquals(a.size.toLong(), SavePack.packedSize(files.map { it.first to it.second.size.toLong() }))
        val back = SavePack.unpack(a, 1_000_000)
        assertEquals(files.map { it.first }.sorted(), back.map { it.first })
        back.forEach { (path, bytes) -> assertArrayEquals(files.first { it.first == path }.second, bytes) }
        // Packing what was unpacked gives the very same bytes again (what a download is verified with).
        assertArrayEquals(a, SavePack.pack(back))
    }

    @Test fun packsFromElsewhereAreReadButUnsafeOnesRefused() {
        val ordinary = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip -> zip.putNextEntry(ZipEntry("DATA.BIN")); zip.write(ByteArray(50) { 7 }); zip.closeEntry() }
        }.toByteArray()
        assertEquals(listOf("DATA.BIN"), SavePack.unpack(ordinary, 1000).map { it.first })
        val evil = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip -> zip.putNextEntry(ZipEntry("../escape.bin")); zip.write(1); zip.closeEntry() }
        }.toByteArray()
        try { SavePack.unpack(evil, 1000); fail("traversal accepted") } catch (_: ArchiveSafetyException) { }
        try { SavePack.unpack(ordinary, 10); fail("size limit ignored") } catch (_: java.io.IOException) { }
    }
}
