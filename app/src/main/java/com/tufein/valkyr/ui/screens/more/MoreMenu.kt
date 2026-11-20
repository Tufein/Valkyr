package com.tufein.valkyr.ui.screens.more

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.navigation.NavRoutes

/** One row on the More screen: the screen it opens, its title and the one line under it. */
data class MoreItem(val route: NavRoutes, @StringRes val title: Int, @StringRes val hint: Int)

/** A heading on the More screen with its rows. */
data class MoreGroup(val id: String, @StringRes val title: Int, @DrawableRes val icon: Int, val items: List<MoreItem>)

/**
 * 3.8.0: what the More tab lists, in order. Plain data (no Compose) so it can be checked in a JVM
 * test: every row opens a real screen, none of them is a tab, none is listed twice.
 */
object MoreMenu {

    val groups: List<MoreGroup> by lazy {
        listOf(
            MoreGroup(
                "yours", R.string.shell38_group_yours, R.drawable.ic_library, listOf(
                    MoreItem(NavRoutes.Wishlist, R.string.nav_wishlist, R.string.tools_wishlist_hint),
                    MoreItem(NavRoutes.Collections, R.string.nav_collections, R.string.tools_collections_hint),
                    MoreItem(NavRoutes.Overview, R.string.settings_overview, R.string.settings_overview_hint),
                    MoreItem(NavRoutes.Stats, R.string.nav_stats, R.string.tools_stats_hint),
                    MoreItem(NavRoutes.History, R.string.coll6_history_title, R.string.lead6_history_hint)
                )
            ),
            MoreGroup(
                "tools", R.string.shell38_group_tools, R.drawable.ic_build, listOf(
                    MoreItem(NavRoutes.Tools, R.string.settings_tools, R.string.shell38_tools_hint),
                    MoreItem(NavRoutes.SearchAll, R.string.find8_title, R.string.find8_hint),
                    MoreItem(NavRoutes.LibraryRepair, R.string.repair30_title, R.string.repair30_hint),
                    MoreItem(NavRoutes.Storage, R.string.nav_storage, R.string.tools_storage_hint),
                    MoreItem(NavRoutes.SourcesTracker, R.string.src91_tracker_title, R.string.src91_tracker_hint),
                    MoreItem(NavRoutes.FreeSpace, R.string.space7_title, R.string.space7_hint)
                )
            ),
            MoreGroup(
                "online", R.string.shell38_group_online, R.drawable.ic_cloud, listOf(
                    MoreItem(NavRoutes.Cloud, R.string.nav_cloud, R.string.shell38_cloud_hint),
                    MoreItem(NavRoutes.SaveSync, R.string.nav_save_sync, R.string.shell38_save_sync_hint)
                )
            ),
            MoreGroup(
                "app", R.string.shell38_group_app, R.drawable.ic_settings, listOf(
                    MoreItem(NavRoutes.Settings, R.string.nav_settings, R.string.shell38_settings_hint),
                    MoreItem(NavRoutes.ReportProblem, R.string.report30_title, R.string.report30_hint),
                    MoreItem(NavRoutes.Contact, R.string.nav_contact, R.string.shell38_contact_hint)
                )
            )
        )
    }

    /** Width (dp) from which the rows sit two side by side (landscape handhelds, tablets). */
    const val TWO_COLUMNS_FROM_DP = 680

    /** How many rows sit side by side on a screen [widthDp] wide. */
    fun columnsFor(widthDp: Int): Int = if (widthDp >= TWO_COLUMNS_FROM_DP) 2 else 1

    /** The rows of [group] laid out in lines of [columns] (the last line may be shorter). */
    fun lines(group: MoreGroup, columns: Int): List<List<MoreItem>> = group.items.chunked(columns.coerceAtLeast(1))
}
