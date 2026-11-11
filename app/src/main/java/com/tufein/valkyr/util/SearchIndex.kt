package com.tufein.valkyr.util

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.navigation.NavRoutes

/** The groups of the "search everything" screen, in the order they are listed (games come last, from the library). */
enum class SearchGroup { SETTINGS, TOOLS, CLOUD }

/** Where a result goes: a route, and for Settings the row to scroll to and focus (see [SettingKeys]). */
data class SearchTarget(val route: String, val rowKey: String? = null)

/**
 * One thing the app can find: a Settings row, a tool or a screen. [keywords] is a comma-separated
 * list of synonyms per language (strings_v8_search.xml); [section] names the Settings card it sits in.
 */
class SearchEntry(
    val id: String,
    val group: SearchGroup,
    @StringRes val title: Int,
    @StringRes val hint: Int?,
    @DrawableRes val icon: Int,
    val target: SearchTarget,
    @StringRes val keywords: Int? = null,
    @StringRes val section: Int? = null,
    /** A Settings row behind its card's collapsed "Advanced" row (the result says so; the jump opens it). */
    val advanced: Boolean = false
)

/** Stable keys of the Settings rows a search result can jump to; SettingsScreen tags its rows with them. */
object SettingKeys {
    const val THEME = "theme"
    const val LANGUAGE = "language"
    const val TEXT_SIZE = "text_size"
    const val ACCENT = "accent"
    const val GAMEPAD_LAYOUT = "gamepad_layout"
    const val SWAP_BUTTONS = "swap_buttons"
    const val SECOND_SCREEN = "second_screen"
    const val BOLD_FOCUS = "bold_focus"
    const val ANIMATIONS = "animations"
    const val GLOW = "glow"
    const val TV_MODE = "tv_mode"
    const val LIST_COVERS = "list_covers"
    const val START_VIEW = "start_view"
    const val COMPACT = "compact"
    const val COVERS_RETRY = "covers_retry"
    const val DOWNLOAD_PRESETS = "download_presets"
    const val DOWNLOAD_DIR = "download_dir"
    const val SEPARATE_CONSOLE = "separate_console"
    const val CONCURRENT = "concurrent"
    const val PER_SERVER = "per_server"
    const val SPEED_LIMIT = "speed_limit"
    const val MIN_FREE = "min_free"
    const val MANUALS_WITH_GAME = "manuals_with_game"
    const val MANUALS_CAP = "manuals_cap"
    const val RESUME = "resume"
    const val REQUEUE = "requeue"
    const val AUTO_RETRY = "auto_retry"
    const val PICK_BEST = "pick_best"
    const val QUEUE_SUMMARY = "queue_summary"
    const val WIFI = "wifi"
    const val CHARGING = "charging"
    const val LOW_BATTERY = "low_battery"
    const val HEAT = "heat"
    const val NIGHT = "night"
    const val AUTO_UNZIP = "auto_unzip"
    const val AUTO_M3U = "auto_m3u"
    const val ESDE_ARTWORK = "esde_artwork"
    const val PEGASUS_ARTWORK = "pegasus_artwork"
    const val RETROARCH_ARTWORK = "retroarch_artwork"
    const val DEBRID = "debrid"
    const val METADATA_TIMEOUT = "metadata_timeout"
    const val AUTOSCAN = "autoscan"
    const val MAX_RESULTS = "max_results"
    const val FAVORITE_LANGUAGES = "favorite_languages"
    const val VERSION_PREF = "version_pref"
    const val WISHLIST_AUTO = "wishlist_auto"
    const val DIGEST = "digest"
    const val META_AUTO = "meta_auto"
    const val FRONTEND_SHORTCUTS = "frontend_shortcuts"
    const val ESDE = "esde"
    const val IISU = "iisu"
    const val DAIJISHO = "daijisho"
    const val EMULATORS = "emulators"
    const val SHELF = "shelf"
    const val COCOON = "cocoon"
    const val BACKUP_EXPORT = "backup_export"
    const val AUTO_BACKUP = "auto_backup"
    const val BACKUP_IMPORT = "backup_import"
    const val PRERELEASES = "prereleases"
    const val UPDATE_CHECK = "update_check"
    const val DIAGNOSTICS = "diagnostics"

    // 4.3: every other row has a key too, so its card and place come from [SettingsLayout].
    const val TOOLS = "tools"
    const val SEARCH_ALL = "search_all"
    const val STORAGE = "storage"
    const val SPEED_DAY_ONLY = "speed_day_only"
    const val BATTERY_LEVEL = "battery_level"
    const val NIGHT_START = "night_start"
    const val NIGHT_END = "night_end"
    const val DEBRID_KEY = "debrid_key"
    const val AUTOSCAN_EVERY = "autoscan_every"
    const val AUTOSCAN_WIFI = "autoscan_wifi"
    const val AUTOSCAN_CHARGING = "autoscan_charging"
    const val AUTOSCAN_NIGHT = "autoscan_night"
    const val PROFILES = "profiles"
    const val RECENT_MENU = "recent_menu"
    const val CLOUD = "cloud"
    const val ROMM = "romm"
    const val SAVE_SYNC = "save_sync"
    const val CLOUD_BACKUP = "cloud_backup"
    const val AUTO_BACKUP_FOLDER = "auto_backup_folder"
    const val REPORT = "report"
    const val ABOUT = "about"

    // 4.4: link rows to tools that belong with a setting (found by search as the tool itself).
    const val FRONTEND_COVERS_TOOL = "frontend_covers_tool"
    const val META_TOOL = "meta_tool"
    const val FRONTEND_CHECK = "frontend_check"
    const val RETROACHIEVEMENTS = "retroachievements"
    const val NEW_HANDHELD = "new_handheld"
    const val RECOVERY = "recovery"
}

/**
 * Everything "search everything" can find besides games. Static: the texts are string resources,
 * resolved in the language the app shows (and in English, as extra keywords) by the screen.
 * Rows that only show while another setting is on point at that setting's row instead.
 */
object SearchIndex {

    /**
     * A Settings row. Its card comes from [SettingsLayout], so search always names the card the
     * row is in; a row that only shows under a master switch jumps to that master ([target]).
     */
    private fun setting(
        key: String, @StringRes title: Int, @StringRes hint: Int?, @DrawableRes icon: Int,
        @StringRes keywords: Int? = null, target: String = SettingsLayout.jumpTarget(key), id: String = key
    ) = SearchEntry(
        id, SearchGroup.SETTINGS, title, hint, icon, SearchTarget(NavRoutes.Settings.route, target), keywords,
        SettingsLayout.groupOf(key)?.title, SettingsLayout.isAdvanced(key)
    )

    private fun screen(
        group: SearchGroup, route: NavRoutes, @StringRes title: Int, @StringRes hint: Int?, @StringRes keywords: Int? = null
    ) = SearchEntry(route.route, group, title, hint, route.icon, SearchTarget(route.route), keywords)

    val entries: List<SearchEntry> by lazy {
        listOf(
        SearchEntry("recovery", SearchGroup.TOOLS, R.string.recovery_title, R.string.recovery_hint, R.drawable.ic_history, SearchTarget(NavRoutes.Recovery.route)),
        SearchEntry("package_transfer", SearchGroup.TOOLS, R.string.package_transfer_title, R.string.package_transfer_hint, R.drawable.ic_stacks, SearchTarget(NavRoutes.PackageTransfer.route)),
        SearchEntry("new_handheld", SearchGroup.TOOLS, R.string.wizard35_title, R.string.wizard35_hint, R.drawable.ic_devices, SearchTarget(NavRoutes.NewHandheld.route), keywords = R.string.wizard35_kw),
        SearchEntry("game_transfer", SearchGroup.TOOLS, R.string.transfer31_title, R.string.transfer31_hint, R.drawable.ic_file_export, SearchTarget(NavRoutes.GameTransfer.route), keywords = R.string.transfer31_kw),
        SearchEntry("version_preference", SearchGroup.TOOLS, R.string.compare24_title, R.string.compare24_row_hint, R.drawable.ic_compare, SearchTarget(NavRoutes.VersionPreference.route), keywords = R.string.find8_kw_regions),
        SearchEntry("up_next", SearchGroup.TOOLS, R.string.next30_title, R.string.next30_hint, R.drawable.ic_playlist_play, SearchTarget(NavRoutes.UpNext.route)),
        SearchEntry("library_repair", SearchGroup.TOOLS, R.string.repair30_title, R.string.repair30_hint, R.drawable.ic_build, SearchTarget(NavRoutes.LibraryRepair.route)),
        SearchEntry("frontend_covers", SearchGroup.TOOLS, R.string.covers32_title, R.string.covers32_hint, R.drawable.ic_image, SearchTarget(NavRoutes.FrontendCovers.route), keywords = R.string.covers32_kw),
        SearchEntry("frontend_covers_extras", SearchGroup.TOOLS, R.string.saves48_extras_section, R.string.saves48_extras_hint, R.drawable.ic_image, SearchTarget(NavRoutes.FrontendCovers.route), keywords = R.string.saves48_extras_kw),
        SearchEntry("cover_sources", SearchGroup.TOOLS, R.string.covers43_title, R.string.covers43_row_hint, R.drawable.ic_image_search, SearchTarget(NavRoutes.CoverSources.route), keywords = R.string.covers43_kw),
        SearchEntry("cover_sources_keys", SearchGroup.TOOLS, R.string.covers44_search_title, R.string.covers44_search_hint, R.drawable.ic_key, SearchTarget(NavRoutes.CoverSources.route), keywords = R.string.covers44_kw),
        SearchEntry("retroarch_playlists", SearchGroup.TOOLS, R.string.playlists35_title, R.string.playlists35_hint, R.drawable.ic_playlist_add, SearchTarget(NavRoutes.RetroArchPlaylists.route), keywords = R.string.playlists35_kw),
        SearchEntry("report_problem", SearchGroup.TOOLS, R.string.report30_title, R.string.report30_hint, R.drawable.ic_bug, SearchTarget(NavRoutes.ReportProblem.route), keywords = R.string.find8_kw_diagnostics),
        SearchEntry("wifi_receive", SearchGroup.TOOLS, R.string.wifi66_title, R.string.wifi66_hint, R.drawable.ic_wifi, SearchTarget(NavRoutes.WifiReceive.route), keywords = R.string.wifi66_kw),
        SearchEntry("perf_check", SearchGroup.TOOLS, R.string.measure47_title, R.string.measure47_hint, R.drawable.ic_speed, SearchTarget(NavRoutes.PerfCheck.route), keywords = R.string.measure47_kw),
        SearchEntry("action_history", SearchGroup.TOOLS, R.string.hist24_title, R.string.hist24_hint, R.drawable.ic_manage_history, SearchTarget(NavRoutes.ActionHistory.route), keywords = R.string.hist24_kw),
            // Settings, in the order of SettingsLayout (their card comes from there too).
            setting(SettingKeys.THEME, R.string.settings_theme, R.string.settings_theme_hint, R.drawable.ic_dark_mode, R.string.find8_kw_theme),
            setting(SettingKeys.ACCENT, R.string.settings43_accent, R.string.settings43_accent_hint, R.drawable.ic_colorize, R.string.find8_kw_accent),
            setting(SettingKeys.LANGUAGE, R.string.settings_language, R.string.settings_language_hint, R.drawable.ic_language, R.string.find8_kw_language),
            setting(SettingKeys.TEXT_SIZE, R.string.settings_text_size, R.string.settings_text_size_hint, R.drawable.ic_text_size, R.string.find8_kw_text_size),
            setting(SettingKeys.COMPACT, R.string.disc6_compact_title, R.string.disc6_compact_hint, R.drawable.ic_list),
            setting(SettingKeys.START_VIEW, R.string.home60_start_view, R.string.home60_start_view_hint, R.drawable.ic_dashboard),
            setting(SettingKeys.LIST_COVERS, R.string.settings_v5_list_covers, R.string.settings_v5_list_covers_hint, R.drawable.ic_image, R.string.find8_kw_covers),
            setting(SettingKeys.ANIMATIONS, R.string.settings_v5_animations, R.string.settings_v5_animations_hint, R.drawable.ic_animation, R.string.find8_kw_animations),
            setting(SettingKeys.GLOW, R.string.settings_v5_glow, R.string.settings43_glow_hint, R.drawable.ic_blur_on),

            setting(SettingKeys.GAMEPAD_LAYOUT, R.string.settings_gamepad_layout, R.string.settings_gamepad_layout_hint, R.drawable.ic_gamepad, R.string.find8_kw_gamepad),
            setting(SettingKeys.SWAP_BUTTONS, R.string.settings_swap_face_buttons, R.string.settings_swap_face_buttons_hint, R.drawable.ic_swap_horiz, R.string.find8_kw_gamepad),
            setting(SettingKeys.BOLD_FOCUS, R.string.settings_bold_focus, R.string.settings_bold_focus_hint, R.drawable.ic_center_focus_strong, R.string.find8_kw_accessibility),
            setting(SettingKeys.TV_MODE, R.string.tv8_setting_title, null, R.drawable.ic_tv),
            setting(SettingKeys.SECOND_SCREEN, R.string.settings_second_screen, R.string.settings_second_screen_hint, R.drawable.ic_tv, R.string.find8_kw_second_screen),

            setting(SettingKeys.DOWNLOAD_DIR, R.string.settings43_download_folder, null, R.drawable.ic_folder_open, R.string.find8_kw_folder),
            setting(SettingKeys.SEPARATE_CONSOLE, R.string.settings43_separate, R.string.settings43_separate_hint, R.drawable.ic_account_tree, R.string.find8_kw_folder),
            setting(SettingKeys.MIN_FREE, R.string.settings_min_free, R.string.settings_min_free_hint, R.drawable.ic_storage, R.string.find8_kw_space),
            setting(SettingKeys.MANUALS_WITH_GAME, R.string.man60_setting_with_game, R.string.man60_setting_with_game_hint, R.drawable.ic_description, R.string.man60_kw),
            setting(SettingKeys.MANUALS_CAP, R.string.man60_setting_cap, R.string.man60_setting_cap_hint, R.drawable.ic_storage, R.string.man60_kw),

            setting(SettingKeys.CONCURRENT, R.string.settings_concurrent_label, null, R.drawable.ic_downloading, R.string.find8_kw_concurrent),
            setting(SettingKeys.SPEED_LIMIT, R.string.settings_limit_label, R.string.settings43_limit_hint, R.drawable.ic_speed, R.string.find8_kw_speed),
            setting(SettingKeys.SPEED_DAY_ONLY, R.string.settings_limit_day_only, null, R.drawable.ic_light_mode, R.string.find8_kw_speed),
            setting(SettingKeys.PER_SERVER, R.string.settings43_per_server, R.string.settings_per_server_hint, R.drawable.ic_hub, R.string.find8_kw_concurrent),
            setting(SettingKeys.PICK_BEST, R.string.src75_pick_best_title, R.string.src75_pick_best_hint, R.drawable.ic_swap_horiz, R.string.find8_kw_mirror),
            setting(SettingKeys.AUTO_RETRY, R.string.settings_auto_retry, R.string.settings_auto_retry_hint, R.drawable.ic_retry, R.string.find8_kw_retry),
            setting(SettingKeys.RESUME, R.string.settings_resume, R.string.settings_resume_hint, R.drawable.ic_play_arrow, R.string.find8_kw_resume),
            setting(SettingKeys.REQUEUE, R.string.settings_requeue, R.string.settings_requeue_hint, R.drawable.ic_restart_alt, R.string.find8_kw_resume),
            setting(SettingKeys.QUEUE_SUMMARY, R.string.settings_queue_summary, R.string.settings_queue_summary_hint, R.drawable.ic_notifications, R.string.find8_kw_notifications),

            setting(SettingKeys.WIFI, R.string.settings_dl_wifi, R.string.settings_dl_wifi_hint, R.drawable.ic_wifi, R.string.find8_kw_wifi),
            setting(SettingKeys.CHARGING, R.string.settings_dl_charging, R.string.settings_dl_charging_hint, R.drawable.ic_charging, R.string.find8_kw_charging),
            setting(SettingKeys.LOW_BATTERY, R.string.settings43_low_battery, null, R.drawable.ic_battery, R.string.find8_kw_battery),
            setting(SettingKeys.BATTERY_LEVEL, R.string.power75_battery_level_title, R.string.power75_battery_level_hint, R.drawable.ic_battery, R.string.find8_kw_battery),
            setting(SettingKeys.HEAT, R.string.power75_heat_title, R.string.power75_heat_hint, R.drawable.ic_thermostat, R.string.find8_kw_heat),
            setting(SettingKeys.NIGHT, R.string.settings_dl_night, null, R.drawable.ic_night, R.string.find8_kw_night),
            setting(SettingKeys.NIGHT_START, R.string.settings_dl_night_start, null, R.drawable.ic_bedtime, R.string.find8_kw_night),
            setting(SettingKeys.NIGHT_END, R.string.settings_dl_night_end, null, R.drawable.ic_wb_twilight, R.string.find8_kw_night),
            setting(SettingKeys.DOWNLOAD_PRESETS, R.string.presets26_title, R.string.presets26_settings_hint, R.drawable.ic_schedule, R.string.find8_kw_speed),

            setting(SettingKeys.AUTO_UNZIP, R.string.settings_auto_unzip, R.string.settings_auto_unzip_hint, R.drawable.ic_extract, R.string.find8_kw_unzip),
            setting(SettingKeys.AUTO_M3U, R.string.settings_auto_m3u, R.string.settings_auto_m3u_hint, R.drawable.ic_playlist_add, R.string.find8_kw_m3u),
            setting(SettingKeys.META_AUTO, R.string.settings43_meta_auto, R.string.settings43_meta_auto_hint, R.drawable.ic_description, R.string.find8_kw_metadata),
            setting(SettingKeys.ESDE_ARTWORK, R.string.settings_esde_artwork, R.string.settings_esde_artwork_hint, R.drawable.ic_photos, R.string.find8_kw_artwork),
            setting(SettingKeys.PEGASUS_ARTWORK, R.string.settings_pegasus_artwork, R.string.settings_pegasus_artwork_hint, R.drawable.ic_photo_album, R.string.find8_kw_artwork),
            setting(SettingKeys.RETROARCH_ARTWORK, R.string.settings_retroarch_artwork, null, R.drawable.ic_wallpaper, R.string.find8_kw_artwork),

            setting(SettingKeys.DEBRID, R.string.settings_debrid, R.string.settings_debrid_hint, R.drawable.ic_bolt, R.string.find8_kw_debrid),
            setting(SettingKeys.METADATA_TIMEOUT, R.string.settings_metadata_timeout, R.string.settings_metadata_timeout_hint, R.drawable.ic_hourglass, R.string.find8_kw_torrent),

            setting(SettingKeys.AUTOSCAN, R.string.settings_autoscan, null, R.drawable.ic_sync, R.string.find8_kw_scan),
            setting(SettingKeys.AUTOSCAN_EVERY, R.string.settings_autoscan_every, null, R.drawable.ic_timer, R.string.find8_kw_scan),
            setting(SettingKeys.AUTOSCAN_WIFI, R.string.settings43_autoscan_wifi, null, R.drawable.ic_wifi, R.string.find8_kw_scan),
            setting(SettingKeys.AUTOSCAN_CHARGING, R.string.settings43_autoscan_charging, null, R.drawable.ic_charging, R.string.find8_kw_scan),
            setting(SettingKeys.AUTOSCAN_NIGHT, R.string.settings_autoscan_night, null, R.drawable.ic_night, R.string.find8_kw_scan),
            setting(SettingKeys.VERSION_PREF, R.string.compare24_title, R.string.compare24_row_hint, R.drawable.ic_compare, R.string.find8_kw_regions),
            setting(SettingKeys.FAVORITE_LANGUAGES, R.string.settings43_favorite_languages, null, R.drawable.ic_translate, R.string.find8_kw_regions),
            setting(SettingKeys.WISHLIST_AUTO, R.string.settings_wishlist_auto, R.string.settings_wishlist_auto_hint, R.drawable.ic_wishlist, R.string.find8_kw_wishlist),
            setting(SettingKeys.COVERS_RETRY, R.string.covers43_title, R.string.covers43_row_hint, R.drawable.ic_image_search, R.string.find8_kw_covers),
            setting(SettingKeys.MAX_RESULTS, R.string.settings_max_results, R.string.settings_max_results_hint, R.drawable.ic_format_list_numbered),
            setting(SettingKeys.DIGEST, R.string.upg7_digest_setting, R.string.upg7_digest_setting_hint, R.drawable.ic_notifications, R.string.find8_kw_notifications),

            setting(SettingKeys.ESDE, R.string.settings_esde, R.string.settings_esde_hint, R.drawable.ic_frontends, R.string.find8_kw_frontends),
            setting(SettingKeys.IISU, R.string.settings_iisu, R.string.settings_iisu_hint, R.drawable.ic_grid, R.string.find8_kw_frontends),
            setting(SettingKeys.DAIJISHO, R.string.settings_daijisho, R.string.settings_daijisho_hint, R.drawable.ic_dashboard, R.string.find8_kw_frontends),
            setting(SettingKeys.COCOON, R.string.settings44_cocoon, R.string.settings_cocoon_hint, R.drawable.ic_help, R.string.find8_kw_frontends),
            setting(SettingKeys.EMULATORS, R.string.play24_settings_title, R.string.play24_settings_hint, R.drawable.ic_controller, R.string.find8_kw_frontends),
            setting(SettingKeys.FRONTEND_SHORTCUTS, R.string.settings_frontend_shortcuts, R.string.settings_frontend_shortcuts_hint, R.drawable.ic_shortcut, R.string.find8_kw_frontends),
            setting(SettingKeys.RECENT_MENU, R.string.pin35_setting_title, R.string.pin35_setting_hint, R.drawable.ic_play_circle),

            setting(SettingKeys.SHELF, R.string.csave_shelf_setting_title, R.string.csave_shelf_setting_hint, R.drawable.ic_play_circle),

            setting(SettingKeys.BACKUP_EXPORT, R.string.settings_backup_export, R.string.settings_backup_export_hint, R.drawable.ic_file_export, R.string.find8_kw_backup),
            setting(SettingKeys.BACKUP_IMPORT, R.string.settings_backup_import, R.string.settings_backup_import_hint, R.drawable.ic_restore, R.string.find8_kw_restore),
            setting(SettingKeys.AUTO_BACKUP, R.string.settings_auto_backup, null, R.drawable.ic_update, R.string.find8_kw_backup),
            setting(SettingKeys.AUTO_BACKUP_FOLDER, R.string.settings_auto_backup_folder, null, R.drawable.ic_folder, R.string.find8_kw_backup),

            setting(SettingKeys.UPDATE_CHECK, R.string.settings_update_check, R.string.settings_update_check_hint, R.drawable.ic_rocket, R.string.find8_kw_update),
            setting(SettingKeys.PRERELEASES, R.string.settings_prereleases, R.string.settings_prereleases_hint, R.drawable.ic_science, R.string.find8_kw_update),
            setting(SettingKeys.DIAGNOSTICS, R.string.settings_diagnostics, R.string.settings_diagnostics_hint, R.drawable.ic_bug, R.string.find8_kw_diagnostics),

            // Tools and screens.
            screen(SearchGroup.TOOLS, NavRoutes.Home, R.string.nav_library, R.string.find8_hint_library, R.string.find8_kw_library),
            screen(SearchGroup.TOOLS, NavRoutes.Downloads, R.string.nav_downloads, R.string.find8_hint_downloads, R.string.find8_kw_downloads),
            screen(SearchGroup.TOOLS, NavRoutes.Sources, R.string.nav_sources, R.string.find8_hint_sources, R.string.find8_kw_sources),
            screen(SearchGroup.TOOLS, NavRoutes.More, R.string.shell38_more, R.string.shell38_more_hint),
            screen(SearchGroup.TOOLS, NavRoutes.Tools, R.string.settings_tools, R.string.settings_tools_hint),
            screen(SearchGroup.TOOLS, NavRoutes.Overview, R.string.settings_overview, R.string.settings_overview_hint),
            screen(SearchGroup.TOOLS, NavRoutes.Duplicates, R.string.settings_duplicates, R.string.settings_duplicates_hint, R.string.find8_kw_duplicates),
            screen(SearchGroup.TOOLS, NavRoutes.Sets, R.string.nav_sets, R.string.tools_sets_hint),
            screen(SearchGroup.TOOLS, NavRoutes.Collections, R.string.nav_collections, R.string.tools_collections_hint),
            screen(SearchGroup.TOOLS, NavRoutes.Files, R.string.nav_files, R.string.tools_files_hint, R.string.find8_kw_files),
            screen(SearchGroup.TOOLS, NavRoutes.Storage, R.string.nav_storage, R.string.tools_storage_hint, R.string.find8_kw_space),
            screen(SearchGroup.TOOLS, NavRoutes.FreeSpace, R.string.space7_title, R.string.space7_hint, R.string.find8_kw_space),
            screen(SearchGroup.TOOLS, NavRoutes.Stats, R.string.nav_stats, R.string.tools_stats_hint),
            screen(SearchGroup.TOOLS, NavRoutes.CollectionGoals, R.string.coll6_goals_title, R.string.lead6_goals_hint),
            screen(SearchGroup.TOOLS, NavRoutes.History, R.string.coll6_history_title, R.string.lead6_history_hint),
            screen(SearchGroup.TOOLS, NavRoutes.Health, R.string.health6_title, R.string.lead6_health_hint, R.string.find8_kw_health),
            screen(SearchGroup.TOOLS, NavRoutes.Recap, R.string.quick7_title, R.string.quick7_hint),
            screen(SearchGroup.TOOLS, NavRoutes.ImportList, R.string.nav_import_list, R.string.tools_import_list_hint),
            screen(SearchGroup.TOOLS, NavRoutes.Wishlist, R.string.nav_wishlist, R.string.tools_wishlist_hint, R.string.find8_kw_wishlist),
            screen(SearchGroup.TOOLS, NavRoutes.Dat, R.string.nav_dat, R.string.tools_dat_hint, R.string.find8_kw_dat),
            screen(SearchGroup.TOOLS, NavRoutes.Bios, R.string.nav_bios, R.string.tools_bios_hint, R.string.find8_kw_bios),
            screen(SearchGroup.TOOLS, NavRoutes.Switch, R.string.nav_switch, R.string.tools_switch_hint, R.string.find8_kw_switch),
            screen(SearchGroup.TOOLS, NavRoutes.BestGames, R.string.top7_title, R.string.top7_hint),
            screen(SearchGroup.TOOLS, NavRoutes.BetterVersions, R.string.upg7_better_title, R.string.upg7_better_hint),
            screen(SearchGroup.TOOLS, NavRoutes.Frontends, R.string.nav_frontends, R.string.tools_frontends_hint, R.string.find8_kw_frontends),
            screen(SearchGroup.TOOLS, NavRoutes.FrontendMetadata, R.string.meta7_title, R.string.meta7_hint, R.string.find8_kw_metadata),
            screen(SearchGroup.TOOLS, NavRoutes.Profiles, R.string.settings_profiles, R.string.settings_profiles_hint, R.string.find8_kw_profiles),
            screen(SearchGroup.TOOLS, NavRoutes.Contact, R.string.brand40_about_title, R.string.find8_hint_about),

            // Everything online.
            screen(SearchGroup.CLOUD, NavRoutes.Cloud, R.string.nav_cloud, R.string.settings_v5_cloud_hint),
            screen(SearchGroup.CLOUD, NavRoutes.Romm, R.string.nav_romm, R.string.settings_romm_hint, R.string.find8_kw_romm),
            screen(SearchGroup.CLOUD, NavRoutes.SaveSync, R.string.nav_save_sync, R.string.settings_save_sync_hint, R.string.find8_kw_saves),
            SearchEntry("save_sync_wiiware", SearchGroup.CLOUD, R.string.saves48_wiiware, R.string.saves48_wiiware_hint, NavRoutes.SaveSync.icon, SearchTarget(NavRoutes.SaveSync.route), keywords = R.string.saves48_wiiware_kw),
            screen(SearchGroup.CLOUD, NavRoutes.CloudBackup, R.string.dav_title, R.string.find8_hint_webdav, R.string.find8_kw_webdav),
            screen(SearchGroup.CLOUD, NavRoutes.RetroAchievements, R.string.nav_ra, R.string.tools_ra_hint, R.string.find8_kw_ra)
        )
    }
}

/**
 * The texts of one [SearchEntry] as the user reads them: title and hint in the app's language,
 * [keywords] the synonyms of that language plus the English title and synonyms (so "battery"
 * still works when the app is in Dutch).
 */
data class SearchDoc(val id: String, val title: String, val hint: String, val keywords: List<String>)

/** A document that matched, and how well. */
data class SearchHit(val doc: SearchDoc, val score: Int)

/**
 * Lenient matching and a simple ranking for "search everything". Both sides go through
 * [SearchNormalizer] (case, accents, punctuation and doubled letters do not matter). Best first:
 * the title starts with the query, every query word starts a title word, the title contains it,
 * then the hint, then a keyword. A one-letter typo still matches, a little lower.
 */
object SearchMatch {
    const val TITLE_PREFIX = 100
    const val TITLE_WORDS = 80
    const val TITLE_CONTAINS = 60
    const val HINT = 40
    const val KEYWORD = 30
    /** Taken off when a word only matched with a typo. */
    const val TYPO = 8

    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")
    private val LIST_SPLIT = Regex("[,;\\n]")

    /** Words of [text], each normalized like a library title. */
    fun words(text: String): List<String> = text.split(SPLIT).map { SearchNormalizer.key(it) }.filter { it.isNotEmpty() }

    /** A comma-separated keyword resource as separate phrases. */
    fun keywordList(text: String): List<String> = text.split(LIST_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }

    /** 0 = no match, otherwise higher is better (see the constants). */
    fun score(query: String, doc: SearchDoc): Int {
        val q = SearchNormalizer.key(query)
        val qWords = words(query)
        if (q.isEmpty() || qWords.isEmpty()) return 0
        val titleKey = SearchNormalizer.key(doc.title)
        if (titleKey.startsWith(q)) return TITLE_PREFIX
        val inTitle = wordsMatch(qWords, words(doc.title))
        if (inTitle == EXACT) return TITLE_WORDS
        if (q.length >= 3 && titleKey.contains(q)) return TITLE_CONTAINS
        if (inTitle == TYPO_MATCH) return TITLE_WORDS - TYPO
        val inHint = wordsMatch(qWords, words(doc.hint))
        if (inHint == EXACT) return HINT
        if (q.length >= 4 && SearchNormalizer.key(doc.hint).contains(q)) return HINT - 2
        var best = if (inHint == TYPO_MATCH) HINT - TYPO else 0
        for (keyword in doc.keywords) {
            val k = SearchNormalizer.key(keyword)
            val s = when {
                k.startsWith(q) -> KEYWORD
                else -> when (wordsMatch(qWords, words(keyword))) {
                    EXACT -> KEYWORD - 1
                    TYPO_MATCH -> KEYWORD - TYPO
                    else -> if (q.length >= 4 && k.contains(q)) KEYWORD - 4 else 0
                }
            }
            if (s > best) best = s
        }
        return best
    }

    /** Matching [docs], best first; equal scores keep the index order (Settings card order). */
    fun rank(query: String, docs: List<SearchDoc>): List<SearchHit> =
        docs.map { SearchHit(it, score(query, it)) }
            .filter { it.score > 0 }
            .sortedByDescending { it.score }

    private const val NONE = 0
    private const val TYPO_MATCH = 1
    private const val EXACT = 2

    /** Whether every query word starts some text word: exactly, or only with a typo. */
    private fun wordsMatch(query: List<String>, text: List<String>): Int {
        if (text.isEmpty()) return NONE
        var result = EXACT
        for (w in query) {
            when {
                text.any { it.startsWith(w) } -> Unit
                text.any { typoPrefix(w, it) } -> result = TYPO_MATCH
                else -> return NONE
            }
        }
        return result
    }

    /**
     * True when [word] (4+ letters) starts [text] with one letter wrong, missing, extra or two
     * letters swapped; a trailing letter of a 5+ letter word may be off too, like the library search.
     */
    internal fun typoPrefix(word: String, text: String): Boolean {
        if (word.length < 4) return false
        if (word.length >= 5 && text.startsWith(word.dropLast(1))) return true
        return (-1..1).any { d ->
            val n = word.length + d
            n in 1..text.length && distance(word, text.take(n)) <= 1
        }
    }

    /** Edit distance with adjacent swaps (optimal string alignment); short strings only. */
    internal fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, d[i - 2][j - 2] + 1)
            d[i][j] = v
        }
        return d[a.length][b.length]
    }
}

/** A game of the library found by "search everything": its clean title and console. */
data class GameHit(val title: String, val consoleId: String)

/** Turns raw library rows (file name, console) into a few distinct games, best match first. */
object GameHits {
    /**
     * One hit per game and console (versions and discs of a game collapse into one), titles that
     * start with [query] first, then shorter titles, otherwise the order of [rows].
     */
    fun pick(query: String, rows: List<Pair<String, String>>, limit: Int): List<GameHit> {
        val q = SearchNormalizer.key(query)
        val seen = HashSet<String>()
        val hits = ArrayList<GameHit>()
        for ((name, console) in rows) {
            val title = GameTitleCleaner.clean(name).ifBlank { name }
            if (seen.add(console + "/" + SearchNormalizer.key(title))) hits += GameHit(title, console)
        }
        return hits.withIndex()
            .sortedWith(compareBy({ if (SearchNormalizer.key(it.value.title).startsWith(q)) 0 else 1 }, { it.value.title.length }, { it.index }))
            .map { it.value }
            .take(limit)
    }
}
