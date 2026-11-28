package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class FingerprintsTest {
    @Test fun knownVectors() {
        val f = Fingerprints.compute("abc.bin", ByteArrayInputStream("abc".toByteArray()))
        assertEquals(3L, f.bytes)
        assertEquals("352441c2", f.crc32)
        assertEquals("900150983cd24fb0d6963f7d28e17f72", f.md5)
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", f.sha1)
    }

    @Test fun emptyFile() {
        val f = Fingerprints.compute("e", ByteArrayInputStream(ByteArray(0)))
        assertEquals("00000000", f.crc32)
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", f.md5)
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", f.sha1)
    }

    @Test fun bigInputReportsProgressAndTheSameSums() {
        val data = ByteArray(1_000_000) { (it % 251).toByte() }
        var last = 0L
        val f = Fingerprints.compute("big", ByteArrayInputStream(data)) { last = it }
        assertEquals(1_000_000L, last)
        assertEquals(1_000_000L, f.bytes)
        assertEquals(Fingerprints.compute("big", ByteArrayInputStream(data)), f)
    }

    @Test fun formatListsEveryFile() {
        val text = Fingerprints.format(listOf(
            Fingerprints.Fingerprint("a.cue", 10, "00000001", "m1", "s1"),
            Fingerprints.Fingerprint("a.bin", 20, "00000002", "m2", "s2")
        ))
        assertTrue(text.contains("a.cue\nSize: 10 bytes\nCRC32: 00000001"))
        assertTrue(text.contains("\n\na.bin\nSize: 20 bytes"))
        assertTrue(text.endsWith("SHA-1: s2"))
    }
}
