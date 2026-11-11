package com.tufein.valkyr.util

data class SmartCollectionRule(
    @field:com.google.gson.annotations.SerializedName("consoles") val consoles: Set<String> = emptySet(), @field:com.google.gson.annotations.SerializedName("languages") val languages: Set<String> = emptySet(),
    @field:com.google.gson.annotations.SerializedName("genre") val genre: String = "", @field:com.google.gson.annotations.SerializedName("fromYear") val fromYear: Int? = null, @field:com.google.gson.annotations.SerializedName("toYear") val toYear: Int? = null,
    @field:com.google.gson.annotations.SerializedName("played") val played: String = "ANY"
) {
    val valid: Boolean get() = (fromYear == null || fromYear in 1900..2100) &&
        (toYear == null || toYear in 1900..2100) && (fromYear == null || toYear == null || fromYear <= toYear) &&
        played in PLAYED_VALUES
    fun matches(console: String, tags: Collection<String>, genres: String?, year: Int?, wasPlayed: Boolean, lastPlayedAt: Long? = null, now: Long = 0L): Boolean =
        valid && (consoles.isEmpty() || console in consoles) &&
        (languages.isEmpty() || tags.any { tag -> languages.any { it.equals(tag, true) } }) &&
        (genre.isBlank() || genres?.split('|', ',', ';')?.any { it.trim().equals(genre.trim(), true) } == true) &&
        (fromYear == null || year != null && year >= fromYear) &&
        (toYear == null || year != null && year <= toYear) &&
        playedOk(wasPlayed, lastPlayedAt, now)

    /** 6.5: "STALE_365" is "not played for a year": never played counts, and so does a game last played longer ago. */
    private fun playedOk(wasPlayed: Boolean, lastPlayedAt: Long?, now: Long): Boolean {
        staleDays(played)?.let { days -> return lastPlayedAt == null || now - lastPlayedAt > days * DAY_MS }
        return played == "ANY" || wasPlayed == (played == "PLAYED")
    }

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        val STALE_VALUES = listOf("STALE_90", "STALE_180", "STALE_365")
        val PLAYED_VALUES = setOf("ANY", "PLAYED", "NEVER") + STALE_VALUES
        fun staleDays(value: String): Int? = if (value in STALE_VALUES) value.removePrefix("STALE_").toInt() else null
    }
}
