package com.tufein.valkyr.ui.screens.tools

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tufein.valkyr.ui.components.requestFocusWhenReady
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.EmptyState
import com.tufein.valkyr.ui.components.Panel
import com.tufein.valkyr.ui.components.PanelTone
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.ProgressRing
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.rememberFocusSource
import com.tufein.valkyr.ui.theme.accentInk
import com.tufein.valkyr.ui.theme.inkOf
import com.tufein.valkyr.ui.theme.tabular
import com.tufein.valkyr.util.CollectionBasis
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.ConsoleProgress
import java.text.NumberFormat
import androidx.compose.ui.unit.Dp

/**
 * Collection goals: "Do I have the whole set?" A ring for the whole collection, a ring per console
 * (against its DAT when one is imported, else against what the sources list, and it says which),
 * stars for the consoles you want to complete, and per console the list of what is missing.
 *
 * @param onOpenImportList opens *Import a list*; called right after the missing titles were handed
 *   over (the same route [DatScreen] navigates to: `NavRoutes.ImportList`).
 */
@Composable
fun CollectionGoalsScreen(
    onOpenImportList: () -> Unit,
    viewModel: CollectionGoalsViewModel = hiltViewModel()
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val missing by viewModel.missing.collectAsStateWithLifecycle()
    val open = missing
    BackHandler(enabled = open != null) { viewModel.close() }
    if (open == null) {
        ConsoleList(ui, onOpen = viewModel::open, onToggleGoal = viewModel::toggleGoal)
    } else {
        val row = ui.rows.firstOrNull { it.consoleId == open.consoleId }
        MissingList(open, row, viewModel, onOpenImportList)
    }
}

private fun count(n: Int): String = NumberFormat.getIntegerInstance().format(n)

@Composable
private fun basisShort(basis: CollectionBasis): String = stringResource(
    when (basis) {
        CollectionBasis.DAT_VERIFIED -> R.string.coll6_basis_short_verified
        CollectionBasis.DAT_NAMES -> R.string.coll6_basis_short_dat
        CollectionBasis.SOURCES -> R.string.coll6_basis_short_sources
    }
)

@Composable
private fun basisLong(basis: CollectionBasis): String = stringResource(
    when (basis) {
        CollectionBasis.DAT_VERIFIED -> R.string.coll6_basis_verified
        CollectionBasis.DAT_NAMES -> R.string.coll6_basis_dat
        CollectionBasis.SOURCES -> R.string.coll6_basis_sources
    }
)

/** The ring of a console or the whole collection: percent inside, a tinted check once complete. */
@Composable
private fun CompletionRing(owned: Int, total: Int, size: Dp, stroke: Dp, bigText: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val complete = total > 0 && owned >= total
    val color = if (complete) scheme.tertiary else scheme.primary
    val fraction = if (total <= 0) 0f else (owned.toFloat() / total).coerceIn(0f, 1f)
    val percent = if (total <= 0) 0 else if (owned >= total) 100 else (owned.toLong() * 100 / total).toInt().coerceIn(0, 99)
    ProgressRing(fraction, size = size, stroke = stroke, color = color) {
        if (complete) {
            Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = inkOf(scheme.tertiary), modifier = Modifier.size(size * 0.46f))
        } else {
            Text(
                stringResource(R.string.coll6_goals_percent, percent),
                style = (if (bigText) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelMedium).tabular(),
                color = scheme.onSurface,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ConsoleList(ui: CollectionUi, onOpen: (String) -> Unit, onToggleGoal: (String) -> Unit) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(ui.rows.isNotEmpty()) {
        if (ui.rows.isNotEmpty()) { firstFocus.requestFocusWhenReady() }
    }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.coll6_goals_title), icon = R.drawable.ic_collection_goals, subtitle = stringResource(R.string.coll6_goals_subtitle))
        when {
            ui.loading && ui.rows.isEmpty() -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.coll6_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ui.rows.isEmpty() -> EmptyState(
                title = stringResource(R.string.coll6_empty_title),
                message = stringResource(R.string.coll6_empty_message),
                illustration = R.drawable.brand_illustration,
                modifier = Modifier.fillMaxWidth()
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                item(key = "summary") { SummaryCard(ui) }
                val goals = ui.rows.filter { it.goal }
                val others = ui.rows.filter { !it.goal }
                if (goals.isNotEmpty()) {
                    item(key = "h-goals") { SectionHeader(stringResource(R.string.coll6_section_goals), icon = R.drawable.ic_star_fill) }
                    items(goals, key = { "g" + it.consoleId }) { row ->
                        ConsoleRow(row, onOpen, onToggleGoal, if (row === goals.first()) Modifier.focusRequester(firstFocus) else Modifier)
                    }
                    if (others.isNotEmpty()) item(key = "h-all") { SectionHeader(stringResource(R.string.coll6_section_all), icon = R.drawable.ic_library) }
                }
                items(others, key = { "c" + it.consoleId }) { row ->
                    ConsoleRow(row, onOpen, onToggleGoal, if (goals.isEmpty() && row === others.first()) Modifier.focusRequester(firstFocus) else Modifier)
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(ui: CollectionUi) {
    val scheme = MaterialTheme.colorScheme
    val complete = ui.total > 0 && ui.owned >= ui.total
    Panel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        tone = if (complete) PanelTone.Accent else PanelTone.Normal,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            CompletionRing(ui.owned, ui.total, size = 96.dp, stroke = 10.dp, bigText = true)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.coll6_goals_overall), style = MaterialTheme.typography.labelLarge, color = if (complete) scheme.onPrimaryContainer else scheme.onSurfaceVariant)
                Text(
                    stringResource(R.string.coll6_goals_count, count(ui.owned), count(ui.total)),
                    style = MaterialTheme.typography.headlineSmall.tabular(),
                    color = scheme.onSurface
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (complete) Pill(stringResource(R.string.coll6_goals_complete), tone = PillTone.Success, icon = R.drawable.ic_check_circle)
                    val measured = ui.rows.count { it.total > 0 }
                    Pill(pluralStringResource(R.plurals.coll6_goals_measured, measured, measured))
                }
            }
        }
    }
}

@Composable
private fun ConsoleRow(row: ConsoleProgress, onOpen: (String) -> Unit, onToggleGoal: (String) -> Unit, modifier: Modifier) {
    val lines = listOf(stringResource(R.string.coll6_goals_count, count(row.owned), count(row.total)) + "  ·  " + basisShort(row.basis))
    ToolRow(
        title = row.name,
        lines = lines,
        onClick = { onOpen(row.consoleId) },
        modifier = modifier,
        badge = if (row.complete) ({ Pill(stringResource(R.string.coll6_goals_complete), tone = PillTone.Success, icon = R.drawable.ic_check_circle) }) else null,
        leading = { CompletionRing(row.owned, row.total, size = 48.dp, stroke = 5.dp) },
        trailing = { GoalStar(row.goal, row.name) { onToggleGoal(row.consoleId) } }
    )
}

/** The star that makes a console a goal: its own focus stop right of the row. */
@Composable
private fun GoalStar(goal: Boolean, name: String, onClick: () -> Unit) {
    val source = rememberFocusSource()
    val description = stringResource(if (goal) R.string.coll6_goal_unset else R.string.coll6_goal_set, name)
    Row(
        modifier = Modifier
            .size(48.dp)
            .focusRing(source, cornerRadius = 10.dp)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            painterResource(if (goal) R.drawable.ic_star_fill else R.drawable.ic_star),
            contentDescription = null,
            tint = if (goal) accentInk() else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun MissingList(open: MissingUi, row: ConsoleProgress?, viewModel: CollectionGoalsViewModel, onOpenImportList: () -> Unit) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val wished by viewModel.wished.collectAsStateWithLifecycle()
    val name = row?.name ?: ConsoleFormatter.getConsoleDisplayName(open.consoleId)
    val titles = open.titles
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(open.consoleId, titles == null) { firstFocus.requestFocusWhenReady() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp)
    ) {
        item(key = "title") { ToolsTitle(stringResource(R.string.coll6_missing_title, name), icon = R.drawable.ic_collection_goals) }
        if (row != null) item(key = "ring") {
            val complete = row.complete
            Panel(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
                tone = if (complete) PanelTone.Accent else PanelTone.Normal,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    CompletionRing(row.owned, row.total, size = 72.dp, stroke = 8.dp, bigText = true)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.coll6_goals_count, count(row.owned), count(row.total)), style = MaterialTheme.typography.titleLarge.tabular(), color = scheme.onSurface)
                        Text(basisLong(row.basis), style = MaterialTheme.typography.bodySmall, color = if (complete) scheme.onPrimaryContainer else scheme.onSurfaceVariant)
                        if (row.basis == CollectionBasis.DAT_NAMES) {
                            Text(stringResource(R.string.coll6_basis_dat_hint), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                        if (complete) Pill(stringResource(R.string.coll6_goals_complete), tone = PillTone.Success, icon = R.drawable.ic_check_circle, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        when {
            titles == null -> item(key = "loading") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.coll6_missing_loading), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
            titles.isEmpty() -> item(key = "none") {
                EmptyState(
                    title = stringResource(R.string.coll6_goals_complete),
                    message = stringResource(R.string.coll6_goals_complete_message),
                    icon = R.drawable.ic_check_circle,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            else -> {
                item(key = "actions") {
                    ToolsActions {
                        ActionPill(
                            stringResource(R.string.coll6_find),
                            onClick = { if (viewModel.findMissing()) onOpenImportList() },
                            modifier = Modifier.focusRequester(firstFocus),
                            icon = R.drawable.ic_search,
                            tone = ActionTone.Accent
                        )
                        ActionPill(stringResource(R.string.coll6_wish_all), onClick = { viewModel.addShownToWishlist(context) }, icon = R.drawable.ic_wishlist)
                    }
                }
                item(key = "hint") {
                    Text(
                        pluralStringResource(R.plurals.coll6_missing_summary, titles.size, count(titles.size)) + "  ·  " + stringResource(R.string.coll6_find_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                    )
                }
                val shown = titles.take(open.shown)
                items(shown, key = { "m$it" }) { title ->
                    val done = CollectionGoalsViewModel.wishKey(open.consoleId, title) in wished
                    ToolRow(
                        title = title,
                        lines = emptyList(),
                        onClick = { viewModel.addToWishlist(context, title) },
                        icon = R.drawable.ic_gamepad,
                        trailing = {
                            Icon(
                                painterResource(if (done) R.drawable.ic_check_circle else R.drawable.ic_wishlist),
                                contentDescription = stringResource(if (done) R.string.coll6_wish_row_done else R.string.coll6_wish_row, title),
                                tint = if (done) scheme.tertiary else scheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    )
                }
                if (titles.size > shown.size) item(key = "more") {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(R.string.coll6_showing, count(shown.size), count(titles.size)),
                            style = MaterialTheme.typography.labelMedium.tabular(),
                            color = scheme.onSurfaceVariant
                        )
                        ActionPill(
                            stringResource(R.string.coll6_more, minOf(200, titles.size - shown.size)),
                            onClick = viewModel::showMore,
                            icon = R.drawable.ic_expand_more
                        )
                    }
                }
            }
        }
    }
}
