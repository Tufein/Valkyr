package com.tufein.valkyr.ui.screens.settings

import android.content.Intent
import android.content.res.Configuration
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.tufein.valkyr.BuildConfig
import com.tufein.valkyr.R
import com.tufein.valkyr.data.model.DebridProvider
import com.tufein.valkyr.ui.common.Gamepad
import com.tufein.valkyr.ui.common.GamepadButton
import com.tufein.valkyr.ui.common.GamepadLayout
import com.tufein.valkyr.ui.common.Legend
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.LegendEntry
import com.tufein.valkyr.ui.components.LocalBoldFocus
import com.tufein.valkyr.ui.components.MeterBar
import com.tufein.valkyr.ui.components.NavChevron
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.coverPlaceholder
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.focusScale
import com.tufein.valkyr.ui.components.formatBytes
import com.tufein.valkyr.ui.components.legendFor
import com.tufein.valkyr.ui.components.pillColors
import com.tufein.valkyr.ui.components.rememberFocusSource
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.ui.navigation.NavRoutes
import com.tufein.valkyr.ui.screens.cloud.saves.ContinuePlayingViewModel
import com.tufein.valkyr.ui.screens.settings.components.ApiKeyDialog
import com.tufein.valkyr.ui.screens.settings.components.DaijishoSetupDialog
import com.tufein.valkyr.ui.screens.settings.components.FavoriteLanguagesDialog
import com.tufein.valkyr.ui.screens.settings.components.maskedSecret
import com.tufein.valkyr.ui.screens.sources.SourcesViewModel
import com.tufein.valkyr.ui.screens.sources.components.ConfirmDialog
import com.tufein.valkyr.ui.theme.AccentPresets
import com.tufein.valkyr.ui.theme.LocalDogmatixTokens
import com.tufein.valkyr.util.ThemePreview
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import com.tufein.valkyr.ui.theme.LocalReduceMotion
import com.tufein.valkyr.ui.theme.Motion
import com.tufein.valkyr.ui.theme.ThemeMode
import com.tufein.valkyr.ui.theme.consoleColor
import com.tufein.valkyr.ui.theme.tabular
import com.tufein.valkyr.util.CardGrid
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.Constants
import com.tufein.valkyr.util.DownloadPolicy
import com.tufein.valkyr.util.FileParsingUtils
import com.tufein.valkyr.util.ToastUtil
import com.tufein.valkyr.util.SettingKeys
import com.tufein.valkyr.util.SettingsGroup
import com.tufein.valkyr.util.SettingsLayout
import com.tufein.valkyr.util.SettingsMasters
import com.tufein.valkyr.util.TorrentConstants
import com.tufein.valkyr.ui.screens.search.SEARCH_ALL_ROUTE
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date

/** A picked folder as the user reads it (".../Roms"), or null while none is set. */
internal fun readableFolder(uri: String): String? = when {
    uri.isBlank() -> null
    uri.startsWith("content://") -> FileParsingUtils.toUserReadablePath(uri)
    else -> uri
}

/** How long a row found through "search everything" stays lit up. */
internal const val JUMP_HIGHLIGHT_MS = 1600L

/** The tint behind a row found through search; [strength] (0..1) is read while drawing only. */
internal fun Modifier.jumpHighlight(color: Color, strength: () -> Float): Modifier = drawBehind {
    val a = strength()
    if (a > 0f) drawRoundRect(color.copy(alpha = 0.16f * a), cornerRadius = CornerRadius(8.dp.toPx()))
}

/** The cards of Settings ([SettingsGroup]); the old name stays for rows that name their card. */
internal typealias SettingsSection = SettingsGroup

/**
 * One Settings entry. [key] (see [SettingKeys]) is the stable name "search everything" jumps to
 * and gives the row its card and place through [SettingsLayout]; a row the layout does not know
 * goes into [section] (at its end). [visible] false leaves it out, as does a master switch that
 * is off ([SettingsLayout.isShown]).
 */
internal class SettingsRow(
    section: SettingsSection? = null,
    val visible: Boolean = true,
    val key: String? = null,
    val content: @Composable () -> Unit
) {
    val section: SettingsSection = SettingsLayout.groupOf(key) ?: section ?: SettingsSection.APP

    fun shown(masters: SettingsMasters): Boolean = visible && SettingsLayout.isShown(key, masters)
}

/**
 * The one full-width row that closes a card's everyday rows: "Advanced (N)" with a chevron. A / Enter
 * toggles, D-pad right opens and left closes; the rows it holds follow it in the D-pad order.
 */
@Composable
internal fun AdvancedToggleRow(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    SettingRow(
        icon = R.drawable.ic_tune,
        title = stringResource(R.string.settings51_advanced, count),
        hint = stringResource(if (expanded) R.string.settings51_advanced_hint_open else R.string.settings51_advanced_hint),
        onClick = onToggle,
        onAdjust = { if ((it > 0) != expanded) onToggle() }
    ) {
        Icon(
            painterResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
    }
}

/** A row that is shown, with its place in the full list (its focus requester and grid key). */
internal class ShownRow(val index: Int, val row: SettingsRow)

/** What the card headers summarise at a glance. */
internal class SectionPillState(
    val limitKb: Int,
    val schedule: ScheduleSettings,
    val rommConfigured: Boolean,
    val profile: String?,
    val autoBackup: Boolean,
    val autoBackupDirSet: Boolean,
    val autoBackupLast: Long,
    val updateProgress: Float?
)

/** The small status marks at the end of a card header (speed limit, download conditions, RomM…). */
@Composable
internal fun SectionPills(section: SettingsSection, state: SectionPillState) {
    when (section) {
        SettingsSection.DOWNLOADS -> {
            if (state.limitKb > 0) Pill("${state.limitKb} KB/s", tone = PillTone.Warning, icon = R.drawable.ic_speed)
        }
        SettingsSection.SCHEDULE -> {
            if (state.schedule.wifiOnly) StatusMark(R.drawable.ic_wifi, stringResource(R.string.settings_dl_wifi))
            if (state.schedule.chargingOnly) StatusMark(R.drawable.ic_charging, stringResource(R.string.settings_dl_charging))
            if (state.schedule.nightOnly) {
                Pill(
                    DownloadPolicy.formatMinutes(state.schedule.nightStart) + "–" + DownloadPolicy.formatMinutes(state.schedule.nightEnd),
                    tone = PillTone.Info,
                    icon = R.drawable.ic_night
                )
            }
        }
        SettingsSection.CLOUD -> {
            if (state.rommConfigured) Pill(stringResource(R.string.settings_v5_pill_set_up), tone = PillTone.Success, icon = R.drawable.ic_cloud_done)
            else Pill(stringResource(R.string.settings_v5_pill_not_set_up), tone = PillTone.Neutral, icon = R.drawable.ic_cloud_off)
        }
        SettingsSection.LIBRARY -> {
            state.profile?.let { Pill(it, modifier = Modifier.widthIn(max = 160.dp), tone = PillTone.Accent, icon = R.drawable.ic_account) }
        }
        SettingsSection.BACKUP -> {
            if (state.autoBackup && !state.autoBackupDirSet) {
                Pill(stringResource(R.string.settings_v5_pill_pick_folder), tone = PillTone.Warning, icon = R.drawable.ic_warning)
            } else if (state.autoBackup && state.autoBackupLast > 0) {
                Pill(DateUtils.getRelativeTimeSpanString(state.autoBackupLast).toString(), tone = PillTone.Success, icon = R.drawable.ic_check_circle)
            }
        }
        SettingsSection.APP -> {
            state.updateProgress?.let { Pill("${(it * 100).toInt()} %", tone = PillTone.Info, icon = R.drawable.ic_download) }
            Pill("v" + BuildConfig.VERSION_NAME, tone = PillTone.Neutral)
        }
        else -> Unit
    }
}

/** A round status mark with an icon only (narrow card headers); [label] is read out by TalkBack. */
@Composable
internal fun StatusMark(@DrawableRes icon: Int, label: String) {
    val (bg, fg) = pillColors(PillTone.Info)
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Icon(painterResource(icon), contentDescription = label, tint = fg, modifier = Modifier.size(13.dp))
    }
}

/** A sample game for the Look preview (a Nintendo console, so the placeholder shows its colour). */
internal const val PREVIEW_CONSOLE = "nintendo_super_nintendo_entertainment_system"

/**
 * A live sample of the look: a library row as it appears focused, in the current theme, accent,
 * text size and focus style, with or without its cover. Drawn once; it never animates.
 */
@Composable
internal fun LookPreview(listCovers: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val dark = LocalDogmatixTokens.current.isDark
    val bold = LocalBoldFocus.current
    val ring = scheme.primary
    val tonal = scheme.primary.copy(alpha = if (dark) 0.14f else 0.12f)
    val edge = scheme.scrim
    val chipColor = consoleColor(PREVIEW_CONSOLE)
    val shortName = ConsoleFormatter.getConsoleShortName(PREVIEW_CONSOLE)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.settings_v5_preview),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .drawWithCache {
                    val radius = 10.dp.toPx()
                    val stroke = (if (bold) 3.5.dp else 2.dp).toPx()
                    val halo = 3.dp.toPx()
                    onDrawBehind {
                        drawRoundRect(tonal, cornerRadius = CornerRadius(radius))
                        if (bold) {
                            drawRoundRect(
                                edge,
                                topLeft = Offset(stroke * 1.5f, stroke * 1.5f),
                                size = Size(size.width - stroke * 3, size.height - stroke * 3),
                                cornerRadius = CornerRadius((radius - stroke).coerceAtLeast(0f)),
                                style = Stroke(stroke / 2)
                            )
                        } else {
                            drawRoundRect(
                                ring.copy(alpha = 0.22f),
                                topLeft = Offset(-halo / 2, -halo / 2),
                                size = Size(size.width + halo, size.height + halo),
                                cornerRadius = CornerRadius(radius + halo / 2),
                                style = Stroke(halo)
                            )
                        }
                        drawRoundRect(
                            ring,
                            topLeft = Offset(stroke / 2, stroke / 2),
                            size = Size(size.width - stroke, size.height - stroke),
                            cornerRadius = CornerRadius(radius),
                            style = Stroke(stroke)
                        )
                    }
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (listCovers) {
                Box(
                    modifier = Modifier
                        .size(width = 30.dp, height = 40.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .coverPlaceholder(PREVIEW_CONSOLE),
                    contentAlignment = Alignment.Center
                ) {
                    Text(shortName, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold, color = if (LocalDogmatixTokens.current.isDark) Color.White.copy(alpha = 0.85f) else lerp(consoleColor(PREVIEW_CONSOLE), Color.Black, 0.5f), maxLines = 1)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.settings_v5_preview_game),
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(shortName, tone = PillTone.Tint(chipColor))
                    Pill(stringResource(R.string.settings_v5_preview_owned), tone = PillTone.Success, icon = R.drawable.ic_check)
                    Pill(stringResource(R.string.new_badge), tone = PillTone.Accent)
                }
            }
            Text(
                formatBytes(PREVIEW_SIZE),
                style = MaterialTheme.typography.bodySmall.tabular(),
                color = scheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

internal const val PREVIEW_SIZE = 4L * 1024 * 1024

/** The accent colours' names, in the order of [AccentPresets.all]. */
internal val accentNames = listOf(
    R.string.settings_v5_accent_orange,
    R.string.settings_v5_accent_amber,
    R.string.settings_v5_accent_lime,
    R.string.settings_v5_accent_green,
    R.string.settings_v5_accent_teal,
    R.string.settings_v5_accent_cyan,
    R.string.settings_v5_accent_blue,
    R.string.settings_v5_accent_indigo,
    R.string.settings_v5_accent_lilac,
    R.string.settings_v5_accent_magenta,
    R.string.settings_v5_accent_pink,
    R.string.settings_v5_accent_coral
)

@StringRes
internal fun accentName(color: Color): Int {
    if (color == AccentPresets.dynamic) return R.string.settings_v5_accent_dynamic
    return accentNames.getOrNull(AccentPresets.all.indexOf(color)) ?: R.string.settings_v5_accent_custom
}

/** The fill of an accent swatch; Material You shows as a four-colour wheel. */
internal fun swatchFill(color: Color): Modifier =
    if (color == AccentPresets.dynamic) Modifier.background(
        Brush.sweepGradient(listOf(Color(0xFFFF7F00), Color(0xFFD4E157), Color(0xFF3CC8FF), Color(0xFFE57BFF), Color(0xFFFF7F00)))
    ) else Modifier.background(color)

/** The current accent as a small circle at the end of its row; tapping it opens the choices. */
@Composable
internal fun AccentSwatch(color: Color, size: Dp, onClick: () -> Unit) {
    val source = rememberFocusSource()
    Box(
        modifier = Modifier
            .size(size)
            .focusRing(source, size / 2, onAccent = true, fill = false)
            .clip(CircleShape)
            .then(swatchFill(color))
            .border(width = 2.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f), shape = CircleShape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
    )
}

/** Every accent colour (plus Material You where the system has it), each with its name. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AccentDialog(selected: Color, themeModeName: String, onPick: (Color) -> Unit, onDismiss: () -> Unit) {
    val choices = AccentPresets.choices
    val firstFocus = rememberInitialFocus()
    // Focus starts on the colour in use, so ◀ ▶ moves from where the user is.
    val start = choices.indexOf(selected).coerceAtLeast(0)
    // The sample above the swatches follows the colour under focus, so ◀ ▶ previews before anything is picked.
    var shown by remember { mutableStateOf(selected) }
    val look = ThemePreview.resolve(themeModeName, LocalDogmatixTokens.current.isDark)
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.accent_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MiniThemeCard(look = look, accent = shown, active = false, modifier = Modifier.width(160.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    choices.forEachIndexed { i, color ->
                        AccentChoice(
                            color = color,
                            selected = color == selected,
                            onClick = { onPick(color) },
                            onFocused = { shown = color },
                            modifier = if (i == start) Modifier.focusRequester(firstFocus) else Modifier
                        )
                    }
                }
                if (AccentPresets.dynamicAvailable) Text(
                    stringResource(R.string.accent_dynamic_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { DialogButton(text = stringResource(R.string.dialog_close), onClick = onDismiss) }
    )
}

/** One choice of the accent dialog: a 48 dp swatch (a check on the one in use) and its name. */
@Composable
internal fun AccentChoice(color: Color, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, onFocused: () -> Unit = {}) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    if (source.collectIsFocusedAsState().value) LaunchedEffect(color) { onFocused() }
    Column(
        modifier = modifier
            .width(68.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .focusScale(source, 1.08f)
                .focusRing(source, 28.dp, onAccent = true, fill = false),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .then(swatchFill(color)),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(
                        painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        tint = Color.Black.copy(alpha = 0.8f),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
        Text(
            stringResource(accentName(color)),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
