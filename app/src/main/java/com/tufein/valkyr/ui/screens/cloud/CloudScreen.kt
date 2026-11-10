package com.tufein.valkyr.ui.screens.cloud

import android.content.res.Configuration
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.tufein.valkyr.R
import com.tufein.valkyr.data.model.DebridProvider
import com.tufein.valkyr.data.service.CloudMessages
import com.tufein.valkyr.data.service.DavStatus
import com.tufein.valkyr.data.service.RommErrorKind
import com.tufein.valkyr.data.service.RommServerInfo
import com.tufein.valkyr.data.service.SaveSyncState
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.IconTile
import com.tufein.valkyr.ui.components.MeterBar
import com.tufein.valkyr.ui.components.Panel
import com.tufein.valkyr.ui.components.PanelTone
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.ScreenTitle
import com.tufein.valkyr.ui.components.StatTile
import com.tufein.valkyr.ui.components.formatBytes
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.ui.navigation.NavRoutes
import com.tufein.valkyr.ui.screens.cloud.sections.RaUserSummaryCard
import com.tufein.valkyr.ui.screens.cloud.sections.rommErrorText
import com.tufein.valkyr.ui.theme.tabular
import com.tufein.valkyr.util.CloudActivity
import com.tufein.valkyr.util.CloudStatus
import com.tufein.valkyr.util.ProgressText
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import java.text.NumberFormat

/**
 * Settings → Cloud (5.0): every cloud feature on one page — the RomM server, save sync, achievements,
 * Debrid — each as a card with its state, the numbers worth seeing and its most common action. Two
 * columns when the screen is wide, one in portrait; every action is reachable with the D-pad.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CloudScreen(navController: NavController, viewModel: CloudViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { viewModel.onOpen() }
    val romm by viewModel.rommInfo.collectAsStateWithLifecycle()
    val saves by viewModel.saveSyncState.collectAsStateWithLifecycle()
    val saveCard by viewModel.saveSyncCard.collectAsStateWithLifecycle()
    val debrid by viewModel.debrid.collectAsStateWithLifecycle()
    val overall by viewModel.overall.collectAsStateWithLifecycle()
    val dav by viewModel.dav.collectAsStateWithLifecycle()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val firstFocus = rememberInitialFocus()
    val open: (NavRoutes) -> Unit = { route -> navController.navigate(route.route) { launchSingleTop = true } }

    val cards: List<@Composable (Modifier) -> Unit> = buildList {
        add { m -> RommCard(romm, m, firstFocus, onRefresh = viewModel::refreshRomm, onOpen = { open(NavRoutes.Romm) }) }
        add { m ->
            SaveSyncCard(
                state = saves,
                configured = saveCard.configured,
                rommConfigured = saveCard.rommConfigured,
                modifier = m,
                onSync = viewModel::syncSaves,
                onOpen = { open(if (saveCard.rommConfigured) NavRoutes.SaveSync else NavRoutes.Romm) }
            )
        }
        if (dav.configured) {
            add { m -> BackupCard(dav, m, onBackup = viewModel::backupNow, onOpen = { open(NavRoutes.CloudBackup) }) }
            add { m -> DeviceSyncCard(dav, m, onSync = viewModel::syncDevices, onOpen = { open(NavRoutes.CloudBackup) }) }
        } else {
            add { m -> DavPitchCard(m) { open(NavRoutes.CloudBackup) } }
        }
        add { m ->
            RaUserSummaryCard(
                modifier = m,
                title = stringResource(R.string.nav_ra),
                compact = true,
                onOpen = { open(NavRoutes.RetroAchievements) },
                onSetUp = { open(NavRoutes.RetroAchievements) }
            )
        }
        add { m -> DebridCard(provider = debrid.provider, hasKey = debrid.hasKey, modifier = m, onOpen = { open(NavRoutes.Settings) }) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ScreenTitle(
            text = stringResource(R.string.nav_cloud),
            subtitle = stringResource(R.string.hub_subtitle),
            icon = R.drawable.ic_cloud,
            trailing = { OverallPill(overall) }
        )
        if (landscape) {
            cards.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    pair.forEach { card -> card(Modifier.weight(1f).fillMaxHeight()) }
                    if (pair.size == 1) Column(Modifier.weight(1f)) {}
                }
            }
        } else {
            cards.forEach { card -> card(Modifier.fillMaxWidth()) }
        }
    }
}

/** The whole picture in one pill: nothing set up, in sync, syncing, or how many things need you. */
@Composable
fun OverallPill(status: CloudStatus) {
    when (status.activity) {
        CloudActivity.HIDDEN -> Pill(stringResource(R.string.hub_overall_none), icon = R.drawable.ic_cloud_off)
        CloudActivity.IDLE -> Pill(stringResource(R.string.hub_overall_ok), tone = PillTone.Success, icon = R.drawable.ic_cloud_done)
        CloudActivity.SYNCING -> Pill(stringResource(R.string.hub_overall_syncing), tone = PillTone.Accent, icon = R.drawable.ic_cloud_sync)
        CloudActivity.ATTENTION -> Pill(
            pluralStringResource(R.plurals.hub_overall_attention, status.attention.coerceAtLeast(1), status.attention.coerceAtLeast(1)),
            tone = PillTone.Warning,
            icon = R.drawable.ic_warning
        )
    }
}

/** The shape every cloud card shares: tile and title, pills, the body, then the action pills. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CloudCard(
    icon: Int,
    title: String,
    modifier: Modifier = Modifier,
    tone: PanelTone = PanelTone.Normal,
    pills: @Composable () -> Unit = {},
    actions: @Composable () -> Unit = {},
    body: @Composable ColumnScope.() -> Unit
) {
    Panel(
        modifier = modifier,
        tone = tone,
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconTile(icon, size = 38.dp)
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { pills() }
        body()
        // Pushes the actions to the card's bottom edge when a neighbour is taller.
        Spacer(Modifier.weight(1f))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

@Composable
private fun Pitch(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun FactLine(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall.tabular(), color = color)
}

// ---- RomM ---------------------------------------------------------------------------------------

@Composable
fun RommCard(
    info: RommServerInfo,
    modifier: Modifier,
    firstFocus: FocusRequester,
    onRefresh: () -> Unit,
    onOpen: () -> Unit
) {
    CloudCard(
        icon = R.drawable.ic_server,
        title = stringResource(R.string.nav_romm),
        modifier = modifier,
        pills = {
            when {
                !info.configured -> Pill(stringResource(R.string.hub_not_set_up), icon = R.drawable.ic_cloud_off)
                info.refreshing && info.checkedAt == 0L -> Pill(stringResource(R.string.hub_checking), icon = R.drawable.ic_sync)
                info.reachable && info.errorKind == null -> Pill(stringResource(R.string.hub_connected), tone = PillTone.Success, icon = R.drawable.ic_check_circle)
                info.reachable -> Pill(stringResource(rommErrorText(info.errorKind)), tone = PillTone.Warning, icon = R.drawable.ic_warning)
                else -> Pill(stringResource(R.string.hub_unreachable), tone = PillTone.Danger, icon = R.drawable.ic_error_circle)
            }
            info.version?.takeIf { it.isNotBlank() }?.let { Pill(stringResource(R.string.hub_romm_version, it), tone = PillTone.Info) }
            info.user?.let { user ->
                val label = user.username.ifBlank { null }
                if (label != null) Pill(label, icon = R.drawable.ic_account)
            }
        },
        actions = {
            if (!info.configured) {
                ActionPill(stringResource(R.string.hub_set_up), onOpen, modifier = Modifier.focusRequester(firstFocus), icon = R.drawable.ic_link, tone = ActionTone.Accent)
            } else {
                ActionPill(stringResource(R.string.hub_refresh), onRefresh, modifier = Modifier.focusRequester(firstFocus), icon = R.drawable.ic_sync)
                ActionPill(stringResource(R.string.hub_romm_settings), onOpen, icon = R.drawable.ic_tune)
            }
        }
    ) {
        if (!info.configured) {
            Pitch(stringResource(R.string.hub_romm_pitch))
        } else {
            val counts = info.counts
            if (counts.roms != null || counts.platforms != null || counts.totalBytes != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    counts.roms?.let { StatTile(stringResource(R.string.hub_stat_games), formatCount(it), Modifier.weight(1f), icon = R.drawable.ic_library) }
                    counts.platforms?.let { StatTile(stringResource(R.string.hub_stat_platforms), formatCount(it), Modifier.weight(1f), icon = R.drawable.ic_controller) }
                    counts.totalBytes?.takeIf { it > 0 }?.let { StatTile(stringResource(R.string.hub_stat_size), formatBytes(it), Modifier.weight(1f), icon = R.drawable.ic_storage) }
                }
            }
            val cloudFiles = (counts.saves ?: 0) + (counts.states ?: 0)
            if (cloudFiles > 0) FactLine(pluralStringResource(R.plurals.hub_romm_saves, cloudFiles, cloudFiles))
            if (!info.reachable && info.errorKind != null && info.errorKind != RommErrorKind.NOT_SET_UP) {
                FactLine(stringResource(rommErrorText(info.errorKind)).replaceFirstChar { it.titlecase() }, MaterialTheme.colorScheme.error)
            }
            if (info.checkedAt > 0) {
                FactLine(stringResource(R.string.hub_checked, relativeTime(info.checkedAt)))
            }
        }
    }
}

// ---- Save sync ----------------------------------------------------------------------------------

@Composable
fun SaveSyncCard(
    state: SaveSyncState,
    configured: Boolean,
    rommConfigured: Boolean,
    modifier: Modifier,
    onSync: () -> Unit,
    onOpen: () -> Unit
) {
    val last = state.last
    val conflicts = state.conflicts.size
    CloudCard(
        icon = R.drawable.ic_cloud_sync,
        title = stringResource(R.string.nav_save_sync),
        modifier = modifier,
        pills = {
            when {
                !configured -> Pill(stringResource(R.string.hub_not_set_up), icon = R.drawable.ic_cloud_off)
                state.running -> Pill(stringResource(R.string.hub_syncing), tone = PillTone.Accent, icon = R.drawable.ic_cloud_sync)
                state.error != null -> Pill(stringResource(R.string.hub_sync_failed), tone = PillTone.Danger, icon = R.drawable.ic_error_circle)
                last == null -> Pill(stringResource(R.string.hub_not_synced_yet), icon = R.drawable.ic_schedule)
                else -> Pill(stringResource(R.string.hub_in_sync), tone = PillTone.Success, icon = R.drawable.ic_check_circle)
            }
            if (conflicts > 0) {
                Pill(pluralStringResource(R.plurals.hub_conflicts, conflicts, conflicts), tone = PillTone.Warning, icon = R.drawable.ic_warning)
            }
        },
        actions = {
            if (!configured) {
                ActionPill(
                    stringResource(if (rommConfigured) R.string.hub_set_up else R.string.hub_set_up_romm_first),
                    onOpen,
                    icon = R.drawable.ic_save,
                    tone = ActionTone.Accent
                )
            } else {
                ActionPill(stringResource(R.string.hub_sync_now), onSync, icon = R.drawable.ic_sync, tone = ActionTone.Accent, enabled = !state.running)
                ActionPill(stringResource(R.string.hub_open), onOpen, icon = R.drawable.ic_tune)
            }
        }
    ) {
        when {
            !configured -> Pitch(stringResource(if (rommConfigured) R.string.hub_saves_pitch else R.string.hub_saves_needs_romm))
            else -> {
                if (state.running) {
                    ProgressText.fraction(state.progress)?.let { MeterBar(it, modifier = Modifier.fillMaxWidth(), height = 6.dp) }
                    state.progress?.let { FactLine(it) }
                }
                if (last != null) {
                    FactLine(stringResource(R.string.hub_last_sync, relativeTime(last.finishedAt)))
                    FactLine(stringResource(R.string.hub_saves_result, last.uploaded, last.downloaded))
                }
                state.error?.let { FactLine(it, MaterialTheme.colorScheme.error) }
            }
        }
    }
}

// ---- Debrid -------------------------------------------------------------------------------------

@Composable
fun DebridCard(provider: DebridProvider, hasKey: Boolean, modifier: Modifier, onOpen: () -> Unit) {
    val on = provider != DebridProvider.NONE
    CloudCard(
        icon = R.drawable.ic_bolt,
        title = stringResource(R.string.hub_debrid_title),
        modifier = modifier,
        pills = {
            if (!on) Pill(stringResource(R.string.hub_not_set_up), icon = R.drawable.ic_cloud_off)
            else {
                Pill(provider.label, tone = PillTone.Info)
                if (hasKey) Pill(stringResource(R.string.hub_debrid_ready), tone = PillTone.Success, icon = R.drawable.ic_check_circle)
                else Pill(stringResource(R.string.hub_debrid_no_key), tone = PillTone.Warning, icon = R.drawable.ic_key)
            }
        },
        actions = {
            ActionPill(
                stringResource(if (on) R.string.hub_open_settings else R.string.hub_set_up),
                onOpen,
                icon = R.drawable.ic_settings,
                tone = if (on && hasKey) ActionTone.Neutral else ActionTone.Accent
            )
        }
    ) {
        Pitch(stringResource(if (on) R.string.hub_debrid_on_hint else R.string.hub_debrid_pitch))
    }
}

// ---- WebDAV: your own cloud ---------------------------------------------------------------------

/** Nothing set up yet: one card that sells both features and opens the setup screen. */
@Composable
fun DavPitchCard(modifier: Modifier, onOpen: () -> Unit) {
    CloudCard(
        icon = R.drawable.ic_cloud_upload,
        title = stringResource(R.string.hub_dav_title),
        modifier = modifier,
        pills = { Pill(stringResource(R.string.hub_not_set_up), icon = R.drawable.ic_cloud_off) },
        actions = { ActionPill(stringResource(R.string.hub_set_up), onOpen, icon = R.drawable.ic_link, tone = ActionTone.Accent) }
    ) { Pitch(stringResource(R.string.hub_dav_pitch)) }
}

/** The connection pill both WebDAV cards start with. */
@Composable
private fun ConnectionPill(dav: DavStatus) {
    when (dav.connected) {
        true -> Pill(stringResource(R.string.hub_connected), tone = PillTone.Success, icon = R.drawable.ic_check_circle)
        false -> Pill(stringResource(R.string.hub_conn_failed), tone = PillTone.Danger, icon = R.drawable.ic_error_circle)
        null -> Pill(stringResource(R.string.hub_conn_untested), icon = R.drawable.ic_schedule)
    }
}

@Composable
fun BackupCard(dav: DavStatus, modifier: Modifier, onBackup: () -> Unit, onOpen: () -> Unit) {
    val context = LocalContext.current
    CloudCard(
        icon = R.drawable.ic_backup,
        title = stringResource(R.string.hub_backup_title),
        modifier = modifier,
        pills = {
            if (dav.backupRunning) Pill(stringResource(R.string.hub_syncing), tone = PillTone.Accent, icon = R.drawable.ic_cloud_sync)
            else ConnectionPill(dav)
            if (dav.autoBackup) Pill(stringResource(R.string.hub_backup_auto), tone = PillTone.Info, icon = R.drawable.ic_schedule)
            if (dav.backupStale) Pill(stringResource(R.string.hub_backup_overdue), tone = PillTone.Warning, icon = R.drawable.ic_warning)
        },
        actions = {
            ActionPill(stringResource(R.string.hub_backup_now), onBackup, icon = R.drawable.ic_cloud_upload, tone = ActionTone.Accent, enabled = !dav.busy)
            ActionPill(stringResource(R.string.hub_open), onOpen, icon = R.drawable.ic_tune)
        }
    ) {
        if (dav.backupRunning) {
            Pitch(stringResource(R.string.hub_backup_running))
        } else if (dav.lastBackupAt > 0) {
            FactLine(stringResource(R.string.hub_backup_last, relativeTime(dav.lastBackupAt), formatBytes(dav.lastBackupBytes)))
        } else {
            Pitch(stringResource(R.string.hub_backup_never))
        }
        val error = dav.lastBackupError.ifBlank { dav.lastTestError }
        if (error.isNotBlank()) FactLine(CloudMessages.render(context, error), MaterialTheme.colorScheme.error)
    }
}

@Composable
fun DeviceSyncCard(dav: DavStatus, modifier: Modifier, onSync: () -> Unit, onOpen: () -> Unit) {
    val context = LocalContext.current
    CloudCard(
        icon = R.drawable.ic_devices,
        title = stringResource(R.string.hub_devsync_title),
        modifier = modifier,
        pills = {
            when {
                dav.syncRunning -> Pill(stringResource(R.string.hub_syncing), tone = PillTone.Accent, icon = R.drawable.ic_cloud_sync)
                !dav.deviceSync -> Pill(stringResource(R.string.hub_off), icon = R.drawable.ic_cloud_off)
                dav.lastSyncError.isNotBlank() -> Pill(stringResource(R.string.hub_sync_failed), tone = PillTone.Danger, icon = R.drawable.ic_error_circle)
                dav.lastSyncAt == 0L -> Pill(stringResource(R.string.hub_not_synced_yet), icon = R.drawable.ic_schedule)
                else -> Pill(stringResource(R.string.hub_in_sync), tone = PillTone.Success, icon = R.drawable.ic_check_circle)
            }
            if (dav.syncHeldBack > 0) {
                Pill(pluralStringResource(R.plurals.hub_devsync_held, dav.syncHeldBack, dav.syncHeldBack), tone = PillTone.Warning, icon = R.drawable.ic_warning)
            }
        },
        actions = {
            if (dav.deviceSync) {
                ActionPill(stringResource(R.string.hub_sync_now), onSync, icon = R.drawable.ic_sync, tone = ActionTone.Accent, enabled = !dav.busy)
            }
            ActionPill(stringResource(if (dav.deviceSync) R.string.hub_open else R.string.hub_turn_on), onOpen, icon = R.drawable.ic_tune)
        }
    ) {
        if (!dav.deviceSync) {
            Pitch(stringResource(R.string.hub_devsync_pitch))
        } else {
            if (dav.lastSyncAt > 0) {
                FactLine(stringResource(R.string.hub_last_sync, relativeTime(dav.lastSyncAt)))
                FactLine(stringResource(R.string.hub_devsync_changes, dav.lastSyncAdded, dav.lastSyncRemoved))
            } else {
                Pitch(stringResource(R.string.hub_devsync_pitch))
            }
            if (dav.lastSyncError.isNotBlank()) FactLine(CloudMessages.render(context, dav.lastSyncError), MaterialTheme.colorScheme.error)
        }
    }
}

private fun relativeTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS).toString()

private fun formatCount(n: Int): String = NumberFormat.getIntegerInstance().format(n)
