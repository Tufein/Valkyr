package com.tufein.valkyr.util

import com.google.gson.JsonParser
import com.tufein.valkyr.util.CoverSources.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared part of the TheGamesDB / SteamGridDB / IGDB cover sources (written from their public documentation). */
class CoverApisTest {

    @Test fun `a source is set up when its key is there`() {
        val none = CoverApis.Keys()
        assertFalse(none.configured(Source.THEGAMESDB))
        assertFalse(none.configured(Source.STEAMGRIDDB))
        assertFalse(none.configured(Source.IGDB))
        val keys = CoverApis.Keys(tgdb = " abc123 ", sgdb = "def456", igdbId = "client", igdbSecret = "")
        assertTrue(keys.configured(Source.THEGAMESDB))
        assertEquals("abc123", keys.tgdbKey)
        assertTrue(keys.configured(Source.STEAMGRIDDB))
        // IGDB needs both the client id and the secret.
        assertFalse(keys.configured(Source.IGDB))
        assertTrue(CoverApis.Keys(igdbId = "client", igdbSecret = "secret").configured(Source.IGDB))
        // Sources that do not use these keys are not held back by them.
        assertTrue(none.configured(Source.LIBRETRO))
    }

    @Test fun `the key the app was built with stands in for a missing TheGamesDB key`() {
        val shared = CoverApis.Keys(tgdbBuiltIn = "builtin")
        assertTrue(shared.configured(Source.THEGAMESDB))
        assertTrue(shared.tgdbShared)
        assertEquals("builtin", shared.tgdbKey)
        val own = CoverApis.Keys(tgdb = "mine", tgdbBuiltIn = "builtin")
        assertEquals("mine", own.tgdbKey)
        assertFalse(own.tgdbShared)
        assertFalse(CoverApis.Keys().tgdbShared)
    }

    @Test fun `only the users own key counts for the default switch`() {
        assertTrue(CoverApis.Keys(tgdb = "k").ownConfigured(Source.THEGAMESDB))
        assertFalse(CoverApis.Keys(tgdbBuiltIn = "builtin").ownConfigured(Source.THEGAMESDB))
        assertTrue(CoverApis.Keys(sgdb = "k").ownConfigured(Source.STEAMGRIDDB))
    }

    @Test fun `redirects are not followed and a slow request has a deadline`() {
        // Any 3xx is "try later", never a miss; the HTTP layer is told not to follow it (JsonHttp followRedirects = false).
        listOf(301, 302, 307, 308).forEach { assertEquals(CoverApis.Status.BUSY, CoverApis.status(it)) }
        assertTrue(CoverApis.DEADLINE_MS in 10_000..30_000)
        assertTrue(CoverApis.SOFT_KEY_PAUSE_MS < CoverApis.KEY_PAUSE_MS)
    }

    @Test fun `a key with spaces or line breaks is not used`() {
        assertNull(CoverApis.plausible("abc def"))
        assertNull(CoverApis.plausible("abc\ndef"))
        assertNull(CoverApis.plausible("   "))
        assertNull(CoverApis.plausible("x".repeat(201)))
        assertEquals("abc", CoverApis.plausible("\tabc \n"))
        assertFalse(CoverApis.Keys(sgdb = "bad key").configured(Source.STEAMGRIDDB))
    }

    @Test fun `the first key of a source is what switches it on`() {
        val before = CoverApis.Keys()
        assertEquals(setOf(Source.THEGAMESDB), CoverApis.Keys(tgdb = "k").newlyConfigured(before))
        assertEquals(emptySet<Source>(), CoverApis.Keys(tgdb = "k2").newlyConfigured(CoverApis.Keys(tgdb = "k")))
        // IGDB only once both parts are there.
        val idOnly = CoverApis.Keys(igdbId = "id")
        assertEquals(emptySet<Source>(), idOnly.newlyConfigured(before))
        assertEquals(setOf(Source.IGDB), CoverApis.Keys(igdbId = "id", igdbSecret = "s").newlyConfigured(idOnly))
    }

    @Test fun `keys never print and a changed key changes the fingerprint`() {
        val keys = CoverApis.Keys(tgdb = "SECRET-TGDB", sgdb = "SECRET-SGDB", igdbId = "CLIENT-ID", igdbSecret = "SECRET-IGDB")
        val text = keys.toString()
        listOf("SECRET-TGDB", "SECRET-SGDB", "CLIENT-ID", "SECRET-IGDB").forEach { assertFalse(it, text.contains(it)) }
        assertTrue(keys.fingerprint != CoverApis.Keys(tgdb = "other", sgdb = "SECRET-SGDB", igdbId = "CLIENT-ID", igdbSecret = "SECRET-IGDB").fingerprint)
        assertTrue(keys.fingerprint(Source.IGDB) != CoverApis.Keys(igdbId = "CLIENT-ID", igdbSecret = "x").fingerprint(Source.IGDB))
        assertEquals(keys.fingerprint(Source.THEGAMESDB), CoverApis.Keys(tgdb = "SECRET-TGDB").fingerprint(Source.THEGAMESDB))
    }

    @Test fun `keys and the client secret stay out of backups but the client id may go`() {
        listOf(CoverApis.TGDB_KEY, CoverApis.SGDB_KEY, CoverApis.IGDB_CLIENT_SECRET_KEY).forEach {
            assertTrue(it, CloudSettingKeys.isSecret(it))
            val setting = JsonParser.parseString("""{"t":"s","v":"abc"}""")
            assertNull(it, BackupJson.decodeSetting(it, setting))
            assertNull(it, BackupJson.decodeSetting("personal:p1:$it", setting))
        }
        assertFalse(CloudSettingKeys.isSecret(CoverApis.IGDB_CLIENT_ID_KEY))
        val id = BackupJson.decodeSetting(CoverApis.IGDB_CLIENT_ID_KEY, JsonParser.parseString("""{"t":"s","v":"abc"}"""))
        assertEquals("abc", id)
        // ... and a value of the wrong type is dropped.
        assertNull(BackupJson.decodeSetting(CoverApis.IGDB_CLIENT_ID_KEY, JsonParser.parseString("""{"t":"b","v":true}""")))
        // The older secrets are untouched.
        assertTrue(CloudSettingKeys.isSecret(ScreenScraper.DEV_PASSWORD_KEY))
        assertTrue(CloudSettingKeys.isSecret(CloudSettingKeys.PASSWORD))
        assertFalse(CoverApis.SECRETS.contains(CoverApis.IGDB_CLIENT_ID_KEY))
    }

    @Test fun `after a restore the keys this install had are kept`() {
        val current = mapOf(
            CoverApis.TGDB_KEY to "tg", CoverApis.SGDB_KEY to "sg",
            CoverApis.IGDB_CLIENT_ID_KEY to "idA", CoverApis.IGDB_CLIENT_SECRET_KEY to "secA"
        )
        // The backup had no IGDB id: the whole login stays.
        val noId = CoverApis.keptSecrets(current, mapOf(CoverApis.IGDB_CLIENT_ID_KEY to "idA"), backupHasIgdbId = false)
        assertEquals(mapOf(CoverApis.TGDB_KEY to "tg", CoverApis.SGDB_KEY to "sg", CoverApis.IGDB_CLIENT_SECRET_KEY to "secA"), noId)
        // The same client id comes back: its secret stays.
        assertEquals("secA", CoverApis.keptSecrets(current, mapOf(CoverApis.IGDB_CLIENT_ID_KEY to " idA "), true)[CoverApis.IGDB_CLIENT_SECRET_KEY])
        // Another client id is another application: its secret is not this one.
        val other = CoverApis.keptSecrets(current, mapOf(CoverApis.IGDB_CLIENT_ID_KEY to "idB"), true)
        assertNull(other[CoverApis.IGDB_CLIENT_SECRET_KEY])
        assertEquals("tg", other[CoverApis.TGDB_KEY])
        // Nothing to keep, nothing invented.
        assertEquals(emptyMap<String, String>(), CoverApis.keptSecrets(emptyMap(), emptyMap(), false))
        // A secret without a client id is not kept.
        assertNull(CoverApis.keptSecrets(mapOf(CoverApis.IGDB_CLIENT_SECRET_KEY to "s"), emptyMap(), false)[CoverApis.IGDB_CLIENT_SECRET_KEY])
    }

    @Test fun `status codes`() {
        assertEquals(CoverApis.Status.OK, CoverApis.status(200))
        assertEquals(CoverApis.Status.NOT_FOUND, CoverApis.status(404))
        assertEquals(CoverApis.Status.RATE_LIMITED, CoverApis.status(429))
        assertEquals(CoverApis.Status.KEY_REJECTED, CoverApis.status(401))
        assertEquals(CoverApis.Status.KEY_REJECTED, CoverApis.status(403))
        assertEquals(CoverApis.Status.BAD_REQUEST, CoverApis.status(400))
        assertEquals(CoverApis.Status.BAD_REQUEST, CoverApis.status(422))
        // A timeout, a server error and anything odd never count as "no cover".
        assertEquals(CoverApis.Status.BUSY, CoverApis.status(408))
        assertEquals(CoverApis.Status.BUSY, CoverApis.status(500))
        assertEquals(CoverApis.Status.BUSY, CoverApis.status(503))
        assertEquals(CoverApis.Status.BUSY, CoverApis.status(302))
        assertEquals(0L, CoverApis.pauseFor(CoverApis.Status.NOT_FOUND))
        assertEquals(60_000L, CoverApis.pauseFor(CoverApis.Status.RATE_LIMITED))
        assertTrue(CoverApis.pauseFor(CoverApis.Status.KEY_REJECTED) > CoverApis.pauseFor(CoverApis.Status.BUSY))
    }

    @Test fun `a key is only sent to the https hosts of its own source`() {
        assertTrue(CoverApis.sendsKeyTo(Source.THEGAMESDB, "https://api.thegamesdb.net/v1/Games/ByGameName?apikey=k"))
        assertTrue(CoverApis.sendsKeyTo(Source.STEAMGRIDDB, "https://www.steamgriddb.com/api/v2/search/autocomplete/zelda"))
        assertTrue(CoverApis.sendsKeyTo(Source.IGDB, "https://api.igdb.com/v4/games"))
        assertTrue(CoverApis.sendsKeyTo(Source.IGDB, "https://id.twitch.tv/oauth2/token"))
        // Plain http, another source's host, look-alike hosts, user info and odd ports are refused.
        assertFalse(CoverApis.sendsKeyTo(Source.THEGAMESDB, "http://api.thegamesdb.net/v1/Games"))
        assertFalse(CoverApis.sendsKeyTo(Source.THEGAMESDB, "https://www.steamgriddb.com/api/v2/"))
        assertFalse(CoverApis.sendsKeyTo(Source.STEAMGRIDDB, "https://www.steamgriddb.com.evil.example/api/v2/"))
        assertFalse(CoverApis.sendsKeyTo(Source.STEAMGRIDDB, "https://evil.example/www.steamgriddb.com/"))
        assertFalse(CoverApis.sendsKeyTo(Source.IGDB, "https://api.igdb.com@evil.example/v4/games"))
        assertFalse(CoverApis.sendsKeyTo(Source.IGDB, "https://api.igdb.com:8443/v4/games"))
        assertFalse(CoverApis.sendsKeyTo(Source.IGDB, "https://images.igdb.com/igdb/image/upload/t_cover_big/a.jpg"))
        assertFalse(CoverApis.sendsKeyTo(Source.LIBRETRO, "https://api.igdb.com/v4/games"))
        assertFalse(CoverApis.sendsKeyTo(Source.IGDB, "not a url"))
    }

    @Test fun `image addresses must be https on the service's own domain`() {
        assertEquals("https://cdn.thegamesdb.net/images/large/a.jpg", CoverApis.httpsOn("https://cdn.thegamesdb.net/images/large/a.jpg", "thegamesdb.net"))
        assertEquals("https://thegamesdb.net/x.jpg", CoverApis.httpsOn("https://thegamesdb.net/x.jpg", "thegamesdb.net"))
        assertNull(CoverApis.httpsOn("http://cdn.thegamesdb.net/a.jpg", "thegamesdb.net"))
        assertNull(CoverApis.httpsOn("https://thegamesdb.net.evil.example/a.jpg", "thegamesdb.net"))
        assertNull(CoverApis.httpsOn("https://evilthegamesdb.net/a.jpg", "thegamesdb.net"))
        assertNull(CoverApis.httpsOn("https://user@cdn.thegamesdb.net/a.jpg", "thegamesdb.net"))
        assertNull(CoverApis.httpsOn("//cdn.thegamesdb.net/a.jpg", "thegamesdb.net"))
    }

    @Test fun `path segments use percent 20 for spaces`() {
        assertEquals("Super%20Mario%20Bros.", CoverApis.pathSegment("Super Mario Bros."))
        assertEquals("a%2Fb", CoverApis.pathSegment("a/b"))
    }

    @Test fun `a monthly allowance starts again on the first of the next month`() {
        val mid = java.time.LocalDate.of(2026, 10, 21).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(java.time.LocalDate.of(2026, 11, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(), CoverApis.nextMonthStart(mid))
        val dec = java.time.LocalDate.of(2026, 12, 31).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(java.time.LocalDate.of(2027, 1, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(), CoverApis.nextMonthStart(dec))
    }

    @Test fun `titles match only when they are the same game`() {
        assertEquals(2, CoverTitles.closeness("Super Mario World", "Super Mario World"))
        assertEquals(2, CoverTitles.closeness("Legend of Zelda, The - A Link to the Past", "The Legend of Zelda: A Link to the Past"))
        assertEquals(2, CoverTitles.closeness("Pokemon Red Version", "Pokémon Red"))
        // A sequel, another number or numeral, or another game of the series is a different game.
        assertEquals(0, CoverTitles.closeness("Super Mario World", "Super Mario World 2: Yoshi's Island"))
        assertEquals(0, CoverTitles.closeness("Sonic the Hedgehog", "Sonic the Hedgehog 2"))
        assertEquals(0, CoverTitles.closeness("Final Fantasy II", "Final Fantasy III"))
        assertEquals(0, CoverTitles.closeness("Final Fantasy", "Final Fantasy VII"))
        assertEquals(0, CoverTitles.closeness("Mario Kart", "Mario Party"))
        // One plain extra word on a longer title (a subtitle) is accepted; on a short title it is not.
        assertEquals(1, CoverTitles.closeness("Zelda A Link to the Past", "The Legend of Zelda A Link to the Past"))
        assertEquals(2, CoverTitles.closeness("Super Mario Bros Special Edition", "Super Mario Bros Special"))
        assertEquals(1, CoverTitles.closeness("Mario Kart Double Dash", "Mario Kart Double Dash Special"))
        assertEquals(0, CoverTitles.closeness("Mario Bros", "Mario Bros Deluxe"))
        assertEquals(0, CoverTitles.closeness("", "Mario"))
    }

    @Test fun `an identical title beats a close one, then the rank, then the service's order`() {
        data class Hit(val name: String, val rank: Int)
        val hits = listOf(Hit("Advance Wars Dual Strike Plus", 1), Hit("Advance Wars Dual Strike", 0), Hit("Advance Wars Dual Strike", 0))
        assertEquals(hits[1], CoverTitles.pick("Advance Wars Dual Strike", hits, { listOf(it.name) }, { it.rank }))
        val ranked = listOf(Hit("Tetris", 0), Hit("Tetris", 1))
        assertEquals(ranked[1], CoverTitles.pick("Tetris", ranked, { listOf(it.name) }, { it.rank }))
        assertNull(CoverTitles.pick("Tetris", listOf(Hit("Dr Mario", 1)), { listOf(it.name) }, { it.rank }))
        // Never "the first result" when nothing qualifies.
        assertNull(CoverTitles.pick("Metroid Prime", listOf(Hit("Metroid Fusion", 1), Hit("Metroid Prime 2 Echoes", 1)), { listOf(it.name) }, { it.rank }))
    }
}
