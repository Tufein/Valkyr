package com.tufein.valkyr.ui.shortcut

import android.app.Activity
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.tufein.valkyr.R
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.local.LookSettings
import com.tufein.valkyr.data.local.SettingsDataStore
import com.tufein.valkyr.data.service.AppShortcutService
import com.tufein.valkyr.ui.common.Gamepad
import com.tufein.valkyr.ui.components.EmptyState
import com.tufein.valkyr.ui.components.IconTile
import com.tufein.valkyr.ui.components.LocalBoldFocus
import com.tufein.valkyr.ui.components.NavChevron
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.ScreenTitle
import com.tufein.valkyr.ui.components.SectionTitle
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.pillColors
import com.tufein.valkyr.ui.components.rememberFocusSource
import com.tufein.valkyr.ui.theme.AccentPresets
import com.tufein.valkyr.ui.theme.ConsolePalette
import com.tufein.valkyr.ui.theme.DogmatixTheme
import com.tufein.valkyr.ui.theme.LocalDogmatixTokens
import com.tufein.valkyr.ui.theme.LocalReduceMotion
import com.tufein.valkyr.ui.theme.ThemeMode
import com.tufein.valkyr.ui.theme.consoleColor
import com.tufein.valkyr.ui.theme.dogmatixBackground
import com.tufein.valkyr.util.AppShortcut
import com.tufein.valkyr.util.ConsoleFormatter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The "create shortcut" picker (`ACTION_CREATE_SHORTCUT`): a launcher or a frontend such as Cocoon
 * opens it when the user adds a Dogmatix+ shortcut, and gets back the chosen console, saved view,
 * section or (3.5) recently played game. It wears the app's own theme and accent, and works with the D-pad.
 */
@AndroidEntryPoint
class CreateShortcutActivity : AppCompatActivity() {

    @Inject lateinit var shortcuts: AppShortcutService
    @Inject lateinit var settings: SettingsDataStore
    @Inject lateinit var look: LookSettings
    @Inject lateinit var appSettings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        val systemAnimationsOff = runCatching {
            Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
        setContent {
            val themeName by settings.themeMode.collectAsStateWithLifecycle(initialValue = null)
            val accentHex by settings.accentColor.collectAsStateWithLifecycle(initialValue = null)
            val glow by look.glow.collectAsStateWithLifecycle(initialValue = true)
            val animations by look.animations.collectAsStateWithLifecycle(initialValue = true)
            val boldFocus by appSettings.boldFocus.collectAsStateWithLifecycle(initialValue = false)
            val accessiblePalette by appSettings.accessiblePalette.collectAsStateWithLifecycle(initialValue = false)
            ConsolePalette.accessible = accessiblePalette
            val swapFaceButtons by settings.swapFaceButtons.collectAsStateWithLifecycle(initialValue = false)
            // This activity can start before the main one: the pad setting is process-wide state.
            LaunchedEffect(swapFaceButtons) { Gamepad.swapFaceButtons.value = swapFaceButtons }
            val name = themeName
            val hex = accentHex
            // The settings arrive in a few milliseconds; nothing is drawn in the default look first.
            if (name != null && hex != null) {
                DogmatixTheme(themeMode = ThemeMode.fromName(name), accent = AccentPresets.fromHex(hex), glow = glow) {
                    CompositionLocalProvider(
                        LocalBoldFocus provides boldFocus,
                        LocalReduceMotion provides (!animations || systemAnimationsOff),
                        LocalContentColor provides MaterialTheme.colorScheme.onBackground
                    ) {
                        var options by remember { mutableStateOf<List<AppShortcut>?>(null) }
                        LaunchedEffect(Unit) { options = runCatching { shortcuts.available() }.getOrDefault(emptyList()) }
                        ShortcutPicker(options, onPick = { choose(it) }, onClose = { finish() })
                    }
                }
            }
        }
    }

    private var choosing = false

    private fun choose(option: AppShortcut) {
        if (choosing) return
        choosing = true
        // A game's shortcut loads its cover first (3.5); the others are ready at once.
        lifecycleScope.launch {
            val shortcut = runCatching { shortcuts.shortcutFor(option) }.getOrNull()
            if (shortcut != null) setResult(Activity.RESULT_OK, ShortcutManagerCompat.createShortcutResultIntent(this@CreateShortcutActivity, shortcut))
            finish()
        }
    }
}

/** The groups the picker lists, in the order [com.tufein.valkyr.util.AppShortcuts.plan] makes them. */
private enum class Kind(val label: Int, val icon: Int) {
    SECTION(R.string.shortcut_group_sections, R.drawable.ic_download),
    /** 3.5: recently played games, which start straight in their emulator. */
    GAME(R.string.pin35_group_recent, R.drawable.ic_play_circle),
    VIEW(R.string.shortcut_group_views, R.drawable.ic_bookmark),
    CONSOLE(R.string.shortcut_group_consoles, R.drawable.ic_controller)
}

private fun kindOf(option: AppShortcut): Kind = when {
    option.game != null -> Kind.GAME
    option.id.startsWith("console:") -> Kind.CONSOLE
    option.route != null || option.id.startsWith("section:") -> Kind.SECTION
    else -> Kind.VIEW
}

@Composable
private fun ShortcutPicker(options: List<AppShortcut>?, onPick: (AppShortcut) -> Unit, onClose: () -> Unit) {
    val firstFocus = remember { FocusRequester() }
    // Focus lands on the first row once the list is there, so the D-pad works at once.
    LaunchedEffect(options) {
        if (options.isNullOrEmpty()) return@LaunchedEffect
        repeat(8) {
            withFrameNanos { }
            if (runCatching { firstFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .dogmatixBackground()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .closeOnGamepadB(onClose),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize().padding(horizontal = 16.dp)) {
            ScreenTitle(
                text = stringResource(R.string.shortcut_pick_title),
                subtitle = stringResource(R.string.shortcut_pick_subtitle),
                icon = R.drawable.ic_link,
                modifier = Modifier.padding(top = 16.dp, bottom = 12.dp),
                trailing = { CloseButton(onClose) }
            )
            when {
                options == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 4.dp)
                        Text(stringResource(R.string.shortcut_loading), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                options.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        title = stringResource(R.string.shortcut_empty_title),
                        message = stringResource(R.string.shortcut_empty_message),
                        icon = R.drawable.ic_link
                    )
                }
                else -> {
                    val groups = remember(options) { options.groupBy { kindOf(it) } }
                    val firstId = options.first().id
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        groups.forEach { (kind, rows) ->
                            item(key = "group:${kind.name}") {
                                SectionTitle(stringResource(kind.label), icon = kind.icon, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                            }
                            items(rows, key = { it.id }) { option ->
                                ShortcutRow(
                                    option = option,
                                    kind = kind,
                                    focus = if (option.id == firstId) firstFocus else null,
                                    onClick = { onPick(option) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A round close button for touch (the gamepad's B and the system back do the same). */
@Composable
private fun CloseButton(onClick: () -> Unit) {
    val source = rememberFocusSource()
    Box(
        modifier = Modifier
            .size(48.dp)
            .focusRing(source, cornerRadius = 24.dp)
            .clip(CircleShape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painterResource(R.drawable.ic_close),
            contentDescription = stringResource(R.string.shortcut_close),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
    }
}

/** One choice: its icon (in the console's colour for a console), its name and, for a console, its short name. */
@Composable
private fun ShortcutRow(option: AppShortcut, kind: Kind, focus: FocusRequester?, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    val shape = RoundedCornerShape(12.dp)
    val hairline = LocalDogmatixTokens.current.hairline
    val consoleId = option.consoleId
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .background(scheme.surfaceContainer, shape)
            .border(1.dp, hairline, shape)
            .focusRing(source, cornerRadius = 12.dp)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (consoleId != null) {
            val (container, tint) = pillColors(PillTone.Tint(consoleColor(consoleId)))
            IconTile(kind.icon, size = 36.dp, container = container, tint = tint)
        } else {
            IconTile(kind.icon, size = 36.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(option.label, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (consoleId != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).background(consoleColor(consoleId), CircleShape))
                    Text(
                        ConsoleFormatter.getConsoleShortName(consoleId),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        NavChevron()
    }
}
