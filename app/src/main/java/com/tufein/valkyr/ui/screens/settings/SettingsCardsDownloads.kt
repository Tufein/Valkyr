package com.tufein.valkyr.ui.screens.settings

import android.text.format.DateUtils
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.NavChevron
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.pillColors
import com.tufein.valkyr.ui.navigation.NavRoutes
import com.tufein.valkyr.ui.screens.settings.components.maskedSecret
import com.tufein.valkyr.util.Constants
import com.tufein.valkyr.util.DownloadPolicy
import com.tufein.valkyr.util.SettingKeys
import com.tufein.valkyr.util.SettingsGroup
import com.tufein.valkyr.util.SettingsLayout

/** The rows of the Sources card (see [SettingsLayout.groups] for their order). */
internal fun SettingsEnv.sourcesCardRows(): List<SettingsRow> = listOf(
    SettingsRow(key = SettingKeys.AUTOSCAN) {
        SettingRow(
            icon = R.drawable.ic_sync,
            title = stringResource(R.string.settings_autoscan),
            hint = if (autoScan.last > 0) stringResource(R.string.settings_autoscan_last, DateUtils.getRelativeTimeSpanString(autoScan.last).toString())
                   else stringResource(R.string.settings_autoscan_hint),
            onClick = { extra.setAutoScan(context, !autoScan.on) },
            onAdjust = { extra.setAutoScan(context, it > 0) }
        ) { ThemedSwitch(autoScan.on) { extra.setAutoScan(context, it) } }
    },
    SettingsRow(key = SettingKeys.AUTOSCAN_EVERY) {
        SettingRow(
            icon = R.drawable.ic_timer,
            title = stringResource(R.string.settings_autoscan_every), hint = null,
            onClick = { extra.shiftAutoScanHours(context, 1) }, onAdjust = { extra.shiftAutoScanHours(context, it) }
        ) {
            SettingsStepper(
                if (autoScan.hours % 24 == 0) pluralStringResource(R.plurals.settings_days, autoScan.hours / 24, autoScan.hours / 24)
                else stringResource(R.string.hours_short, autoScan.hours),
                onDecrement = { extra.shiftAutoScanHours(context, -1) }, onIncrement = { extra.shiftAutoScanHours(context, 1) }
            )
        }
    },
    SettingsRow(key = SettingKeys.AUTOSCAN_WIFI) {
        SettingRow(
            icon = R.drawable.ic_wifi,
            title = stringResource(R.string.settings43_autoscan_wifi), hint = null,
            onClick = { extra.setAutoScanWifi(context, !autoScan.wifiOnly) }, onAdjust = { extra.setAutoScanWifi(context, it > 0) }
        ) { ThemedSwitch(autoScan.wifiOnly) { extra.setAutoScanWifi(context, it) } }
    },
    SettingsRow(key = SettingKeys.AUTOSCAN_CHARGING) {
        SettingRow(
            icon = R.drawable.ic_charging,
            title = stringResource(R.string.settings43_autoscan_charging), hint = null,
            onClick = { extra.setAutoScanCharging(context, !autoScan.charging) }, onAdjust = { extra.setAutoScanCharging(context, it > 0) }
        ) { ThemedSwitch(autoScan.charging) { extra.setAutoScanCharging(context, it) } }
    },
    SettingsRow(key = SettingKeys.AUTOSCAN_NIGHT) {
        SettingRow(
            icon = R.drawable.ic_night,
            title = stringResource(R.string.settings_autoscan_night),
            hint = stringResource(R.string.settings43_autoscan_night_hint, DownloadPolicy.formatMinutes(schedule.nightStart), DownloadPolicy.formatMinutes(schedule.nightEnd)),
            onClick = { extra.setAutoScanNight(context, !autoScan.nightOnly) }, onAdjust = { extra.setAutoScanNight(context, it > 0) }
        ) { ThemedSwitch(autoScan.nightOnly) { extra.setAutoScanNight(context, it) } }
    },
    SettingsRow(key = SettingKeys.WISHLIST_AUTO) {
        SettingRow(
            icon = R.drawable.ic_wishlist,
            title = stringResource(R.string.settings_wishlist_auto),
            hint = stringResource(R.string.settings_wishlist_auto_hint),
            onClick = { extra.setWishlistAuto(context, !afterDownload.wishlistAuto) },
            onAdjust = { extra.setWishlistAuto(context, it > 0) }
        ) { ThemedSwitch(afterDownload.wishlistAuto) { extra.setWishlistAuto(context, it) } }
    },
    SettingsRow(key = SettingKeys.DIGEST) {
        SettingRow(
            icon = R.drawable.ic_notifications,
            title = stringResource(R.string.upg7_digest_setting),
            hint = stringResource(R.string.upg7_digest_setting_hint),
            onClick = { setDigest(!digestOn) },
            onAdjust = { setDigest(it > 0) }
        ) { ThemedSwitch(digestOn) { setDigest(it) } }
    }
)

/** The rows of the Storage card (see [SettingsLayout.groups] for their order). */
internal fun SettingsEnv.storageCardRows(): List<SettingsRow> = listOf(
    SettingsRow(key = SettingKeys.DOWNLOAD_DIR) {
        SettingRow(
            icon = R.drawable.ic_folder_open,
            title = stringResource(R.string.settings43_download_folder),
            hint = readableFolder(ui.downloadDirectory) ?: stringResource(R.string.settings43_download_folder_unset),
            onClick = { launcher.launch(null) }
        ) {
            ActionPill(stringResource(R.string.settings_change), { launcher.launch(null) }, icon = R.drawable.ic_folder_open)
        }
    },
    SettingsRow(key = SettingKeys.SEPARATE_CONSOLE) {
        SettingRow(
            icon = R.drawable.ic_account_tree,
            title = stringResource(R.string.settings43_separate),
            hint = stringResource(R.string.settings43_separate_hint),
            onClick = { viewModel.onSeparateByConsoleChanged(context, !ui.separateByConsole) },
            onAdjust = { viewModel.onSeparateByConsoleChanged(context, it > 0) }
        ) {
            ThemedSwitch(ui.separateByConsole) { viewModel.onSeparateByConsoleChanged(context, it) }
        }
    },
    SettingsRow(key = SettingKeys.MIN_FREE) {
        SettingRow(
            icon = R.drawable.ic_storage,
            title = stringResource(R.string.settings_min_free),
            hint = stringResource(R.string.settings_min_free_hint),
            onClick = { extra.shiftMinFree(context, 1) },
            onAdjust = { extra.shiftMinFree(context, it) }
        ) {
            SettingsStepper(
                if (queue.minFreeGb == 0) stringResource(R.string.settings_off) else "${queue.minFreeGb} GB",
                onDecrement = { extra.shiftMinFree(context, -1) }, onIncrement = { extra.shiftMinFree(context, 1) }
            )
        }
    },
    SettingsRow(key = SettingKeys.STORAGE) {
        SettingRow(
            icon = R.drawable.ic_storage,
            title = stringResource(R.string.nav_storage),
            hint = stringResource(R.string.tools_storage_hint),
            onClick = { navController.navigate(NavRoutes.Storage.route) },
        ) { NavChevron() }
    }
)

/** The rows of the Downloads card (see [SettingsLayout.groups] for their order). */
internal fun SettingsEnv.downloadsCardRows(): List<SettingsRow> = listOf(
    SettingsRow(key = SettingKeys.DOWNLOAD_PRESETS) {
        SettingRow(
            icon = R.drawable.ic_schedule,
            title = stringResource(R.string.presets26_title),
            hint = stringResource(R.string.presets26_settings_hint),
            onClick = { dialogs.showDownloadPresets = true }
        ) {
            ActionPill(stringResource(R.string.settings_change), { dialogs.showDownloadPresets = true }, icon = R.drawable.ic_edit)
        }
    },
    SettingsRow(key = SettingKeys.CONCURRENT) {
        SettingRow(
            icon = R.drawable.ic_downloading,
            title = stringResource(R.string.settings_concurrent_label),
            hint = stringResource(R.string.settings43_concurrent_hint, Constants.DEFAULT_CONCURRENT_DOWNLOADS),
            onClick = { adjustConcurrent(1) },
            onAdjust = { adjustConcurrent(it) }
        ) {
            SettingsStepper(ui.concurrentDownloads.toString(), onDecrement = { adjustConcurrent(-1) }, onIncrement = { adjustConcurrent(1) })
        }
    },
    SettingsRow(key = SettingKeys.PER_SERVER) {
        SettingRow(
            icon = R.drawable.ic_hub,
            title = stringResource(R.string.settings43_per_server),
            hint = stringResource(R.string.settings_per_server_hint),
            onClick = { extra.shiftPerServer(context, 1) },
            onAdjust = { extra.shiftPerServer(context, it) }
        ) {
            SettingsStepper(
                if (queue.perServer == 0) stringResource(R.string.settings_off) else "${queue.perServer}",
                onDecrement = { extra.shiftPerServer(context, -1) }, onIncrement = { extra.shiftPerServer(context, 1) }
            )
        }
    },
    SettingsRow(key = SettingKeys.SPEED_LIMIT) {
        SettingRow(
            icon = R.drawable.ic_speed,
            title = stringResource(R.string.settings_limit_label),
            hint = stringResource(R.string.settings43_limit_hint),
            onClick = { adjustLimit(1) },
            onAdjust = { adjustLimit(it) }
        ) {
            SettingsStepper(
                if (limitKb == 0) stringResource(R.string.settings_unlimited) else "$limitKb KB/s",
                onDecrement = { adjustLimit(-1) },
                onIncrement = { adjustLimit(1) }
            )
        }
    },
    SettingsRow(key = SettingKeys.SPEED_DAY_ONLY) {
        val reason = SettingsLayout.inactiveReason(SettingKeys.SPEED_DAY_ONLY, masters)
        SettingRow(
            icon = R.drawable.ic_light_mode,
            title = stringResource(R.string.settings_limit_day_only),
            hint = reason?.let { stringResource(it) }
                ?: stringResource(R.string.settings43_limit_day_only_hint, DownloadPolicy.formatMinutes(schedule.nightStart), DownloadPolicy.formatMinutes(schedule.nightEnd)),
            modifier = if (reason != null) Modifier.alpha(INACTIVE_ROW_ALPHA) else Modifier,
            onClick = { extra.setSpeedLimitDayOnly(context, !schedule.speedLimitDayOnly) },
            onAdjust = { extra.setSpeedLimitDayOnly(context, it > 0) }
        ) { ThemedSwitch(schedule.speedLimitDayOnly) { extra.setSpeedLimitDayOnly(context, it) } }
    },
    SettingsRow(key = SettingKeys.RESUME) {
        SettingRow(
            icon = R.drawable.ic_play_arrow,
            title = stringResource(R.string.settings_resume),
            hint = stringResource(R.string.settings_resume_hint),
            onClick = { extra.setResume(context, !queue.resume) },
            onAdjust = { extra.setResume(context, it > 0) }
        ) { ThemedSwitch(queue.resume) { extra.setResume(context, it) } }
    },
    SettingsRow(key = SettingKeys.REQUEUE) {
        SettingRow(
            icon = R.drawable.ic_restart_alt,
            title = stringResource(R.string.settings_requeue),
            hint = stringResource(R.string.settings_requeue_hint),
            onClick = { extra.setRequeue(context, !queue.requeue) },
            onAdjust = { extra.setRequeue(context, it > 0) }
        ) { ThemedSwitch(queue.requeue) { extra.setRequeue(context, it) } }
    },
    SettingsRow(key = SettingKeys.AUTO_RETRY) {
        SettingRow(
            icon = R.drawable.ic_retry,
            title = stringResource(R.string.settings_auto_retry),
            hint = stringResource(R.string.settings_auto_retry_hint),
            onClick = { extra.setAutoRetry(context, !queue.autoRetry) },
            onAdjust = { extra.setAutoRetry(context, it > 0) }
        ) { ThemedSwitch(queue.autoRetry) { extra.setAutoRetry(context, it) } }
    },
    SettingsRow(key = SettingKeys.PICK_BEST) {
        SettingRow(
            icon = R.drawable.ic_swap_horiz,
            title = stringResource(R.string.src75_pick_best_title),
            hint = stringResource(R.string.src75_pick_best_hint),
            onClick = { extra.setPickBest(context, !pickBest) },
            onAdjust = { extra.setPickBest(context, it > 0) }
        ) { ThemedSwitch(pickBest) { extra.setPickBest(context, it) } }
    }
)

/** The rows of the Schedule card (see [SettingsLayout.groups] for their order). */
internal fun SettingsEnv.scheduleCardRows(): List<SettingsRow> = listOf(
    SettingsRow(key = SettingKeys.WIFI) {
        SettingRow(
            icon = R.drawable.ic_wifi,
            title = stringResource(R.string.settings_dl_wifi),
            hint = stringResource(R.string.settings_dl_wifi_hint),
            onClick = { extra.setWifiOnly(context, !schedule.wifiOnly) },
            onAdjust = { extra.setWifiOnly(context, it > 0) }
        ) { ThemedSwitch(schedule.wifiOnly) { extra.setWifiOnly(context, it) } }
    },
    SettingsRow(key = SettingKeys.CHARGING) {
        SettingRow(
            icon = R.drawable.ic_charging,
            title = stringResource(R.string.settings_dl_charging),
            hint = stringResource(R.string.settings_dl_charging_hint),
            onClick = { extra.setChargingOnly(context, !schedule.chargingOnly) },
            onAdjust = { extra.setChargingOnly(context, it > 0) }
        ) { ThemedSwitch(schedule.chargingOnly) { extra.setChargingOnly(context, it) } }
    },
    SettingsRow(key = SettingKeys.LOW_BATTERY) {
        SettingRow(
            icon = R.drawable.ic_battery,
            title = stringResource(R.string.settings43_low_battery),
            hint = stringResource(R.string.power75_battery_hint, power.batteryPercent, power.batteryPercent + 5),
            onClick = { extra.setLowBattery(context, !power.lowBatteryOn) },
            onAdjust = { extra.setLowBattery(context, it > 0) }
        ) { ThemedSwitch(power.lowBatteryOn) { extra.setLowBattery(context, it) } }
    },
    SettingsRow(key = SettingKeys.BATTERY_LEVEL) {
        SettingRow(
            icon = R.drawable.ic_battery,
            title = stringResource(R.string.power75_battery_level_title),
            hint = stringResource(R.string.power75_battery_level_hint),
            onClick = { extra.shiftBatteryPercent(context, 1) },
            onAdjust = { extra.shiftBatteryPercent(context, it) }
        ) {
            SettingsStepper(
                stringResource(R.string.power75_percent, power.batteryPercent),
                onDecrement = { extra.shiftBatteryPercent(context, -1) },
                onIncrement = { extra.shiftBatteryPercent(context, 1) }
            )
        }
    },
    SettingsRow(key = SettingKeys.HEAT) {
        SettingRow(
            icon = R.drawable.ic_thermostat,
            title = stringResource(R.string.power75_heat_title),
            hint = stringResource(R.string.power75_heat_hint),
            onClick = { extra.setHeat(context, !power.heatOn) },
            onAdjust = { extra.setHeat(context, it > 0) }
        ) { ThemedSwitch(power.heatOn) { extra.setHeat(context, it) } }
    },
    SettingsRow(key = SettingKeys.NIGHT) {
        SettingRow(
            icon = R.drawable.ic_night,
            title = stringResource(R.string.settings_dl_night),
            hint = stringResource(R.string.settings_dl_night_hint, DownloadPolicy.formatMinutes(schedule.nightStart), DownloadPolicy.formatMinutes(schedule.nightEnd)),
            onClick = { extra.setNightOnly(context, !schedule.nightOnly) },
            onAdjust = { extra.setNightOnly(context, it > 0) }
        ) { ThemedSwitch(schedule.nightOnly) { extra.setNightOnly(context, it) } }
    },
    SettingsRow(key = SettingKeys.NIGHT_START) {
        val reason = SettingsLayout.inactiveReason(SettingKeys.NIGHT_START, masters)
        SettingRow(
            icon = R.drawable.ic_bedtime,
            title = stringResource(R.string.settings_dl_night_start),
            hint = reason?.let { stringResource(it) },
            modifier = if (reason != null) Modifier.alpha(INACTIVE_ROW_ALPHA) else Modifier,
            onClick = { shiftNightStart(1) }, onAdjust = { shiftNightStart(it) }
        ) { SettingsStepper(DownloadPolicy.formatMinutes(schedule.nightStart), onDecrement = { shiftNightStart(-1) }, onIncrement = { shiftNightStart(1) }) }
    },
    SettingsRow(key = SettingKeys.NIGHT_END) {
        val reason = SettingsLayout.inactiveReason(SettingKeys.NIGHT_END, masters)
        SettingRow(
            icon = R.drawable.ic_wb_twilight,
            title = stringResource(R.string.settings_dl_night_end),
            hint = reason?.let { stringResource(it) },
            modifier = if (reason != null) Modifier.alpha(INACTIVE_ROW_ALPHA) else Modifier,
            onClick = { shiftNightEnd(1) }, onAdjust = { shiftNightEnd(it) }
        ) { SettingsStepper(DownloadPolicy.formatMinutes(schedule.nightEnd), onDecrement = { shiftNightEnd(-1) }, onIncrement = { shiftNightEnd(1) }) }
    }
)

/** The rows of the After download card (see [SettingsLayout.groups] for their order). */
internal fun SettingsEnv.afterCardRows(): List<SettingsRow> = listOf(
    SettingsRow(key = SettingKeys.QUEUE_SUMMARY) {
        SettingRow(
            icon = R.drawable.ic_notifications,
            title = stringResource(R.string.settings_queue_summary),
            hint = stringResource(R.string.settings_queue_summary_hint),
            onClick = { extra.setQueueSummary(context, !queue.queueSummary) },
            onAdjust = { extra.setQueueSummary(context, it > 0) }
        ) { ThemedSwitch(queue.queueSummary) { extra.setQueueSummary(context, it) } }
    },
    SettingsRow(key = SettingKeys.AUTO_UNZIP) {
        SettingRow(
            icon = R.drawable.ic_extract,
            title = stringResource(R.string.settings_auto_unzip),
            hint = stringResource(R.string.settings_auto_unzip_hint),
            onClick = { viewModel.onAutoUnzipChanged(context, !ui.autoUnzip) },
            onAdjust = { viewModel.onAutoUnzipChanged(context, it > 0) }
        ) {
            ThemedSwitch(ui.autoUnzip) { viewModel.onAutoUnzipChanged(context, it) }
        }
    },
    SettingsRow(key = SettingKeys.AUTO_M3U) {
        SettingRow(
            icon = R.drawable.ic_playlist_add,
            title = stringResource(R.string.settings_auto_m3u),
            hint = stringResource(R.string.settings_auto_m3u_hint),
            onClick = { extra.setAutoM3u(context, !afterDownload.autoM3u) },
            onAdjust = { extra.setAutoM3u(context, it > 0) }
        ) { ThemedSwitch(afterDownload.autoM3u) { extra.setAutoM3u(context, it) } }
    },
    SettingsRow(key = SettingKeys.ESDE_ARTWORK) {
        // On without an ES-DE folder writes nothing (PostDownloadService skips it): say why.
        val esdeMissing = afterDownload.esdeArtwork && ui.esdeDirectory.isBlank()
        SettingRow(
            icon = R.drawable.ic_photos,
            title = stringResource(R.string.settings_esde_artwork),
            hint = stringResource(if (esdeMissing) R.string.settings43_esde_needs_folder else R.string.settings_esde_artwork_hint),
            hintColor = if (esdeMissing) pillColors(PillTone.Warning).second else null,
            onClick = { extra.setEsdeArtwork(context, !afterDownload.esdeArtwork) },
            onAdjust = { extra.setEsdeArtwork(context, it > 0) }
        ) { ThemedSwitch(afterDownload.esdeArtwork) { extra.setEsdeArtwork(context, it) } }
    },
    SettingsRow(key = SettingKeys.PEGASUS_ARTWORK) {
        SettingRow(
            icon = R.drawable.ic_photo_album,
            title = stringResource(R.string.settings_pegasus_artwork),
            hint = stringResource(R.string.settings_pegasus_artwork_hint),
            onClick = { extra.setPegasusArtwork(context, !afterDownload.pegasusArtwork) },
            onAdjust = { extra.setPegasusArtwork(context, it > 0) }
        ) { ThemedSwitch(afterDownload.pegasusArtwork) { extra.setPegasusArtwork(context, it) } }
    },
    SettingsRow(key = SettingKeys.RETROARCH_ARTWORK) {
        SettingRow(
            icon = R.drawable.ic_wallpaper,
            title = stringResource(R.string.settings_retroarch_artwork),
            hint = readableFolder(afterDownload.retroArchThumbnailsDir) ?: stringResource(R.string.settings_retroarch_artwork_hint),
            onClick = { retroArchThumbsLauncher.launch(null) }
        ) {
            if (afterDownload.retroArchThumbnailsDir.isNotBlank()) PillButton(stringResource(R.string.settings_retroarch_artwork_off)) { extra.setRetroArchThumbnailsDir(context, "") }
            ActionPill(stringResource(R.string.settings_change), { retroArchThumbsLauncher.launch(null) }, icon = R.drawable.ic_folder_open)
        }
    },
    SettingsRow(key = SettingKeys.FRONTEND_COVERS_TOOL) {
        SettingRow(
            icon = R.drawable.ic_image,
            title = stringResource(R.string.covers32_title),
            hint = stringResource(R.string.covers32_hint),
            onClick = { navController.navigate(NavRoutes.FrontendCovers.route) },
        ) { NavChevron() }
    },
    SettingsRow(key = SettingKeys.META_AUTO) {
        SettingRow(
            icon = R.drawable.ic_description,
            title = stringResource(R.string.settings43_meta_auto),
            hint = stringResource(R.string.settings43_meta_auto_hint),
            onClick = { extra.setMetaAuto(context, !metaAuto) },
            onAdjust = { extra.setMetaAuto(context, it > 0) }
        ) { ThemedSwitch(metaAuto) { extra.setMetaAuto(context, it) } }
    },
    SettingsRow(key = SettingKeys.META_TOOL) {
        SettingRow(
            icon = R.drawable.ic_description,
            title = stringResource(R.string.meta7_title),
            hint = stringResource(R.string.meta7_hint),
            onClick = { navController.navigate(NavRoutes.FrontendMetadata.route) },
        ) { NavChevron() }
    }
)

/** The rows of the Torrents and debrid card (see [SettingsLayout.groups] for their order). */
internal fun SettingsEnv.torrentsCardRows(): List<SettingsRow> = listOf(
    SettingsRow(key = SettingKeys.DEBRID) {
        SettingRow(
            icon = R.drawable.ic_bolt,
            title = stringResource(R.string.settings_debrid),
            hint = stringResource(R.string.settings_debrid_hint),
            onClick = { cycleDebrid(1) },
            onAdjust = { cycleDebrid(it) }
        ) {
            SettingsStepper(debrid.label, onDecrement = { cycleDebrid(-1) }, onIncrement = { cycleDebrid(1) })
        }
    },
    SettingsRow(key = SettingKeys.DEBRID_KEY) {
        SettingRow(
            icon = R.drawable.ic_key,
            title = debridKeyTitle,
            hint = maskedSecret(debridKey),
            onClick = { dialogs.showDebridKeyDialog = true }
        ) {
            ActionPill(stringResource(R.string.settings_change), { dialogs.showDebridKeyDialog = true }, icon = R.drawable.ic_key)
        }
    },
    SettingsRow(key = SettingKeys.METADATA_TIMEOUT) {
        SettingRow(
            icon = R.drawable.ic_hourglass,
            title = stringResource(R.string.settings_metadata_timeout),
            hint = stringResource(R.string.settings_metadata_timeout_hint),
            onClick = { adjustMetadataTimeout(1) },
            onAdjust = { adjustMetadataTimeout(it) }
        ) {
            SettingsStepper(stringResource(R.string.seconds_short, ui.metadataTimeoutSeconds), onDecrement = { adjustMetadataTimeout(-1) }, onIncrement = { adjustMetadataTimeout(1) })
        }
    },
    // A link row to its own screen, like Romm / Save sync: not a layout row (the card is full).
    SettingsRow(section = SettingsGroup.DOWNLOADS, key = "torrent_trackers") {
        SettingRow(
            icon = R.drawable.ic_hub,
            title = stringResource(R.string.src91_trackers_title),
            hint = stringResource(R.string.src91_trackers_hint),
            onClick = { navController.navigate(NavRoutes.TorrentTrackers.route) }
        ) { NavChevron() }
    }
)
