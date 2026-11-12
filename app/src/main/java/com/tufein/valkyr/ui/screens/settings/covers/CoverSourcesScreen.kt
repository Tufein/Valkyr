package com.tufein.valkyr.ui.screens.settings.covers

import android.text.format.DateUtils
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tufein.valkyr.R
import com.tufein.valkyr.data.local.CoverSourceSettings
import com.tufein.valkyr.data.service.CoverSourceStats
import com.tufein.valkyr.data.service.ScreenScraperService
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.IconTile
import com.tufein.valkyr.ui.components.LegendEntry
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.rememberFocusSource
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.ui.components.requestFocusWhenReady
import com.tufein.valkyr.ui.screens.settings.SettingRow
import com.tufein.valkyr.ui.screens.settings.SettingsCardHeader
import com.tufein.valkyr.ui.screens.settings.SettingsCardsGrid
import com.tufein.valkyr.ui.screens.settings.SettingsSurface
import com.tufein.valkyr.ui.screens.settings.ThemedSwitch
import com.tufein.valkyr.ui.screens.tools.PublishLegend
import com.tufein.valkyr.util.CardGrid
import com.tufein.valkyr.util.CoverApis
import com.tufein.valkyr.util.CoverSources
import com.tufein.valkyr.util.CoverSources.Source
import com.tufein.valkyr.util.ScreenScraper
import com.tufein.valkyr.util.ScreenScraperQuota
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

/** Where a developer id is asked for, opened from the explanation row. */
private const val SCREENSCRAPER_SITE = "https://www.screenscraper.fr/"

/**
 * Settings → Cover sources (4.3.0): the order of the cover sources with a switch each (RomM stays
 * first), title screens and snaps as a fallback, the ScreenScraper login with "Test login", what
 * every source did today, and "find missing covers again". One column of settings cards; every
 * control works from the D-pad (move up / down buttons, no dragging) and no text is cut off.
 */
@Composable
fun CoverSourcesScreen(viewModel: CoverSourcesViewModel = hiltViewModel()) {
    val chain = viewModel.chain.collectAsStateWithLifecycle().value ?: CoverSources.Chain()
    val artwork by viewModel.artwork.collectAsStateWithLifecycle()
    val login by viewModel.login.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val testing by viewModel.testing.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()
    val retried by viewModel.retried.collectAsStateWithLifecycle()
    val keys by viewModel.keys.collectAsStateWithLifecycle()
    val testingKey by viewModel.testingKey.collectAsStateWithLifecycle()
    val keyTests by viewModel.keyTests.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val today = ScreenScraper.dayOf(System.currentTimeMillis())
    var editing by rememberSaveable { mutableStateOf<CoverSourceSettings.Field?>(null) }

    PublishLegend(listOf(LegendEntry("A", stringResource(R.string.pad_change)), LegendEntry("B", stringResource(R.string.pad_back))))

    val sources = listOf(Source.ROMM) + chain.order
    val orderRows: List<@Composable () -> Unit> = buildList {
        sources.forEachIndexed { index, source ->
            add {
                val movable = source != Source.ROMM
                val place = index - 1 // place among the movable sources
                SourceRow(
                    position = index + 1,
                    source = source,
                    hint = sourceHint(source, chain, login, stats, keys),
                    enabled = chain.enabled(source),
                    canUp = movable && place > 0,
                    canDown = movable && place < chain.order.lastIndex,
                    onToggle = { viewModel.setEnabled(source, it) },
                    onUp = { viewModel.move(source, -1) },
                    onDown = { viewModel.move(source, 1) }
                )
            }
        }
        add {
            SettingRow(
                icon = R.drawable.ic_image,
                title = stringResource(R.string.covers43_non_box),
                hint = stringResource(R.string.covers43_non_box_hint),
                onClick = { viewModel.setNonBoxArt(!chain.nonBoxArt) },
                onAdjust = { viewModel.setNonBoxArt(it > 0) }
            ) { ThemedSwitch(chain.nonBoxArt) { viewModel.setNonBoxArt(it) } }
        }
    }

    // 5.6.0: the second field, with its own order and switches (RomM is not pinned here).
    val artworkRows: List<@Composable () -> Unit> = buildList {
        add { InfoRow(R.drawable.ic_info, R.string.romm55_art_info_title, stringResource(R.string.romm55_art_info_hint)) }
        artwork.order.forEachIndexed { index, source ->
            add {
                SourceRow(
                    position = index + 1,
                    source = source,
                    hint = stringResource(artworkHint(source)) + if (source == Source.SCREENSCRAPER) "\n" + screenScraperState(login, stats) else "",
                    enabled = artwork.enabled(source),
                    canUp = index > 0,
                    canDown = index < artwork.order.lastIndex,
                    onToggle = { viewModel.setArtworkEnabled(source, it) },
                    onUp = { viewModel.moveArtwork(source, -1) },
                    onDown = { viewModel.moveArtwork(source, 1) },
                    pinned = false
                )
            }
        }
    }

    val loginRows: List<@Composable () -> Unit> = listOf(
        {
            SettingRow(
                icon = R.drawable.ic_info,
                title = stringResource(R.string.covers43_ss_where_title),
                hint = stringResource(R.string.covers43_ss_where_hint),
                onClick = { runCatching { uri.openUri(SCREENSCRAPER_SITE) } }
            ) {
                ActionPill(stringResource(R.string.covers43_ss_open_site), { runCatching { uri.openUri(SCREENSCRAPER_SITE) } }, icon = R.drawable.ic_open_in_new)
            }
        },
        { CredentialRow(R.drawable.ic_key, R.string.covers43_ss_dev_id, login.devId.ifBlank { stringResource(R.string.settings_not_set) }) { editing = CoverSourceSettings.Field.DEV_ID } },
        { CredentialRow(R.drawable.ic_lock, R.string.covers43_ss_dev_password, secretState(login.devPassword)) { editing = CoverSourceSettings.Field.DEV_PASSWORD } },
        {
            CredentialRow(
                R.drawable.ic_label, R.string.covers43_ss_soft_name,
                login.softName.ifBlank { stringResource(R.string.covers43_ss_soft_name_default, ScreenScraper.DEFAULT_SOFT_NAME) }
            ) { editing = CoverSourceSettings.Field.SOFT_NAME }
        },
        { CredentialRow(R.drawable.ic_account, R.string.covers43_ss_user, login.user.ifBlank { stringResource(R.string.covers43_ss_user_none) }) { editing = CoverSourceSettings.Field.USER } },
        { CredentialRow(R.drawable.ic_lock, R.string.covers43_ss_password, secretState(login.password)) { editing = CoverSourceSettings.Field.PASSWORD } },
        {
            val result = testResult
            SettingRow(
                icon = R.drawable.ic_network_check,
                title = stringResource(R.string.covers43_ss_test),
                hint = when {
                    testing -> stringResource(R.string.covers43_ss_testing)
                    result != null -> loginResultText(result)
                    else -> stringResource(R.string.covers43_ss_test_hint)
                },
                hintColor = when (result) {
                    is ScreenScraperService.LoginResult.LoggedIn, ScreenScraperService.LoginResult.DeveloperOnly, null -> null
                    else -> MaterialTheme.colorScheme.error
                },
                onClick = viewModel::testLogin
            ) {
                ActionPill(
                    stringResource(if (testing) R.string.covers43_ss_testing_short else R.string.settings_test),
                    viewModel::testLogin,
                    icon = R.drawable.ic_sync,
                    tone = if (login.ready && result == null) ActionTone.Accent else ActionTone.Neutral,
                    enabled = !testing
                )
            }
        },
        {
            // 5.5.0: the account's limits as ScreenScraper last reported them; Check runs the login test again.
            SettingRow(
                icon = R.drawable.ic_bar_chart,
                title = stringResource(R.string.ss55_quota_title),
                hint = if (testing) stringResource(R.string.ss55_checking) else quotaText(login, stats),
                hintColor = if (ScreenScraperQuota.state(stats.user, stats.userAt, stats.pausedUntil, System.currentTimeMillis()) is ScreenScraperQuota.State.Exhausted) MaterialTheme.colorScheme.error else null,
                onClick = viewModel::testLogin
            ) {
                ActionPill(
                    stringResource(if (testing) R.string.ss55_checking_short else R.string.ss55_check),
                    viewModel::testLogin,
                    icon = R.drawable.ic_sync,
                    enabled = !testing && login.ready
                )
            }
        }
    )

    // 4.4.0: one section per source that needs an API key of its own.
    val theGamesDbRows: List<@Composable () -> Unit> = buildList {
        add { InfoRow(R.drawable.ic_info, R.string.covers44_where_title, stringResource(R.string.covers44_tgdb_where_hint)) }
        if (keys.tgdbShared) add { InfoRow(R.drawable.ic_key, R.string.covers44_tgdb_shared_title, stringResource(R.string.covers44_tgdb_shared_hint)) }
        add {
            val state = when {
                keys.tgdb.isNotBlank() -> stringResource(R.string.covers44_key_set)
                keys.tgdbShared -> stringResource(R.string.covers44_tgdb_key_app)
                else -> stringResource(R.string.settings_not_set)
            }
            CredentialRow(R.drawable.ic_key, R.string.covers44_tgdb_key, state) { editing = CoverSourceSettings.Field.TGDB_KEY }
        }
        add { KeyTestRow(Source.THEGAMESDB, keys, testingKey, keyTests[Source.THEGAMESDB], viewModel::testKey) }
    }
    val steamGridDbRows: List<@Composable () -> Unit> = listOf(
        { InfoRow(R.drawable.ic_info, R.string.covers44_where_title, stringResource(R.string.covers44_sgdb_where_hint)) },
        { CredentialRow(R.drawable.ic_key, R.string.covers44_sgdb_key, keyState(keys.sgdb)) { editing = CoverSourceSettings.Field.SGDB_KEY } },
        { KeyTestRow(Source.STEAMGRIDDB, keys, testingKey, keyTests[Source.STEAMGRIDDB], viewModel::testKey) }
    )
    val igdbRows: List<@Composable () -> Unit> = listOf(
        { InfoRow(R.drawable.ic_info, R.string.covers44_where_title, stringResource(R.string.covers44_igdb_where_hint)) },
        { CredentialRow(R.drawable.ic_account, R.string.covers44_igdb_client_id, keyState(keys.igdbId)) { editing = CoverSourceSettings.Field.IGDB_CLIENT_ID } },
        { CredentialRow(R.drawable.ic_lock, R.string.covers44_igdb_client_secret, keyState(keys.igdbSecret)) { editing = CoverSourceSettings.Field.IGDB_CLIENT_SECRET } },
        { KeyTestRow(Source.IGDB, keys, testingKey, keyTests[Source.IGDB], viewModel::testKey) }
    )

    val statusRows: List<@Composable () -> Unit> = buildList {
        chain.order.forEach { source ->
            add {
                val t = stats.tally(source, today)
                SettingRow(
                    icon = sourceIcon(source),
                    title = sourceName(source),
                    hint = stringResource(R.string.covers43_today, t.hits, t.misses, t.errors),
                    onClick = {}
                ) {}
            }
        }
        add {
            SettingRow(
                icon = R.drawable.ic_image_search,
                title = stringResource(R.string.settings_v5_covers_retry),
                hint = retried?.let { pluralStringResource(R.plurals.settings_v5_covers_reset_done, it, it) }
                    ?: stringResource(R.string.covers43_retry_hint),
                onClick = { viewModel.retryMisses(context) }
            ) {
                ActionPill(stringResource(R.string.settings_v5_covers_retry_action), { viewModel.retryMisses(context) }, icon = R.drawable.ic_retry)
            }
        }
    }

    // The sections of the screen, top to bottom: header and rows.
    val sections: List<Pair<Pair<Int, Int>, List<@Composable () -> Unit>>> = listOf(
        (R.string.covers43_order_title to R.drawable.ic_sort) to orderRows,
        (R.string.romm55_art_title to R.drawable.ic_image) to artworkRows,
        (R.string.covers43_ss_title to R.drawable.ic_key) to loginRows,
        (R.string.covers44_tgdb_title to R.drawable.ic_storage) to theGamesDbRows,
        (R.string.covers44_sgdb_title to R.drawable.ic_palette) to steamGridDbRows,
        (R.string.covers44_igdb_title to R.drawable.ic_collections) to igdbRows,
        (R.string.covers43_status_title to R.drawable.ic_bar_chart) to statusRows
    )
    val cells = remember(sections.map { it.second.size }) {
        CardGrid.layout(sections.map { CardGrid.Section(header = true, items = it.second.size) }, 1)
    }
    val firstRow = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstRow.requestFocusWhenReady() }

    SettingsSurface {
        SettingsCardsGrid(cells = cells, columns = 1, title = { CoverSourcesTitle() }) { _, cell ->
            when (cell.kind) {
                CardGrid.Kind.HEADER -> sections[cell.section].first.let { (title, icon) -> SettingsCardHeader(stringResource(title), icon) }
                CardGrid.Kind.ITEM -> {
                    val content = sections[cell.section].second[cell.item]
                    Box(
                        modifier = if (cell.section == 0 && cell.item == 0) Modifier.focusRequester(firstRow) else Modifier,
                        propagateMinConstraints = true
                    ) { content() }
                }
            }
        }
    }

    editing?.let { field ->
        val (title, hint, value) = when (field) {
            CoverSourceSettings.Field.DEV_ID -> Triple(R.string.covers43_ss_dev_id, R.string.covers43_ss_dev_hint, login.devId)
            CoverSourceSettings.Field.DEV_PASSWORD -> Triple(R.string.covers43_ss_dev_password, R.string.covers43_ss_dev_hint, login.devPassword)
            CoverSourceSettings.Field.SOFT_NAME -> Triple(R.string.covers43_ss_soft_name, R.string.covers43_ss_soft_name_hint, login.softName)
            CoverSourceSettings.Field.USER -> Triple(R.string.covers43_ss_user, R.string.covers43_ss_user_hint, login.user)
            CoverSourceSettings.Field.PASSWORD -> Triple(R.string.covers43_ss_password, R.string.covers43_ss_user_hint, login.password)
            CoverSourceSettings.Field.TGDB_KEY -> Triple(R.string.covers44_tgdb_key, R.string.covers44_tgdb_key_hint, keys.tgdb)
            CoverSourceSettings.Field.SGDB_KEY -> Triple(R.string.covers44_sgdb_key, R.string.covers44_sgdb_key_hint, keys.sgdb)
            CoverSourceSettings.Field.IGDB_CLIENT_ID -> Triple(R.string.covers44_igdb_client_id, R.string.covers44_igdb_id_hint, keys.igdbId)
            CoverSourceSettings.Field.IGDB_CLIENT_SECRET -> Triple(R.string.covers44_igdb_client_secret, R.string.covers44_igdb_secret_hint, keys.igdbSecret)
        }
        CredentialDialog(
            title = stringResource(title),
            hint = stringResource(hint),
            value = value,
            secret = field in SECRET_FIELDS,
            onSave = { viewModel.set(field, it) },
            onDismiss = { editing = null }
        )
    }
}

/** The screen title: the icon tile and the name, with a short line under it (wraps, never cut off). */
@Composable
private fun CoverSourcesTitle() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile(R.drawable.ic_image_search, size = 38.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.covers43_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.covers43_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun sourceName(source: Source): String = stringResource(
    when (source) {
        Source.ROMM -> R.string.covers43_source_romm
        Source.LIBRETRO -> R.string.covers43_source_libretro
        Source.SCREENSCRAPER -> R.string.covers43_source_screenscraper
        Source.THEGAMESDB -> R.string.covers44_source_tgdb
        Source.STEAMGRIDDB -> R.string.covers44_source_sgdb
        Source.IGDB -> R.string.covers44_source_igdb
        Source.METADATA -> R.string.covers43_source_metadata
    }
)

private fun artworkHint(source: Source): Int = when (source) {
    Source.ROMM -> R.string.romm55_art_romm_hint
    Source.SCREENSCRAPER -> R.string.romm55_art_screenscraper_hint
    else -> R.string.romm55_art_libretro_hint
}

private fun sourceIcon(source: Source): Int = when (source) {
    Source.ROMM -> R.drawable.ic_server
    Source.LIBRETRO -> R.drawable.ic_image
    Source.SCREENSCRAPER -> R.drawable.ic_globe
    Source.THEGAMESDB -> R.drawable.ic_storage
    Source.STEAMGRIDDB -> R.drawable.ic_palette
    Source.IGDB -> R.drawable.ic_collections
    Source.METADATA -> R.drawable.ic_description
}

/** What a source is, and below it, whether it can answer now and what it did today. */
@Composable
private fun sourceHint(source: Source, chain: CoverSources.Chain, login: ScreenScraperLogin, stats: CoverSourceStats.State, keys: CoverApis.Keys): String {
    val about = stringResource(
        when (source) {
            Source.ROMM -> R.string.covers43_source_romm_hint
            Source.LIBRETRO -> R.string.covers43_source_libretro_hint
            Source.SCREENSCRAPER -> R.string.covers43_source_screenscraper_hint
            Source.THEGAMESDB -> R.string.covers44_source_tgdb_hint
            Source.STEAMGRIDDB -> R.string.covers44_source_sgdb_hint
            Source.IGDB -> R.string.covers44_source_igdb_hint
            Source.METADATA -> R.string.covers43_source_metadata_hint
        }
    )
    val state = when {
        !chain.enabled(source) -> stringResource(R.string.covers43_state_off)
        source == Source.SCREENSCRAPER -> screenScraperState(login, stats)
        source in CoverApis.OWN -> keyedState(source, keys, stats)
        else -> null
    }
    return if (state == null) about else about + "\n" + state
}

/** The quota row's text (5.5.0): used today / daily limit, unrecognised ROMs, threads and level; or the "resumes tomorrow" state. Never truncated. */
@Composable
internal fun quotaText(login: ScreenScraperLogin, stats: CoverSourceStats.State): String {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    if (!login.ready) return stringResource(R.string.covers43_state_needs_dev)
    fun time(at: Long) = DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY)
    return when (val q = ScreenScraperQuota.state(stats.user, stats.userAt, stats.pausedUntil, now)) {
        is ScreenScraperQuota.State.Exhausted -> stringResource(R.string.ss55_quota_exhausted, time(q.resumeAt))
        ScreenScraperQuota.State.Unknown ->
            stringResource(if (login.user.isBlank() || login.password.isBlank()) R.string.ss55_quota_no_login else R.string.ss55_quota_none)
        is ScreenScraperQuota.State.Open -> buildList {
            if (q.used != null && q.max != null) add(stringResource(R.string.ss55_quota_used, q.used, q.max, q.remaining ?: 0))
            else if (q.used != null) add(stringResource(R.string.ss55_quota_used_only, q.used))
            if (q.koUsed != null && q.koMax != null) add(stringResource(R.string.ss55_quota_ko, q.koUsed, q.koMax))
            add(stringResource(R.string.ss55_quota_threads, q.threads))
            if (q.level.isNotBlank()) add(stringResource(R.string.ss55_quota_level, q.level))
            add(stringResource(R.string.ss55_quota_asof, time(q.asOf)))
        }.joinToString("\n")
    }
}

@Composable
internal fun screenScraperState(login: ScreenScraperLogin, stats: CoverSourceStats.State): String {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    if (!login.ready) return stringResource(R.string.covers43_state_needs_dev)
    if (now < stats.pausedUntil) {
        val until = DateUtils.formatDateTime(context, stats.pausedUntil, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY)
        return stringResource(R.string.covers43_state_quota, until)
    }
    return when (stats.problem) {
        CoverSourceStats.Problem.LOGIN -> stringResource(R.string.covers43_state_login)
        CoverSourceStats.Problem.BUSY -> stringResource(R.string.covers43_state_busy)
        CoverSourceStats.Problem.RATE_LIMITED -> stringResource(R.string.covers43_state_rate)
        CoverSourceStats.Problem.OFFLINE -> stringResource(R.string.covers43_state_offline)
        CoverSourceStats.Problem.QUOTA, null -> {
            val user = stats.user
            val left = user?.remainingToday
            when {
                user != null && left != null && ScreenScraper.dayOf(stats.userAt) == ScreenScraper.dayOf(now) ->
                    stringResource(R.string.covers43_state_ready_quota, left, user.maxRequestsPerDay ?: 0)
                else -> stringResource(R.string.covers43_state_ready)
            }
        }
    }
}

/** Whether a 4.4.0 source can answer now, and why not: no key yet, key refused, allowance used up, busy, offline. */
@Composable
internal fun keyedState(source: Source, keys: CoverApis.Keys, stats: CoverSourceStats.State): String {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    if (!keys.configured(source)) return stringResource(if (source == Source.IGDB) R.string.covers44_state_needs_igdb else R.string.covers44_state_needs_key)
    val pausedUntil = stats.pausedUntil(source)
    if (now < pausedUntil) {
        return stringResource(R.string.covers44_state_quota_month, DateUtils.formatDateTime(context, pausedUntil, DateUtils.FORMAT_SHOW_DATE))
    }
    return when (stats.problemOf(source)) {
        CoverSourceStats.Problem.LOGIN -> stringResource(R.string.covers44_state_key_refused)
        CoverSourceStats.Problem.BUSY -> stringResource(R.string.covers43_state_busy)
        CoverSourceStats.Problem.RATE_LIMITED -> stringResource(R.string.covers43_state_rate)
        CoverSourceStats.Problem.OFFLINE -> stringResource(R.string.covers43_state_offline)
        CoverSourceStats.Problem.QUOTA, null -> {
            val left = stats.allowance
            val sameMonth = YearMonth.from(Instant.ofEpochMilli(stats.allowanceAt).atZone(ZoneOffset.UTC)) ==
                YearMonth.from(Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC))
            if (source == Source.THEGAMESDB && left != null && sameMonth) stringResource(R.string.covers44_state_ready_month, left)
            else stringResource(R.string.covers43_state_ready)
        }
    }
}

@Composable
private fun keyTestText(result: CoverApis.KeyTest, source: Source): String = when (result) {
    is CoverApis.KeyTest.Ok -> result.remaining?.let { stringResource(R.string.covers44_test_ok_month, it) } ?: stringResource(R.string.covers44_test_ok)
    CoverApis.KeyTest.Missing -> stringResource(if (source == Source.IGDB) R.string.covers44_test_missing_igdb else R.string.covers44_test_missing)
    CoverApis.KeyTest.Rejected -> stringResource(R.string.covers44_test_rejected)
    CoverApis.KeyTest.IdRejected -> stringResource(R.string.covers44_test_id_rejected)
    CoverApis.KeyTest.SecretRejected -> stringResource(R.string.covers44_test_secret_rejected)
    CoverApis.KeyTest.RateLimited -> stringResource(R.string.covers44_test_rate)
    CoverApis.KeyTest.Busy -> stringResource(R.string.covers44_test_busy)
    CoverApis.KeyTest.Offline -> stringResource(R.string.covers44_test_offline)
    CoverApis.KeyTest.Unreadable -> stringResource(R.string.covers44_test_unreadable)
}

/** "Test key" of one source: the pill and, in the hint, the answer of the service. */
@Composable
private fun KeyTestRow(source: Source, keys: CoverApis.Keys, testing: Source?, result: CoverApis.KeyTest?, onTest: (Source) -> Unit) {
    val busy = testing == source
    SettingRow(
        icon = R.drawable.ic_network_check,
        title = stringResource(R.string.covers44_test_title),
        hint = when {
            busy -> stringResource(R.string.covers44_testing, sourceName(source))
            result != null -> keyTestText(result, source)
            else -> stringResource(R.string.covers44_test_hint, sourceName(source))
        },
        hintColor = if (result == null || result is CoverApis.KeyTest.Ok) null else MaterialTheme.colorScheme.error,
        onClick = { onTest(source) }
    ) {
        ActionPill(
            stringResource(if (busy) R.string.covers43_ss_testing_short else R.string.settings_test),
            { onTest(source) },
            icon = R.drawable.ic_sync,
            tone = if (keys.configured(source) && result == null) ActionTone.Accent else ActionTone.Neutral,
            enabled = testing == null
        )
    }
}

/** A row that only explains (where to get a key); nothing to press. */
@Composable
private fun InfoRow(icon: Int, title: Int, hint: String) {
    SettingRow(icon = icon, title = stringResource(title), hint = hint, onClick = {}) {}
}

/** A key row never shows any of its characters: "Set" or "Not set". */
@Composable
private fun keyState(value: String): String =
    if (value.isBlank()) stringResource(R.string.settings_not_set) else stringResource(R.string.covers44_key_set)

private val SECRET_FIELDS = setOf(
    CoverSourceSettings.Field.DEV_PASSWORD, CoverSourceSettings.Field.PASSWORD,
    CoverSourceSettings.Field.TGDB_KEY, CoverSourceSettings.Field.SGDB_KEY, CoverSourceSettings.Field.IGDB_CLIENT_SECRET
)

@Composable
private fun loginResultText(result: ScreenScraperService.LoginResult): String {
    val context = LocalContext.current
    return when (result) {
        is ScreenScraperService.LoginResult.LoggedIn -> {
            val left = result.user.remainingToday
            if (left != null) stringResource(R.string.covers43_login_ok_quota, result.user.name, left, result.user.maxRequestsPerDay ?: 0, result.user.maxThreads)
            else stringResource(R.string.covers43_login_ok, result.user.name, result.user.maxThreads)
        }
        ScreenScraperService.LoginResult.DeveloperOnly -> stringResource(R.string.covers43_login_dev_only)
        ScreenScraperService.LoginResult.DeveloperMissing -> stringResource(R.string.covers43_login_dev_missing)
        ScreenScraperService.LoginResult.DeveloperRejected -> stringResource(R.string.covers43_login_dev_rejected)
        ScreenScraperService.LoginResult.WrongLogin -> stringResource(R.string.covers43_login_wrong)
        is ScreenScraperService.LoginResult.QuotaReached -> {
            val until = result.until.takeIf { it > System.currentTimeMillis() } ?: ScreenScraper.nextReset(System.currentTimeMillis())
            stringResource(R.string.covers43_login_quota, DateUtils.formatDateTime(context, until, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY))
        }
        ScreenScraperService.LoginResult.Busy -> stringResource(R.string.covers43_login_busy)
        ScreenScraperService.LoginResult.Offline -> stringResource(R.string.covers43_login_offline)
    }
}

/** One source of the chain: its place, name, what it is and does now; switch, and move up / down. */
@Composable
private fun SourceRow(
    position: Int,
    source: Source,
    hint: String,
    enabled: Boolean,
    canUp: Boolean,
    canDown: Boolean,
    onToggle: (Boolean) -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    /** RomM stays first in the cover chain; in the screenshots chain nothing is pinned. */
    pinned: Boolean = source == Source.ROMM
) {
    val name = sourceName(source)
    SettingRow(
        icon = sourceIcon(source),
        title = stringResource(R.string.covers43_source_place, position, name),
        hint = hint,
        onClick = { onToggle(!enabled) }
    ) {
        if (!pinned) {
            MoveButton(R.drawable.ic_arrow_upward, stringResource(R.string.covers43_move_up, name), canUp, onUp)
            MoveButton(R.drawable.ic_arrow_downward, stringResource(R.string.covers43_move_down, name), canDown, onDown)
        }
        ThemedSwitch(enabled, onToggle)
    }
}

/** A 48 dp icon button with the focus ring; not focusable while it can do nothing. */
@Composable
private fun MoveButton(icon: Int, description: String, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    Box(
        modifier = Modifier
            .size(48.dp)
            .focusRing(source, 10.dp)
            .background(if (enabled) scheme.surfaceContainerHigh else scheme.surfaceContainer, RoundedCornerShape(10.dp))
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painterResource(icon), contentDescription = description,
            tint = if (enabled) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.size(20.dp)
        )
    }
}

/** A password row never shows any of its characters: fixed bullets when set, else "Not set". */
@Composable
private fun secretState(value: String): String =
    if (value.isEmpty()) stringResource(R.string.settings_not_set) else "••••••••"

@Composable
private fun CredentialRow(icon: Int, title: Int, value: String, onEdit: () -> Unit) {
    SettingRow(icon = icon, title = stringResource(title), hint = value, onClick = onEdit) {
        ActionPill(stringResource(R.string.settings_change), onEdit, icon = R.drawable.ic_edit)
    }
}

/** Edits one credential; a password is typed hidden. Opens with the field focused, B cancels. */
@Composable
private fun CredentialDialog(title: String, hint: String, value: String, secret: Boolean, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(value) }
    val fieldFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(title) },
                    singleLine = true,
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus)
                )
            }
        },
        confirmButton = { DialogButton(text = stringResource(R.string.dialog_save), onClick = { onSave(text); onDismiss() }) },
        dismissButton = { DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss) }
    )
}
