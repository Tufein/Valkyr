package com.tufein.valkyr.util

import com.tufein.valkyr.data.local.entity.DownloadableFileEntity
import com.tufein.valkyr.data.model.DownloadItemModel
import com.tufein.valkyr.data.model.DownloadProvenance
import com.tufein.valkyr.data.model.DownloadStatus
import com.tufein.valkyr.data.local.entity.DownloadHistoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProvenanceTest {

    private fun file(sourceUrl: String) = DownloadableFileEntity(
        name = "Game", fileName = "Game.gba", consoleId = "gba",
        downloadUrl = "https://example.invalid/Game.gba?key=abc", fileSize = 100L,
        fileExtension = "gba", sourceUrl = sourceUrl
    )

    private fun history(sourceUrl: String) = DownloadHistoryEntity.from(file(sourceUrl), DownloadItemModel(
        name = "Game", fileName = "Game.gba", downloadSpeed = 0f, progress = 0f,
        fileSize = 100L, downloadedBytes = 0L, status = DownloadStatus.COMPLETED, startedAt = 1L
    ))

    @Test fun `a new history row remembers its source and treats it as the first one`() {
        val row = history("https://host.example.org/set")
        assertEquals("https://host.example.org/set", row.sourceUrl)
        assertEquals("https://host.example.org/set", row.originalSourceUrl)
        assertEquals("", row.switchedFrom)
    }

    @Test fun `provenance survives a backup roundtrip`() {
        val row = history("https://host.example.org/set").copy(
            originalSourceUrl = "magnet:?xt=urn:btih:abc&dn=Set",
            switchedFrom = "Torrent"
        )
        val restored = BackupJson.historyFromJson(BackupJson.historyToJson(listOf(row))).single()
        assertEquals(row.sourceUrl, restored.sourceUrl)
        assertEquals(row.originalSourceUrl, restored.originalSourceUrl)
        assertEquals("Torrent", restored.switchedFrom)
    }

    @Test fun `labels never expose credentials paths or magnet hashes`() {
        assertEquals("host.example.org", DownloadSourcePolicy.safeLabel("https://user:secret@host.example.org/files/set?token=abc123"))
        assertEquals("RomM", DownloadSourcePolicy.safeLabel("romm://snes"))
        assertEquals("Torrent", DownloadSourcePolicy.safeLabel("magnet:?xt=urn:btih:deadbeef&dn=Secret%20Set"))
        assertEquals("Source", DownloadSourcePolicy.safeLabel(""))
        assertFalse(DownloadSourcePolicy.safeLabel("https://user:secret@host.example.org/x").contains("secret"))
        assertFalse(DownloadSourcePolicy.safeLabel("magnet:?xt=urn:btih:deadbeef").contains("deadbeef"))
    }

    @Test fun `the first source stays visible after a switch`() {
        val p = DownloadProvenance(source = "host.example.org", original = "Torrent", switchedFrom = "Torrent")
        assertEquals("host.example.org", p.source)
        assertEquals("Torrent", p.original)
        assertTrue(p.switchedFrom.isNotEmpty())
    }
}
