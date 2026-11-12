package com.tufein.valkyr.ui.screens.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.LargePrimaryButton
import com.tufein.valkyr.ui.components.Panel
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.RoundAction
import com.tufein.valkyr.ui.components.SectionTitle
import com.tufein.valkyr.ui.components.formatBytes
import com.tufein.valkyr.ui.components.pillColors
import com.tufein.valkyr.ui.components.stripExtension
import com.tufein.valkyr.ui.screens.home.DetailsState
import com.tufein.valkyr.ui.screens.home.components.GameHero
import com.tufein.valkyr.ui.screens.home.components.GameHeroCover
import com.tufein.valkyr.ui.screens.home.components.rememberGameArt
import com.tufein.valkyr.ui.theme.consoleColor
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.GameHeaderModel
import com.tufein.valkyr.util.GameHeaderModel.Badge
import com.tufein.valkyr.util.GameHeaderModel.Quick
import com.tufein.valkyr.util.GamePageModel
import com.tufein.valkyr.util.NewGames
import com.tufein.valkyr.util.RommSource
import com.tufein.valkyr.util.TagClassifier
import java.text.DateFormat
import java.util.Date

/*
 * 3.8 "covers first" game page header: a big cover over the softened art, the full title, key chips
 * (console, year, size), the status badges, then one big main button and a row of round quick
 * actions. The rules live in GameHeaderModel; GamePage wires the actions.
 */

/** Corner radius of the page's cards (the 3.8 look: rounded, with air). */
val GamePageCardRadius: Dp = 20.dp

/** Gap between the page's cards. */
val GamePageGap: Dp = 16.dp

/**
 * The page's top for [state]: reads the art and the facts, then draws [GameHeaderLayout].
 * Portrait: the cover takes about 40% of the width with the title beside it. Landscape: the
 * header sits in the left column, the cover a fixed height beside the title.
 */
@Composable
internal fun GameHeader(
    state: DetailsState,
    owned: Boolean,
    downloading: Boolean,
    favourite: Boolean,
    onRomm: Boolean,
    isLandscape: Boolean,
    onBack: () -> Unit,
    /** "Use RomM" is on; off hides the RomM source badge. */
    rommOn: Boolean = true
) {
    val rom = state.item.file
    val (frontUrl, backdropUrl) = rememberGameArt(state)
    val title = state.details?.title?.takeIf { it.isNotBlank() } ?: stripExtension(rom.name)
    val region = remember(state.item.tags) { state.item.tags.firstOrNull { TagClassifier.kindOf(it) == TagClassifier.Kind.REGION } }
    // The first region is a badge; the other regions and the languages follow as tags.
    val extraTags = remember(state.item.tags) { GamePageModel.heroTags(state.item.tags).filter { it != region } }
    val fromRomm = rommOn && remember(rom.downloadUrl) { RommSource.romIdOf(rom.downloadUrl) != null }
    val found = remember(rom.firstSeenAt) {
        if (rom.firstSeenAt > 0L) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(rom.firstSeenAt)) to
            NewGames.isNew(rom.firstSeenAt, System.currentTimeMillis())
        else null
    }
    val badges = GameHeaderModel.badges(
        hasRegion = region != null,
        torrent = rom.isTorrent,
        fromRomm = fromRomm,
        downloading = downloading,
        owned = owned,
        onRomm = onRomm,
        verified = !rom.expectedHash.isNullOrBlank(),
        found = found != null,
        favourite = favourite
    )
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val coverHeight = if (isLandscape) 188.dp else (GameHeaderModel.portraitCoverWidth(screenWidth) * 4 / 3).dp
    GameHero(backdropUrl, rom.consoleId, Modifier.fillMaxWidth()) {
        GameHeaderLayout(
            title = title,
            consoleId = rom.consoleId,
            year = GameHeaderModel.year(state.details?.released),
            size = rom.fileSize.takeIf { it > 0 }?.let { formatBytes(it) },
            coverHeight = coverHeight,
            onBack = onBack,
            cover = { GameHeroCover(frontUrl, rom.consoleId, height = coverHeight, maxRatio = if (isLandscape) 1.2f else 1.0f) },
            badges = {
                badges.forEach { badge ->
                    when (badge) {
                        Badge.REGION -> region?.let { Pill(it, tone = PillTone.Neutral, icon = R.drawable.ic_globe) }
                        Badge.SOURCE_TORRENT -> Pill(stringResource(R.string.source_torrent), tone = PillTone.Neutral, icon = R.drawable.ic_p2p)
                        Badge.SOURCE_ROMM -> Pill(stringResource(R.string.source_romm), tone = PillTone.Info, icon = R.drawable.ic_cloud)
                        Badge.SOURCE_DIRECT -> Pill(stringResource(R.string.source_direct), tone = PillTone.Neutral, icon = R.drawable.ic_link)
                        Badge.DOWNLOADING -> Pill(stringResource(R.string.downloading_badge), tone = PillTone.Accent, icon = R.drawable.ic_download)
                        Badge.ON_DEVICE -> Pill(stringResource(R.string.home_pill_on_device), tone = PillTone.Success, icon = R.drawable.ic_check_circle)
                        Badge.ON_ROMM -> Pill(stringResource(R.string.home_pill_on_romm), tone = PillTone.Info, icon = R.drawable.ic_cloud_done)
                        Badge.VERIFIED -> Pill(stringResource(R.string.home_pill_verified), tone = PillTone.Success, icon = R.drawable.ic_verified)
                        Badge.FOUND -> found?.let { (date, fresh) ->
                            Pill(stringResource(R.string.home_pill_found, date), tone = if (fresh) PillTone.Accent else PillTone.Neutral, icon = R.drawable.ic_calendar_today)
                        }
                        Badge.FAVOURITE -> Pill(stringResource(R.string.favourite), tone = PillTone.Accent, icon = R.drawable.ic_star)
                    }
                }
                CompletedBadge(rom.consoleId, rom.fileName)
                RaStatusBadge(state.achievements)
                extraTags.forEach { Pill(it, tone = PillTone.Neutral, icon = R.drawable.ic_translate) }
            }
        )
    }
}

/**
 * The header's layout without any data loading (also what the screenshot gallery draws): a back
 * pill, the [cover] with the title and the key chips beside it, then the [badges]. The title is
 * never cut: it wraps as far as it needs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameHeaderLayout(
    title: String,
    consoleId: String,
    year: String?,
    size: String?,
    coverHeight: Dp,
    onBack: () -> Unit,
    cover: @Composable () -> Unit,
    badges: @Composable () -> Unit
) {
    BoxWithConstraints {
    // Beside the cover the title needs a real column. Upright phones give it ~170 dp; the landscape page's
    // left column on a handheld (~260 dp wide, 188 dp cover) left it ~70 dp, where the title broke letter by
    // letter. Then the cover sits on top and title and chips get the full width below it.
    val titleRoom = maxWidth - 32.dp - coverHeight * 0.75f - 16.dp
    val stacked = titleRoom < MinTitleBesideCoverDp.dp
    val titleBlock: @Composable (Modifier) -> Unit = { mod ->
        Column(
            modifier = mod,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom)
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                KeyChip(ConsoleFormatter.getConsoleShortName(consoleId), R.drawable.ic_gamepad, PillTone.Tint(consoleColor(consoleId)))
                year?.let { KeyChip(it, R.drawable.ic_calendar_today, PillTone.Neutral) }
                size?.let { KeyChip(it, R.drawable.ic_storage, PillTone.Neutral) }
            }
        }
    }
    Column(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ActionPill(stringResource(R.string.page8_back), onBack, modifier = Modifier.heightIn(min = 48.dp), icon = R.drawable.ic_chevron_left)
        if (stacked) {
            cover()
            titleBlock(Modifier.fillMaxWidth())
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
                cover()
                titleBlock(Modifier.weight(1f).heightIn(min = coverHeight * 0.5f))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { badges() }
    }
    }
}

/** The title column beside the cover keeps at least this much width (dp); narrower, the cover goes above the title. */
private const val MinTitleBesideCoverDp = 150

/** A key fact beside the cover (console, year, size): a [Pill] one size up, easy to read at arm's length. */
@Composable
private fun KeyChip(text: String, icon: Int, tone: PillTone) {
    val (bg, fg) = pillColors(tone)
    Row(
        modifier = Modifier
            .heightIn(min = 30.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/**
 * The page's one big button: Play for a game on the device, Download otherwise, "Downloading…"
 * while it runs (one button whose label changes, so the focus stays on it when a download starts).
 */
@Composable
fun GamePrimaryAction(
    primary: GamePageModel.Primary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    LargePrimaryButton(
        stringResource(
            when (primary) {
                GamePageModel.Primary.DOWNLOAD -> R.string.details_download
                GamePageModel.Primary.DOWNLOAD_AGAIN -> R.string.owned_download_again
                GamePageModel.Primary.DOWNLOADING -> R.string.page8_downloading
                GamePageModel.Primary.PLAY -> R.string.play_game
                GamePageModel.Primary.RESUME -> R.string.flow52_resume
                GamePageModel.Primary.RETRY -> R.string.flow52_retry
            }
        ),
        onClick = onClick,
        modifier = modifier,
        compact = compact,
        icon = when (primary) {
            GamePageModel.Primary.DOWNLOADING -> R.drawable.ic_downloading
            GamePageModel.Primary.PLAY -> R.drawable.ic_controller
            GamePageModel.Primary.RESUME -> R.drawable.ic_play_arrow
            GamePageModel.Primary.RETRY -> R.drawable.ic_retry
            else -> R.drawable.ic_download
        }
    )
}

/** The round quick actions under the main button, in [quick]'s order. */
@Composable
fun GameQuickActions(
    quick: List<Quick>,
    favourite: Boolean,
    onFavourite: () -> Unit,
    onHomeScreen: () -> Unit,
    onShare: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top
    ) {
        quick.forEach { action ->
            when (action) {
                Quick.FAVOURITE -> RoundAction(
                    icon = if (favourite) R.drawable.ic_star_fill else R.drawable.ic_star,
                    label = stringResource(R.string.favourite),
                    onClick = onFavourite,
                    selected = favourite,
                    contentDescription = stringResource(if (favourite) R.string.home_details_unfavourite else R.string.home_details_favourite),
                    modifier = Modifier.weight(1f)
                )
                Quick.HOME_SCREEN -> RoundAction(
                    icon = R.drawable.ic_shortcut,
                    label = stringResource(R.string.gamepage38_home_screen),
                    onClick = onHomeScreen,
                    contentDescription = stringResource(R.string.pin35_action),
                    modifier = Modifier.weight(1f)
                )
                Quick.SHARE -> RoundAction(
                    icon = R.drawable.ic_share,
                    label = stringResource(R.string.lead6_share),
                    onClick = onShare,
                    modifier = Modifier.weight(1f)
                )
                Quick.MORE -> RoundAction(
                    icon = R.drawable.ic_more_vert,
                    label = stringResource(R.string.gamepage38_more),
                    onClick = onMore,
                    contentDescription = stringResource(R.string.gamepage38_more_actions),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** The rounded "More actions" card: every other action of the page as large pills. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameMoreActions(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Panel(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(GamePageCardRadius),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionTitle(stringResource(R.string.gamepage38_more_actions), icon = R.drawable.ic_more_vert)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically
        ) { content() }
    }
}

/** The minimum height of the page's pills: a 48 dp touch target. */
val PageTarget: Modifier = Modifier.heightIn(min = 48.dp)
