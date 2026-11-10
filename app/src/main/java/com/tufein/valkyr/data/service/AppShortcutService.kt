package com.tufein.valkyr.data.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.tufein.valkyr.MainActivity
import com.tufein.valkyr.R
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.local.GameShortcutSettings
import com.tufein.valkyr.data.local.dao.ConsoleDao
import com.tufein.valkyr.data.state.PendingLibraryFilters
import com.tufein.valkyr.ui.navigation.NavRoutes
import com.tufein.valkyr.util.ActionKind
import com.tufein.valkyr.util.AppShortcut
import com.tufein.valkyr.util.AppShortcuts
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.GameShortcuts
import com.tufein.valkyr.util.LibraryViews
import com.tufein.valkyr.util.QuickAction
import com.tufein.valkyr.util.QuickActions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * Android app shortcuts for Dogmatix+: Surprise me, Downloads and Search (7.0 quick access), the
 * recently played games (3.5, they start straight in their emulator), each saved view and each
 * console. Launchers show
 * them on a long press of the icon, and frontends that read app shortcuts (Cocoon picks them up in
 * *Add Games*) turn them into tiles that open Dogmatix+ on that console or view. They follow the
 * consoles, views and plays as those change. [shortcutFor] builds one on request, for the "add a
 * shortcut" picker a launcher or frontend opens.
 */
@Singleton
class AppShortcutService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val consoleDao: ConsoleDao,
    private val appSettings: AppSettings,
    private val actionLog: ActionLogService,
    private val profiles: ProfileService,
    private val library: LibraryIndexService,
    private val games: GameShortcutService,
    private val gameSettings: GameShortcutSettings
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The shortcuts to publish, and which of the games are confirmed on the device (their shortcuts may be enabled again). */
    private data class Menu(val plan: List<AppShortcut>, val onDevice: Set<String>)

    /** A recently played game for the menu, and whether the library index confirmed it is on the device. */
    private data class RecentEntry(val shortcut: AppShortcut, val onDevice: Boolean)

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            combine(consoleDao.getAllConsoles(), appSettings.libraryViews, recentEntries(), gameSettings.recentMenu) { consoles, views, recent, showRecent ->
                val plan = AppShortcuts.plan(
                    downloads(),
                    consoles.map { it.id to ConsoleFormatter.getConsoleDisplayName(it.id) },
                    LibraryViews.fromJson(views),
                    ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).takeIf { it > 0 } ?: AppShortcuts.MAX_DYNAMIC,
                    surprise(), search(),
                    games = recent.map { it.shortcut }, showGames = showRecent
                )
                Menu(plan, recent.filter { it.onDevice }.mapTo(HashSet()) { it.shortcut.id })
            }.distinctUntilChanged().debounce(2_000).collect {
                // A shortcut a launcher or the builder refuses must not end the collector (and the menu's updates with it).
                try { publish(it) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
        }
        scope.launch { followRemovals() }
    }

    private suspend fun publish(menu: Menu) {
        val shortcuts = menu.plan.mapIndexed { i, s -> buildAny(s, rank = i) }
        // A game that is on the device again (downloaded again, say) gets its disabled pinned icon back.
        shortcuts.filter { it.id in menu.onDevice }.takeIf { it.isNotEmpty() }?.let { back ->
            runCatching { ShortcutManagerCompat.enableShortcuts(context, back) }
        }
        val published = runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
        // Should a launcher refuse a game's shortcut, the rest of the menu is still kept up to date.
        if (published.isFailure) runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts.filter { GameShortcuts.parseId(it.id) == null }) }
    }

    /**
     * 3.5: the games last started from Dogmatix+ (the action history's "Played" lines of the active
     * profile, the app's own record of what it started), as library games still on the device,
     * newest first. Before the library index has looked at the device (no owned files known yet)
     * the games are kept, so the menu does not lose them on every start.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun recentEntries(): Flow<List<RecentEntry>> =
        combine(actionLog.entries.filterNotNull(), profiles.activeId, profiles.restrictions) { entries, profile, restrictions ->
            Triple(GameShortcuts.recentPlays(entries, profile, RECENT_LOOK_BACK), profile, restrictions)
        }.distinctUntilChanged()
            .mapLatest { (plays, _, _) -> runCatching { games.recentGames(plays) }.getOrDefault(emptyList()) }
            .combine(library.ownedKeys) { recent, keys ->
                recent.mapNotNull { game ->
                    val row = if (keys.isEmpty()) game.rows.first() else game.rows.firstOrNull { library.isOwned(it, keys) } ?: return@mapNotNull null
                    AppShortcuts.game(row.consoleId, row.fileName, games.labelOf(row))?.let { RecentEntry(it, onDevice = keys.isNotEmpty()) }
                }.distinctBy { it.shortcut.id }
            }
            .distinctUntilChanged()

    /**
     * 3.5: a game removed from the device (to the trash, from anywhere in the app) disables its
     * shortcuts, a restore enables them again. Only lines written while the app runs: older ones
     * were handled when they happened.
     */
    private suspend fun followRemovals() {
        var seen: MutableSet<String>? = null
        actionLog.entries.filterNotNull().collect { all ->
            val known = seen
            if (known == null) {
                seen = all.mapTo(HashSet()) { it.id }
                return@collect
            }
            for (entry in all) {
                if (!known.add(entry.id)) continue
                val consoleId = entry.consoleId?.takeIf { it.isNotBlank() } ?: continue
                val fileName = entry.fileName?.takeIf { it.isNotBlank() } ?: continue
                when (entry.kind) {
                    ActionKind.REMOVED -> games.disable(consoleId, fileName)
                    ActionKind.RESTORED -> games.enable(consoleId, fileName)
                    else -> Unit
                }
            }
        }
    }

    /** Everything that can become a shortcut, for the picker: the quick actions, recently played games, saved views, consoles. */
    suspend fun available(): List<AppShortcut> {
        val consoles = consoleDao.getAllConsoles().first()
        val views = LibraryViews.fromJson(appSettings.libraryViews.first())
        val recent = runCatching { withTimeoutOrNull(5_000) { recentEntries().first() }.orEmpty().map { it.shortcut } }.getOrDefault(emptyList())
        return AppShortcuts.plan(
            downloads(), consoles.map { it.id to ConsoleFormatter.getConsoleDisplayName(it.id) }, views, max = Int.MAX_VALUE,
            surprise = surprise(), search = search(), games = recent
        )
    }

    /** One shortcut for the picker; a game's loads its cover, so call it off the main thread. */
    suspend fun shortcutFor(shortcut: AppShortcut): ShortcutInfoCompat = buildAny(shortcut, rank = 0)

    private fun downloads() = AppShortcuts.downloads(context.getString(R.string.nav_downloads), NavRoutes.Downloads.route)

    private fun surprise() = AppShortcuts.surprise(context.getString(R.string.quick7_shortcut_surprise))

    private fun search() = AppShortcuts.search(context.getString(R.string.quick7_shortcut_search))

    private suspend fun buildAny(s: AppShortcut, rank: Int): ShortcutInfoCompat =
        GameShortcuts.parse(s.game)?.let { games.info(it, s.label, rank) } ?: build(s, rank)

    private fun build(s: AppShortcut, rank: Int): ShortcutInfoCompat {
        val intent = Intent(context, MainActivity::class.java).apply {
            // 7.0: Surprise me / Search travel as QuickActions.ACTION_QUICK + the action's key (MainActivity hands them on).
            action = if (s.quick != null) QuickActions.ACTION_QUICK else Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            s.deepLink?.let { data = Uri.parse(it) }
            s.route?.let { putExtra(PendingLibraryFilters.EXTRA_OPEN_ROUTE, it) }
            s.quick?.let { putExtra(QuickActions.EXTRA_QUICK_ACTION, it.key) }
        }
        val icon = when {
            s.quick == QuickAction.SURPRISE -> R.drawable.ic_shortcut_surprise
            s.quick == QuickAction.SEARCH -> R.drawable.ic_shortcut_search
            s.route != null -> R.drawable.ic_shortcut_downloads
            else -> R.drawable.ic_shortcut_library
        }
        return ShortcutInfoCompat.Builder(context, s.id)
            .setShortLabel(s.label.take(24))
            .setLongLabel(s.label)
            .setIcon(IconCompat.createWithResource(context, icon))
            .setIntent(intent)
            .setRank(rank)
            .apply { s.consoleId?.let { setCategories(setOf("com.tufein.valkyr.console.$it")) } }
            .build()
    }

    private companion object {
        /** Plays looked at for the menu's games: some may be gone from the library or the device. */
        const val RECENT_LOOK_BACK = 12
    }
}
