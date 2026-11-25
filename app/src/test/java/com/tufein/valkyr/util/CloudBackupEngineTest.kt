package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class CloudBackupEngineTest {

    private val json = """{"format":"dogmatix-backup","version":1,"settings":{}}"""

    private fun nextcloud(): FakeDavStore = FakeDavStore("/remote.php/dav/files/john").apply {
        failing["/"] = DavProblem.NOT_WEBDAV          // the server root is the web interface
    }

    @Test fun `finds the Nextcloud files address and prepares the folder`() {
        val store = nextcloud()
        val c = CloudBackupEngine.connect(store, "cloud.example.com", "john", "Dogmatix")
        assertEquals("https://cloud.example.com/remote.php/dav/files/john/", c.serverUrl)
        assertEquals("https://cloud.example.com/remote.php/dav/files/john/Dogmatix/", c.rootUrl)
        assertTrue(c.createdFolder)
        assertTrue("/remote.php/dav/files/john/Dogmatix/backups" in store.collections)
        assertTrue("/remote.php/dav/files/john/Dogmatix/sync" in store.collections)
        // The write test leaves nothing behind.
        assertTrue(store.files.isEmpty())
        assertTrue(store.requests.any { it.startsWith("PUT ") && it.contains(CloudBackupEngine.PROBE_NAME) })
        // A second time nothing new is created.
        assertFalse(CloudBackupEngine.connect(store, c.serverUrl, "john", "Dogmatix").createdFolder)
    }

    @Test fun `a login problem stops the search and is what the user hears`() {
        val store = FakeDavStore().apply { failing["/"] = DavProblem.AUTH }
        try {
            CloudBackupEngine.connect(store, "https://cloud.example.com", "john", "Dogmatix")
            fail("expected DavException")
        } catch (e: DavException) {
            assertEquals(DavProblem.AUTH, e.problem)
        }
        assertEquals(1, store.requests.count { it.startsWith("PROPFIND") })

        val guessed = FakeDavStore().apply {
            failing["/"] = DavProblem.NOT_WEBDAV
            failing["/remote.php/dav/files/john"] = DavProblem.AUTH
        }
        try {
            CloudBackupEngine.connect(guessed, "https://cloud.example.com", "john", "Dogmatix")
            fail("expected DavException")
        } catch (e: DavException) {
            assertEquals(DavProblem.AUTH, e.problem)
        }
    }

    @Test fun `a bad address fails before any request`() {
        val store = FakeDavStore()
        try {
            CloudBackupEngine.connect(store, "ftp://nas", "john", "Dogmatix")
            fail("expected DavException")
        } catch (e: DavException) {
            assertEquals(DavProblem.BAD_URL, e.problem)
        }
        assertTrue(store.requests.isEmpty())
    }

    @Test fun `a read-only folder fails the connection test`() {
        val store = object : DavStore by FakeDavStore("/dav") {
            override fun put(url: String, bytes: ByteArray, contentType: String, ifMatch: String?, ifNoneMatch: Boolean): String? =
                throw DavException(DavProblem.FORBIDDEN, 403)
        }
        try {
            CloudBackupEngine.connect(store, "https://nas.local/dav/", "john", "Dogmatix")
            fail("expected DavException")
        } catch (e: DavException) {
            assertEquals(DavProblem.FORBIDDEN, e.problem)
        }
    }

    @Test fun `backups are uploaded, listed, read back and rotated per device`() {
        val store = FakeDavStore("/dav")
        val c = CloudBackupEngine.connect(store, "https://nas.local/dav", "", "Games/Dogmatix")
        val sealed = BackupCrypto.seal(json, "passphrase one")
        val days = (1..5).map { Instant.parse("2026-10-0${it}T03:00:00Z") }
        days.forEach { CloudBackupEngine.upload(store, c.serverUrl, c.rootUrl, sealed, "Thor", keep = 3, now = it) }
        CloudBackupEngine.upload(store, c.serverUrl, c.rootUrl, sealed, "Odin", keep = 1, now = Instant.parse("2026-10-01T05:00:00Z"))
        val last = CloudBackupEngine.upload(store, c.serverUrl, c.rootUrl, sealed, "Odin", keep = 1, now = Instant.parse("2026-10-02T05:00:00Z"))
        assertEquals(listOf("valkyr-20261001-0500-odin.dgxb"), last.deleted)

        val listed = CloudBackupEngine.list(store, c.rootUrl, "Thor")
        assertEquals(
            listOf("20261005-0300-thor", "20261004-0300-thor", "20261003-0300-thor", "20261002-0500-odin"),
            listed.map { it.name.removePrefix("valkyr-").removeSuffix(".dgxb") }
        )
        assertTrue(listed[0].isThisDevice)
        assertEquals(sealed.size.toLong(), listed[0].size)
        assertEquals("https://nas.local/dav/Games/Dogmatix/backups/valkyr-20261005-0300-thor.dgxb", listed[0].url)

        assertEquals(json, CloudBackupEngine.download(store, listed[3].url, "passphrase one"))
        try {
            CloudBackupEngine.download(store, listed[3].url, "passphrase two")
            fail("expected UnreadableException")
        } catch (_: BackupCrypto.UnreadableException) {
        }
    }

    @Test fun `a second cloud gets the same backup under the same name and keeps its own rotation`() {
        val main = FakeDavStore("/dav"); val copy = FakeDavStore("/dav")
        val a = CloudBackupEngine.connect(main, "https://one.local/dav", "", "Games/Valkyr")
        val b = CloudBackupEngine.connect(copy, "https://two.local/dav", "", "Backups")
        val sealed = BackupCrypto.seal(json, "passphrase one")
        (1..4).forEach {
            val at = Instant.parse("2026-10-0${it}T03:00:00Z")
            val first = CloudBackupEngine.upload(main, a.serverUrl, a.rootUrl, sealed, "Thor", keep = 2, now = at, deviceId = "d1")
            val second = CloudBackupEngine.upload(copy, b.serverUrl, b.rootUrl, sealed, "Thor", keep = 2, now = at, deviceId = "d1")
            assertEquals(first.name, second.name)
            assertEquals(first.deleted, second.deleted)
        }
        val names = { store: FakeDavStore, c: CloudBackupEngine.Connection -> CloudBackupEngine.list(store, c.rootUrl, "Thor", "d1").map { it.name } }
        assertEquals(names(main, a), names(copy, b))
        assertEquals(2, names(copy, b).size)
        // A backup written to the second cloud restores with the same passphrase.
        assertEquals(json, CloudBackupEngine.download(copy, CloudBackupEngine.list(copy, b.rootUrl, "Thor", "d1")[0].url, "passphrase one"))
    }

    @Test fun `with keep everything on, a long run of backups leaves every remote file in place`() {
        val store = FakeDavStore("/dav")
        val c = CloudBackupEngine.connect(store, "https://nas.local/dav", "", "Games/Dogmatix")
        val sealed = BackupCrypto.seal(json, "passphrase one")
        (1..12).forEach { day ->
            val up = CloudBackupEngine.upload(store, c.serverUrl, c.rootUrl, sealed, "Thor", keep = CloudBackupNames.KEEP_ALL, now = Instant.parse("2026-10-%02dT03:00:00Z".format(day)))
            assertTrue(up.deleted.isEmpty())
        }
        assertEquals(12, CloudBackupEngine.list(store, c.rootUrl, "Thor").size)
        // The old rule still deletes: the same twelve with a keep of 3 leave three.
        CloudBackupEngine.upload(store, c.serverUrl, c.rootUrl, sealed, "Thor", keep = 3, now = Instant.parse("2026-10-13T03:00:00Z"))
        assertEquals(3, CloudBackupEngine.list(store, c.rootUrl, "Thor").size)
    }

    @Test fun `a backup the server stored short is reported`() {
        val store = FakeDavStore("/dav").apply { sizeSkew = -10 }
        val root = "https://nas.local/dav/Dogmatix/"
        try {
            CloudBackupEngine.upload(store, "https://nas.local/dav/", root, ByteArray(100), "Thor", 7, Instant.parse("2026-10-01T00:00:00Z"))
            fail("expected DavException")
        } catch (e: DavException) {
            assertEquals(DavProblem.BAD_RESPONSE, e.problem)
        }
    }

    @Test fun `listing a folder that does not exist yet is empty`() {
        assertTrue(CloudBackupEngine.list(FakeDavStore("/dav"), "https://nas.local/dav/Dogmatix/", "Thor").isEmpty())
    }

    @Test fun `a vanished backup is reported as not found`() {
        try {
            CloudBackupEngine.download(FakeDavStore("/dav"), "https://nas.local/dav/Dogmatix/backups/x.dgxb", "passphrase")
            fail("expected DavException")
        } catch (e: DavException) {
            assertEquals(DavProblem.NOT_FOUND, e.problem)
        }
        assertNull(FakeDavStore().get("https://h/x", 10))
    }

    private val root = "https://nas.local/dav/Dogmatix/"
    private val backups = "/dav/Dogmatix/backups"
    private val at = Instant.parse("2026-10-01T03:00:00Z")

    @Test fun `an upload arrives as a part file and is moved into place`() {
        val store = FakeDavStore("/dav", backups)
        val up = CloudBackupEngine.upload(store, "https://nas.local/dav/", root, ByteArray(50), "Thor", 7, at, "aaaaaaaa-0")
        assertTrue(store.requests.any { it.startsWith("PUT ") && it.contains(".dgxb.part") })
        assertTrue(store.requests.any { it.startsWith("MOVE ") })
        assertEquals(listOf("$backups/${up.name}"), store.files.keys.toList())
    }

    @Test fun `a server without MOVE gets a direct upload and no part file is left`() {
        val store = FakeDavStore("/dav", backups).apply { moveUnsupported = true }
        val up = CloudBackupEngine.upload(store, "https://nas.local/dav/", root, ByteArray(50), "Thor", 7, at)
        assertEquals(listOf("$backups/${up.name}"), store.files.keys.toList())
    }

    @Test fun `a cut-off upload is never listed as a backup and is cleaned up next time`() {
        val store = FakeDavStore("/dav", backups)
        val name = CloudBackupNames.fileName(at, "Thor", "aaaaaaaa-0")
        store.write("https://nas.local/dav/Dogmatix/backups/$name.part", "half")
        assertTrue(CloudBackupEngine.list(store, root, "Thor", "aaaaaaaa-0").isEmpty())
        CloudBackupEngine.upload(store, "https://nas.local/dav/", root, ByteArray(50), "Thor", 7, at.plusSeconds(86_400), "aaaaaaaa-0")
        assertEquals(1, store.files.size)
        assertFalse(store.files.keys.any { it.endsWith(".part") })
    }

    @Test fun `a full server fails the upload instead of falling back`() {
        val store = FakeDavStore("/dav", backups).apply { failNextPut = DavProblem.NO_SPACE }
        try {
            CloudBackupEngine.upload(store, "https://nas.local/dav/", root, ByteArray(50), "Thor", 7, at)
            fail("expected DavException")
        } catch (e: DavException) {
            assertEquals(DavProblem.NO_SPACE, e.problem)
        }
        assertEquals(1, store.requests.count { it.startsWith("PUT ") })
    }

    @Test fun `two handhelds with one name keep their own backups`() {
        val store = FakeDavStore("/dav", backups)
        val sealed = ByteArray(20)
        (1..4).forEach { d ->
            CloudBackupEngine.upload(store, "https://nas.local/dav/", root, sealed, "Retroid", 2, Instant.parse("2026-10-0${d}T03:00:00Z"), "aaaaaaaa-0")
            CloudBackupEngine.upload(store, "https://nas.local/dav/", root, sealed, "Retroid", 2, Instant.parse("2026-10-0${d}T04:00:00Z"), "bbbbbbbb-0")
        }
        assertEquals(2, store.files.keys.count { it.contains(".aaaaaaaa.") })
        assertEquals(2, store.files.keys.count { it.contains(".bbbbbbbb.") })
        val mine = CloudBackupEngine.list(store, root, "Retroid", "aaaaaaaa-0").filter { it.isThisDevice }
        assertEquals(2, mine.size)
    }
}
