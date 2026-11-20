package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentTrackersTest {

    @Test fun `only announce schemes with a host are accepted`() {
        assertEquals("udp://tracker.example.org:1337/announce", TorrentTrackers.validate(" udp://tracker.example.org:1337/announce/ ")?.url)
        assertNotNull(TorrentTrackers.validate("https://tracker.example.org/announce"))
        assertNull(TorrentTrackers.validate("file:///etc/passwd"))
        assertNull(TorrentTrackers.validate("udp://user:secret@tracker.example.org/announce"))
        assertNull(TorrentTrackers.validate("udp:///announce"))
        assertNull(TorrentTrackers.validate("udp://tracker.example.org/announce#x"))
        assertNull(TorrentTrackers.validate("udp://tracker.example.org/a\u0000b"))
    }

    private fun assertNotNull(value: Any?) = assertTrue(value != null)

    @Test fun `the list is bounded and duplicates are dropped`() {
        var entries = emptyList<TrackerEntry>()
        repeat(TorrentTrackers.MAX_TRACKERS) { i ->
            entries = TorrentTrackers.add(entries, "udp://tracker$i.example.org/announce")!!
        }
        assertEquals(TorrentTrackers.MAX_TRACKERS, entries.size)
        assertNull(TorrentTrackers.add(entries, "udp://tracker0.example.org/announce"))
        assertNull(TorrentTrackers.add(entries, "udp://one-too-many.example.org/announce"))
        assertNull(TorrentTrackers.add(entries, "not a tracker"))
    }

    @Test fun `decode keeps the order and the switches and skips bad rows`() {
        val order = listOf("udp://a.example.org/announce", "bad", "https://b.example.org/announce", "udp://a.example.org/announce").joinToString("\n")
        val entries = TorrentTrackers.decode(order, setOf("https://b.example.org/announce"))
        assertEquals(2, entries.size)
        assertEquals("udp://a.example.org/announce", entries[0].url)
        assertTrue(entries[0].enabled)
        assertFalse(entries[1].enabled)
        assertEquals("udp://a.example.org/announce\nhttps://b.example.org/announce", TorrentTrackers.encode(entries))
    }

    @Test fun `enabled filters switched off trackers`() {
        val entries = listOf(TrackerEntry("udp://a.example.org/announce"), TrackerEntry("udp://b.example.org/announce", false))
        assertEquals(listOf("udp://a.example.org/announce"), TorrentTrackers.enabled(entries))
        assertTrue(TorrentTrackers.remove(entries, "udp://a.example.org/announce").size == 1)
    }

    @Test fun `magnets get encoded tracker parameters without losing the original ones`() {
        val magnet = "magnet:?xt=urn:btih:abc&dn=My%20Set&tr=udp%3A%2F%2Foriginal.example.org%2Fannounce"
        val result = TorrentTrackers.appendToMagnet(magnet, listOf("udp://mine.example.org:1337/announce"))
        assertTrue(result.contains("&tr=udp%3A%2F%2Fmine.example.org%3A1337%2Fannounce"))
        assertTrue(result.contains("original.example.org"))
        assertTrue(result.startsWith("magnet:"))
        assertEquals("https://example.org/set.torrent", TorrentTrackers.appendToMagnet("https://example.org/set.torrent", listOf("udp://x/")))
    }
}
