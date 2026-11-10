package com.tufein.valkyr.ui.screens.download

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.Stepper
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.rememberFocusSource
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.util.ConditionKind
import com.tufein.valkyr.util.DownloadCondition
import com.tufein.valkyr.util.DownloadConditions
import com.tufein.valkyr.util.DownloadPolicy
import com.tufein.valkyr.util.ItemWait
import com.tufein.valkyr.util.WaitInfo
import java.text.DateFormat
import java.util.Date
import java.time.Instant
import java.time.ZoneId

/** The options of the dialog; null = "right away" (no condition). */
private val OPTIONS: List<Pair<ConditionKind?, Int>> = listOf(
    null to R.string.plan6_when_now,
    ConditionKind.WIFI to R.string.plan6_when_wifi,
    ConditionKind.CHARGING to R.string.plan6_when_charging,
    ConditionKind.WIFI_AND_CHARGING to R.string.plan6_when_both,
    ConditionKind.TONIGHT to R.string.plan6_when_tonight,
    ConditionKind.AT_TIME to R.string.plan6_when_time
)

private fun iconOf(kind: ConditionKind?): Int = when (kind) {
    null -> R.drawable.ic_play_arrow
    ConditionKind.WIFI -> R.drawable.ic_wifi
    ConditionKind.CHARGING -> R.drawable.ic_charging
    ConditionKind.WIFI_AND_CHARGING -> R.drawable.ic_bolt
    ConditionKind.TONIGHT -> R.drawable.ic_night
    ConditionKind.AT_TIME -> R.drawable.ic_schedule
}

/**
 * "Download when..." (6.0): asks under which condition one download, or a whole batch of [count],
 * should start. Works for new downloads (the Home details dialog and the bulk dialog) and for
 * downloads already in the queue (Downloads screen).
 *
 * [onConfirm] gets the chosen [DownloadCondition], or null for "right away" (no condition: for a
 * download already waiting that means start now). The time of "At a time I pick" is resolved to its
 * next occurrence when the user confirms. Gamepad: up / down pick an option, A selects, the time
 * row opens a ‹ HH:MM › stepper, B closes. Wiring example for the Home dialog:
 * ```
 * if (askWhen) DownloadWhenDialog(onDismiss = { askWhen = false }) { c ->
 *     askWhen = false
 *     downloadService.startDownload(file, c)   // or startDownloads(files, c)
 * }
 * ```
 *
 * @param initial what to preselect (the download's current condition, or null).
 * @param count how many downloads the choice applies to; above 1 the dialog says so.
 */
@Composable
fun DownloadWhenDialog(
    onDismiss: () -> Unit,
    onConfirm: (DownloadCondition?) -> Unit,
    modifier: Modifier = Modifier,
    initial: DownloadCondition? = null,
    count: Int = 1
) {
    var kind by remember { mutableStateOf(initial?.kind) }
    var minute by remember {
        mutableIntStateOf(
            if (initial?.kind == ConditionKind.AT_TIME) initial.minuteOfDay
            else (DownloadConditions.minuteOfDay(System.currentTimeMillis()) / 15 * 15 + 60) % 1440
        )
    }
    val focus = rememberInitialFocus()
    AlertDialog(
        modifier = modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plan6_when_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (count > 1) {
                    Text(
                        pluralStringResource(R.plurals.plan6_when_for, count, count),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OPTIONS.forEach { (option, label) ->
                    val selected = option == kind
                    OptionRow(
                        icon = iconOf(option),
                        label = stringResource(label),
                        selected = selected,
                        modifier = if (selected) Modifier.focusRequester(focus) else Modifier,
                        onClick = { kind = option }
                    )
                    if (option == ConditionKind.AT_TIME && selected) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 40.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Stepper(
                                value = DownloadPolicy.formatMinutes(minute),
                                onDecrement = { minute = DownloadConditions.shiftQuarter(minute, -1) },
                                onIncrement = { minute = DownloadConditions.shiftQuarter(minute, 1) },
                                valueWidth = 72.dp
                            )
                        }
                        Text(
                            stringResource(R.string.plan6_when_time_hint, formatMoment(DownloadConditions.nextOccurrence(minute, System.currentTimeMillis()))),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 40.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            DialogButton(stringResource(R.string.pad_apply), onClick = {
                val now = System.currentTimeMillis()
                onConfirm(
                    when (kind) {
                        null -> null
                        ConditionKind.AT_TIME -> DownloadConditions.atTime(minute, now)
                        else -> DownloadCondition(kind!!)
                    }
                )
            })
        },
        dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), onClick = onDismiss) }
    )
}

@Composable
private fun OptionRow(icon: Int, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh)
            .focusRing(source, cornerRadius = 12.dp)
            .semantics { role = Role.RadioButton }
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val ink = if (selected) scheme.onPrimaryContainer else scheme.onSurface
        Icon(painterResource(icon), contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = ink, modifier = Modifier.weight(1f))
        Icon(
            painterResource(if (selected) R.drawable.ic_radio_on else R.drawable.ic_radio_off),
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** A moment as the device formats it: just the time today, date and time otherwise. */
internal fun formatMoment(at: Long, now: Long = System.currentTimeMillis()): String {
    val zone = ZoneId.systemDefault()
    val sameDay = Instant.ofEpochMilli(at).atZone(zone).toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return if (sameDay) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))
    else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))
}

/** The text of the pill of a download whose own condition is not met yet. */
@Composable
fun conditionPillText(info: WaitInfo): String = when (info.condition.kind) {
    ConditionKind.TONIGHT -> stringResource(R.string.plan6_pill_tonight)
    ConditionKind.AT_TIME -> stringResource(R.string.plan6_pill_time, formatMoment(info.condition.atMillis))
    ConditionKind.WIFI, ConditionKind.CHARGING, ConditionKind.WIFI_AND_CHARGING -> when {
        ItemWait.WIFI in info.reasons && ItemWait.CHARGER in info.reasons -> stringResource(R.string.plan6_pill_both)
        ItemWait.CHARGER in info.reasons -> stringResource(R.string.plan6_pill_charger)
        else -> stringResource(R.string.plan6_pill_wifi)
    }
}

/** Icon of that pill. */
fun conditionPillIcon(info: WaitInfo): Int = when (info.condition.kind) {
    ConditionKind.TONIGHT -> R.drawable.ic_night
    ConditionKind.AT_TIME -> R.drawable.ic_schedule
    else -> if (ItemWait.CHARGER in info.reasons && ItemWait.WIFI !in info.reasons) R.drawable.ic_charging else R.drawable.ic_wifi
}
