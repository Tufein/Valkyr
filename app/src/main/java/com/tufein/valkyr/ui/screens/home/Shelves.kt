package com.tufein.valkyr.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.common.Gamepad
import com.tufein.valkyr.ui.components.ConsoleTag
import com.tufein.valkyr.ui.components.CoverCard
import com.tufein.valkyr.ui.components.CoverCardCoverHeight
import com.tufein.valkyr.ui.components.CoverCardWidth
import com.tufein.valkyr.ui.components.CoverImage
import com.tufein.valkyr.ui.components.EmptyState
import com.tufein.valkyr.ui.components.GameCover
import com.tufein.valkyr.ui.components.SectionTitle
import com.tufein.valkyr.ui.components.SeeAllCoverCard
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.rememberFocusSource
import com.tufein.valkyr.ui.components.stripExtension
import com.tufein.valkyr.ui.screens.cloud.saves.relativeTime
import com.tufein.valkyr.ui.theme.LocalDogmatixTokens
import com.tufein.valkyr.ui.theme.consoleColor
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.ui.navigation.NavRoutes
import com.tufein.valkyr.ui.navigation.OnTabReselect

/** Space at the sides of the shelves; the cards' focus halo and scale fit in it. */
private val ShelfGutter = 16.dp
private val HeroRadius = 20.dp

/**
 * The 3.8.0 Home start page ("covers first"): the game to continue as a big card with a big Play
 * button, "New for you" and one shelf of covers per console. Shown by [HomeScreen] instead of the
 * result list while nothing narrows the library; [ShelvesViewModel] gathers the games.
 *
 * @param onOpenGame opens the game page (console id, file name).
 * @param onPlay starts a game that is on the device (the game page's Play).
 * @param onOpenConsole a console shelf's "See all": the list filtered on that console.
 * @param onOpenForYou "New for you"'s "See all": the Up next / For you screen.
 * @param onShowList the whole library as the list (the empty state's action).
 * @param initialFocus taken by the hero's main button (or the first card) when [focusOnShow] is set.
 * @param focusOnShow take the focus once the shelves are known; [onFocusTaken] is called after.
 * @param header drawn above the hero, scrolling with the shelves (the console chips in landscape).
 * @param afterHero drawn under the hero (the RomM "Continue playing" shelf).
 * @param returnGame 3.9.0: the game (console id, file name) whose card or hero button takes [returnFocus].
 */
@Composable
fun HomeShelves(
    onOpenGame: (String, String) -> Unit,
    onPlay: (String, String) -> Unit,
    onOpenConsole: (String) -> Unit,
    onOpenForYou: () -> Unit,
    onShowList: () -> Unit,
    modifier: Modifier = Modifier,
    initialFocus: FocusRequester = remember { FocusRequester() },
    focusOnShow: Boolean = false,
    onFocusTaken: () -> Unit = {},
    landscape: Boolean = false,
    header: (@Composable () -> Unit)? = null,
    afterHero: (@Composable () -> Unit)? = null,
    returnGame: Pair<String, String>? = null,
    returnFocus: FocusRequester? = null,
    viewModel: ShelvesViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ShelvesContent(
        state = state,
        onOpenGame = onOpenGame,
        onPlay = onPlay,
        onOpenConsole = onOpenConsole,
        onOpenForYou = onOpenForYou,
        onShowList = onShowList,
        modifier = modifier,
        initialFocus = initialFocus,
        focusOnShow = focusOnShow,
        onFocusTaken = onFocusTaken,
        landscape = landscape,
        header = header,
        afterHero = afterHero,
        returnGame = returnGame,
        returnFocus = returnFocus
    )
}

/**
 * The shelves for a known [state] (null = still loading: static placeholders). Stateless, so it
 * can be previewed; [lookup] off draws the console-coloured cover placeholders only.
 */
@Composable
fun ShelvesContent(
    state: ShelvesModel.State?,
    onOpenGame: (String, String) -> Unit,
    onPlay: (String, String) -> Unit,
    onOpenConsole: (String) -> Unit,
    onOpenForYou: () -> Unit,
    onShowList: () -> Unit,
    modifier: Modifier = Modifier,
    initialFocus: FocusRequester = remember { FocusRequester() },
    focusOnShow: Boolean = false,
    onFocusTaken: () -> Unit = {},
    landscape: Boolean = false,
    header: (@Composable () -> Unit)? = null,
    afterHero: (@Composable () -> Unit)? = null,
    lookup: Boolean = true,
    returnGame: Pair<String, String>? = null,
    returnFocus: FocusRequester? = null
) {
    val hasTarget = state != null && !state.isEmpty
    // A on the hero is Play: the focus starts there (gamepad only: a touch user sees no ring),
    // once per visit, never while the cursor is parked after a section switch.
    // Keyed on the target only: a change of [focusOnShow] must not restart (and cancel) the retries.
    val wantFocus by rememberUpdatedState(focusOnShow)
    val focusTaken by rememberUpdatedState(onFocusTaken)
    LaunchedEffect(hasTarget) {
        if (!hasTarget || !wantFocus) return@LaunchedEffect
        if (Gamepad.connected.value && !Gamepad.pointerHidden.value) {
            for (attempt in 0 until 5) {
                if (runCatching { initialFocus.requestFocus() }.getOrDefault(false)) break
                withFrameNanos { }
            }
        }
        focusTaken()
    }
    if (state != null && state.isEmpty) {
        Column(modifier = modifier.fillMaxSize()) {
            header?.invoke()
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = stringResource(R.string.shelves38_empty_title),
                    message = stringResource(R.string.shelves38_empty_message),
                    icon = R.drawable.ic_grid,
                    actionLabel = stringResource(R.string.shelves38_show_list),
                    onAction = onShowList
                )
            }
        }
        return
    }
    // Which item takes the first focus: the hero's button, else the first card of the first shelf.
    val firstShelf = when {
        state == null || state.hero != null -> null
        state.forYou.isNotEmpty() -> "foryou"
        else -> state.shelves.firstOrNull()?.key
    }
    val coverWidth = if (landscape) CoverCardWidth else 128.dp
    val coverHeight = if (landscape) CoverCardCoverHeight else 174.dp
    val listState = rememberLazyListState()
    // 6.0: tapping the Library tab again brings the shelves back to the top.
    OnTabReselect(NavRoutes.Home.route) { listState.scrollToItem(0) }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        if (header != null) item(key = "header", contentType = "header") { header() }
        if (state == null) {
            item(key = "loading", contentType = "loading") { ShelvesPlaceholder(coverWidth, coverHeight) }
            return@LazyColumn
        }
        state.hero?.let { hero ->
            item(key = "hero", contentType = "hero") {
                HeroCard(
                    hero = hero,
                    landscape = landscape,
                    onPlay = { onPlay(hero.game.consoleId, hero.game.fileName) },
                    onOpen = { onOpenGame(hero.game.consoleId, hero.game.fileName) },
                    focus = initialFocus,
                    returnFocus = returnFocus?.takeIf { returnGame == hero.game.consoleId to hero.game.fileName },
                    lookup = lookup,
                    modifier = Modifier.padding(horizontal = ShelfGutter)
                )
            }
        }
        if (afterHero != null) item(key = "after-hero", contentType = "after-hero") {
            Box(Modifier.padding(horizontal = ShelfGutter - 6.dp)) { afterHero() }
        }
        if (state.forYou.isNotEmpty()) item(key = "foryou", contentType = "shelf") {
            CoverShelf(
                title = stringResource(R.string.shelves38_for_you_title),
                subtitle = null,
                icon = R.drawable.ic_sparkle,
                games = state.forYou,
                onOpenGame = onOpenGame,
                firstFocus = if (firstShelf == "foryou") initialFocus else null,
                returnGame = returnGame,
                returnFocus = returnFocus,
                coverWidth = coverWidth,
                coverHeight = coverHeight,
                lookup = lookup
            ) {
                SeeAllCoverCard(stringResource(R.string.shelves38_see_all), onOpenForYou, width = coverWidth, coverHeight = coverHeight)
            }
        }
        items(state.shelves, key = { it.key }, contentType = { "shelf" }) { shelf ->
            val short = ConsoleFormatter.getConsoleShortName(shelf.consoleId)
            CoverShelf(
                title = ConsoleFormatter.getConsoleDisplayName(shelf.consoleId),
                subtitle = when (shelf.kind) {
                    ShelvesModel.ShelfKind.ON_DEVICE -> stringResource(R.string.shelves38_on_device_count, shelf.total)
                    ShelvesModel.ShelfKind.NEWEST -> stringResource(R.string.shelves38_newest)
                },
                dot = consoleColor(shelf.consoleId),
                games = shelf.games,
                onOpenGame = onOpenGame,
                firstFocus = if (firstShelf == shelf.key) initialFocus else null,
                returnGame = returnGame,
                returnFocus = returnFocus,
                coverWidth = coverWidth,
                coverHeight = coverHeight,
                lookup = lookup
            ) {
                SeeAllCoverCard(
                    stringResource(R.string.shelves38_see_all_console, short),
                    onClick = { onOpenConsole(shelf.consoleId) },
                    consoleId = shelf.consoleId,
                    width = coverWidth,
                    coverHeight = coverHeight
                )
            }
        }
    }
}

/** A titled row of cover cards ending in [seeAll]; ◀ ▶ move along it, ▲ ▼ to the shelves around it. */
@Composable
private fun CoverShelf(
    title: String,
    subtitle: String?,
    games: List<ShelvesModel.Game>,
    onOpenGame: (String, String) -> Unit,
    firstFocus: FocusRequester?,
    coverWidth: Dp,
    coverHeight: Dp,
    lookup: Boolean,
    icon: Int? = null,
    dot: Color? = null,
    returnGame: Pair<String, String>? = null,
    returnFocus: FocusRequester? = null,
    seeAll: @Composable () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = ShelfGutter + 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            dot?.let { Box(Modifier.size(10.dp).clip(CircleShape).background(it)) }
            icon?.let { Icon(painterResource(it), contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp)) }
            Text(title, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface, modifier = Modifier.weight(1f, fill = false))
            subtitle?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant) }
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            // Room around the cards for the focus halo and the 1.04 scale.
            contentPadding = PaddingValues(horizontal = ShelfGutter, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(games.size, key = { games[it].key }, contentType = { "cover" }) { index ->
                val game = games[index]
                CoverCard(
                    consoleId = game.consoleId,
                    fileName = game.fileName,
                    title = remember(game.title) { stripExtension(game.title) },
                    onClick = { onOpenGame(game.consoleId, game.fileName) },
                    owned = game.owned,
                    width = coverWidth,
                    coverHeight = coverHeight,
                    lookup = lookup,
                    modifier = (if (index == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier).then(
                        if (returnFocus != null && returnGame != null && returnGame.first == game.consoleId && returnGame.second == game.fileName)
                            Modifier.focusRequester(returnFocus) else Modifier
                    )
                )
            }
            item(key = "all", contentType = "all") { seeAll() }
        }
    }
}

/**
 * "Continue playing": the newest game played or downloaded, with its cover, when that was and one
 * big button. On the device that button is Play (it starts the game the way the game page does);
 * otherwise it opens the game page. The second button always opens the page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HeroCard(
    hero: ShelvesModel.Recent,
    landscape: Boolean,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    focus: FocusRequester,
    lookup: Boolean,
    modifier: Modifier = Modifier,
    returnFocus: FocusRequester? = null
) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalDogmatixTokens.current
    val game = hero.game
    val title = remember(game.title) { stripExtension(game.title) }
    val shape = RoundedCornerShape(HeroRadius)
    val glow = lerp(scheme.surfaceContainer, consoleColor(game.consoleId), if (tokens.isDark) 0.22f else 0.16f)
    val ground = scheme.surfaceContainer
    val coverWidth = if (landscape) 120.dp else 108.dp
    val coverHeight = if (landscape) 162.dp else 146.dp
    val whenText = relativeTime(hero.at)?.let {
        stringResource(if (hero.kind == ShelvesModel.RecentKind.PLAYED) R.string.shelves38_played_ago else R.string.shelves38_downloaded_ago, it)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            // A plain console-tinted gradient, drawn once: no blur, no offscreen layer.
            .drawBehind { drawRect(Brush.horizontalGradient(listOf(glow, ground))) }
            .border(1.dp, tokens.hairline, shape)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val coverModifier = Modifier.size(coverWidth, coverHeight)
        val coverShape = RoundedCornerShape(14.dp)
        if (lookup) GameCover(game.consoleId, game.fileName, title, coverModifier, coverShape)
        else CoverImage(null, game.consoleId, coverModifier, coverShape)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Back from this game's page the cursor returns to the hero's main button.
            val mainFocus = Modifier.focusRequester(focus).then(returnFocus?.let { Modifier.focusRequester(it) } ?: Modifier)
            SectionTitle(stringResource(R.string.shelves38_continue_title), icon = R.drawable.ic_play_circle)
            Text(
                title,
                style = if (landscape) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                color = scheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConsoleTag(game.consoleId)
                val line = listOfNotNull(whenText, if (!game.owned) stringResource(R.string.shelves38_not_on_device) else null).joinToString(" · ")
                if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(2.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (game.owned) {
                    HeroButton(stringResource(R.string.shelves38_play), R.drawable.ic_play_arrow, primary = true, onClick = onPlay, modifier = mainFocus)
                    HeroButton(stringResource(R.string.shelves38_game_page), R.drawable.ic_info, primary = false, onClick = onOpen)
                } else {
                    HeroButton(stringResource(R.string.shelves38_game_page), R.drawable.ic_info, primary = true, onClick = onOpen, modifier = mainFocus)
                }
            }
        }
    }
}

/** A big rounded button (56 dp): accent-filled for the main action, tonal for the other. Its label is never cut. */
@Composable
private fun HeroButton(text: String, icon: Int, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    val shape = RoundedCornerShape(16.dp)
    val fg = if (primary) scheme.onPrimary else scheme.onSurface
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 56.dp, minWidth = 56.dp)
            .focusRing(source, cornerRadius = 16.dp, onAccent = primary, fill = !primary)
            .clip(shape)
            .background(if (primary) scheme.primary else scheme.surfaceContainerHigh)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = fg, modifier = Modifier.size(24.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = fg)
    }
}

/** While the shelves load: a hero and a row of covers as static blocks (nothing animates). */
@Composable
private fun ShelvesPlaceholder(coverWidth: Dp, coverHeight: Dp) {
    val strong = MaterialTheme.colorScheme.surfaceContainerHigh
    val soft = MaterialTheme.colorScheme.surfaceContainer
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(
            Modifier
                .padding(horizontal = ShelfGutter)
                .fillMaxWidth()
                .height(178.dp)
                .background(soft, RoundedCornerShape(HeroRadius))
        )
        Box(Modifier.padding(horizontal = ShelfGutter + 4.dp).width(140.dp).height(14.dp).background(strong, RoundedCornerShape(6.dp)))
        Row(
            modifier = Modifier.padding(horizontal = ShelfGutter),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            repeat(6) { Box(Modifier.size(coverWidth, coverHeight).background(strong, RoundedCornerShape(16.dp))) }
        }
    }
}
