package com.tufein.valkyr.ui.secondscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tufein.valkyr.R
import com.tufein.valkyr.data.local.dao.DownloadableFileDao
import com.tufein.valkyr.data.model.DownloadableFileWithTags
import com.tufein.valkyr.data.service.ContinueItem
import com.tufein.valkyr.data.service.DownloadService
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.EmptyState
import com.tufein.valkyr.ui.components.GameCover
import com.tufein.valkyr.ui.components.Panel
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.stripExtension
import com.tufein.valkyr.ui.theme.consoleColor
import com.tufein.valkyr.ui.theme.tabular
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.DualScreen
import com.tufein.valkyr.util.PowerRules
import com.tufein.valkyr.util.SecondScreenControls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext

/**
 * The second-screen hub for dual-screen handhelds, drawn away from the library (in the library
 * the game under the cursor is shown instead): the game to continue, the recent games with their
 * box art and console chips as a quick filter, the download queue with a pause or resume button per
 * item, and the battery and heat of the device. It is touch only (no gamepad focus on the second
 * screen), so every target is at least 48 dp, and no text is cut off: long titles wrap and the
 * screen scrolls.
 *
 * Not tried on a device: how it looks and scrolls on the real lower screen of an AYN Thor or a
 * Retroid Flip. The sizes follow the screen's dp size through [DualScreen.layout].
 *
 * @param shelf the *Continue playing* games, newest first (RomM saves, or ES-DE plays without RomM)
 * @param shelfEnabled the *Continue playing* setting
 * @param rommOn the *Use RomM* setting; off hides what only RomM knows (the device a save came from)
 * @param onOpen a tapped game: the second screen shows its details
 */
@Composable
internal fun DualHub(
    queue: SecondScreenQueue,
    files: DownloadableFileDao,
    service: DownloadService,
    shelf: List<ContinueItem>,
    shelfEnabled: Boolean,
    rommOn: Boolean,
    onOpen: (DownloadableFileWithTags) -> Unit
) {
    var selected by remember { mutableStateOf<String?>(null) }
    val chips = remember(shelf) { DualScreen.chips(shelf.map { it.row.file.consoleId }) }
    val console = DualScreen.resolveSelection(selected, chips)
    val shown = remember(shelf, console) { DualScreen.filter(shelf, console) { it.row.file.consoleId } }
    val sections = DualScreen.sections(shown.size, shelf.size, shelfEnabled, chips.size, queue.controls.mode)
    val device = rememberDeviceState()

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val layout = DualScreen.layout(maxWidth.value.toInt(), maxHeight.value.toInt())
        val compact = DualScreen.compact(maxHeight.value.toInt())
        val pad = if (compact) 14.dp else 20.dp
        val gap = if (compact) 12.dp else 16.dp
        Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.spacedBy(gap)) {
            HubHeader(device)
            if (sections.empty) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    EmptyState(
                        title = stringResource(R.string.app_name),
                        message = stringResource(R.string.second_screen_idle),
                        illustration = R.drawable.brand_mark
                    )
                }
                return@Column
            }
            val games: @Composable () -> Unit = {
                GamesBlock(sections, shown, chips, console, rommOn, gap, onSelect = { selected = it }, onOpen = onOpen)
            }
            val downloads: @Composable () -> Unit = {
                if (sections.queue) QueueBlock(queue, files, service, gap)
            }
            if (layout == DualScreen.Layout.WIDE) {
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    val hasGames = sections.continueCard || sections.chips
                    if (hasGames) {
                        Column(Modifier.weight(1.25f).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(gap)) { games() }
                    }
                    if (sections.queue) {
                        Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(gap)) { downloads() }
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(gap)) {
                    games()
                    downloads()
                }
            }
        }
    }
}

/** The app name, and the battery and heat of the device in large chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HubHeader(device: DualScreen.DeviceState?) {
    val scheme = MaterialTheme.colorScheme
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            brandText(scheme.primary),
            style = MaterialTheme.typography.headlineSmall,
            color = scheme.onSurface,
            modifier = Modifier.heightIn(min = TOUCH).padding(end = 6.dp).align(Alignment.CenterVertically)
        )
        if (device != null) {
            val batteryText = device.batteryPercent?.let { stringResource(R.string.dual60_battery, it) } ?: stringResource(R.string.dual60_battery_unknown)
            StatusChip(
                icon = if (device.charging) R.drawable.ic_bolt else R.drawable.ic_battery,
                text = if (device.charging) stringResource(R.string.dual60_battery_charging, batteryText) else batteryText,
                level = device.battery
            )
            val heatText = when (device.heat) {
                DualScreen.Level.OK -> stringResource(R.string.dual60_heat_ok)
                DualScreen.Level.WARN -> stringResource(R.string.dual60_heat_warm)
                DualScreen.Level.CRITICAL -> stringResource(R.string.dual60_heat_hot)
            }
            StatusChip(
                icon = R.drawable.ic_thermostat,
                text = device.tempCelsius?.let { stringResource(R.string.dual60_heat_with_temp, heatText, Math.round(it)) } ?: heatText,
                level = device.heat
            )
        }
    }
}

/** A readable status chip: grey when fine, amber on a warning, red when critical. */
@Composable
private fun StatusChip(icon: Int, text: String, level: DualScreen.Level) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when (level) {
        DualScreen.Level.OK -> scheme.surfaceContainerHigh to scheme.onSurface
        DualScreen.Level.WARN -> Color(0xFFFFB300).copy(alpha = 0.22f) to Color(0xFFFFC94D)
        DualScreen.Level.CRITICAL -> scheme.errorContainer to scheme.onErrorContainer
    }
    Row(
        Modifier.heightIn(min = TOUCH).clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
        Text(text, style = MaterialTheme.typography.titleMedium.tabular(), color = fg)
    }
}

@Composable
private fun GamesBlock(
    sections: DualScreen.Sections,
    shown: List<ContinueItem>,
    chips: List<DualScreen.ConsoleChip>,
    console: String?,
    rommOn: Boolean,
    gap: Dp,
    onSelect: (String?) -> Unit,
    onOpen: (DownloadableFileWithTags) -> Unit
) {
    if (sections.chips) ConsoleChips(chips, console, onSelect)
    shown.firstOrNull()?.takeIf { sections.continueCard }?.let { ContinueCard(it, rommOn, onOpen) }
    val rail = DualScreen.railItems(shown).distinctBy { it.row.file.consoleId + "|" + it.row.file.fileName }
    if (sections.rail && rail.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(gap / 2)) {
            Text(stringResource(R.string.dual60_recent_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(rail, key = { it.row.file.consoleId + "|" + it.row.file.fileName }) { RailCard(it, onOpen) }
            }
        }
    }
}

/** The console chips of the quick filter: *All* and one per console, 48 dp high, wrapping onto more lines. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConsoleChips(chips: List<DualScreen.ConsoleChip>, selected: String?, onSelect: (String?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChipTile(stringResource(R.string.dual60_filter_all), selected == null, null) { onSelect(null) }
        chips.forEach { chip ->
            FilterChipTile(
                "${ConsoleFormatter.getConsoleDisplayName(chip.consoleId)} · ${chip.count}",
                selected == chip.consoleId,
                consoleColor(chip.consoleId)
            ) { onSelect(if (selected == chip.consoleId) null else chip.consoleId) }
        }
    }
}

@Composable
private fun FilterChipTile(label: String, selected: Boolean, tint: Color?, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh
    val fg = if (selected) scheme.onPrimaryContainer else scheme.onSurface
    Row(
        Modifier
            .heightIn(min = TOUCH)
            .clip(RoundedCornerShape(24.dp))
            .background(bg)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (tint != null) Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(tint))
        Text(label, style = MaterialTheme.typography.titleSmall, color = fg)
    }
}

/** The newest game: cover, title, console and where it was last saved. Tapping shows its details. */
@Composable
private fun ContinueCard(item: ContinueItem, rommOn: Boolean, onOpen: (DownloadableFileWithTags) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val file = item.row.file
    Panel(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(item.row) }, contentPadding = PaddingValues(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            GameCover(file.consoleId, file.fileName, file.name, Modifier.width(104.dp).heightIn(min = 130.dp), RoundedCornerShape(12.dp), showLabel = false)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.dual60_continue_title), style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                Text(stripExtension(file.name), style = MaterialTheme.typography.titleLarge, color = scheme.onSurface)
                Pill(
                    text = ConsoleFormatter.getConsoleDisplayName(file.consoleId),
                    tone = PillTone.Tint(consoleColor(file.consoleId)),
                    icon = R.drawable.ic_controller
                )
                // The device a save came from is something only RomM knows.
                item.via?.takeIf { rommOn && it.isNotBlank() }?.let {
                    Text(stringResource(R.string.dual60_continue_via, it), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun RailCard(item: ContinueItem, onOpen: (DownloadableFileWithTags) -> Unit) {
    val file = item.row.file
    Column(
        Modifier
            .width(120.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button) { onOpen(item.row) },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        GameCover(file.consoleId, file.fileName, file.name, Modifier.fillMaxWidth().heightIn(min = 150.dp), RoundedCornerShape(12.dp), showLabel = false)
        Text(stripExtension(file.name), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** The queue: totals, *Pause all / Resume all*, and a row per download with its own pause or resume button. */
@Composable
private fun QueueBlock(queue: SecondScreenQueue, files: DownloadableFileDao, service: DownloadService, gap: Dp) {
    val scheme = MaterialTheme.colorScheme
    val view = queue.controls
    val glance = queue.glance
    val scope = rememberCoroutineScope()
    val act: (DownloadService.() -> Unit) -> Unit = { action -> scope.launch(Dispatchers.Default) { runCatching { service.action() } } }
    val paused = view.mode != SecondScreenControls.Mode.ACTIVE
    Panel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.dual60_queue_title), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                val summary = if (view.mode == SecondScreenControls.Mode.HELD) stringResource(R.string.screen75_held_title)
                    else if (paused) stringResource(R.string.screen75_paused) else listOfNotNull(
                    stringResource(R.string.status_downloading, glance.percent),
                    speedLabel(glance),
                    etaLabel(glance)
                ).joinToString(" · ")
                Text(summary, style = MaterialTheme.typography.bodyMedium.tabular(), color = scheme.onSurfaceVariant)
            }
            when (view.holdButton) {
                SecondScreenControls.HoldButton.PAUSE_ALL ->
                    ActionPill(stringResource(R.string.screen75_pause_all), { service.gate.setHeld(true) }, icon = R.drawable.ic_pause)
                SecondScreenControls.HoldButton.RESUME_ALL ->
                    ActionPill(stringResource(R.string.screen75_resume_all), { service.gate.setHeld(false) }, icon = R.drawable.ic_play_arrow)
                null -> Unit
            }
        }
        view.rows.forEach { d ->
            androidx.compose.runtime.key(d.fileName) {
                val action = SecondScreenControls.rowAction(d.status, service.entityFor(d.fileName)?.isTorrent)
                DashboardRow(d, files, 44.dp, queue.held, action, wrapTitle = true) {
                    when (action) {
                        SecondScreenControls.RowAction.PAUSE -> act { pauseDownload(d.fileName) }
                        SecondScreenControls.RowAction.RESUME -> act { retryDownload(d.fileName) }
                        SecondScreenControls.RowAction.NONE -> Unit
                    }
                }
            }
        }
        val more = queue.totalRows - view.rows.size
        if (more > 0) Text(stringResource(R.string.second_screen_more, more), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
    }
}

/**
 * Battery and heat from the sticky battery broadcast (needs no permission) and the system's thermal
 * status, updated whenever the battery broadcast arrives. Null until the first reading. Public
 * Android API only; not tried on a handheld.
 */
@Composable
private fun rememberDeviceState(): DualScreen.DeviceState? {
    val context = LocalContext.current.applicationContext
    var state by remember { mutableStateOf<DualScreen.DeviceState?>(null) }
    DisposableEffect(context) {
        val power = context.getSystemService(PowerManager::class.java)
        fun read(intent: Intent) {
            val level = PowerRules.levelPercent(intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1))
            val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
            val thermal = runCatching { power?.currentThermalStatus ?: 0 }.getOrDefault(0)
            state = DualScreen.deviceState(level, charging, temp, thermal)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = read(intent)
        }
        runCatching {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        }.getOrNull()?.let(::read)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return state
}
