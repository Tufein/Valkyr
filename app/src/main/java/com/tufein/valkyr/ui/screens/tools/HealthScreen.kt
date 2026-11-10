package com.tufein.valkyr.ui.screens.tools

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.R
import com.tufein.valkyr.data.service.HealthService
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.IconTile
import com.tufein.valkyr.ui.components.Panel
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.ProgressRing
import com.tufein.valkyr.ui.components.pillColors
import com.tufein.valkyr.ui.theme.LocalReduceMotion
import com.tufein.valkyr.ui.theme.tabular
import com.tufein.valkyr.ui.components.formatBytes
import com.tufein.valkyr.util.HealthCheck
import com.tufein.valkyr.util.HealthDetail
import com.tufein.valkyr.util.HealthFix
import com.tufein.valkyr.util.HealthGroup
import com.tufein.valkyr.util.HealthReport
import com.tufein.valkyr.util.HealthResult
import com.tufein.valkyr.util.HealthRollup
import com.tufein.valkyr.util.HealthStatus
import com.tufein.valkyr.util.HealthSummary
import com.tufein.valkyr.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.Job

@HiltViewModel
class HealthViewModel @Inject constructor(private val service: HealthService) : ViewModel() {
    private val _results = MutableStateFlow<Map<HealthCheck, HealthResult>>(emptyMap())
    /** The checks that have answered so far; a check that is missing is still running. */
    val results: StateFlow<Map<HealthCheck, HealthResult>> = _results.asStateFlow()

    private var job: Job? = null
    private val rechecks = HashMap<HealthCheck, Job>()

    init { runAll() }

    /** Runs every check again, all at once. */
    fun runAll() {
        job?.cancel()
        rechecks.values.forEach { it.cancel() }
        rechecks.clear()
        _results.value = emptyMap()
        job = viewModelScope.launch {
            service.runAll().collect { r -> _results.update { it + (r.check to r) } }
        }
    }

    /** Runs one check again (after a fix, or when the user comes back from a settings screen). */
    fun recheck(check: HealthCheck) {
        rechecks[check]?.cancel()
        _results.update { it - check }
        rechecks[check] = viewModelScope.launch {
            val r = service.runOne(check)
            _results.update { it + (check to r) }
        }
    }

    /** Forgets the cover misses so they are looked up again, then checks the covers once more. */
    fun retryCovers() {
        viewModelScope.launch {
            service.retryCovers()
            recheck(HealthCheck.COVERS)
        }
    }
}

private fun HealthCheck.icon(): Int = when (this) {
    HealthCheck.SOURCES -> R.drawable.ic_globe
    HealthCheck.STORAGE -> R.drawable.ic_storage
    HealthCheck.BIOS -> R.drawable.ic_memory
    HealthCheck.COVERS -> R.drawable.ic_image
    HealthCheck.SAVE_SYNC -> R.drawable.ic_save
    HealthCheck.ROMM -> R.drawable.ic_server
    HealthCheck.WEBDAV -> R.drawable.ic_cloud
    HealthCheck.RETRO_ACHIEVEMENTS -> R.drawable.ic_trophy
    HealthCheck.FRONTENDS -> R.drawable.ic_frontends
    HealthCheck.NOTIFICATIONS -> R.drawable.ic_notifications
    HealthCheck.BATTERY -> R.drawable.ic_battery
    HealthCheck.UPDATE -> R.drawable.ic_download
}

private fun HealthCheck.nameRes(): Int = when (this) {
    HealthCheck.SOURCES -> R.string.health6_check_sources
    HealthCheck.STORAGE -> R.string.health6_check_storage
    HealthCheck.BIOS -> R.string.health6_check_bios
    HealthCheck.COVERS -> R.string.health6_check_covers
    HealthCheck.SAVE_SYNC -> R.string.health6_check_save_sync
    HealthCheck.ROMM -> R.string.health6_check_romm
    HealthCheck.WEBDAV -> R.string.health6_check_webdav
    HealthCheck.RETRO_ACHIEVEMENTS -> R.string.health6_check_ra
    HealthCheck.FRONTENDS -> R.string.health6_check_frontends
    HealthCheck.NOTIFICATIONS -> R.string.health6_check_notifications
    HealthCheck.BATTERY -> R.string.health6_check_battery
    HealthCheck.UPDATE -> R.string.health6_check_update
}

private fun HealthGroup.titleRes(): Int = when (this) {
    HealthGroup.LIBRARY -> R.string.health6_group_library
    HealthGroup.CLOUD -> R.string.health6_group_cloud
    HealthGroup.DEVICE -> R.string.health6_group_device
}

private fun HealthGroup.icon(): Int = when (this) {
    HealthGroup.LIBRARY -> R.drawable.ic_library
    HealthGroup.CLOUD -> R.drawable.ic_cloud
    HealthGroup.DEVICE -> R.drawable.ic_devices
}

private fun HealthStatus.statusRes(): Int = when (this) {
    HealthStatus.OK -> R.string.health6_status_ok
    HealthStatus.HINT -> R.string.health6_status_hint
    HealthStatus.LOOK -> R.string.health6_status_look
    HealthStatus.PROBLEM -> R.string.health6_status_problem
    HealthStatus.NOT_SET_UP -> R.string.health6_status_not_set_up
}

private fun HealthStatus.tone(): PillTone = when (this) {
    HealthStatus.OK -> PillTone.Success
    HealthStatus.HINT -> PillTone.Info
    HealthStatus.LOOK -> PillTone.Warning
    HealthStatus.PROBLEM -> PillTone.Danger
    HealthStatus.NOT_SET_UP -> PillTone.Neutral
}

private fun HealthStatus.icon(): Int = when (this) {
    HealthStatus.OK -> R.drawable.ic_check_circle
    HealthStatus.HINT -> R.drawable.ic_info
    HealthStatus.LOOK -> R.drawable.ic_warning
    HealthStatus.PROBLEM -> R.drawable.ic_error_circle
    HealthStatus.NOT_SET_UP -> R.drawable.ic_remove
}

private fun HealthFix.labelRes(): Int = when (this) {
    HealthFix.RETRY_COVERS -> R.string.health6_fix_retry_covers
    HealthFix.REFRESH_ROMM -> R.string.health6_fix_refresh
    HealthFix.OPEN_APP_NOTIFICATION_SETTINGS -> R.string.health6_fix_notifications
    HealthFix.OPEN_BATTERY_SETTINGS -> R.string.health6_fix_battery
    HealthFix.OPEN_SOURCES -> R.string.health6_fix_sources
    HealthFix.OPEN_BIOS -> R.string.health6_fix_bios
    HealthFix.OPEN_SAVE_SYNC -> R.string.health6_fix_save_sync
    HealthFix.OPEN_ROMM -> R.string.health6_fix_romm
    HealthFix.OPEN_CLOUD_BACKUP -> R.string.health6_fix_cloud
    HealthFix.OPEN_STORAGE -> R.string.health6_fix_storage
    HealthFix.OPEN_FRONTENDS -> R.string.health6_fix_frontends
    HealthFix.OPEN_RETROACHIEVEMENTS -> R.string.health6_fix_ra
    HealthFix.OPEN_UPDATES -> R.string.health6_fix_updates
}

/** "today" / "3 days ago". */
@Composable
private fun ageText(days: Long): String =
    if (days <= 0L) stringResource(R.string.health6_age_today)
    else pluralStringResource(R.plurals.health6_age_days, days.toInt(), days.toInt())

/** The one-line explanation of a result, in the user's language. */
@Composable
private fun detailText(r: HealthResult): String {
    val a = r.a.toInt()
    val b = r.b.toInt()
    return when (r.detail) {
        HealthDetail.SOURCES_NONE -> stringResource(R.string.health6_d_sources_none)
        HealthDetail.SOURCES_NOT_SCANNED -> stringResource(R.string.health6_d_sources_not_scanned)
        HealthDetail.SOURCES_OK -> pluralStringResource(R.plurals.health6_d_sources_ok, a, a, ageText(r.b))
        HealthDetail.SOURCES_PARTIAL -> pluralStringResource(R.plurals.health6_d_sources_partial, a, a, b)
        HealthDetail.SOURCES_FAILED -> pluralStringResource(R.plurals.health6_d_sources_failed, b, b)
        HealthDetail.STORAGE_NO_FOLDER -> stringResource(R.string.health6_d_storage_no_folder)
        HealthDetail.STORAGE_OK -> stringResource(R.string.health6_d_storage_ok, formatBytes(r.a), formatBytes(r.b))
        HealthDetail.STORAGE_FREE_UNKNOWN -> stringResource(R.string.health6_d_storage_free_unknown, formatBytes(r.b))
        HealthDetail.STORAGE_LOW -> stringResource(R.string.health6_d_storage_low, formatBytes(r.a))
        HealthDetail.STORAGE_QUEUE_SHORT -> stringResource(R.string.health6_d_storage_queue_short, formatBytes(r.a))
        HealthDetail.BIOS_NO_FOLDER -> stringResource(R.string.health6_d_bios_no_folder)
        HealthDetail.BIOS_NONE_NEEDED -> stringResource(R.string.health6_d_bios_none_needed)
        HealthDetail.BIOS_OK -> pluralStringResource(R.plurals.health6_d_bios_ok, a, a)
        HealthDetail.BIOS_INCOMPLETE -> pluralStringResource(R.plurals.health6_d_bios_incomplete, a, a, b)
        HealthDetail.COVERS_OK -> stringResource(R.string.health6_d_covers_ok)
        HealthDetail.COVERS_MISSES -> pluralStringResource(R.plurals.health6_d_covers_misses, a, a)
        HealthDetail.SAVE_NOT_SET_UP -> stringResource(R.string.health6_d_save_not_set_up)
        HealthDetail.SAVE_NO_ROMM -> stringResource(R.string.health6_d_save_no_romm)
        HealthDetail.SAVE_CONFLICTS -> pluralStringResource(R.plurals.health6_d_save_conflicts, a, a)
        HealthDetail.SAVE_ERROR -> stringResource(R.string.health6_d_save_error, r.text)
        HealthDetail.SAVE_FAILED -> pluralStringResource(R.plurals.health6_d_save_failed, a, a)
        HealthDetail.SAVE_NEVER -> stringResource(R.string.health6_d_save_never)
        HealthDetail.SAVE_STALE -> stringResource(R.string.health6_d_save_stale, ageText(r.a))
        HealthDetail.SAVE_OK -> stringResource(R.string.health6_d_save_ok, ageText(r.a))
        HealthDetail.ROMM_NOT_SET_UP -> stringResource(R.string.health6_d_romm_not_set_up)
        HealthDetail.ROMM_OK ->
            if (r.text.isBlank()) stringResource(R.string.health6_d_romm_ok_plain)
            else stringResource(R.string.health6_d_romm_ok, r.text)
        HealthDetail.ROMM_AUTH -> stringResource(R.string.health6_d_romm_auth)
        HealthDetail.ROMM_FORBIDDEN -> stringResource(R.string.health6_d_romm_forbidden)
        HealthDetail.ROMM_TLS -> stringResource(R.string.health6_d_romm_tls)
        HealthDetail.ROMM_UNREACHABLE -> stringResource(R.string.health6_d_romm_unreachable)
        HealthDetail.ROMM_SERVER -> stringResource(R.string.health6_d_romm_server) + if (r.text.isBlank()) "" else " ${r.text}"
        HealthDetail.DAV_NOT_SET_UP -> stringResource(R.string.health6_d_dav_not_set_up)
        HealthDetail.DAV_NOT_CONNECTED -> stringResource(R.string.health6_d_dav_not_connected)
        HealthDetail.DAV_ATTENTION -> pluralStringResource(R.plurals.health6_d_dav_attention, a, a)
        HealthDetail.DAV_BACKUP_OVERDUE -> pluralStringResource(R.plurals.health6_d_dav_overdue, a, a)
        HealthDetail.DAV_NO_BACKUP -> stringResource(R.string.health6_d_dav_no_backup)
        HealthDetail.DAV_OK_BACKUP -> stringResource(R.string.health6_d_dav_ok_backup, ageText(r.a))
        HealthDetail.DAV_OK_NO_BACKUP -> stringResource(R.string.health6_d_dav_ok_no_backup)
        HealthDetail.RA_NOT_SET_UP -> stringResource(R.string.health6_d_ra_not_set_up)
        HealthDetail.RA_INCOMPLETE -> stringResource(R.string.health6_d_ra_incomplete)
        HealthDetail.RA_OK -> stringResource(R.string.health6_d_ra_ok)
        HealthDetail.FRONT_NOT_SET_UP -> stringResource(R.string.health6_d_front_not_set_up)
        HealthDetail.FRONT_OK -> pluralStringResource(R.plurals.health6_d_front_ok, a, a)
        HealthDetail.FRONT_PARTIAL -> stringResource(R.string.health6_d_front_partial)
        HealthDetail.NOTIF_OK -> stringResource(R.string.health6_d_notif_ok)
        HealthDetail.NOTIF_OFF -> stringResource(R.string.health6_d_notif_off)
        HealthDetail.BATTERY_OK -> stringResource(R.string.health6_d_battery_ok)
        HealthDetail.BATTERY_HINT -> stringResource(R.string.health6_d_battery_hint)
        HealthDetail.UPDATE_OK -> stringResource(R.string.health6_d_update_ok, r.text)
        HealthDetail.UPDATE_AVAILABLE -> stringResource(R.string.health6_d_update_available, r.text)
        HealthDetail.UPDATE_UNKNOWN -> stringResource(R.string.health6_d_update_unknown)
        HealthDetail.TIMEOUT -> stringResource(R.string.health6_d_timeout)
        HealthDetail.ERROR -> stringResource(R.string.health6_d_error, r.text)
    }
}

private fun Context.findLifecycleOwner(): LifecycleOwner? {
    var c: Context? = this
    while (c != null) {
        if (c is LifecycleOwner) return c
        c = (c as? ContextWrapper)?.baseContext
    }
    return null
}

private fun startSettings(context: Context, fix: HealthFix): Boolean {
    val primary = when (fix) {
        HealthFix.OPEN_APP_NOTIFICATION_SETTINGS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        // The list of apps with battery optimisation: it only shows the page, it asks for no permission.
        else -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }
    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    for (intent in listOf(primary, fallback)) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: Exception) {
            // try the next screen
        }
    }
    return false
}

/**
 * "Check everything": every health check of the app in one report. Each row has a status pill,
 * a one-line explanation and, where there is one, a fix. Fixes that can run here do; the others
 * go to [onFix] so the host can open the right screen.
 */
@Composable
fun HealthScreen(onFix: (HealthFix) -> Unit, viewModel: HealthViewModel = hiltViewModel(), onRecovery: () -> Unit = {}) {
    val context = LocalContext.current
    val results by viewModel.results.collectAsStateWithLifecycle()
    val summary = remember(results) { HealthRollup.summarize(results.values) }
    val requesters = remember { HealthCheck.entries.associateWith { FocusRequester() } }
    var initialFocusDone by remember { mutableStateOf(false) }

    // The first problem gets the D-pad once everything has answered (rows still checking cannot take focus).
    LaunchedEffect(summary.finished) {
        if (summary.finished && !initialFocusDone) {
            initialFocusDone = true
            val target = HealthRollup.firstFocus(results)
            withFrameNanos { }
            target?.let { runCatching { requesters.getValue(it).requestFocus() } }
        }
    }

    // Coming back from the system settings: the two device checks may have changed.
    val owner = remember(context) { context.findLifecycleOwner() }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && initialFocusDone) {
                viewModel.recheck(HealthCheck.NOTIFICATIONS)
                viewModel.recheck(HealthCheck.BATTERY)
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }

    fun runFix(fix: HealthFix) {
        when (fix) {
            HealthFix.RETRY_COVERS -> {
                ToastUtil.showInfo(context, context.getString(R.string.health6_covers_cleared))
                viewModel.retryCovers()
            }
            HealthFix.REFRESH_ROMM -> viewModel.recheck(HealthCheck.ROMM)
            HealthFix.OPEN_APP_NOTIFICATION_SETTINGS, HealthFix.OPEN_BATTERY_SETTINGS ->
                if (!startSettings(context, fix)) ToastUtil.showError(context, context.getString(R.string.health6_no_settings))
            else -> onFix(fix)
        }
    }

    // Everything the report needs, in the user's language.
    val ordered = HealthRollup.ordered(results)
    val details = ordered.associate { it.check to detailText(it) }
    val reportTitle = stringResource(R.string.health6_report_title)
    val summaryLine = summaryText(summary)
    val appVersion = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    val appLine = if (appVersion.isBlank()) "" else stringResource(R.string.health6_report_app, appVersion)
    val shareTitle = stringResource(R.string.health6_share_chooser)
    val statusLabels = HealthStatus.entries.associateWith { stringResource(it.statusRes()) }
    val groupLabels = HealthGroup.entries.associateWith { stringResource(it.titleRes()) }
    val checkNames = HealthCheck.entries.associateWith { stringResource(it.nameRes()) }

    fun share() {
        val text = HealthReport.text(
            title = reportTitle,
            summary = summaryLine,
            appLine = appLine,
            rows = ordered.map {
                HealthReport.Row(
                    group = groupLabels.getValue(it.check.group),
                    name = checkNames.getValue(it.check),
                    status = statusLabels.getValue(it.status),
                    detail = details[it.check].orEmpty()
                )
            }
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, reportTitle)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            context.startActivity(Intent.createChooser(send, shareTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            // no app can take it
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 12.dp)
    ) {
        ToolsTitle(stringResource(R.string.health6_title), icon = R.drawable.ic_health)
        ActionPill(stringResource(R.string.recovery_title), onRecovery, icon = R.drawable.ic_history)
        SummaryPanel(summary, summaryLine, onAgain = viewModel::runAll, onShare = ::share, canShare = results.isNotEmpty())

        HealthGroup.entries.forEach { group ->
            SectionHeader(stringResource(group.titleRes()), icon = group.icon())
            HealthCheck.entries.filter { it.group == group }.forEach { check ->
                val result = results[check]
                HealthRow(
                    check = check,
                    result = result,
                    detail = result?.let { details[check] },
                    focusRequester = requesters.getValue(check),
                    onClick = { result?.fix?.let(::runFix) }
                )
            }
        }
    }
}

@Composable
private fun summaryText(summary: HealthSummary): String = when {
    !summary.finished -> stringResource(R.string.health6_summary_checking, summary.done, summary.expected)
    summary.total == 0 -> stringResource(R.string.health6_summary_none)
    else -> stringResource(R.string.health6_summary_fine, summary.fine, summary.total)
}

@Composable
private fun SummaryPanel(
    summary: HealthSummary,
    headline: String,
    onAgain: () -> Unit,
    onShare: () -> Unit,
    canShare: Boolean
) {
    val scheme = MaterialTheme.colorScheme
    val ringColor = when {
        !summary.finished -> scheme.primary
        summary.worst == HealthStatus.PROBLEM -> scheme.error
        summary.worst == HealthStatus.LOOK -> Color(0xFFFFB300)
        else -> scheme.tertiary
    }
    // While checking, the ring shows how many checks have answered.
    val fraction = if (!summary.finished) summary.done.toFloat() / summary.expected.coerceAtLeast(1) else summary.fraction
    val centre = if (!summary.finished) "${summary.done}/${summary.expected}" else "${summary.fine}/${summary.total}"
    val sub = if (!summary.finished) null else buildList {
        if (summary.problems > 0) add(pluralStringResource(R.plurals.health6_sub_problems, summary.problems, summary.problems))
        if (summary.looks > 0) add(pluralStringResource(R.plurals.health6_sub_looks, summary.looks, summary.looks))
    }.joinToString(", ").ifBlank { if (summary.total > 0) stringResource(R.string.health6_sub_all_good) else "" }

    Panel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ProgressRing(fraction = fraction, size = 76.dp, stroke = 8.dp, color = ringColor) {
                Text(centre, style = MaterialTheme.typography.titleMedium.tabular(), color = scheme.onSurface, maxLines = 1)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(headline, style = MaterialTheme.typography.headlineSmall, color = scheme.onSurface)
                if (!sub.isNullOrBlank()) {
                    Text(sub, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        ToolsActions(horizontalPadding = 0.dp, modifier = Modifier.padding(top = 10.dp)) {
            ActionPill(stringResource(R.string.health6_check_again), onAgain, icon = R.drawable.ic_retry, tone = ActionTone.Accent)
            ActionPill(stringResource(R.string.health6_share), onShare, icon = R.drawable.ic_share, enabled = canShare)
        }
    }
}

@Composable
private fun HealthRow(
    check: HealthCheck,
    result: HealthResult?,
    detail: String?,
    focusRequester: FocusRequester,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val name = stringResource(check.nameRes())
    if (result == null) {
        // Still checking: a spinner, and no D-pad stop until the answer is in.
        ToolRow(
            title = name,
            lines = listOf(stringResource(R.string.health6_row_checking)),
            onClick = {},
            modifier = Modifier.focusProperties { canFocus = false },
            leading = { IconTile(check.icon(), size = 36.dp, container = scheme.surfaceContainerHigh, tint = scheme.onSurfaceVariant) },
            trailing = { Spinner() }
        )
        return
    }
    val (tileBg, tileFg) = pillColors(result.status.tone())
    val fix = result.fix
    ToolRow(
        title = name,
        lines = listOf(detail.orEmpty()),
        onClick = onClick,
        modifier = Modifier.focusRequester(focusRequester),
        leading = { IconTile(check.icon(), size = 36.dp, container = tileBg, tint = tileFg) },
        badge = { Pill(stringResource(result.status.statusRes()), tone = result.status.tone(), icon = result.status.icon()) },
        trailing = {
            if (fix != null) {
                val label = if (result.status == HealthStatus.NOT_SET_UP && !fix.inline) R.string.health6_fix_setup else fix.labelRes()
                Pill(stringResource(label), tone = PillTone.Accent)
            }
        }
    )
}

@Composable
private fun Spinner() {
    if (LocalReduceMotion.current) {
        Icon(
            painterResource(R.drawable.ic_hourglass),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
    } else {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
    }
}
