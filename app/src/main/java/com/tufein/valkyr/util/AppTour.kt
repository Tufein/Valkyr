package com.tufein.valkyr.util

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.tufein.valkyr.R

/** One card of the tour: an existing icon, a heading and a few lines. */
data class TourCard(@DrawableRes val icon: Int, @StringRes val title: Int, @StringRes val body: Int)

/**
 * 6.0: the short first-run tour. Shown once after install or after updating to the version that
 * introduced it, and re-openable from About. Plain data so a JVM test can check it.
 */
object AppTour {
    /** Bump when a later version should show the tour again. */
    const val VERSION = 60

    val cards: List<TourCard> = listOf(
        TourCard(R.drawable.ic_library, R.string.tour60_library_title, R.string.tour60_library_body),
        TourCard(R.drawable.ic_play, R.string.tour60_game_title, R.string.tour60_game_body),
        TourCard(R.drawable.ic_cloud, R.string.tour60_romm_title, R.string.tour60_romm_body),
        TourCard(R.drawable.ic_download, R.string.tour60_downloads_title, R.string.tour60_downloads_body),
        TourCard(R.drawable.ic_settings, R.string.tour60_settings_title, R.string.tour60_settings_body)
    )

    /** [seen] is the stored tour version (0 = never). Not before the first-run setup is done. */
    fun shouldShow(seen: Int, onboarded: Boolean): Boolean = onboarded && seen < VERSION

    /** The card after [index], or null on the last one (the button then finishes). */
    fun next(index: Int): Int? = (index + 1).takeIf { it < cards.size }

    /** The card before [index], or null on the first one. */
    fun previous(index: Int): Int? = (index - 1).takeIf { it >= 0 }
}
