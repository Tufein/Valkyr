package com.tufein.valkyr.ui.screens.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.NavController
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.util.HandheldLab
import kotlinx.coroutines.delay
import java.util.Locale

private enum class LabMode { NONE, CONTROLLER, SCREEN, TOUCH }

/**
 * 10.0: the Handheld lab. Three checks that look and measure and change nothing: a controller test
 * (every button, both sticks, both triggers, and how far a stick drifts when let go), a full-screen
 * colour and pattern test for dead pixels and burn-in, and a live battery and heat readout.
 */
@Composable
fun HandheldLabScreen(@Suppress("UNUSED_PARAMETER") navController: NavController) {
    var mode by remember { mutableStateOf(LabMode.NONE) }
    val context = LocalContext.current
    var battery by remember { mutableStateOf(readBattery(context)) }
    LaunchedEffect(Unit) { while (true) { battery = readBattery(context); delay(2_000) } }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.lab100_title), icon = R.drawable.ic_science, subtitle = stringResource(R.string.lab100_subtitle))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "what") {
                InfoCard(listOf(stringResource(R.string.lab100_what)), modifier = Modifier.focusRequester(first).focusableCard(), icon = R.drawable.ic_info)
            }
            item(key = "controller") {
                ToolRow(
                    title = stringResource(R.string.lab100_ctl_title), lines = listOf(stringResource(R.string.lab100_ctl_hint)),
                    onClick = { mode = LabMode.CONTROLLER }, icon = R.drawable.ic_controller, chevron = true
                )
            }
            item(key = "screen") {
                ToolRow(
                    title = stringResource(R.string.lab100_scr_title), lines = listOf(stringResource(R.string.lab100_scr_hint)),
                    onClick = { mode = LabMode.SCREEN }, icon = R.drawable.ic_smartphone, chevron = true
                )
            }
            item(key = "touch") {
                ToolRow(
                    title = stringResource(R.string.lab100_touch_title), lines = listOf(stringResource(R.string.lab100_touch_hint)),
                    onClick = { mode = LabMode.TOUCH }, icon = R.drawable.ic_smartphone, chevron = true
                )
            }
            item(key = "sound") { SpeakerRow() }
            item(key = "h-battery") { SectionHeader(stringResource(R.string.lab100_bat_title), subtitle = stringResource(R.string.lab100_bat_hint), icon = R.drawable.ic_battery) }
            item(key = "battery") { BatteryCard(battery) }
        }
    }
    when (mode) {
        LabMode.CONTROLLER -> ControllerTest(onClose = { mode = LabMode.NONE })
        LabMode.SCREEN -> ScreenTest(onClose = { mode = LabMode.NONE })
        LabMode.TOUCH -> TouchTest(onClose = { mode = LabMode.NONE })
        LabMode.NONE -> Unit
    }
}

// ---- Speaker ---------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeakerRow() {
    val scope = rememberCoroutineScope()
    var playing by remember { mutableStateOf(false) }
    fun play(left: Boolean, right: Boolean) {
        if (playing) return
        playing = true
        scope.launch { try { playTone(left, right) } finally { playing = false } }
    }
    ToolRow(
        title = stringResource(R.string.lab100_snd_title), lines = listOf(stringResource(R.string.lab100_snd_hint)),
        onClick = {}, icon = R.drawable.ic_volume_up,
        below = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                ActionPill(stringResource(R.string.lab100_snd_left), { play(true, false) }, enabled = !playing)
                ActionPill(stringResource(R.string.lab100_snd_right), { play(false, true) }, enabled = !playing)
                ActionPill(stringResource(R.string.lab100_snd_both), { play(true, true) }, enabled = !playing)
            }
        }
    )
}

/** A short beep on the chosen channels at a low level; failures (no audio output) are silent. */
private suspend fun playTone(left: Boolean, right: Boolean) = withContext(Dispatchers.IO) {
    runCatching {
        val rate = 44_100
        val pcm = HandheldLab.tone(880, 700, rate, left, right)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            delay(800)
        } finally {
            track.release()
        }
    }
    Unit
}

// ---- Touch test ------------------------------------------------------------------------------

@Composable
private fun TouchTest(onClose: () -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    var grid by remember { mutableStateOf<HandheldLab.TouchGrid?>(null) }
    val density = LocalDensity.current
    val lit = MaterialTheme.colorScheme.primary
    FullScreenDialog(onClose) {
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .onSizeChanged { s ->
                    if (grid == null && s.width > 0 && s.height > 0) {
                        val cell = with(density) { 56.dp.toPx() }
                        grid = HandheldLab.TouchGrid(maxOf(1, (s.width / cell).roundToInt()), maxOf(1, (s.height / cell).roundToInt()))
                    }
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        val last = HashMap<Long, Offset>()
                        while (true) {
                            val event = awaitPointerEvent()
                            val g = grid
                            val w = size.width.toFloat(); val h = size.height.toFloat()
                            for (c in event.changes) {
                                if (g != null && w > 0f && h > 0f) {
                                    if (c.pressed) {
                                        val prev = last[c.id.value]
                                        val changed = if (prev == null) g.mark(c.position.x / w, c.position.y / h)
                                        else g.markLine(prev.x / w, prev.y / h, c.position.x / w, c.position.y / h)
                                        last[c.id.value] = c.position
                                        if (changed) version++
                                    } else last.remove(c.id.value)
                                }
                                c.consume()
                            }
                        }
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val g = grid ?: return@Canvas
                @Suppress("UNUSED_VARIABLE") val redraw = version
                val cw = size.width / g.cols
                val ch = size.height / g.rows
                for (r in 0 until g.rows) for (c in 0 until g.cols) {
                    drawRect(if (g.isHit(c, r)) lit else Color(0xFF1A1A1A), Offset(c * cw + 1f, r * ch + 1f), Size(cw - 2f, ch - 2f))
                }
            }
            val g = grid
            @Suppress("UNUSED_VARIABLE") val redraw = version
            if (g != null) {
                Text(
                    if (g.complete) stringResource(R.string.lab100_touch_done) else stringResource(R.string.lab100_touch_count, g.touched, g.total),
                    color = Color.White, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center).background(Color(0xCC000000)).padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

// ---- Battery and heat ------------------------------------------------------------------------

private fun readBattery(context: Context): HandheldLab.Battery {
    val i: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    return HandheldLab.battery(
        level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1,
        scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1,
        tempTenths = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0,
        status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, 1) ?: 1,
        voltageMv = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
    )
}

@Composable
private fun BatteryCard(b: HandheldLab.Battery) {
    val state = when (b.charge) {
        HandheldLab.Charge.CHARGING -> stringResource(R.string.lab100_bat_charging)
        HandheldLab.Charge.DISCHARGING -> stringResource(R.string.lab100_bat_on_battery)
        HandheldLab.Charge.FULL -> stringResource(R.string.lab100_bat_full)
        HandheldLab.Charge.UNKNOWN -> stringResource(R.string.lab100_bat_unknown)
    }
    val lines = buildList {
        b.percent?.let { add(stringResource(R.string.lab100_bat_level, "$it%", state)) } ?: add(state)
        b.celsius?.let { add(stringResource(R.string.lab100_bat_temp, String.format(Locale.getDefault(), "%.1f °C", it))) }
        b.millivolts?.let { add(stringResource(R.string.lab100_bat_volt, String.format(Locale.getDefault(), "%.2f V", it / 1000f))) }
    }
    val heat = b.celsius?.let { HandheldLab.heat(it) }
    ToolRow(
        title = stringResource(R.string.lab100_bat_title), lines = lines, onClick = {}, icon = R.drawable.ic_thermostat,
        badge = {
            when (heat) {
                HandheldLab.Heat.COOL -> Pill(stringResource(R.string.lab100_heat_cool), tone = PillTone.Success, icon = R.drawable.ic_check_circle)
                HandheldLab.Heat.WARM -> Pill(stringResource(R.string.lab100_heat_warm), tone = PillTone.Warning, icon = R.drawable.ic_warning)
                HandheldLab.Heat.HOT -> Pill(stringResource(R.string.lab100_heat_hot), tone = PillTone.Warning, icon = R.drawable.ic_warning)
                null -> Unit
            }
        },
        below = heat?.let { h ->
            {
                Text(
                    when (h) {
                        HandheldLab.Heat.COOL -> stringResource(R.string.lab100_heat_cool_text)
                        HandheldLab.Heat.WARM -> stringResource(R.string.lab100_heat_warm_text)
                        HandheldLab.Heat.HOT -> stringResource(R.string.lab100_heat_hot_text)
                    },
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    )
}

// ---- Full-screen dialogs ---------------------------------------------------------------------

/** A dialog that covers the whole screen, hides the system bars and keeps the display on while it is open. */
@Composable
private fun FullScreenDialog(onClose: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            window?.let {
                WindowCompat.setDecorFitsSystemWindows(it, false)
                it.attributes = it.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
                it.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                WindowInsetsControllerCompat(it, it.decorView).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
            onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        content()
    }
}

@Composable
private fun ScreenTest(onClose: () -> Unit) {
    var pattern by remember { mutableStateOf(HandheldLab.Pattern.BLACK) }
    var showName by remember { mutableStateOf(true) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(pattern) { showName = true; delay(2_000); showName = false }
    FullScreenDialog(onClose) {
        Box(
            Modifier.fillMaxSize()
                .focusRequester(focus).focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionRight, Key.DirectionDown, Key.ButtonA, Key.Enter, Key.DirectionCenter, Key.ButtonR1 -> { pattern = HandheldLab.next(pattern); true }
                        Key.DirectionLeft, Key.DirectionUp, Key.ButtonL1 -> { pattern = HandheldLab.previous(pattern); true }
                        Key.ButtonB -> { onClose(); true }
                        else -> false
                    }
                }
                .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { pattern = HandheldLab.next(pattern) }
        ) {
            val solid = HandheldLab.solidColor(pattern)
            if (solid != null) {
                Box(Modifier.fillMaxSize().background(Color(solid)))
            } else if (pattern == HandheldLab.Pattern.GREY_RAMP) {
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black, Color.White))))
            } else {
                Canvas(Modifier.fillMaxSize().background(Color.Black)) {
                    val step = 40.dp.toPx()
                    var x = 0f
                    while (x <= size.width) { drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
                    var y = 0f
                    while (y <= size.height) { drawLine(Color.White, Offset(0f, y), Offset(size.width, y), 1f); y += step }
                    drawRect(Color.White, topLeft = Offset(1f, 1f), size = Size(size.width - 2f, size.height - 2f), style = Stroke(4f))
                }
            }
            if (showName) {
                Text(
                    stringResource(patternName(pattern)) + "  ·  " + stringResource(R.string.lab100_scr_leave),
                    color = Color.White, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).background(Color(0xAA000000)).padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

private fun patternName(p: HandheldLab.Pattern): Int = when (p) {
    HandheldLab.Pattern.BLACK -> R.string.lab100_pat_black
    HandheldLab.Pattern.WHITE -> R.string.lab100_pat_white
    HandheldLab.Pattern.RED -> R.string.lab100_pat_red
    HandheldLab.Pattern.GREEN -> R.string.lab100_pat_green
    HandheldLab.Pattern.BLUE -> R.string.lab100_pat_blue
    HandheldLab.Pattern.GREY_RAMP -> R.string.lab100_pat_ramp
    HandheldLab.Pattern.GRID -> R.string.lab100_pat_grid
}

// ---- Controller test -------------------------------------------------------------------------

/** A view that takes every button press and stick movement; only Back is left to the dialog so the test can be left. */
private class PadView(
    context: Context,
    val onKey: (Int, Boolean) -> Unit,
    val onMotion: (MotionEvent) -> Unit
) : View(context) {
    init { isFocusable = true; isFocusableInTouchMode = true }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return false
        onKey(keyCode, true); return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return false
        onKey(keyCode, false); return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK && event.action == MotionEvent.ACTION_MOVE) {
            onMotion(event); return true
        }
        return super.onGenericMotionEvent(event)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControllerTest(onClose: () -> Unit) {
    var pressed by remember { mutableStateOf(setOf<Int>()) }
    var seen by remember { mutableStateOf(setOf<Int>()) }
    var lx by remember { mutableFloatStateOf(0f) }
    var ly by remember { mutableFloatStateOf(0f) }
    var rx by remember { mutableFloatStateOf(0f) }
    var ry by remember { mutableFloatStateOf(0f) }
    var lt by remember { mutableFloatStateOf(0f) }
    var rt by remember { mutableFloatStateOf(0f) }
    val leftMeter = remember { HandheldLab.DriftMeter() }
    val rightMeter = remember { HandheldLab.DriftMeter() }
    var keyHistory by remember { mutableStateOf(listOf<Int>()) }
    var thirtyLives by remember { mutableStateOf(false) }
    var measuring by remember { mutableStateOf(false) }
    var left by remember { mutableStateOf<HandheldLab.Drift?>(null) }
    var right by remember { mutableStateOf<HandheldLab.Drift?>(null) }

    LaunchedEffect(measuring) {
        if (!measuring) return@LaunchedEffect
        leftMeter.reset(); rightMeter.reset(); left = null; right = null
        repeat(60) { delay(50); leftMeter.add(lx, ly); rightMeter.add(rx, ry) }
        left = leftMeter.result(); right = rightMeter.result()
        measuring = false
    }

    FullScreenDialog(onClose) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            AndroidView(
                modifier = Modifier.size(1.dp),
                factory = { ctx ->
                    PadView(
                        ctx,
                        onKey = { code, down ->
                            if (down) {
                                pressed = pressed + code; seen = seen + code
                                val (h, done) = HandheldLab.konami(keyHistory, code)
                                keyHistory = h
                                if (done) thirtyLives = true
                            } else pressed = pressed - code
                        },
                        onMotion = { e ->
                            lx = e.getAxisValue(MotionEvent.AXIS_X); ly = e.getAxisValue(MotionEvent.AXIS_Y)
                            rx = HandheldLab.pick(e.getAxisValue(MotionEvent.AXIS_Z), e.getAxisValue(MotionEvent.AXIS_RX))
                            ry = HandheldLab.pick(e.getAxisValue(MotionEvent.AXIS_RZ), e.getAxisValue(MotionEvent.AXIS_RY))
                            lt = HandheldLab.pick(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE))
                            rt = HandheldLab.pick(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))
                        }
                    ).also { it.post { it.requestFocus() } }
                }
            )
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.lab100_ctl_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.lab100_ctl_leave), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val names = pressed.mapNotNull { HandheldLab.buttonName(it) }.sorted()
                Text(
                    if (names.isEmpty()) stringResource(R.string.lab100_ctl_none) else stringResource(R.string.lab100_ctl_pressed, names.joinToString(" + ")),
                    style = MaterialTheme.typography.headlineSmall
                )
                if (thirtyLives) Text(stringResource(R.string.lab100_konami), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.lab100_ctl_seen, seen.count { HandheldLab.buttonName(it) != null }), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    StickDial(stringResource(R.string.lab100_ctl_left), lx, ly)
                    StickDial(stringResource(R.string.lab100_ctl_right), rx, ry)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.lab100_ctl_triggers), style = MaterialTheme.typography.labelMedium)
                        TriggerBar("L2", lt); TriggerBar("R2", rt)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionPill(
                        if (measuring) stringResource(R.string.lab100_ctl_drift_running) else stringResource(R.string.lab100_ctl_drift_start),
                        onClick = { if (!measuring) measuring = true }, icon = R.drawable.ic_speed, enabled = !measuring
                    )
                    ActionPill(stringResource(R.string.lab100_ctl_done), onClick = onClose, tone = ActionTone.Neutral)
                }
                driftText(stringResource(R.string.lab100_ctl_left), left)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                driftText(stringResource(R.string.lab100_ctl_right), right)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun driftText(side: String, d: HandheldLab.Drift?): String? {
    d ?: return null
    val text = when (d.verdict) {
        HandheldLab.DriftVerdict.OK -> stringResource(R.string.lab100_drift_ok, "${d.maxPercent}%")
        HandheldLab.DriftVerdict.SLIGHT -> stringResource(R.string.lab100_drift_slight, "${d.meanPercent}%")
        HandheldLab.DriftVerdict.DRIFTING -> stringResource(R.string.lab100_drift_bad, "${d.meanPercent}%")
    }
    return "$side: $text"
}

@Composable
private fun StickDial(label: String, x: Float, y: Float) {
    val ring = MaterialTheme.colorScheme.outline
    val dot = MaterialTheme.colorScheme.primary
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.size(96.dp)) {
            val r = size.minDimension / 2f - 6f
            drawCircle(ring, radius = r, style = Stroke(2f))
            drawLine(ring, Offset(center.x - r, center.y), Offset(center.x + r, center.y), 1f)
            drawLine(ring, Offset(center.x, center.y - r), Offset(center.x, center.y + r), 1f)
            drawCircle(dot, radius = 8f, center = Offset(center.x + x.coerceIn(-1f, 1f) * r, center.y + y.coerceIn(-1f, 1f) * r))
        }
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun TriggerBar(name: String, value: Float) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.size(width = 28.dp, height = 18.dp))
        Box(Modifier.size(width = 120.dp, height = 12.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            Box(Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).height(12.dp).background(MaterialTheme.colorScheme.primary))
        }
    }
}
