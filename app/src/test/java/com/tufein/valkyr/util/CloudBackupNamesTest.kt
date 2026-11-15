package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CloudBackupNamesTest {

    private val hour = 3_600_000L

    @Test fun `names carry the UTC time and the device`() {
        assertEquals(
            "valkyr-20261004-2114-retroid-pocket-5.dgxb",
            CloudBackupNames.fileName(Instant.parse("2026-10-04T21:14:59Z"), "Retroid Pocket 5")
        )
    }

    @Test fun `device names become short slugs`() {
        assertEquals("ayn-odin-2-portal", CloudBackupNames.deviceSlug("  AYN Odin 2 Portal ✨ "))
        assertEquals("senor-s-pocket", CloudBackupNames.deviceSlug("Señor's Pocket"))
        assertEquals("device", CloudBackupNames.deviceSlug("✨✨"))
        assertEquals("device", CloudBackupNames.deviceSlug(""))
        val long = CloudBackupNames.deviceSlug("a".repeat(31) + " b c d")
        assertTrue(long.length <= 32)
        assertFalse(long.endsWith("-"))
    }

    @Test fun `parses its own names only`() {
        assertEquals(1791148440000L to "retroid-pocket-5", CloudBackupNames.parse("dogmatix-20261004-2114-retroid-pocket-5.dgxb"))
        assertNull(CloudBackupNames.parse("dogmatix-auto-backup-2026-10-04.json"))
        assertNull(CloudBackupNames.parse("dogmatix-20261304-2114-x.dgxb"))     // month 13
        assertNull(CloudBackupNames.parse("holiday.dgxb"))
    }

    @Test fun `rotation keeps the newest of this device and never touches others`() {
        val mine = (1..9).map { "dogmatix-2026100${it}-0300-thor.dgxb" }
        val other = (1..9).map { "dogmatix-2026100${it}-0300-odin.dgxb" }
        val foreign = listOf("notes.txt", "dogmatix-old.dgxb")
        val delete = CloudBackupNames.toDelete(mine + other + foreign, "Thor", keep = 7)
        assertEquals(listOf("dogmatix-20261002-0300-thor.dgxb", "dogmatix-20261001-0300-thor.dgxb"), delete)
        assertTrue(CloudBackupNames.toDelete(mine, "Thor", keep = 30).isEmpty())
        // At least one backup always stays.
        assertEquals(8, CloudBackupNames.toDelete(mine, "Thor", keep = -1).size)   // a damaged value never deletes everything
        assertTrue(CloudBackupNames.toDelete(mine, "Thor", keep = CloudBackupNames.KEEP_ALL).isEmpty())
    }

    @Test fun `the listing shows backups newest first, with device and size`() {
        val folder = "https://h/dav/Dogmatix/backups/"
        val listed = CloudBackupNames.listing(
            listOf(
                Triple("dogmatix-20261001-0300-thor.dgxb", 4000L, null),
                Triple("dogmatix-20261003-0300-odin.dgxb", 5000L, null),
                Triple("hand made.dgxb", null, 1791148442000L),
                Triple("readme.txt", 10L, null),
                Triple(".dogmatix-test.dgxb", 1L, null)
            ),
            folder, "Thor"
        )
        assertEquals(listOf("hand made.dgxb", "dogmatix-20261003-0300-odin.dgxb", "dogmatix-20261001-0300-thor.dgxb"), listed.map { it.name })
        assertEquals("https://h/dav/Dogmatix/backups/hand%20made.dgxb", listed[0].url)
        assertEquals("", listed[0].device)
        assertEquals("odin", listed[1].device)
        assertFalse(listed[1].isThisDevice)
        assertTrue(listed[2].isThisDevice)
        assertEquals(4000L, listed[2].size)
    }

    @Test fun `a backup is due once a day`() {
        val now = 1_800_000_000_000L
        assertTrue(CloudBackupNames.isDue(0, now))
        assertFalse(CloudBackupNames.isDue(now - 2 * hour, now))
        assertTrue(CloudBackupNames.isDue(now - 23 * hour, now))
        assertTrue(CloudBackupNames.isDue(now + 5 * hour, now))   // clock went back
    }

    @Test fun `keep steps through its choices`() {
        assertEquals(10, CloudBackupNames.shiftKeep(7, 1))
        assertEquals(5, CloudBackupNames.shiftKeep(7, -1))
        assertEquals(CloudBackupNames.KEEP_ALL, CloudBackupNames.shiftKeep(30, 1))
        assertEquals(CloudBackupNames.KEEP_ALL, CloudBackupNames.shiftKeep(CloudBackupNames.KEEP_ALL, 1))
        assertEquals(30, CloudBackupNames.shiftKeep(CloudBackupNames.KEEP_ALL, -1))
        assertEquals(3, CloudBackupNames.shiftKeep(3, -1))
        assertEquals(10, CloudBackupNames.shiftKeep(8, 1))
    }

    @Test fun `the installation id is part of the name and tells devices with the same name apart`() {
        val at = Instant.parse("2026-10-04T21:14:59Z")
        assertEquals("valkyr-20261004-2114-retroid-pocket-5.a1b2c3d4.dgxb", CloudBackupNames.fileName(at, "Retroid Pocket 5", "A1B2C3D4-0000-4000-8000-000000000000"))
        assertEquals("a1b2c3d4", CloudBackupNames.shortId("a1b2c3d4-0000-4000-8000-000000000000"))
        assertEquals("", CloudBackupNames.shortId(""))
        val parsed = CloudBackupNames.parseName("dogmatix-20261004-2114-retroid-pocket-5.a1b2c3d4.dgxb")!!
        assertEquals("retroid-pocket-5", parsed.slug)
        assertEquals("a1b2c3d4", parsed.id)
        assertEquals("", CloudBackupNames.parseName("dogmatix-20261004-2114-retroid-pocket-5.dgxb")!!.id)
        // A part file of a cut-off upload is not a backup.
        assertNull(CloudBackupNames.parseName("dogmatix-20261004-2114-thor.a1b2c3d4.dgxb.part"))
    }

    @Test fun `two handhelds with the same name rotate only their own backups`() {
        val mine = "aaaaaaaa-0000"
        val twin = "bbbbbbbb-0000"
        val names = (1..9).map { CloudBackupNames.fileName(Instant.parse("2026-10-0${it}T03:00:00Z"), "Retroid Pocket 5", mine) } +
            (1..9).map { CloudBackupNames.fileName(Instant.parse("2026-10-0${it}T03:00:00Z"), "Retroid Pocket 5", twin) }
        val delete = CloudBackupNames.toDelete(names, "Retroid Pocket 5", keep = 7, deviceId = mine)
        assertEquals(2, delete.size)
        assertTrue(delete.all { it.endsWith(".aaaaaaaa.dgxb") })
    }

    @Test fun `a name in another script still has its own backups`() {
        val a = CloudBackupNames.fileName(Instant.parse("2026-10-04T21:14:59Z"), "掌机", "aaaaaaaa-0")
        val b = CloudBackupNames.fileName(Instant.parse("2026-10-04T21:14:59Z"), "ゲーム機", "bbbbbbbb-0")
        assertEquals("valkyr-20261004-2114-device.aaaaaaaa.dgxb", a)
        assertTrue(CloudBackupNames.toDelete(listOf(a, b), "掌机", keep = 1, deviceId = "aaaaaaaa-0").isEmpty())
        val listed = CloudBackupNames.listing(listOf(Triple(a, 1L, null), Triple(b, 1L, null)), "https://h/b/", "掌机", "aaaaaaaa-0")
        assertEquals(listOf(true, false), listed.sortedBy { it.name }.map { it.isThisDevice })
    }

    @Test fun `backups named before 6_0 are still listed and rotated by their slug`() {
        val old = (1..9).map { "dogmatix-2026100${it}-0300-thor.dgxb" }
        val fresh = CloudBackupNames.fileName(Instant.parse("2026-10-09T03:00:00Z"), "Thor", "aaaaaaaa-0")
        val delete = CloudBackupNames.toDelete(old + fresh, "Thor", keep = 7, deviceId = "aaaaaaaa-0")
        // The id of a new name is mine; legacy names of the same slug count as mine too (nothing else can tell).
        assertEquals(listOf("dogmatix-20261003-0300-thor.dgxb", "dogmatix-20261002-0300-thor.dgxb", "dogmatix-20261001-0300-thor.dgxb"), delete)
        // A new-style backup of another installation with the same name is never mine.
        val twin = CloudBackupNames.fileName(Instant.parse("2026-10-01T03:00:00Z"), "Thor", "bbbbbbbb-0")
        assertFalse(twin in CloudBackupNames.toDelete(listOf(twin) + old, "Thor", keep = 1, deviceId = "aaaaaaaa-0"))
    }

    @Test fun `4_0 names start with valkyr and the dogmatix names of earlier versions are still read and rotated`() {
        val at = Instant.parse("2026-10-04T21:14:59Z")
        assertTrue(CloudBackupNames.fileName(at, "Thor", "aaaaaaaa-0").startsWith(CloudBackupNames.PREFIX))
        val legacy = CloudBackupNames.parseName("dogmatix-20261004-2114-thor.aaaaaaaa.dgxb")!!
        val current = CloudBackupNames.parseName("valkyr-20261004-2114-thor.aaaaaaaa.dgxb")!!
        assertEquals(legacy, current)
        assertNull(CloudBackupNames.parseName("valkyr-auto-backup-2026-10-04.json"))
        assertNull(CloudBackupNames.parseName("other-20261004-2114-thor.dgxb"))
        // Old and new names of one installation rotate together, by the time in the name.
        val names = (1..4).map { "dogmatix-2026100${it}-0300-thor.aaaaaaaa.dgxb" } +
            (5..8).map { CloudBackupNames.fileName(Instant.parse("2026-10-0${it}T03:00:00Z"), "Thor", "aaaaaaaa-0") }
        assertEquals(
            listOf("dogmatix-20261002-0300-thor.aaaaaaaa.dgxb", "dogmatix-20261001-0300-thor.aaaaaaaa.dgxb"),
            CloudBackupNames.toDelete(names, "Thor", keep = 6, deviceId = "aaaaaaaa-0")
        )
        val listed = CloudBackupNames.listing(names.map { Triple(it, 1L, null) }, "https://h/b/", "Thor", "aaaaaaaa-0")
        assertEquals("valkyr-20261008-0300-thor.aaaaaaaa.dgxb", listed.first().name)
        assertEquals("dogmatix-20261001-0300-thor.aaaaaaaa.dgxb", listed.last().name)
        assertTrue(listed.all { it.isThisDevice })
    }
}
