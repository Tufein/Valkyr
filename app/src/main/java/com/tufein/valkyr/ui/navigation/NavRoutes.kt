package com.tufein.valkyr.ui.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.screens.tools.ACTION_HISTORY_ROUTE
import com.tufein.valkyr.ui.screens.tools.FRONTEND_COVERS_ROUTE
import com.tufein.valkyr.ui.screens.tools.GAME_TRANSFER_ROUTE
import com.tufein.valkyr.ui.screens.tools.LIBRARY_REPAIR_ROUTE
import com.tufein.valkyr.ui.screens.tools.NEW_HANDHELD_ROUTE
import com.tufein.valkyr.ui.screens.tools.RETROARCH_PLAYLISTS_ROUTE
import com.tufein.valkyr.util.GamePageModel

sealed class NavRoutes(val route: String, val labelRes: Int, val icon: Int) {
    /** 3.8.0: the first tab reads "Games" (the library with its shelves). */
    object Home : NavRoutes("home", R.string.shell38_tab_games, R.drawable.ic_controller)
    object Downloads : NavRoutes("downloads", R.string.nav_downloads, R.drawable.ic_download)
    object Sources : NavRoutes("sources", R.string.nav_sources, R.drawable.ic_globe)
    /** Since 3.8.0 a normal screen, reached from [More]; it is no longer a tab. */
    object Settings : NavRoutes("settings", R.string.nav_settings, R.drawable.ic_settings)
    /** 3.8.0: the fourth tab, one list of everything that is not a tab (settings, tools, cloud, wishlist…). */
    object More : NavRoutes("more", R.string.shell38_more, R.drawable.ic_more_horiz)
    object Contact : NavRoutes("contact", R.string.nav_contact, R.drawable.ic_heart)
    object Romm : NavRoutes("romm", R.string.nav_romm, R.drawable.ic_server)
    object SaveSync : NavRoutes("save_sync", R.string.nav_save_sync, R.drawable.ic_cloud_sync)
    object Overview : NavRoutes("overview", R.string.nav_overview, R.drawable.ic_dashboard)
    object Duplicates : NavRoutes("duplicates", R.string.nav_duplicates, R.drawable.ic_duplicates)
    object Tools : NavRoutes("tools", R.string.settings_tools, R.drawable.ic_build)
    object Sets : NavRoutes("sets", R.string.nav_sets, R.drawable.ic_stacks)
    object Storage : NavRoutes("storage", R.string.nav_storage, R.drawable.ic_storage)
    object Wishlist : NavRoutes("wishlist", R.string.nav_wishlist, R.drawable.ic_wishlist)
    object Files : NavRoutes("files", R.string.nav_files, R.drawable.ic_folder_open)
    object Collections : NavRoutes("collections", R.string.nav_collections, R.drawable.ic_collections)
    object Switch : NavRoutes("switch", R.string.nav_switch, R.drawable.ic_joystick)
    object Dat : NavRoutes("dat", R.string.nav_dat, R.drawable.ic_verified)
    object ImportList : NavRoutes("import_list", R.string.nav_import_list, R.drawable.ic_playlist_add)
    object Bios : NavRoutes("bios", R.string.nav_bios, R.drawable.ic_memory)
    object Stats : NavRoutes("stats", R.string.nav_stats, R.drawable.ic_bar_chart)
    object Profiles : NavRoutes("profiles", R.string.settings_profiles, R.drawable.ic_account)
    object Frontends : NavRoutes("frontends", R.string.nav_frontends, R.drawable.ic_frontends)
    object RetroAchievements : NavRoutes("retroachievements", R.string.nav_ra, R.drawable.ic_trophy)
    /** 5.0: everything that lives online in one place (RomM, saves, cloud backup, devices, RetroAchievements). */
    object Cloud : NavRoutes("cloud", R.string.nav_cloud, R.drawable.ic_cloud)
    /** 5.0: encrypted backup and device sync on the user's own WebDAV server (Nextcloud, ownCloud, Synology…). */
    object CloudBackup : NavRoutes("cloud_backup", R.string.dav_title, R.drawable.ic_cloud_upload)

    /** 6.0: how complete each console's collection is. */
    object CollectionGoals : NavRoutes("collection_goals", R.string.coll6_goals_title, R.drawable.ic_collection_goals)
    /** 6.0: what was downloaded and played, by day. */
    object History : NavRoutes("play_history", R.string.coll6_history_title, R.drawable.ic_history)
    /** 6.0: every health check in one report, with a fix per problem. */
    object Health : NavRoutes("health", R.string.health6_title, R.drawable.ic_health)

    /** 7.0: the year in games, with a card to share. */
    object Recap : NavRoutes("recap", R.string.quick7_title, R.drawable.ic_sparkle)
    /** 7.0: the most-loved games per console (RetroAchievements) against the library. */
    object BestGames : NavRoutes("bestgames", R.string.top7_title, R.drawable.ic_military_tech)
    /** 7.0: write descriptions, genres and years into ES-DE and Pegasus. */
    object FrontendMetadata : NavRoutes("frontend_metadata", R.string.meta7_title, R.drawable.ic_description)
    /** 7.0: a newer revision or a clean dump of a game you already have. */
    object BetterVersions : NavRoutes("better_versions", R.string.upg7_better_title, R.drawable.ic_better_version)

    /** 7.0: games never played, biggest first, to remove or move to the wishlist. */
    object FreeSpace : NavRoutes("freespace", R.string.space7_title, R.drawable.ic_free_space)

    /** 8.0: the full-screen page of one library game; build the path with GamePageModel.route(consoleId, fileName). Not in [allRoutes]. */
    object Game : NavRoutes(GamePageModel.ROUTE, R.string.page8_title, R.drawable.ic_controller)

    /** 8.0: one search for settings, tools, screens and games. */
    object Recovery : NavRoutes("recovery", R.string.recovery_title, R.drawable.ic_history)
    object PackageTransfer : NavRoutes("package_transfer", R.string.package_transfer_title, R.drawable.ic_stacks)
    object VersionPreference : NavRoutes("version_preference", R.string.compare24_title, R.drawable.ic_compare)
    object ActionHistory : NavRoutes(ACTION_HISTORY_ROUTE, R.string.hist24_title, R.drawable.ic_manage_history)

    object UpNext : NavRoutes("up_next", R.string.next30_title, R.drawable.ic_playlist_play)
    object LibraryRepair : NavRoutes(LIBRARY_REPAIR_ROUTE, R.string.repair30_title, R.drawable.ic_build)
    object ReportProblem : NavRoutes("report_problem", R.string.report30_title, R.drawable.ic_bug)
    /** 4.7.0: the on-device performance self-test (database, covers, storage, frame times) with a shareable report. */
    /** 6.6.0: take game files from a browser on the same Wi-Fi into the console folders. */
    object WifiReceive : NavRoutes("wifi_receive", R.string.wifi66_title, R.drawable.ic_wifi)
    /** 6.7.0: which known emulators are installed and which library consoles each can start. */
    object EmulatorCheck : NavRoutes("emulator_check", R.string.emucheck67_title, R.drawable.ic_controller)
    object PerfCheck : NavRoutes("perf_check", R.string.measure47_title, R.drawable.ic_speed)
    /** 3.1.0: whole games to a folder, RomM or the NAS, with a preview and a check of every file. */
    object GameTransfer : NavRoutes(GAME_TRANSFER_ROUTE, R.string.transfer31_title, R.drawable.ic_file_export)
    /** 3.1.0: cover images for iiSU and Daijishō in a media folder the user picks. */
    object FrontendCovers : NavRoutes(FRONTEND_COVERS_ROUTE, R.string.covers32_title, R.drawable.ic_image)
    /** 3.5.0: RetroArch playlists of the games on the device, in RetroArch's playlists folder. */
    object RetroArchPlaylists : NavRoutes(RETROARCH_PLAYLISTS_ROUTE, R.string.playlists35_title, R.drawable.ic_playlist_add)
    /** 3.5.0: the checklist that takes a fresh install to a ready handheld, step by step. */
    object NewHandheld : NavRoutes(NEW_HANDHELD_ROUTE, R.string.wizard35_title, R.drawable.ic_devices)

    object SearchAll : NavRoutes("search_all", R.string.find8_title, R.drawable.ic_search)
    /** 4.3.0: the cover sources, their order and the ScreenScraper login (Settings → Cover sources). */
    object CoverSources : NavRoutes("cover_sources", R.string.covers43_title, R.drawable.ic_image_search)

    companion object {
        /** The four sections shown as tabs (3.8.0); every other screen is reached from More. */
        val tabs by lazy { listOf(Home, Downloads, Sources, More) }
        val allRoutes by lazy { tabs + Settings + Contact + Romm + SaveSync + Overview + Duplicates + Tools + Sets + Storage + Wishlist + Files + Collections + Switch + Dat + ImportList + Bios + Stats + Profiles + RetroAchievements + Frontends + Cloud + CloudBackup + CollectionGoals + History + Health + Recap + BestGames + FrontendMetadata + BetterVersions + FreeSpace + SearchAll + Recovery + PackageTransfer + ActionHistory + VersionPreference + UpNext + LibraryRepair + ReportProblem + PerfCheck + GameTransfer + FrontendCovers + RetroArchPlaylists + NewHandheld + CoverSources + WifiReceive + EmulatorCheck }
    }
}

/**
 * The tab a route belongs to: a game page belongs to the Library, every screen that is not a tab
 * (Settings, Tools, Cloud, the wishlist…) keeps More lit.
 */
fun tabRouteFor(route: String): String = when {
    NavRoutes.tabs.any { it.route == route } -> route
    route == NavRoutes.Game.route || route.startsWith("game/") -> NavRoutes.Home.route
    else -> NavRoutes.More.route
}

/**
 * +1 when going "right" (a later tab, or deeper into a screen opened from More), -1 when going
 * back "left": the side the new screen slides in from.
 */
fun travelDirection(from: String?, to: String?): Int {
    val tabs = NavRoutes.tabs.map { it.route }
    val a = tabs.indexOf(from)
    val b = tabs.indexOf(to)
    return when {
        a >= 0 && b >= 0 -> if (b >= a) 1 else -1
        b < 0 -> 1      // into a screen opened from More
        else -> -1      // back out to a tab
    }
}

/** The tab [delta] steps from the one [route] belongs to, wrapping around (ZL / ZR). */
fun neighbourTab(route: String?, delta: Int): NavRoutes {
    val tabs = NavRoutes.tabs
    val index = tabs.indexOfFirst { it.route == tabRouteFor(route.orEmpty()) }.coerceAtLeast(0)
    return tabs[((index + delta) % tabs.size + tabs.size) % tabs.size]
}

/**
 * Switches to a section tab. Tapping the tab that is open posts [TabReselect] (its list scrolls to
 * the top); tapping a tab from one of its deeper screens pops back to the tab's root. A tab already in the back stack is popped back to: navigate() with
 * restoreState would restore the very stack it has just saved and leave the user where they were.
 */
fun NavController.switchToTab(route: String) {
    if (currentDestination?.route == route) { TabReselect.post(route); return }
    if (runCatching { getBackStackEntry(route) }.isSuccess) {
        popBackStack(route, inclusive = false)
        return
    }
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
