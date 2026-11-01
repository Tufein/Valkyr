package com.tufein.valkyr.util

/** The types used by all readers of user_settings, including profile-scoped and dynamic keys. */
object SettingSchema {
    val types: Map<String, String> = buildMap {
        fun keys(type: String, names: String) = names.split(Regex("\\s+")).filter(String::isNotEmpty).forEach { put(it, type) }
        keys("b", """
            auto_backup auto_m3u auto_retry_failed auto_scan auto_scan_charging auto_scan_night auto_scan_wifi
            auto_unzip bold_focus csave_continue_playing dav_last_shared_sent dav_last_sync_sent download_charging_only
            download_night_only download_wifi_only esde_artwork look_animations look_compact_lists look_glow look_list_covers
            meta7_auto meta7_esde meta7_pegasus offline_collections_wifi onboarding_done pegasus_artwork pick_best_source
            power_heat power_low_battery queue_held queue_summary requeue_after_restart resume_downloads romm5_favourites_two_way
            romm_enabled romm_auto_upload romm_mark_games save_sync_auto save_sync_background save_sync_bg_charging save_sync_bg_wifi_only
            save_sync_deletions second_screen separate_by_console smart_storage_enabled smart_storage_weekly speed_limit_day_only
            swap_face_buttons torbox_enabled update_pre_releases upg7_digest_enabled wishlist_auto_download
        """)
        keys("i", """
            auto_scan_hours concurrent_downloads dav_last_shared_added dav_last_shared_removed dav_last_sync_added
            dav_last_sync_removed dav_sync_held_back download_night_end download_night_start last_seen_version_code tour_seen_version
            max_search_results metadata_timeout_s min_free_gb offline_collections_cap offline_collections_reserve_gb
            per_server_limit power_battery_percent save_sync_bg_interval_h smart_storage_recent_days text_size_percent
        """)
        keys("l", """
            auto_backup_last auto_scan_last dav_last_backup_at dav_last_backup_bytes dav_last_backup_error_at dav_last_shared_at
            dav_last_shared_error_at dav_last_sync_at dav_last_sync_error_at dav_last_test_at upg7_digest_last_sent
        """)
        keys("s", """
            accent_color active_profile auto_backup_dir bios_dir dav_device_id dav_device_name dav_last_backup_error
            dav_last_backup_name dav_last_shared_error dav_last_sync_error dav_last_test_error dav_last_test_key dav_passphrase
            debrid_provider download_directory esde_directory gamepad_layout iisu_directory library_views offline_collections_last
            profile_pin_hash profiles ra_api_key ra_user realdebrid_api_key recent_searches retroarch_thumbnails_dir romm_token
            romm_trust_fingerprint romm_url save_sync_emulator_folders save_sync_saves_dir save_sync_states_dir search_all_recent
            smart_collection_rules smart_storage_last smart_storage_sd_uri theme_mode torbox_api_key tv8_mode
        """)
        keys("ss", """
            coll6_goal_consoles console_download_directories console_scanned_at favorite_languages offline_collections_fetched
            offline_collections_ids offline_collections_quotas romm_platform_map smart_storage_records upg7_better_ignored wishlist_romm_announced
        """)
        put("limit_speed", "f")
        putAll(CloudSettingKeys.TYPES)
        put(VersionPreferences.PINNED_KEY, "s")
        put(VersionPreferences.OVERRIDES_KEY, "s")
        put(DownloadPresets.KEY, "s")
        putAll(FrontendCovers.SETTING_TYPES)
        putAll(GameShortcuts.SETTING_TYPES)
        putAll(RetroArchPlaylists.SETTING_TYPES)
        putAll(NewHandheldPlan.SETTING_TYPES)
        putAll(TargetFolderLayouts.SETTING_TYPES)
        putAll(CoverSources.SETTING_TYPES)
        putAll(ScreenScraper.SETTING_TYPES)
        putAll(CoverApis.SETTING_TYPES)
        putAll(LibraryBrowse.SETTING_TYPES)
        putAll(VariantGroups.SETTING_TYPES)
        putAll(SaveMerge.SETTING_TYPES)
        putAll(SyncthingFolderSettings.TYPES)
        putAll(RommRemoteInstall.SETTING_TYPES)
        putAll(CoverArtwork.SETTING_TYPES)
    }

    fun localName(name: String): String =
        if (name.startsWith("personal:")) name.substringAfter(':').substringAfter(':') else name

    /** Unknown keys may belong to a newer version; they can still round-trip through backups. */
    fun expectedType(name: String): String? = localName(name).let { local ->
        if (local.startsWith("fixed_version:")) "s" else types[local]
    }

    fun compatible(name: String, value: Any): Boolean {
        val type = when (value) {
            is Boolean -> "b"
            is Int -> "i"
            is Long -> "l"
            is Float -> "f"
            is Double -> "d"
            is String -> "s"
            is Set<*> -> if (value.all { it is String }) "ss" else return false
            else -> return false
        }
        return expectedType(name)?.let { it == type } ?: true
    }

    fun sanitizeNumber(name: String, value: Any): Any = when (val local = localName(name)) {
        "concurrent_downloads" -> (value as Int).coerceIn(1, 10)
        "metadata_timeout_s" -> (value as Int).coerceIn(TorrentConstants.MIN_METADATA_TIMEOUT_S, TorrentConstants.MAX_METADATA_TIMEOUT_S)
        "max_search_results", "min_free_gb", "last_seen_version_code", "tour_seen_version",
        "dav_last_shared_added", "dav_last_shared_removed", "dav_last_sync_added", "dav_last_sync_removed", "dav_sync_held_back" -> (value as Int).coerceAtLeast(0)
        "save_sync_bg_interval_h" -> (value as Int).coerceIn(1, 24)
        SaveMerge.KEY_BACKUP_KEEP -> SaveBackups.clampKeep(value as Int)
        "download_night_start", "download_night_end" -> (value as Int).coerceIn(0, 1439)
        "auto_scan_hours" -> (value as Int).coerceIn(1, 168)
        "per_server_limit" -> (value as Int).coerceIn(0, 10)
        "offline_collections_cap" -> OfflineCollections.clampCap(value as Int)
        "offline_collections_reserve_gb" -> (value as Int).coerceIn(0, 100)
        "smart_storage_recent_days" -> SmartStorage.clampDays(value as Int)
        "power_battery_percent" -> PowerRules.clampPercent(value as Int)
        "text_size_percent" -> (value as Int).coerceIn(TextSize.STEPS.first(), TextSize.STEPS.last())
        "limit_speed" -> (value as Float).let { if (it.isNaN() || it <= 0f) Float.POSITIVE_INFINITY else it }
        else -> if (types[local] == "l") (value as Long).coerceAtLeast(0L) else value
    }
}
