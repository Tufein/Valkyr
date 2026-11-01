package com.tufein.valkyr.ui.screens.home.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tufein.valkyr.R
import com.tufein.valkyr.data.model.DownloadableFileWithTags
import com.tufein.valkyr.ui.components.GameCover
import com.tufein.valkyr.ui.components.LocalTvMode
import com.tufein.valkyr.ui.components.tvSized
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.TagRow
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.formatBytes
import com.tufein.valkyr.ui.components.pillColors
import com.tufein.valkyr.ui.components.rememberFocusSource
import com.tufein.valkyr.ui.components.stripExtension
import com.tufein.valkyr.ui.theme.LocalReduceMotion
import com.tufein.valkyr.ui.theme.tabular
import com.tufein.valkyr.util.RaStatus
import kotlinx.coroutines.flow.collectLatest

/** Size of the cover thumbnail in the one-line table row (portrait box art, 3:4). */
val TableCoverWidth: Dp = 30.dp
private val TableCoverHeight: Dp = 40.dp
private val StackedCoverWidth: Dp = 42.dp
private val StackedCoverHeight: Dp = 56.dp
/** 6.0 compact lists: smaller covers and tighter rows (min height stays above the 40 dp touch target). */
private val DenseTableCoverWidth: Dp = 24.dp
private val DenseTableCoverHeight: Dp = 32.dp
private val DenseStackedCoverWidth: Dp = 32.dp
private val DenseStackedCoverHeight: Dp = 42.dp
/** Row spacing of the library list: [com.tufein.valkyr.ui.screens.home] reads it for the compact setting. */
val RowGap: Dp = 2.dp
val DenseRowGap: Dp = 0.dp
/** Space between the cover and the badges / name. */
val CoverGap: Dp = 10.dp

/**
 * One library result. [compact] is the landscape table row; otherwise name and tags stack.
 * Tap downloads, a long press opens the details card (X does the same on a gamepad).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RomRow(
    item: DownloadableFileWithTags,
    consoleName: String,
    compact: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    owned: Boolean = false,
    /** The RomM server already has this game. */
    onRomm: Boolean = false,
    favourite: Boolean = false,
    downloading: Boolean = false,
    /** A recent rescan found this file (see [com.tufein.valkyr.util.NewGames]). */
    isNew: Boolean = false,
    /** RetroAchievements has achievements for this game (see RetroAchievementsService). */
    achievements: Boolean = false,
    /**
     * Horizontal shift (px) of the left-anchored content, read at placement time so the filter
     * panel animation can slide names along without re-measuring the row. 0 when idle.
     */
    contentShift: () -> Int = { 0 },
    /** 5.0: how many achievements RetroAchievements lists for the game (0 = unknown). */
    achievementCount: Int = 0,
    /** 5.0: a small cover in front of the name (Settings → Look). */
    showCover: Boolean = false,
    /**
     * 5.0: progress (0..1) of this game's download while [downloading]. Only read while drawing
     * the badge's ring, so progress ticks never recompose the row.
     */
    downloadProgress: () -> Float = { 0f },
    /** 6.0: compact lists (Settings → Look): tighter height and padding, smaller cover. */
    dense: Boolean = false,
    /** 5.5: the player marked this game as completed (a check mark in front of the name). */
    completed: Boolean = false,
    /** 5.5: the user's RetroAchievements status in this game; null shows nothing (also with RA off). */
    raStatus: RaStatus? = null,
    /** 6.0: how many versions of the game this row stands for ("Group versions"); 1 shows nothing. */
    versions: Int = 1
) {
    // 8.0 TV mode: never the compact rows; rows (and the stacked covers) grow. The table's cover and
    // column widths stay, so the header in HomeScreen keeps lining up; the TV text scale does the rest.
    val tight = dense && !LocalTvMode.current
    val source = rememberFocusSource()
    val rom = item.file
    // No clip: the focus halo is drawn just outside the row, and a clip would also cost a layer per row.
    val base = modifier
        .fillMaxWidth()
        .focusRing(source, cornerRadius = 10.dp, startShift = contentShift)
        .combinedClickable(interactionSource = source, indication = null, onClick = onClick, onLongClick = onLongClick)
    val nameColor = if (owned) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val badges: @Composable () -> Unit = {
        Badges(owned, onRomm, favourite, downloading, isNew, achievements, achievementCount, downloadProgress, completed, raStatus)
        if (versions > 1) Pill(androidx.compose.ui.res.pluralStringResource(R.plurals.var60_versions, versions, versions), tone = PillTone.Accent)
    }

    if (compact) {
        Row(
            modifier = base
                .defaultMinSize(minHeight = if (tight) 40.dp else tvSized(46.dp))
                .padding(horizontal = 14.dp, vertical = if (tight) 2.dp else if (showCover) 4.dp else tvSized(6.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.weight(1f).slidingCell(contentShift),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (showCover) {
                    GameCover(
                        consoleId = rom.consoleId,
                        fileName = rom.fileName,
                        title = rom.name,
                        // The extra end padding plus the row's 8 dp spacing make CoverGap, as in the table header.
                        modifier = Modifier.padding(end = CoverGap - 8.dp).size(
                            if (tight) DenseTableCoverWidth else TableCoverWidth, if (tight) DenseTableCoverHeight else TableCoverHeight
                        ),
                        shape = RoundedCornerShape(5.dp),
                        showLabel = false
                    )
                }
                badges()
                Text(
                    stripExtension(rom.name),
                    style = MaterialTheme.typography.bodyLarge,
                    color = nameColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TagRow(
                console = consoleName,
                tags = item.tags,
                extension = rom.fileExtension,
                maxLines = 1,
                consoleId = rom.consoleId,
                modifier = Modifier.width(300.dp)
            )
            SizeText(rom.fileSize, Modifier.width(64.dp))
        }
    } else {
        Row(
            modifier = base
                .defaultMinSize(minHeight = if (tight) 44.dp else tvSized(64.dp))
                .padding(horizontal = 12.dp, vertical = if (tight) 3.dp else if (showCover) 8.dp else tvSized(10.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.weight(1f).slidingCell(contentShift),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (tight) 8.dp else CoverGap + 2.dp)
            ) {
                if (showCover) {
                    GameCover(
                        consoleId = rom.consoleId,
                        fileName = rom.fileName,
                        title = rom.name,
                        modifier = Modifier.size(
                            if (tight) DenseStackedCoverWidth else tvSized(StackedCoverWidth), if (tight) DenseStackedCoverHeight else tvSized(StackedCoverHeight)
                        ),
                        shape = RoundedCornerShape(if (tight) 5.dp else 7.dp),
                        showLabel = !tight
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(if (tight) 2.dp else 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        badges()
                        Text(
                            stripExtension(rom.name),
                            style = MaterialTheme.typography.bodyLarge,
                            color = nameColor,
                            maxLines = if (tight) 1 else 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TagRow(
                        console = consoleName, tags = item.tags, extension = rom.fileExtension, consoleId = rom.consoleId,
                        maxLines = if (tight) 1 else Int.MAX_VALUE
                    )
                }
            }
            SizeText(rom.fileSize, Modifier.width(64.dp))
        }
    }
}

/**
 * Cell whose content is pushed [shift] px to the right and re-measured with the width that is
 * left, so the name ellipsises progressively while the filter panel slides. The cell keeps
 * reporting its full size, hence only this node re-measures per frame, not the row or the list.
 */
internal fun Modifier.slidingCell(shift: () -> Int): Modifier = clipToBounds().layout { measurable, constraints ->
    val dx = shift().coerceAtLeast(0)
    val available = (constraints.maxWidth - dx).coerceAtLeast(0)
    // Only cells whose text would actually be cut get narrower constraints; the others keep the
    // same constraints as the previous frame, which lets Compose skip their measurement entirely.
    val needed = measurable.maxIntrinsicWidth(constraints.maxHeight)
    val placeable = measurable.measure(
        if (available >= needed) constraints.copy(minWidth = 0)
        else constraints.copy(minWidth = 0, maxWidth = available)
    )
    layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(dx, 0) }
}

/** The marks in front of a name, in a fixed order: favourite, on device / downloading, RomM, new, RA. */
@Composable
private fun Badges(
    owned: Boolean,
    onRomm: Boolean,
    favourite: Boolean,
    downloading: Boolean,
    isNew: Boolean,
    achievements: Boolean,
    achievementCount: Int,
    downloadProgress: () -> Float,
    completed: Boolean = false,
    raStatus: RaStatus? = null
) {
    if (favourite) IconBadge(R.drawable.ic_star, PillTone.Accent, stringResource(R.string.favourite))
    if (completed) IconBadge(R.drawable.ic_verified, PillTone.Success, stringResource(R.string.done55_completed))
    if (downloading) DownloadingBadge(downloadProgress)
    else if (owned) IconBadge(R.drawable.ic_check, PillTone.Success, stringResource(R.string.owned))
    if (onRomm) Pill(stringResource(R.string.romm_badge), tone = PillTone.Info)
    if (isNew) Pill(stringResource(R.string.new_badge), tone = PillTone.Accent)
    // 5.5: a beaten / mastered game says so in place of the achievement count; one still in progress keeps the count.
    when {
        raStatus == RaStatus.MASTERED -> Pill(stringResource(R.string.done55_ra_mastered), tone = PillTone.Warning, icon = R.drawable.ic_trophy)
        raStatus == RaStatus.BEATEN -> Pill(stringResource(R.string.done55_ra_beaten), tone = PillTone.Info, icon = R.drawable.ic_trophy)
        achievements -> Pill(
            if (achievementCount > 0) achievementCount.toString() else stringResource(R.string.home_ra_badge),
            tone = PillTone.Warning,
            icon = R.drawable.ic_trophy
        )
    }
}

/** A round 18 dp mark with an icon in a [PillTone]'s colours (owned, favourite). */
@Composable
private fun IconBadge(icon: Int, tone: PillTone, description: String) {
    val (bg, fg) = pillColors(tone)
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Icon(painterResource(icon), contentDescription = description, tint = fg, modifier = Modifier.size(11.dp))
    }
}

/**
 * Down arrow in an accent circle whose rim fills with the download's progress. The progress is
 * animated between the twice-a-second samples and drawn in the draw phase only.
 */
@Composable
private fun DownloadingBadge(progress: () -> Float) {
    val scheme = MaterialTheme.colorScheme
    val reduce = LocalReduceMotion.current
    val current by rememberUpdatedState(progress)
    // Read without observation: the first value must not subscribe the composition to progress.
    val shown = remember { Animatable(Snapshot.withoutReadObservation { progress() }.coerceIn(0f, 1f)) }
    LaunchedEffect(reduce) {
        snapshotFlow { current().coerceIn(0f, 1f) }.collectLatest { target ->
            if (reduce) shown.snapTo(target) else shown.animateTo(target, tween(450, easing = LinearEasing))
        }
    }
    val fill = scheme.primaryContainer
    val track = scheme.primary.copy(alpha = 0.25f)
    val arc = scheme.primary
    val label = stringResource(R.string.downloading_badge)
    Box(
        modifier = Modifier
            .size(20.dp)
            .semantics { contentDescription = label }
            .drawBehind {
                val stroke = 2.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawCircle(fill)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                val sweep = 360f * shown.value
                if (sweep > 0f) drawArc(arc, -90f, sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painterResource(R.drawable.ic_arrow_down),
            contentDescription = null,
            tint = scheme.onPrimaryContainer,
            modifier = Modifier.size(11.dp)
        )
    }
}

@Composable
private fun SizeText(bytes: Long, modifier: Modifier) {
    Text(
        if (bytes > 0) formatBytes(bytes) else "",
        style = MaterialTheme.typography.bodySmall.tabular(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = modifier
    )
}
