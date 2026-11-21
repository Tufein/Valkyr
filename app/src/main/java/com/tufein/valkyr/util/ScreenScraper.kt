package com.tufein.valkyr.util

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId

/**
 * ScreenScraper (screenscraper.fr) as a cover source (4.3.0). Pure JVM for the tests: addresses,
 * the console table, status codes, the JSON of `jeuInfos` / `jeuRecherche` / `ssuserInfos`, and
 * which box art to take. The requests themselves are in `ScreenScraperService`.
 *
 * Written from ScreenScraper's public API documentation (api2), not tested against the real
 * service: every request needs a developer id, its password and the software name registered with
 * it (a developer id is asked for on the screenscraper.fr forum), plus, optionally, the user's own
 * screenscraper.fr login, which raises the daily quota and the number of threads.
 *
 * The media URLs ScreenScraper returns carry all those credentials in their query. They are stored
 * and shown without them ([stripCredentials]) and get them back only on the way out
 * ([withCredentials], see `ScreenScraperImageAuth`), so no password ends up in the covers table, a
 * cache key or a log.
 */
object ScreenScraper {

    const val API = "https://api.screenscraper.fr/api2/"

    const val DEV_ID_KEY = "covers43_ss_devid"
    const val DEV_PASSWORD_KEY = "covers43_ss_devpassword"
    const val SOFT_NAME_KEY = "covers43_ss_softname"
    const val USER_KEY = "covers43_ss_user"
    const val PASSWORD_KEY = "covers43_ss_password"

    /** Software name sent when the user leaves it empty. */
    const val DEFAULT_SOFT_NAME = "Valkyr"

    val SETTING_TYPES: Map<String, String> = listOf(DEV_ID_KEY, DEV_PASSWORD_KEY, SOFT_NAME_KEY, USER_KEY, PASSWORD_KEY).associateWith { "s" }

    /** The two passwords never travel in a backup file (like the WebDAV password, see [CloudSettingKeys]). */
    val SECRETS: Set<String> = setOf(DEV_PASSWORD_KEY, PASSWORD_KEY)

    /**
     * After a restore (which never carries the passwords): the passwords of [current] that still
     * belong, by key. The developer password stays when the restored developer id is the same, the
     * user password when the restored user name is the same; a backup without any ScreenScraper
     * setting (made before 4.3.0, or before it was set up) keeps the current values as they are.
     */
    fun keptSecrets(current: Map<String, String>, restored: Map<String, String>, backupHasScreenScraper: Boolean): Map<String, String> {
        fun same(key: String) = !backupHasScreenScraper || current[key].orEmpty().trim() == restored[key].orEmpty().trim()
        return buildMap {
            current[DEV_PASSWORD_KEY]?.takeIf { it.isNotEmpty() && current[DEV_ID_KEY].orEmpty().isNotBlank() && same(DEV_ID_KEY) }?.let { put(DEV_PASSWORD_KEY, it) }
            current[PASSWORD_KEY]?.takeIf { it.isNotEmpty() && current[USER_KEY].orEmpty().isNotBlank() && same(USER_KEY) }?.let { put(PASSWORD_KEY, it) }
        }
    }

    /** The query parameters that hold credentials. */
    private val CREDENTIAL_PARAMS = setOf("devid", "devpassword", "softname", "ssid", "sspassword")

    /** Threads ScreenScraper allows without a login, and the most this app ever uses. */
    const val DEFAULT_THREADS = 1
    const val MAX_THREADS = 4

    class Credentials(
        val devId: String = "",
        val devPassword: String = "",
        softName: String = "",
        val user: String = "",
        val password: String = ""
    ) {
        val softName: String = softName.trim().ifEmpty { DEFAULT_SOFT_NAME }
        /** Developer id and password are there: the source can be used. */
        val ready: Boolean get() = devId.isNotBlank() && devPassword.isNotBlank()
        val hasLogin: Boolean get() = user.isNotBlank() && password.isNotBlank()
        /** Changes whenever any value changes (a rejected login is forgotten then). */
        val fingerprint: Int get() = listOf(devId, devPassword, softName, user, password).hashCode()

        override fun equals(other: Any?): Boolean = other is Credentials && other.fingerprint == fingerprint &&
            other.devId == devId && other.devPassword == devPassword && other.softName == softName && other.user == user && other.password == password
        override fun hashCode(): Int = fingerprint
        /** Never the values: this may end up in a log. */
        override fun toString(): String = "Credentials(ready=$ready, login=$hasLogin)"
    }

    /** libretro-thumbnails repository → ScreenScraper `systemeid` (from ScreenScraper's systemesListe). */
    val SYSTEM_IDS: Map<String, Int> = mapOf(
        "Nintendo_-_Game_Boy_Advance" to 12,
        "Nintendo_-_Game_Boy_Color" to 10,
        "Nintendo_-_Game_Boy" to 9,
        "Nintendo_-_Nintendo_Entertainment_System" to 3,
        "Nintendo_-_Family_Computer_Disk_System" to 106,
        "Nintendo_-_Super_Nintendo_Entertainment_System" to 4,
        "Nintendo_-_Nintendo_64" to 14,
        "Nintendo_-_Nintendo_DS" to 15,
        "Nintendo_-_Nintendo_3DS" to 17,
        "Nintendo_-_GameCube" to 13,
        "Nintendo_-_Wii" to 16,
        "Nintendo_-_Virtual_Boy" to 11,
        "Nintendo_-_Pokemon_Mini" to 211,
        "Sega_-_Mega_Drive_-_Genesis" to 1,
        "Sega_-_Master_System_-_Mark_III" to 2,
        "Sega_-_Game_Gear" to 21,
        "Sega_-_32X" to 19,
        "Sega_-_Mega-CD_-_Sega_CD" to 20,
        "Sega_-_Saturn" to 22,
        "Sega_-_Dreamcast" to 23,
        "Sega_-_SG-1000" to 109,
        "Sony_-_PlayStation" to 57,
        "Sony_-_PlayStation_2" to 58,
        "Sony_-_PlayStation_Portable" to 61,
        "NEC_-_PC_Engine_-_TurboGrafx_16" to 31,
        "NEC_-_PC_Engine_CD_-_TurboGrafx-CD" to 114,
        "SNK_-_Neo_Geo_Pocket_Color" to 82,
        "SNK_-_Neo_Geo_Pocket" to 25,
        "Bandai_-_WonderSwan_Color" to 46,
        "Bandai_-_WonderSwan" to 45,
        "Atari_-_2600" to 26,
        "Atari_-_7800" to 41,
        "Atari_-_Lynx" to 28,
        "Atari_-_Jaguar" to 27,
        "Coleco_-_ColecoVision" to 48,
        "Mattel_-_Intellivision" to 115,
        "Microsoft_-_MSX" to 113,
        "The_3DO_Company_-_3DO" to 29
    )

    /** ScreenScraper's system id of a library console (the same console matching as libretro-thumbnails), or null. */
    fun systemId(consoleId: String): Int? = LibretroThumbnails.systemFor(consoleId)?.let { SYSTEM_IDS[it.repo] }

    /** The app's region names (version preference) → ScreenScraper's region codes. */
    private val REGION_CODES = mapOf(
        "world" to "wor", "europe" to "eu", "usa" to "us", "japan" to "jp", "australia" to "au", "asia" to "asi",
        "korea" to "kr", "brazil" to "br", "uk" to "uk", "germany" to "de", "france" to "fr", "spain" to "sp",
        "italy" to "it", "netherlands" to "nl", "russia" to "ru", "china" to "cn", "poland" to "pl", "portugal" to "pt"
    )

    /** Region order for the box art: the user's preferred regions first, then world, USA, Europe, Japan and ScreenScraper's own pick. */
    fun regionCodes(preferred: List<String>): List<String> =
        (preferred.mapNotNull { REGION_CODES[it.trim().lowercase()] } + listOf("wor", "us", "eu", "jp", "ss")).distinct()

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun base(endpoint: String, c: Credentials): String = buildString {
        append(API).append(endpoint)
        append("?devid=").append(enc(c.devId))
        append("&devpassword=").append(enc(c.devPassword))
        append("&softname=").append(enc(c.softName))
        append("&output=json")
        if (c.hasLogin) append("&ssid=").append(enc(c.user)).append("&sspassword=").append(enc(c.password))
    }

    /** The game behind a ROM file name on [systemId] (no checksum: the app does not hash ROMs for covers). */
    fun gameUrl(c: Credentials, systemId: Int, romName: String): String =
        base("jeuInfos.php", c) + "&systemeid=$systemId&romtype=rom&romnom=" + enc(romName)

    /** A name search on [systemId], best match first. */
    fun searchUrl(c: Credentials, systemId: Int, text: String): String =
        base("jeuRecherche.php", c) + "&systemeid=$systemId&recherche=" + enc(text)

    /** The user's account: threads and today's quota. Needs the user login. */
    fun userUrl(c: Credentials): String = base("ssuserInfos.php", c)

    /** Infrastructure info: answers with the developer credentials alone (tells a wrong login from wrong developer credentials). */
    fun infraUrl(c: Credentials): String = base("ssinfraInfos.php", c)

    enum class Status {
        OK,
        /** The game (or ROM) is not in ScreenScraper's database: a real miss. */
        NOT_FOUND,
        /** Too many requests or threads right now: wait a minute. */
        RATE_LIMITED,
        /** The quota of the day is used up: pause until tomorrow. */
        QUOTA,
        /** Login or developer credentials refused. */
        LOGIN_REJECTED,
        /** API closed or the server too busy, or this software refused: try later, not a miss. */
        CLOSED,
        BAD_REQUEST,
        SERVER_ERROR
    }

    /**
     * ScreenScraper's HTTP codes, as its documentation lists them: 400 bad request, 401 closed to
     * non-members / login refused, 403 developer login error, 404 not found, 423 API closed,
     * 426 software refused / out of date, 429 too many threads or requests per minute, 430 daily
     * quota, 431 too many unrecognised ROMs today.
     */
    fun status(code: Int): Status = when (code) {
        in 200..299 -> Status.OK
        404 -> Status.NOT_FOUND
        429 -> Status.RATE_LIMITED
        430, 431 -> Status.QUOTA
        401, 403 -> Status.LOGIN_REJECTED
        423, 426 -> Status.CLOSED
        in 400..499 -> Status.BAD_REQUEST
        else -> Status.SERVER_ERROR
    }

    data class Media(val type: String, val region: String, val url: String, val format: String)
    data class Game(val id: String, val names: List<String>, val medias: List<Media>)
    data class User(
        val name: String,
        val maxThreads: Int,
        val requestsToday: Int?,
        val maxRequestsPerDay: Int?,
        /** `niveau`: the account level as text (5.5.0), "" when not sent. */
        val level: String = "",
        /** `requestskotoday` / `maxrequestskoperday` (5.5.0): today's requests for ROMs ScreenScraper did not recognise, and their limit. */
        val requestsKoToday: Int? = null,
        val maxRequestsKoPerDay: Int? = null
    ) {
        /** Requests left today, when ScreenScraper said both numbers. */
        val remainingToday: Int? get() = if (requestsToday != null && maxRequestsPerDay != null) (maxRequestsPerDay - requestsToday).coerceAtLeast(0) else null
        val quotaUsedUp: Boolean get() = remainingToday == 0
    }

    private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.arr(name: String): JsonArray? = get(name)?.takeIf { it.isJsonArray }?.asJsonArray
    /** ScreenScraper sends numbers as text ("1"); both are read. */
    private fun JsonObject.text(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
    private fun JsonObject.int(name: String): Int? = text(name).toIntOrNull()

    private fun response(json: JsonElement?): JsonObject? = (json as? JsonObject)?.obj("response")

    private fun gameOf(o: JsonObject): Game? {
        val id = o.text("id").takeIf { it.isNotEmpty() } ?: return null
        val names = o.arr("noms")?.mapNotNull { (it as? JsonObject)?.text("text")?.takeIf { t -> t.isNotEmpty() } }.orEmpty() +
            listOfNotNull(o.text("nom").takeIf { it.isNotEmpty() })
        val medias = o.arr("medias")?.mapNotNull { m ->
            (m as? JsonObject)?.let { Media(it.text("type"), it.text("region"), httpsOnly(it.text("url")), it.text("format")) }?.takeIf { it.url.startsWith("https://") }
        }.orEmpty()
        return Game(id, names.distinct(), medias)
    }

    /** A media address as https (ScreenScraper serves both; the credentials only ever travel encrypted); "" for anything else. */
    fun httpsOnly(url: String): String = when {
        url.startsWith("https://", ignoreCase = true) -> url
        url.startsWith("http://", ignoreCase = true) -> "https://" + url.substring("http://".length)
        else -> ""
    }

    /** `response.jeu` of `jeuInfos.php`, or null. */
    fun parseGame(json: JsonElement?): Game? = response(json)?.obj("jeu")?.let(::gameOf)

    /** `response.jeux` of `jeuRecherche.php`, best first; an empty result is `[{}]`, which gives an empty list. */
    fun parseSearch(json: JsonElement?): List<Game> =
        response(json)?.arr("jeux")?.mapNotNull { (it as? JsonObject)?.let(::gameOf) }.orEmpty()

    /** `response.ssuser` (sent by `ssuserInfos.php` and, with a login, by every game request), or null. */
    fun parseUser(json: JsonElement?): User? {
        val u = response(json)?.obj("ssuser") ?: return null
        val name = u.text("id")
        return User(
            name = name,
            maxThreads = (u.int("maxthreads") ?: DEFAULT_THREADS).coerceIn(1, MAX_THREADS),
            requestsToday = u.int("requeststoday"),
            maxRequestsPerDay = u.int("maxrequestsperday"),
            level = u.text("niveau"),
            requestsKoToday = u.int("requestskotoday"),
            maxRequestsKoPerDay = u.int("maxrequestskoperday")
        )
    }

    /** The first game of a search whose name matches [title] (ScreenScraper's search is fuzzy). */
    fun bestMatch(games: List<Game>, title: String): Game? =
        games.firstOrNull { g -> g.names.any { GameTitleCleaner.matches(title, it) } }

    /**
     * The cover of [game]: 2D box art in the first region of [regions] that has one, else any 2D
     * box art; with [nonBoxArt] a title screen or screenshot when there is no box art at all.
     * Returned without credentials, or null.
     */
    fun pickCover(game: Game, regions: List<String>, nonBoxArt: Boolean): String? {
        val media = pickMedia(game, regions, "box-2D") ?: if (nonBoxArt) (pickMedia(game, regions, "sstitle") ?: pickMedia(game, regions, "ss")) else null
        return media?.url?.let(::stripCredentials)
    }

    /**
     * 5.6.0: the title screen (`sstitle`) or screenshot (`ss`) of [game] for the launcher covers, in the first of [regions]
     * that has one, else any. Returned without credentials, or null.
     */
    fun pickArt(game: Game, regions: List<String>, art: LibretroThumbnails.Art): String? =
        pickMedia(game, regions, if (art == LibretroThumbnails.Art.TITLE) "sstitle" else "ss")?.url?.let(::stripCredentials)

    /**
     * 7.7: a game's manual (ScreenScraper's `manual` media), in the first preferred region that has
     * one, else any. Returned without credentials; `ScreenScraperService.signed` puts them back only
     * on the way out, so no password is stored in the manuals folder or a cache.
     */
    fun pickManual(game: Game, regions: List<String>): String? =
        pickMedia(game, regions, "manual")?.url?.let(::stripCredentials)

    private fun pickMedia(game: Game, regions: List<String>, type: String): Media? {
        val of = game.medias.filter { it.type.equals(type, ignoreCase = true) }
        if (of.isEmpty()) return null
        return regions.firstNotNullOfOrNull { r -> of.firstOrNull { it.region.equals(r, ignoreCase = true) } } ?: of.first()
    }

    /** Whether [url] is a ScreenScraper API address (media included), which needs the credentials. */
    fun isApiUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase() ?: return false
        // Only https: credentials are never added to a plain-text address.
        uri.scheme.equals("https", ignoreCase = true) && (host == "screenscraper.fr" || host.endsWith(".screenscraper.fr")) && (uri.rawPath ?: "").startsWith("/api2/")
    }.getOrDefault(false)

    private fun splitQuery(url: String): Pair<String, List<String>> {
        val hash = url.indexOf('#').let { if (it < 0) url.length else it }
        val main = url.substring(0, hash)
        val q = main.indexOf('?')
        if (q < 0) return main to emptyList()
        return main.substring(0, q) to main.substring(q + 1).split('&').filter { it.isNotEmpty() }
    }

    /** [url] without the developer and user credentials in its query (what is stored and cached). */
    fun stripCredentials(url: String): String {
        val (path, params) = splitQuery(url)
        val kept = params.filter { it.substringBefore('=').lowercase() !in CREDENTIAL_PARAMS }
        return if (kept.isEmpty()) path else path + "?" + kept.joinToString("&")
    }

    /** [url] with [c]'s credentials (any it already had replaced); other addresses unchanged. */
    fun withCredentials(url: String, c: Credentials): String {
        if (!isApiUrl(url) || !c.ready) return url
        val (path, params) = splitQuery(stripCredentials(url))
        val creds = buildList {
            add("devid=" + enc(c.devId)); add("devpassword=" + enc(c.devPassword)); add("softname=" + enc(c.softName))
            if (c.hasLogin) { add("ssid=" + enc(c.user)); add("sspassword=" + enc(c.password)) }
        }
        return path + "?" + (creds + params).joinToString("&")
    }

    /** When a quota reached [now] ends: the next midnight in France, where ScreenScraper counts its days. */
    fun nextReset(now: Long, zone: ZoneId = ZoneId.of("Europe/Paris")): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** The day a moment falls on in [zone] (the daily tallies). */
    fun dayOf(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()
}
