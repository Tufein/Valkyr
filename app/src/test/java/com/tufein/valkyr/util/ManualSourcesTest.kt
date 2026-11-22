package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualSourcesTest {

    @Test fun `source settings round trip and an empty value means none`() {
        assertEquals(ManualSources.DEFAULT_ENABLED, ManualSources.decodeEnabled(null))
        assertEquals(ManualSources.ORDER.toSet(), ManualSources.decodeEnabled("romm,screenscraper,archive"))
        assertEquals(emptySet<ManualSource>(), ManualSources.decodeEnabled(""))
        assertEquals("romm,archive", ManualSources.encodeEnabled(setOf(ManualSource.ARCHIVE, ManualSource.ROMM)))
        assertEquals(setOf(ManualSource.SCREENSCRAPER), ManualSources.decodeEnabled("screenscraper,unknown"))
    }

    @Test fun `a remembered miss is skipped for a month, an unavailable source is not`() {
        val key = "manuals/snes/Game"
        val now = 1_000_000L
        val misses = ManualSources.rememberMiss(emptyMap(), key, ManualSource.SCREENSCRAPER, now)
        assertEquals(
            listOf(ManualSource.ROMM, ManualSource.ARCHIVE),
            ManualSources.askOrder(ManualSources.DEFAULT_ENABLED, misses, key, now + 1_000)
        )
        assertEquals(
            ManualSources.ORDER,
            ManualSources.askOrder(ManualSources.DEFAULT_ENABLED, misses, key, now + ManualSources.MISS_TTL_MS + 1)
        )
        // Another game is unaffected, and a new miss replaces the old one for the same game and source.
        assertEquals(ManualSources.ORDER, ManualSources.askOrder(ManualSources.DEFAULT_ENABLED, misses, "other", now + 1_000))
        val newer = ManualSources.rememberMiss(misses, key, ManualSource.SCREENSCRAPER, now + 5_000)
        assertEquals(1, newer.size)
    }

    @Test fun `hits are named safely and de-duplicated`() {
        val scan = ManualHit(ManualSource.ARCHIVE, "https://archive.org/download/x/Super%20Mario%20Manual.pdf", "Super Mario Manual.pdf")
        assertEquals("Super Mario Manual.pdf", ManualSources.fileName(scan, "Super Mario"))
        val noName = ManualHit(ManualSource.SCREENSCRAPER, "https://api.screenscraper.fr/api2/manual.php", "", "ScreenScraper")
        assertEquals("Super Mario.pdf", ManualSources.fileName(noName, "Super Mario"))
        val hits = listOf(scan, scan.copy(source = ManualSource.ROMM, url = "https://romm.test/manual.pdf", name = "manual.pdf"))
        assertEquals(2, ManualSources.dedupe(hits).size) // different file names, both kept
        assertEquals(1, ManualSources.dedupe(listOf(scan, scan.copy(label = "second"))).size)
    }

    @Test fun `only web links are accepted and redirects stay at the provider`() {
        assertTrue(ManualSources.validLink("https://example.com/manual.pdf"))
        assertTrue(ManualSources.validLink(" http://example.com/manual.pdf "))
        assertFalse(ManualSources.validLink("file:///etc/passwd"))
        assertFalse(ManualSources.validLink("javascript:alert(1)"))
        assertFalse(ManualSources.validLink("https://localhost/manual.pdf"))
        assertFalse(ManualSources.validLink("https://0.0.0.0/manual.pdf"))
        assertFalse(ManualSources.validLink("http://[::1]/manual.pdf"))
        assertFalse(ManualSources.validLink("http://127.8.9.10/manual.pdf"))
        assertTrue(ManualSources.acceptsRedirect(ManualSource.LINK, "https://a.test/x", "https://a.test/y"))
        assertFalse(ManualSources.acceptsRedirect(ManualSource.LINK, "https://a.test/x", "https://other.test/y"))
        assertTrue(ManualSources.acceptsRedirect(ManualSource.ARCHIVE, "https://archive.org/download/x/y", "https://ia800.us.archive.org/9/items/x/y"))
    }

    @Test fun `internet archive search and metadata are parsed`() {
        val search = """{"response":{"numFound":1,"docs":[{"identifier":"mario_manual","title":"Super Mario Manual"},{"title":"no id"}]}}"""
        assertEquals(listOf(ManualSources.ArchiveItem("mario_manual", "Super Mario Manual")), ManualSources.parseArchiveSearch(search))
        assertTrue(ManualSources.archiveSearchUrl("Super Mario World").contains("mediatype%3Atexts"))
        assertEquals(
            "https://archive.org/download/mario_manual/Super%20Mario%20Manual.pdf",
            ManualSources.archiveDownloadUrl("mario_manual", "Super Mario Manual.pdf")
        )
        val meta = """{"files":[
            {"name":"mario_djvu.txt","format":"DjVuTXT"},
            {"name":"Super_Mario_manual.pdf","format":"Text PDF"},
            {"name":"manual.pdf","format":"Text PDF"}
        ]}"""
        assertEquals("Super_Mario_manual.pdf", ManualSources.parseArchiveManual(meta, "Super Mario"))
        assertNull(ManualSources.parseArchiveManual("""{"files":[]}""", "Mario"))
    }

    @Test fun `screen scraper manual media is picked and stripped of credentials`() {
        val game = ScreenScraper.Game("1", listOf("Game"), listOf(
            ScreenScraper.Media("box-2D", "us", "https://api.screenscraper.fr/api2/img.php?devid=d&devpassword=p&softname=Valkyr", "png"),
            ScreenScraper.Media("manual", "eu", "https://api.screenscraper.fr/api2/manual.php?devid=d&devpassword=p&softname=Valkyr", "pdf"),
            ScreenScraper.Media("manual", "us", "https://api.screenscraper.fr/api2/manual.php?ssid=u&sspassword=p", "pdf")
        ))
        val picked = ScreenScraper.pickManual(game, listOf("wor", "us", "eu"))
        // The preferred US manual wins, and no password is left in the stored URL.
        assertEquals("https://api.screenscraper.fr/api2/manual.php", picked)
        assertEquals(picked, ScreenScraper.stripCredentials(picked.orEmpty()))
    }

    @Test fun `text map stores attribution and misses`() {
        var text: String? = null
        text = Manuals.TextMap.put(text, "manuals/snes/A/manual.pdf", "screenscraper")
        text = Manuals.TextMap.put(text, "manuals/snes/A/other.pdf", "archive")
        assertEquals("screenscraper", Manuals.TextMap.get(text, "manuals/snes/A/manual.pdf"))
        text = Manuals.TextMap.remove(text, "manuals/snes/A/manual.pdf")
        assertNull(Manuals.TextMap.get(text, "manuals/snes/A/manual.pdf"))
        assertEquals("archive", Manuals.TextMap.get(text, "manuals/snes/A/other.pdf"))
    }
}
