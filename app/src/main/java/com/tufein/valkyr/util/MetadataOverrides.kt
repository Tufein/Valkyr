package com.tufein.valkyr.util

import com.tufein.valkyr.data.local.entity.GameOverrideEntity
import com.tufein.valkyr.data.model.GameDetails

/**
 * 9.0: "Fix the match". A user's correction wins over the providers, but only for the fields that
 * were actually changed — everything else stays automatic. The key is `consoleId|stem`, the same
 * one covers and RomM marks use, so a rescan does not throw the correction away. Pure JVM.
 */
object MetadataOverrides {

    const val MAX_FIELD = 120

    fun key(consoleId: String, fileName: String): String = RommMarks.key(consoleId, fileName)

    /** Blank becomes null (automatic); long input is cut, not rejected. */
    fun clean(value: String?): String? = value?.trim()?.take(MAX_FIELD)?.ifBlank { null }

    /** Only a real four-digit year is accepted as an override. */
    fun year(value: String?): Int? = clean(value)?.toIntOrNull()?.takeIf { it in 1950..2100 }

    /** The fields the user changed, ready to store; unchanged fields are null. */
    fun fields(title: String?, year: String?, genre: String?, coverUrl: String?): GameOverrideEntity {
        val url = clean(coverUrl)
        return GameOverrideEntity(
            key = "",
            title = clean(title),
            year = year(year),
            genre = clean(genre),
            pinnedCoverUrl = if (url.isNullOrBlank() || !(url.startsWith("https://") || url.startsWith("content://") || url.startsWith("file://"))) null else url,
            updatedAt = 0L
        )
    }

    /** Applies the override on top of whatever the providers said. */
    fun apply(details: GameDetails, override: GameOverrideEntity?): GameDetails {
        if (override == null) return details
        val genres = override.genre?.let { listOf(it) } ?: details.genres
        val released = override.year?.let { year ->
            val old = details.released
            if (old.length >= 4 && old.take(4).all { it.isDigit() }) year.toString() + old.drop(4) else year.toString()
        } ?: details.released
        return details.copy(
            title = override.title ?: details.title,
            genres = genres,
            released = released,
            source = if (override.title != null || override.genre != null || override.year != null) "your correction · ${details.source}".trim(' ', '·') else details.source
        )
    }
}
