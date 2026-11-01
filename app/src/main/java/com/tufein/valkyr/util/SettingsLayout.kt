package com.tufein.valkyr.util

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.tufein.valkyr.R
import com.tufein.valkyr.util.SettingKeys as K

/*
 * 5.0 decision ("the settings were still too extensive"): 10 cards, and in every card only what people
 * actually change shows; the rest sits behind one collapsed "Advanced (N)" row at the end of the card
 * (never lost: search finds it, jumps to it and opens the card). Every key has a [SettingLevel].
 * Switches whose default is right for nearly everyone, steppers with tuning values, per-server limits,
 * battery / night / retry settings, maintenance dialogs and diagnostics are ADVANCED; a master and its
 * dependents are always on the same level (a dependent never sits behind a different door than its switch).
 * Merged since 4.9: Controls into Look, Storage and Torrents into Downloads, After a download into Frontends.
 *
 *  TOOLS      (no heading) everyday: Library tools, Search settings and tools
 *  LOOK       Look and controls
 *               everyday: Theme, Accent colour, App language, Text size, Gamepad layout, Swap A/B X/Y
 *               advanced: TV mode, Second screen, Bold focus ring, Compact lists, Animations, Background glow
 *  LIBRARY    Library and covers
 *               everyday: Profiles, Favourite game languages, Version preference, Covers in the library list, Cover sources
 *               advanced: Maximum search results, Recently played in the icon menu
 *  SOURCES    Sources and new games
 *               everyday: Scan sources automatically (+ every / Wi-Fi / charging / night), Download wishlist
 *               games automatically, Weekly digest
 *               advanced: nothing (so no Advanced row)
 *  DOWNLOADS  Downloads and storage
 *               everyday: Download presets (they set limit and conditions together, so first), Download folder,
 *               Debrid service (+ API key)
 *               advanced: A subfolder per console, Keep free space, Storage (link), Concurrent downloads,
 *               Downloads per server, Speed limit (+ No limit at night), Pick the best source, Retry by itself,
 *               Continue interrupted, Continue the queue after a restart, Metadata timeout
 *  SCHEDULE   When to download
 *               everyday: Wi-Fi, Charging
 *               advanced: Low battery (+ limit), Hot, Night (+ start / end)
 *  FRONTENDS  Frontends and after a download
 *               everyday: Set up ES-DE / iiSU / Daijishō, Emulator per console, Auto-unzip, m3u playlists
 *               advanced: Cocoon, Frontend shortcuts, Frontend check (link), Covers for ES-DE / Pegasus / RetroArch,
 *               Covers for launchers (link), Game details for frontends, Descriptions (link), Queue-done notice
 *  CLOUD      Cloud and saves
 *               everyday: Cloud (hub), RomM server, Save sync, RetroAchievements (link)
 *               advanced: Continue playing on Home
 *  BACKUP     Backup and recovery
 *               everyday: Back up, Automatic backup (+ folder), Restore backup
 *               advanced: Cloud backup, Set up a new handheld (link), Trash and recovery (link)
 *  APP        App and updates
 *               everyday: Check for updates, Report a problem, About
 *               advanced: Include pre-releases, Share diagnostics
 *
 * Rules: a dependent row sits directly under its master. It is hidden while the master is off only
 * when the master explains it by itself; when the explanation lives elsewhere (the night hours
 * are used by three switches in three cards, "No limit at night" needs a speed limit) the row
 * stays visible, dimmed, with the reason as its hint ([SettingsLayout.inactiveReason]).
 * Rows that open a tool or hub are single link rows, never copies of the tool itself.
 */

/** How prominent a row is: shown at once, or behind the card's collapsed "Advanced" row. */
enum class SettingLevel { EVERYDAY, ADVANCED }

/**
 * The cards of the Settings screen, in the order they are shown, with their heading, a one-line
 * description of their purpose and their icon. [title] null is the way into Tools: first,
 * without a heading, on the accent card.
 */
enum class SettingsGroup(@StringRes val title: Int?, @StringRes val description: Int?, @DrawableRes val icon: Int) {
    TOOLS(null, null, R.drawable.ic_build),
    LOOK(R.string.settings51_group_look, R.string.settings51_desc_look, R.drawable.ic_palette),
    LIBRARY(R.string.settings44_group_library, R.string.settings51_desc_library, R.drawable.ic_library),
    SOURCES(R.string.settings44_group_sources, R.string.settings51_desc_sources, R.drawable.ic_sync),
    DOWNLOADS(R.string.settings51_group_downloads, R.string.settings51_desc_downloads, R.drawable.ic_download),
    SCHEDULE(R.string.settings_section_schedule, R.string.settings51_desc_schedule, R.drawable.ic_schedule),
    FRONTENDS(R.string.settings51_group_frontends, R.string.settings51_desc_frontends, R.drawable.ic_frontends),
    CLOUD(R.string.settings43_group_cloud, R.string.settings51_desc_cloud, R.drawable.ic_cloud),
    BACKUP(R.string.settings44_group_backup, R.string.settings51_desc_backup, R.drawable.ic_backup),
    APP(R.string.settings_section_app, R.string.settings51_desc_app, R.drawable.ic_info)
}

/**
 * The switches and choices that show, hide or dim other rows. A dependent row sits directly under
 * its master; see [SettingsLayout.isShown] and [SettingsLayout.inactiveReason].
 */
data class SettingsMasters(
    val speedLimitOn: Boolean = false,
    val speedLimitDayOnly: Boolean = false,
    val lowBatteryOn: Boolean = false,
    val nightOnly: Boolean = false,
    val autoScanOn: Boolean = false,
    val autoScanNightOnly: Boolean = false,
    val debridOn: Boolean = false,
    val autoBackupOn: Boolean = false
)

/**
 * Where every Settings row lives (4.3, regrouped in 4.4; see the decision at the top of this
 * file): its card, its place in the card (most used first, the advanced last; a master switch
 * is directly followed by its own rows) and when it shows. The screen and "search everything"
 * both read it, so a row found by search names the card it is in.
 */
object SettingsLayout {

    /** The everyday rows of each card, in order (a master is directly followed by its own rows). */
    val everyday: Map<SettingsGroup, List<String>> = linkedMapOf(
        SettingsGroup.TOOLS to listOf(K.TOOLS, K.SEARCH_ALL),
        SettingsGroup.LOOK to listOf(K.THEME, K.ACCENT, K.LANGUAGE, K.TEXT_SIZE, K.GAMEPAD_LAYOUT, K.SWAP_BUTTONS),
        SettingsGroup.LIBRARY to listOf(K.PROFILES, K.FAVORITE_LANGUAGES, K.VERSION_PREF, K.LIST_COVERS, K.COVERS_RETRY),
        SettingsGroup.SOURCES to listOf(
            K.AUTOSCAN, K.AUTOSCAN_EVERY, K.AUTOSCAN_WIFI, K.AUTOSCAN_CHARGING, K.AUTOSCAN_NIGHT, K.WISHLIST_AUTO, K.DIGEST
        ),
        SettingsGroup.DOWNLOADS to listOf(K.DOWNLOAD_PRESETS, K.DOWNLOAD_DIR, K.DEBRID, K.DEBRID_KEY),
        SettingsGroup.SCHEDULE to listOf(K.WIFI, K.CHARGING),
        SettingsGroup.FRONTENDS to listOf(K.ESDE, K.IISU, K.DAIJISHO, K.EMULATORS, K.AUTO_UNZIP, K.AUTO_M3U),
        SettingsGroup.CLOUD to listOf(K.CLOUD, K.ROMM, K.SAVE_SYNC, K.RETROACHIEVEMENTS),
        SettingsGroup.BACKUP to listOf(K.BACKUP_EXPORT, K.AUTO_BACKUP, K.AUTO_BACKUP_FOLDER, K.BACKUP_IMPORT),
        SettingsGroup.APP to listOf(K.UPDATE_CHECK, K.REPORT, K.ABOUT)
    )

    /** The rows behind each card's "Advanced" row, in order; a card without any has no such row. */
    val advanced: Map<SettingsGroup, List<String>> = linkedMapOf(
        SettingsGroup.LOOK to listOf(K.TV_MODE, K.SECOND_SCREEN, K.BOLD_FOCUS, K.COMPACT, K.ANIMATIONS, K.GLOW),
        SettingsGroup.LIBRARY to listOf(K.MAX_RESULTS, K.RECENT_MENU, K.MANUALS_WITH_GAME, K.MANUALS_CAP),
        SettingsGroup.DOWNLOADS to listOf(
            K.SEPARATE_CONSOLE, K.MIN_FREE, K.STORAGE, K.CONCURRENT, K.PER_SERVER, K.SPEED_LIMIT, K.SPEED_DAY_ONLY,
            K.PICK_BEST, K.AUTO_RETRY, K.RESUME, K.REQUEUE, K.METADATA_TIMEOUT
        ),
        SettingsGroup.SCHEDULE to listOf(K.LOW_BATTERY, K.BATTERY_LEVEL, K.HEAT, K.NIGHT, K.NIGHT_START, K.NIGHT_END),
        SettingsGroup.FRONTENDS to listOf(
            K.COCOON, K.FRONTEND_SHORTCUTS, K.FRONTEND_CHECK, K.ESDE_ARTWORK, K.PEGASUS_ARTWORK, K.RETROARCH_ARTWORK,
            K.FRONTEND_COVERS_TOOL, K.META_AUTO, K.META_TOOL, K.QUEUE_SUMMARY
        ),
        SettingsGroup.CLOUD to listOf(K.SHELF),
        SettingsGroup.BACKUP to listOf(K.CLOUD_BACKUP, K.NEW_HANDHELD, K.RECOVERY),
        SettingsGroup.APP to listOf(K.PRERELEASES, K.DIAGNOSTICS)
    )

    /** The rows of each card, in order: the everyday ones, then the advanced ones. */
    val groups: Map<SettingsGroup, List<String>> = SettingsGroup.entries.associateWith {
        everyday[it].orEmpty() + advanced[it].orEmpty()
    }

    private val advancedKeys: Set<String> = advanced.values.flatten().toSet()

    /** The level of [key]; a row this layout does not know counts as everyday. */
    fun levelOf(key: String?): SettingLevel = if (key != null && key in advancedKeys) SettingLevel.ADVANCED else SettingLevel.EVERYDAY

    /** Whether [key] sits behind its card's "Advanced" row. */
    fun isAdvanced(key: String?): Boolean = levelOf(key) == SettingLevel.ADVANCED

    /** A dependent row and the master row it hangs under. */
    val parents: Map<String, String> = mapOf(
        K.SPEED_DAY_ONLY to K.SPEED_LIMIT,
        K.BATTERY_LEVEL to K.LOW_BATTERY,
        K.NIGHT_START to K.NIGHT,
        K.NIGHT_END to K.NIGHT,
        K.DEBRID_KEY to K.DEBRID,
        K.AUTOSCAN_EVERY to K.AUTOSCAN,
        K.AUTOSCAN_WIFI to K.AUTOSCAN,
        K.AUTOSCAN_CHARGING to K.AUTOSCAN,
        K.AUTOSCAN_NIGHT to K.AUTOSCAN,
        K.AUTO_BACKUP_FOLDER to K.AUTO_BACKUP
    )

    /**
     * Dependent rows that stay visible while they do nothing, dimmed and with the reason as their
     * hint ([inactiveReason]), because their master does not explain them: hiding them would hide
     * the explanation too (the night hours are used by three switches in three cards).
     */
    val alwaysShownDependents: Set<String> = setOf(K.SPEED_DAY_ONLY, K.NIGHT_START, K.NIGHT_END)

    private val groupByKey: Map<String, SettingsGroup> =
        groups.flatMap { (group, keys) -> keys.map { it to group } }.toMap()

    private val placeByKey: Map<String, Int> =
        groups.values.flatMap { it }.withIndex().associate { (i, key) -> key to i }

    /** The card of [key]; null for a row this layout does not know (it keeps the card it asks for). */
    fun groupOf(key: String?): SettingsGroup? = key?.let { groupByKey[it] }

    /** The place of [key] over the whole screen; unknown rows go after the known ones of their card. */
    fun placeOf(key: String?): Int = key?.let { placeByKey[it] } ?: Int.MAX_VALUE

    /** The row a search result for [key] should jump to: the master of a hidden dependent row, else the row itself. */
    fun jumpTarget(key: String): String = if (key in alwaysShownDependents) key else parents[key] ?: key

    /**
     * The night hours (start / end) are used by three switches: downloads only at night, no speed
     * limit at night and scanning only at night. They are in effect while any of them is.
     */
    fun nightHoursInUse(m: SettingsMasters): Boolean =
        m.nightOnly || (m.speedLimitOn && m.speedLimitDayOnly) || (m.autoScanOn && m.autoScanNightOnly)

    /** Whether the row [key] shows with the masters in [m]; rows without a master, and the always shown dependents, always show. */
    fun isShown(key: String?, m: SettingsMasters): Boolean = when (key) {
        K.BATTERY_LEVEL -> m.lowBatteryOn
        K.DEBRID_KEY -> m.debridOn
        K.AUTOSCAN_EVERY, K.AUTOSCAN_WIFI, K.AUTOSCAN_CHARGING, K.AUTOSCAN_NIGHT -> m.autoScanOn
        K.AUTO_BACKUP_FOLDER -> m.autoBackupOn
        else -> true
    }

    /**
     * Why a visible row does nothing right now (a string resource the row shows as its hint, and
     * the row is dimmed), or null while it is in effect. Only [alwaysShownDependents] have one.
     */
    @StringRes
    fun inactiveReason(key: String?, m: SettingsMasters): Int? = when (key) {
        K.SPEED_DAY_ONLY -> if (m.speedLimitOn) null else R.string.settings44_limit_needed
        K.NIGHT_START, K.NIGHT_END -> if (nightHoursInUse(m)) null else R.string.settings44_night_unused
        else -> null
    }
}
