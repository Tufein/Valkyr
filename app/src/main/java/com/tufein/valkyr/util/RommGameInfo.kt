package com.tufein.valkyr.util

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Calendar
import java.util.TimeZone

/** RomM's play status of a game (`rom_user.status`); `now_playing` and `backlogged` are separate flags. */
enum class RommPlayStatus(val api: String) {
    INCOMPLETE("incomplete"),
    FINISHED("finished"),
    COMPLETED_100("completed_100"),
    RETIRED("retired"),
    NEVER_PLAYING("never_playing");

    companion object {
        /** Accepts the API values in any case and the few spellings older servers used. */
        fun fromApi(value: String?): RommPlayStatus? {
            val v = value?.trim()?.lowercase()?.replace('-', '_')?.replace(' ', '_')?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.api == v || it.name.lowercase() == v } ?: when (v) {
                "completed", "complete", "100", "completed100", "100_percent" -> COMPLETED_100
                "unfinished", "playing_incomplete" -> INCOMPLETE
                "never", "never_play", "wont_play" -> NEVER_PLAYING
                "dropped", "abandoned" -> RETIRED
                "beaten" -> FINISHED
                else -> null
            }
        }
    }
}

/** The account's own data about a game in RomM (`rom_user`). [rating] is 0–10, 0 = not rated. */
data class RommUserProps(
    val status: RommPlayStatus? = null,
    val backlogged: Boolean = false,
    val nowPlaying: Boolean = false,
    val rating: Int = 0,
    val hidden: Boolean = false,
    /** ISO time of the last play RomM knows, as the server sent it; null when never. */
    val lastPlayed: String? = null
)

/** What the details dialog shows of a game on the RomM server. */
data class RommGameInfo(
    val romId: Int,
    val name: String,
    val summary: String,
    val genres: List<String>,
    val releaseYear: Int?,
    /** Community rating normalised to 0–100; null when no source rated it. */
    val ratingPercent: Int?,
    val coverUrl: String?,
    /** Full URLs, server ones first. */
    val screenshots: List<String>,
    val platformName: String,
    val props: RommUserProps,
    /** False when the server sent no `rom_user` with play data (RomM before 3.7): the status pills are hidden. */
    val propsSupported: Boolean,
    /** The manual RomM itself stores for the game (`path_manual`), as a full URL on the server; null when it has none. */
    val manualUrl: String? = null
)

/**
 * Turns `GET /api/roms/{id}` into [RommGameInfo] and maps the play-status / rating controls to
 * the `PUT /api/roms/{id}/props` body. Pure, so the odd shapes of every RomM release are tested.
 */
object RommGameDetails {

    /** null when [json] is not a ROM at all (no id); every other field is optional. */
    fun parse(json: JsonElement?, baseUrl: String, fallbackId: Int? = null): RommGameInfo? {
        val o = RommJson.obj(json) ?: return null
        return with(RommJson) { parseRom(o, baseUrl, fallbackId) }
    }

    private fun RommJson.parseRom(o: JsonObject, baseUrl: String, fallbackId: Int?): RommGameInfo? {
        val id = o.int("id") ?: fallbackId ?: return null
        val metadatum = o.child("metadatum")
        val igdb = o.child("igdb_metadata")
        val moby = o.child("moby_metadata")
        val ss = o.child("ss_metadata")
        val launchbox = o.child("launchbox_metadata")

        val summary = listOfNotNull(o.text("summary"), igdb?.text("summary"), moby?.text("summary"), o.text("description"))
            .firstOrNull().orEmpty()
        val genres = (o.strings("genres")?.takeIf { it.isNotEmpty() }
            ?: metadatum?.strings("genres")?.takeIf { it.isNotEmpty() }
            ?: igdb?.strings("genres")?.takeIf { it.isNotEmpty() }
            ?: moby?.strings("genres")?.takeIf { it.isNotEmpty() }
            ?: ss?.strings("genres")?.takeIf { it.isNotEmpty() }
            ?: emptyList())
            .map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

        val release = sequenceOf(o.get("first_release_date"), metadatum?.get("first_release_date"), igdb?.get("first_release_date"), launchbox?.get("first_release_date"))
            .firstNotNullOfOrNull { releaseYear(it) }

        val rating = ratingPercent(o.get("average_rating"))
            ?: ratingPercent(metadatum?.get("average_rating"))
            ?: ratingPercent(igdb?.get("total_rating"))
            ?: ratingPercent(igdb?.get("aggregated_rating"))
            ?: ratingPercent(moby?.get("moby_score"), outOfTen = true)
            ?: ratingPercent(ss?.get("ss_score"), outOfTwenty = true)
            ?: ratingPercent(launchbox?.get("community_rating"), outOfFive = true)

        val cover = listOf("path_cover_large", "path_cover_l", "path_cover_small", "path_cover_s")
            .firstNotNullOfOrNull { o.text(it) }?.let { serverUrl(baseUrl, it) }
            ?: o.text("url_cover")?.let { serverUrl(baseUrl, it) }

        val shots = LinkedHashSet<String>()
        o.strings("merged_screenshots")?.forEach { p -> serverUrl(baseUrl, p)?.let(shots::add) }
        if (shots.isEmpty()) o.strings("path_screenshots")?.forEach { p -> serverUrl(baseUrl, p)?.let(shots::add) }
        // User screenshots (objects with a download_path) of servers without merged_screenshots.
        if (shots.isEmpty()) items(o.get("screenshots")).forEach { s -> serverUrl(baseUrl, s.text("download_path") ?: s.text("path"))?.let(shots::add) }
        if (shots.isEmpty()) o.strings("url_screenshots")?.forEach { p -> serverUrl(baseUrl, p)?.let(shots::add) }
        if (shots.isEmpty()) igdb?.strings("screenshots")?.forEach { p -> serverUrl(baseUrl, p)?.let(shots::add) }

        val user = o.child("rom_user")
        return RommGameInfo(
            romId = id,
            name = o.text("name") ?: o.text("fs_name_no_tags") ?: o.text("fs_name").orEmpty(),
            summary = summary,
            genres = genres,
            releaseYear = release,
            ratingPercent = rating,
            coverUrl = cover,
            screenshots = shots.toList(),
            platformName = o.text("platform_display_name") ?: o.text("platform_custom_name") ?: o.text("platform_name") ?: o.text("platform_slug").orEmpty(),
            props = parseProps(user) ?: RommUserProps(),
            propsSupported = user != null && PROP_FIELDS.any { user.has(it) },
            // Only the server's own copy: the provider link (`url_manual`) points outside the user's RomM and is never fetched.
            manualUrl = o.text("path_manual")?.let { serverUrl(baseUrl, it) }
        )
    }

    /** The `rom_user` object (or a `PUT …/props` answer); null when [json] is not an object. */
    fun parseProps(json: JsonElement?): RommUserProps? {
        val o = RommJson.obj(json) ?: return null
        return with(RommJson) { readProps(o) }
    }

    private fun RommJson.readProps(o: JsonObject): RommUserProps {
        // Some answers wrap it: {"rom_user": {...}} or {"data": {...}}.
        val u = o.child("rom_user") ?: o.child("data")?.takeIf { d -> PROP_FIELDS.any { d.has(it) } } ?: o
        return RommUserProps(
            status = RommPlayStatus.fromApi(u.text("status")),
            backlogged = u.bool("backlogged") ?: false,
            nowPlaying = u.bool("now_playing") ?: false,
            rating = (u.double("rating") ?: 0.0).let { r -> (if (r > 10.0) r / 10.0 else r).toInt() }.coerceIn(0, 10),
            hidden = u.bool("hidden") ?: false,
            lastPlayed = u.text("last_played")
        )
    }

    /**
     * Epoch millis of a RomM time (`last_played`, `updated_at`): ISO with an offset
     * (`2024-05-01T10:00:00+00:00`, `…Z`) or without one (read as UTC); null when unreadable.
     */
    fun epochMillis(iso: String?): Long? {
        val t = iso?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { OffsetDateTime.parse(t).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(t).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(t.replace(' ', 'T')).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(t.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }

    /**
     * The year of a release date: epoch seconds (IGDB), epoch milliseconds (RomM 3.x/4.x), or an
     * ISO date string (`2001-03-21`, `2001`). Implausible values are dropped.
     */
    fun releaseYear(value: JsonElement?): Int? {
        if (value == null || value.isJsonNull) return null
        val p = RommJson.primitive(value) ?: return null
        val number = RommJson.number(p)
        if (number != null && !(p.isString && p.asString.contains('-'))) {
            if (number in 1950.0..2100.0) return number.toInt()
            if (number <= 0) return null
            val millis = if (number < 100_000_000_000.0) number * 1000 else number
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis.toLong() }
            return cal.get(Calendar.YEAR).takeIf { it in 1950..2100 }
        }
        val text = p.asString.trim()
        return Regex("""^(\d{4})""").find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1950..2100 }
    }

    /**
     * A rating on 0–100. RomM's averages and IGDB use 0–100; [outOfTen] (MobyGames),
     * [outOfTwenty] (ScreenScraper) and [outOfFive] (LaunchBox) are scaled. Unmarked values
     * that are 10 or below are read as 0–10. 0 counts as "not rated".
     */
    fun ratingPercent(value: JsonElement?, outOfTen: Boolean = false, outOfTwenty: Boolean = false, outOfFive: Boolean = false): Int? {
        val v = RommJson.number(value) ?: return null
        if (v <= 0.0) return null
        val scaled = when {
            outOfFive -> v * 20
            outOfTen -> v * 10
            outOfTwenty -> v * 5
            v <= 10.0 -> v * 10
            else -> v
        }
        return scaled.toInt().coerceIn(1, 100)
    }

    private val PROP_FIELDS = listOf("status", "backlogged", "now_playing", "rating", "hidden", "last_played", "completion", "difficulty")
}

/** The controls of the RomM section and what each of them writes. */
object RommProps {

    /** One focusable pill: the two flags and the five statuses. */
    enum class Pill { NOW_PLAYING, BACKLOG, INCOMPLETE, FINISHED, COMPLETED_100, RETIRED, NEVER_PLAYING }

    fun statusOf(pill: Pill): RommPlayStatus? = when (pill) {
        Pill.INCOMPLETE -> RommPlayStatus.INCOMPLETE
        Pill.FINISHED -> RommPlayStatus.FINISHED
        Pill.COMPLETED_100 -> RommPlayStatus.COMPLETED_100
        Pill.RETIRED -> RommPlayStatus.RETIRED
        Pill.NEVER_PLAYING -> RommPlayStatus.NEVER_PLAYING
        Pill.NOW_PLAYING, Pill.BACKLOG -> null
    }

    fun isOn(props: RommUserProps, pill: Pill): Boolean = when (pill) {
        Pill.NOW_PLAYING -> props.nowPlaying
        Pill.BACKLOG -> props.backlogged
        else -> props.status == statusOf(pill)
    }

    /** Flags flip; a status is chosen, or cleared when it was already the chosen one. */
    fun toggle(props: RommUserProps, pill: Pill): RommUserProps = when (pill) {
        Pill.NOW_PLAYING -> props.copy(nowPlaying = !props.nowPlaying)
        Pill.BACKLOG -> props.copy(backlogged = !props.backlogged)
        else -> {
            val status = statusOf(pill)
            props.copy(status = if (props.status == status) null else status)
        }
    }

    /** The rating moved by [delta], kept within 0–10 (0 = not rated). */
    fun stepRating(props: RommUserProps, delta: Int): RommUserProps = props.copy(rating = (props.rating + delta).coerceIn(0, 10))

    /** The fields that differ between [old] and [new], by their API names (a cleared status is null). */
    fun changes(old: RommUserProps, new: RommUserProps): Map<String, Any?> = buildMap {
        if (old.status != new.status) put("status", new.status?.api)
        if (old.nowPlaying != new.nowPlaying) put("now_playing", new.nowPlaying)
        if (old.backlogged != new.backlogged) put("backlogged", new.backlogged)
        if (old.rating != new.rating) put("rating", new.rating)
        if (old.hidden != new.hidden) put("hidden", new.hidden)
    }

    /** `{"data": {...changes...}}`, the body RomM's `PUT /api/roms/{id}/props` reads. */
    fun body(changes: Map<String, Any?>): String {
        val data = JsonObject()
        changes.forEach { (k, v) ->
            when (v) {
                null -> data.add(k, JsonNull.INSTANCE)
                is Boolean -> data.addProperty(k, v)
                is Number -> data.addProperty(k, v)
                else -> data.addProperty(k, v.toString())
            }
        }
        return JsonObject().apply { add("data", data) }.toString()
    }

    /** [changes] applied to [props]: what the server holds once a write without a usable answer succeeded. */
    fun apply(props: RommUserProps, changes: Map<String, Any?>): RommUserProps {
        var p = props
        if ("status" in changes) p = p.copy(status = RommPlayStatus.fromApi(changes["status"] as? String))
        (changes["now_playing"] as? Boolean)?.let { p = p.copy(nowPlaying = it) }
        (changes["backlogged"] as? Boolean)?.let { p = p.copy(backlogged = it) }
        (changes["rating"] as? Number)?.let { p = p.copy(rating = it.toInt().coerceIn(0, 10)) }
        (changes["hidden"] as? Boolean)?.let { p = p.copy(hidden = it) }
        return p
    }
}
