package com.tufein.valkyr.ui.screens.cloud.sections

import android.text.format.DateUtils
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.CoverImage
import com.tufein.valkyr.ui.components.IconTile
import com.tufein.valkyr.ui.components.Panel
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.SectionTitle
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.rememberFocusSource
import com.tufein.valkyr.ui.theme.LocalReduceMotion
import com.tufein.valkyr.ui.theme.Motion
import com.tufein.valkyr.ui.theme.tabular
import com.tufein.valkyr.util.RommGameDetails
import com.tufein.valkyr.util.RommGameInfo
import com.tufein.valkyr.util.RommProps
import com.tufein.valkyr.util.ToastUtil
import kotlinx.coroutines.launch
import java.util.Locale

/*
 * Stage B: call once inside GameDetailsDialog (its extra-sections slot, below the body) as
 * RommGameSection(consoleId = state.item.file.consoleId, fileName = state.item.file.fileName,
 *                 showSummary = state.details?.description.isNullOrBlank())
 */

/**
 * The RomM part of a game's details dialog (5.0): the server's summary, genres, year, rating and
 * screenshots, and the account's play status and rating, which are written to RomM as they change
 * (a failure shows a toast and puts the old value back). Draws nothing when RomM is not set up or
 * does not have the game. Gamepad: the pills and the rating row are focusable; ◀ ▶ on the
 * rating row change it, ◀ ▶ on the screenshot strip scroll it.
 *
 * @param showSummary show RomM's summary (pass false when the dialog already shows one)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RommGameSection(
    consoleId: String,
    fileName: String,
    modifier: Modifier = Modifier,
    showSummary: Boolean = true
) {
    val viewModel: RommGameViewModel = hiltViewModel(key = "romm5-game|$consoleId|$fileName")
    LaunchedEffect(consoleId, fileName) { viewModel.load(consoleId, fileName) }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { kind ->
            ToastUtil.showError(context, context.getString(R.string.romm5_save_failed, context.getString(rommErrorText(kind))))
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.queued.collect { ToastUtil.showInfo(context, context.getString(R.string.man60_queued)) }
    }
    if (ui.phase == RommGamePhase.RESOLVING || ui.phase == RommGamePhase.ABSENT) return

    val scheme = MaterialTheme.colorScheme
    Panel(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconTile(R.drawable.ic_server, size = 30.dp)
            Column(modifier = Modifier.weight(1f)) {
                SectionTitle(stringResource(R.string.romm5_section_title))
                val platform = ui.info?.platformName.orEmpty()
                if (platform.isNotBlank()) {
                    Text(platform, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (ui.saving) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        val info = ui.info
        when {
            ui.phase == RommGamePhase.LOADING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.romm5_loading), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            ui.phase == RommGamePhase.FAILED || info == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.romm5_load_failed, stringResource(rommErrorText(ui.errorKind))),
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f)
                )
                ActionPill(stringResource(R.string.romm5_retry), onClick = viewModel::retry, icon = R.drawable.ic_retry)
            }
            else -> RommGameContent(info, ui, consoleId, showSummary, viewModel)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RommGameContent(
    info: RommGameInfo,
    ui: RommGameUiState,
    consoleId: String,
    showSummary: Boolean,
    viewModel: RommGameViewModel
) {
    val scheme = MaterialTheme.colorScheme
    val year = info.releaseYear
    val rating = info.ratingPercent
    if (year != null || rating != null || info.genres.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (year != null) Pill(year.toString(), tone = PillTone.Accent, icon = R.drawable.ic_calendar)
            if (rating != null) Pill(
                stringResource(R.string.romm5_rating_value, String.format(Locale.getDefault(), "%.1f", rating / 10.0)),
                tone = PillTone.Warning, icon = R.drawable.ic_star
            )
            info.genres.take(5).forEach { Pill(it) }
        }
    }
    if (showSummary && info.summary.isNotBlank()) {
        Text(info.summary, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface, maxLines = 8, overflow = TextOverflow.Ellipsis)
    }
    if (info.screenshots.isNotEmpty()) ScreenshotStrip(info.screenshots, consoleId)

    SectionTitle(stringResource(R.string.romm5_play_status), icon = R.drawable.ic_controller)
    if (!info.propsSupported) {
        Text(stringResource(R.string.romm5_props_unsupported), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RommProps.Pill.entries.forEach { pill ->
            StatusChip(
                label = stringResource(pillLabel(pill)),
                icon = pillIcon(pill),
                selected = RommProps.isOn(ui.props, pill),
                onClick = { viewModel.toggle(pill) }
            )
        }
    }
    RatingRow(rating = ui.props.rating, onStep = viewModel::stepRating)
    val lastPlayed = RommGameDetails.epochMillis(ui.props.lastPlayed)
    if (lastPlayed != null && lastPlayed > 0) {
        Text(
            stringResource(R.string.romm5_last_played, DateUtils.getRelativeTimeSpanString(lastPlayed, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()),
            style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant
        )
    }
}

/** A focusable on/off pill: accent fill when on (animated in the draw phase), ring and halo when focused. */
@Composable
private fun StatusChip(label: String, icon: Int, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val reduce = LocalReduceMotion.current
    val source = rememberFocusSource()
    val fill by animateColorAsState(
        targetValue = if (selected) scheme.primary else scheme.surfaceContainerHigh,
        animationSpec = Motion.spec<Color>(reduce, Motion.FAST),
        label = "romm5-chip"
    )
    val fg = if (selected) scheme.onPrimary else scheme.onSurface
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .defaultMinSize(minHeight = 48.dp)
            .focusRing(source, cornerRadius = 24.dp, onAccent = selected, fill = false)
            .clip(shape)
            .drawBehind { drawRect(fill) }
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

/** "Your rating": ◀ ▶ (D-pad or the arrows) from 0 to 10; A steps up and wraps to "not rated". */
@Composable
private fun RatingRow(rating: Int, onStep: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .focusRing(source, cornerRadius = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onStep(-1); true }
                    Key.DirectionRight -> { onStep(1); true }
                    else -> false
                }
            }
            .clickable(interactionSource = source, indication = null) { onStep(if (rating >= 10) -10 else 1) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(painterResource(R.drawable.ic_star), contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.romm5_your_rating), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            RatingDots(rating)
        }
        StepArrow(R.drawable.ic_chevron_left) { onStep(-1) }
        Text(
            if (rating == 0) stringResource(R.string.romm5_rating_none) else stringResource(R.string.romm5_your_rating_value, rating),
            style = MaterialTheme.typography.titleSmall.tabular(),
            color = if (rating == 0) scheme.onSurfaceVariant else scheme.onSurface,
            maxLines = 1,
            modifier = Modifier.width(72.dp),
            textAlign = TextAlign.Center
        )
        StepArrow(R.drawable.ic_chevron_right) { onStep(1) }
    }
}

/** Ten small dots, filled up to the rating. */
@Composable
private fun RatingDots(rating: Int) {
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = Modifier.width(86.dp).height(6.dp)) {
        val d = size.height
        val gap = (size.width - d * 10) / 9f
        for (i in 0 until 10) {
            val cx = d / 2 + i * (d + gap)
            drawCircle(if (i < rating) on else off, radius = d / 2, center = Offset(cx, size.height / 2))
        }
    }
}

/** A touch arrow of the rating row; not a focus stop (the row itself answers ◀ ▶). */
@Composable
private fun StepArrow(icon: Int, onClick: () -> Unit) {
    Icon(
        painterResource(icon),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick)
            .padding(13.dp)
    )
}

/** RomM's screenshots in a row; one focus stop whose ◀ ▶ scroll it (and leave it at either end). */
@Composable
private fun ScreenshotStrip(urls: List<String>, consoleId: String) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val reduce = LocalReduceMotion.current
    val source = rememberFocusSource()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle(stringResource(R.string.romm5_screenshots), icon = R.drawable.ic_photos)
        LazyRow(
            state = state,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .focusRing(source, cornerRadius = 12.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val step = when (event.key) {
                        Key.DirectionRight -> if (state.canScrollForward) 1 else return@onPreviewKeyEvent false
                        Key.DirectionLeft -> if (state.canScrollBackward) -1 else return@onPreviewKeyEvent false
                        else -> return@onPreviewKeyEvent false
                    }
                    val target = (state.firstVisibleItemIndex + step).coerceIn(0, urls.lastIndex)
                    scope.launch { if (reduce) state.scrollToItem(target) else state.animateScrollToItem(target) }
                    true
                }
                .focusable(interactionSource = source)
        ) {
            items(urls, key = { it }) { url ->
                CoverImage(url, consoleId, Modifier.width(176.dp).height(99.dp), showLabel = false)
            }
        }
    }
}

private fun pillLabel(pill: RommProps.Pill): Int = when (pill) {
    RommProps.Pill.NOW_PLAYING -> R.string.romm5_status_now_playing
    RommProps.Pill.BACKLOG -> R.string.romm5_status_backlog
    RommProps.Pill.INCOMPLETE -> R.string.romm5_status_incomplete
    RommProps.Pill.FINISHED -> R.string.romm5_status_finished
    RommProps.Pill.COMPLETED_100 -> R.string.romm5_status_completed
    RommProps.Pill.RETIRED -> R.string.romm5_status_retired
    RommProps.Pill.NEVER_PLAYING -> R.string.romm5_status_never
}

private fun pillIcon(pill: RommProps.Pill): Int = when (pill) {
    RommProps.Pill.NOW_PLAYING -> R.drawable.ic_play_circle
    RommProps.Pill.BACKLOG -> R.drawable.ic_bookmark
    RommProps.Pill.INCOMPLETE -> R.drawable.ic_hourglass
    RommProps.Pill.FINISHED -> R.drawable.ic_check_circle
    RommProps.Pill.COMPLETED_100 -> R.drawable.ic_trophy
    RommProps.Pill.RETIRED -> R.drawable.ic_archive
    RommProps.Pill.NEVER_PLAYING -> R.drawable.ic_block
}
