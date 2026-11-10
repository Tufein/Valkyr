package com.tufein.valkyr.util

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.net.URLEncoder

/**
 * IGDB (api.igdb.com v4) as a cover source (4.4.0): the Twitch token request, the game search
 * body, the console → IGDB platform table and the JSON of the answers. Pure JVM for the tests; the
 * requests and the token cache are in `IgdbService`.
 *
 * Written from IGDB's and Twitch's public API documentation, not tested against the real services.
 * The client secret is only ever sent in the body of the token request to id.twitch.tv; games are
 * asked with the client id and the bearer token to api.igdb.com.
 */
object Igdb {

    const val TOKEN_URL = "https://id.twitch.tv/oauth2/token"
    const val GAMES_URL = "https://api.igdb.com/v4/games"
    const val IMAGE_HOST = "igdb.com"

    /** Form body of the client-credentials token request (the secret stays out of the address). */
    fun tokenBody(clientId: String, clientSecret: String): ByteArray =
        listOf("client_id" to clientId, "client_secret" to clientSecret, "grant_type" to "client_credentials")
            .joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }
            .toByteArray(Charsets.UTF_8)

    /** Twitch's answer: the token and how long it lives. */
    data class Token(val accessToken: String, val expiresAt: Long) {
        /** Valid until a minute before it expires. */
        fun validAt(now: Long): Boolean = accessToken.isNotEmpty() && now < expiresAt
        override fun toString(): String = "Token(expiresAt=$expiresAt)"
    }

    /** Refresh a minute before the token ends, so a request never leaves with a token that dies on the way. */
    const val EXPIRY_MARGIN_MS = 60_000L

    fun parseToken(json: JsonElement?, now: Long): Token? {
        val o = json as? JsonObject ?: return null
        val token = o.get("access_token")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        val seconds = o.get("expires_in")?.takeIf { it.isJsonPrimitive }?.asString?.toLongOrNull() ?: return null
        if (token.isEmpty() || seconds <= 0) return null
        return Token(token, now + seconds * 1000 - EXPIRY_MARGIN_MS)
    }

    /**
     * libretro-thumbnails repository (the app's console matching) → IGDB platform id, as in IGDB's
     * public platforms list. From the documentation, not checked against a live response; a wrong
     * id only costs the platform preference (see [pick]).
     */
    val PLATFORM_IDS: Map<String, Int> = mapOf(
        "Nintendo_-_Nintendo_Entertainment_System" to 18,
        "Nintendo_-_Family_Computer_Disk_System" to 51,
        "Nintendo_-_Super_Nintendo_Entertainment_System" to 19,
        "Nintendo_-_Game_Boy" to 33,
        "Nintendo_-_Game_Boy_Color" to 22,
        "Nintendo_-_Game_Boy_Advance" to 24,
        "Nintendo_-_Nintendo_64" to 4,
        "Nintendo_-_Nintendo_DS" to 20,
        "Nintendo_-_Nintendo_3DS" to 37,
        "Nintendo_-_GameCube" to 21,
        "Nintendo_-_Wii" to 5,
        "Nintendo_-_Virtual_Boy" to 87,
        "Nintendo_-_Pokemon_Mini" to 166,
        "Sony_-_PlayStation" to 7,
        "Sony_-_PlayStation_2" to 8,
        "Sony_-_PlayStation_Portable" to 38,
        "Sega_-_Master_System_-_Mark_III" to 64,
        "Sega_-_Mega_Drive_-_Genesis" to 29,
        "Sega_-_Mega-CD_-_Sega_CD" to 78,
        "Sega_-_32X" to 30,
        "Sega_-_Saturn" to 32,
        "Sega_-_Dreamcast" to 23,
        "Sega_-_Game_Gear" to 35,
        "Sega_-_SG-1000" to 84,
        "NEC_-_PC_Engine_-_TurboGrafx_16" to 86,
        "NEC_-_PC_Engine_CD_-_TurboGrafx-CD" to 150,
        "SNK_-_Neo_Geo_Pocket" to 119,
        "SNK_-_Neo_Geo_Pocket_Color" to 120,
        "Bandai_-_WonderSwan" to 57,
        "Bandai_-_WonderSwan_Color" to 123,
        "Atari_-_2600" to 59,
        "Atari_-_7800" to 60,
        "Atari_-_Lynx" to 61,
        "Atari_-_Jaguar" to 62,
        "Coleco_-_ColecoVision" to 68,
        "Mattel_-_Intellivision" to 67,
        "Microsoft_-_MSX" to 27,
        "The_3DO_Company_-_3DO" to 50
    )

    fun platformId(consoleId: String): Int? = LibretroThumbnails.systemFor(consoleId)?.let { PLATFORM_IDS[it.repo] }

    /** An IGDB (Apicalypse) string literal: quotes and backslashes escaped, line breaks gone. */
    fun quoted(text: String): String =
        "\"" + text.map { if (it.isISOControl()) ' ' else it }.joinToString("").replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * The search: name, cover image id and platforms of the ten best hits. (Ten and not five: the
     * platform is weighed here, in [pick], so the right game on the right console may not be the first hit.)
     */
    fun searchBody(title: String): String = "search ${quoted(title)}; fields name,cover.image_id,platforms; limit 10;"

    /** Cheapest call that proves the token and the client id work. */
    const val PING_BODY = "fields name; limit 1;"

    /**
     * Why Twitch refused the token request: it answers 400 "invalid client" for an unknown client
     * id and 403 "invalid client secret" for a wrong secret (as its documentation shows); any other
     * 400 / 401 / 403 is a refusal without a reason. Null for anything else (not a refusal).
     */
    fun tokenRefusal(code: Int, json: JsonElement?): CoverApis.KeyTest? {
        val message = ((json as? JsonObject)?.get("message")?.takeIf { it.isJsonPrimitive }?.asString).orEmpty().lowercase()
        return when {
            code !in setOf(400, 401, 403) -> null
            "invalid client secret" in message -> CoverApis.KeyTest.SecretRejected
            "invalid client" in message -> CoverApis.KeyTest.IdRejected
            else -> CoverApis.KeyTest.Rejected
        }
    }

    data class Game(val id: Long, val name: String, val imageId: String?, val platforms: List<Int>)

    /** An IGDB image id is letters, digits, `_` and `-` (it ends up in an address). */
    private val IMAGE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

    /** The games of a `/games` answer; null when the answer is not a JSON array (an error object): that is no "no cover". */
    fun parseGames(json: JsonElement?): List<Game>? = (json as? JsonArray)?.mapNotNull { e ->
        val g = e as? JsonObject ?: return@mapNotNull null
        val id = g.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.toLongOrNull() ?: return@mapNotNull null
        val name = g.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        if (name.isEmpty()) return@mapNotNull null
        val image = (g.get("cover") as? JsonObject)?.get("image_id")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { IMAGE_ID.matches(it) }
        val platforms = (g.get("platforms") as? JsonArray)?.mapNotNull { p -> p.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull() }.orEmpty()
        Game(id, name, image, platforms)
    }

    fun coverUrl(imageId: String): String = "https://images.igdb.com/igdb/image/upload/t_cover_big/$imageId.jpg"

    /**
     * The game that is [title] and has a cover. A hit on [platformId] is preferred; a hit on
     * another console only when its title is exactly the same (the same game, another version); without a
     * known platform every hit with a cover is weighed by its title alone.
     */
    fun pick(title: String, games: List<Game>, platformId: Int?): Game? {
        val withCover = games.filter { it.imageId != null }
        val onConsole = { g: Game -> platformId != null && platformId in g.platforms }
        val eligible = if (platformId == null) withCover else withCover.filter { onConsole(it) || CoverTitles.closeness(title, it.name) == 2 }
        return CoverTitles.pick(title, eligible, names = { listOf(it.name) }, rank = { if (onConsole(it)) 1 else 0 })
    }
}
