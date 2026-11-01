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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.common.Gamepad
import com.tufein.valkyr.ui.components.SectionTitle
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.rememberFocusSource
import com.tufein.valkyr.ui.components.tvSized
import com.tufein.valkyr.ui.navigation.NavRoutes
import com.tufein.valkyr.ui.navigation.OnTabReselect
import com.tufein.valkyr.ui.theme.LocalDogmatixTokens
import com.tufein.valkyr.ui.theme.consoleColor
import com.tufein.valkyr.ui.theme.tabular
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.ConsoleHome

private val Gutter = 16.dp

/**
 * 6.0: the "Consoles" start page of the Library tab. Continue playing on top (the same card as the
 * shelves), the quick links Wishlist / For you / Collections, then a grid of console tiles starting
 * with "All games". A tile opens the list filtered to that console ([onOpenConsole]); "All games"
 * opens the whole list ([onShowAll]). The counts come from [ShelvesViewModel] (cached per rescan).
 * D-pad: the grid is a plain focus grid, A opens, B is handled by the screen (back to this page).
 *
 * @param initialFocus taken by the Continue card's button (else the first tile) when [focusOnShow] is set.
 */
@Composable
fun ConsolesHome(
    onOpenGame: (String, String) -> Unit,
    onPlay: (String, String) -> Unit,
    onOpenConsole: (String) -> Unit,
    onShowAll: () -> Unit,
    onOpenWishlist: () -> Unit,
    onOpenForYou: () -> Unit,
    onOpenCollections: () -> Unit,
    modifier: Modifier = Modifier,
    initialFocus: FocusRequester = remember { FocusRequester() },
    focusOnShow: Boolean = false,
    onFocusTaken: () -> Unit = {},
    landscape: Boolean = false,
    afterHero: (@Composable () -> Unit)? = null,
    returnGame: Pair<String, String>? = null,
    returnFocus: FocusRequester? = null,
    viewModel: ShelvesViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hero = state?.hero
    val tiles = state?.tiles.orEmpty()
    val wantFocus by rememberUpdatedState(focusOnShow)
    val focusTaken by rememberUpdatedState(onFocusTaken)
    // The same hand-over as the shelves: once the page has something to hold the cursor, gamepad only.
    LaunchedEffect(state != null) {
        if (state == null || !wantFocus) return@LaunchedEffect
        if (Gamepad.connected.value && !Gamepad.pointerHidden.value) {
            for (attempt in 0 until 5) {
                if (runCatching { initialFocus.requestFocus() }.getOrDefault(false)) break
                withFrameNanos { }
            }
        }
        focusTaken()
    }
    val gridState = rememberLazyGridState()
    OnTabReselect(NavRoutes.Home.route) { gridState.scrollToItem(0) }
    val overall = remember(tiles) { ConsoleHome.overall(tiles) }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(if (landscape) 3 else 2),
        modifier = modifier,
        contentPadding = PaddingValues(start = Gutter, end = Gutter, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (hero != null) item(key = "hero", span = { GridItemSpan(maxLineSpan) }, contentType = "hero") {
            HeroCard(
                hero = hero,
                landscape = landscape,
                onPlay = { onPlay(hero.game.consoleId, hero.game.fileName) },
                onOpen = { onOpenGame(hero.game.consoleId, hero.game.fileName) },
                focus = initialFocus,
                returnFocus = returnFocus?.takeIf { returnGame == hero.game.consoleId to hero.game.fileName },
                lookup = true
            )
        }
        if (afterHero != null) item(key = "after-hero", span = { GridItemSpan(maxLineSpan) }, contentType = "after-hero") {
            afterHero()
        }
        item(key = "links", span = { GridItemSpan(maxLineSpan) }, contentType = "links") {
            QuickLinks(onOpenWishlist, onOpenForYou, onOpenCollections)
        }
        item(key = "title", span = { GridItemSpan(maxLineSpan) }, contentType = "title") {
            SectionTitle(stringResource(R.string.home60_consoles_title), modifier = Modifier.padding(start = 4.dp, top = 2.dp), icon = R.drawable.ic_grid)
        }
        if (state != null) {
            item(key = "all", contentType = "tile") {
                ConsoleTileCard(
                    title = stringResource(R.string.home60_all_games),
                    total = overall.total,
                    here = overall.here,
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = onShowAll,
                    modifier = if (hero == null) Modifier.focusRequester(initialFocus) else Modifier
                )
            }
            items(tiles, key = { "console:${it.consoleId}" }, contentType = { "tile" }) { tile ->
                ConsoleTileCard(
                    title = ConsoleFormatter.getConsoleDisplayName(tile.consoleId),
                    total = tile.total,
                    here = tile.here,
                    tint = consoleColor(tile.consoleId),
                    onClick = { onOpenConsole(tile.consoleId) }
                )
            }
        }
    }
}

/** "N games · M here" in the app's language (the "here" part only when something is on the device). */
@Composable
private fun countsText(total: Int, here: Int): String {
    val games = pluralStringResource(R.plurals.home60_games, total, total)
    val hereText = if (here > 0) pluralStringResource(R.plurals.home60_here, here, here) else ""
    return ConsoleHome.countsLine(total, here, { games }, { hereText })
}

/** One console tile: tinted with the console's accent colour, name and counts, never cut. */
@Composable
private fun ConsoleTileCard(
    title: String,
    total: Int,
    here: Int,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalDogmatixTokens.current
    val source = rememberFocusSource()
    val shape = RoundedCornerShape(16.dp)
    val ground = lerp(scheme.surfaceContainer, tint, if (tokens.isDark) 0.18f else 0.14f)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = tvSized(88.dp))
            .focusRing(source, cornerRadius = 16.dp)
            .clip(shape)
            .background(ground)
            .border(1.dp, tokens.hairline, shape)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(tint))
            Text(title, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface, modifier = Modifier.weight(1f, fill = false))
        }
        Text(countsText(total, here), style = MaterialTheme.typography.labelMedium.tabular(), color = scheme.onSurfaceVariant)
    }
}

/** Wishlist, For you and Collections as three 48 dp chips that wrap on narrow screens. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickLinks(onWishlist: () -> Unit, onForYou: () -> Unit, onCollections: () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ChipGap),
        verticalArrangement = Arrangement.spacedBy(ChipGap),
        itemVerticalAlignment = Alignment.CenterVertically
    ) {
        LinkChip(stringResource(R.string.home60_link_wishlist), R.drawable.ic_wishlist, onWishlist)
        LinkChip(stringResource(R.string.home60_link_for_you), R.drawable.ic_sparkle, onForYou)
        LinkChip(stringResource(R.string.home60_link_collections), R.drawable.ic_collections, onCollections)
    }
}

@Composable
private fun LinkChip(label: String, icon: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier = Modifier
            .defaultMinSize(minHeight = ChipHeight)
            .background(scheme.surfaceContainer, shape)
            .border(1.dp, LocalDogmatixTokens.current.hairline, shape)
            .focusRing(source, 24.dp)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
    }
}
