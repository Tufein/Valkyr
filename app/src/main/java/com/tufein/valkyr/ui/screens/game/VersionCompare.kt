package com.tufein.valkyr.ui.screens.game

import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.Panel
import com.tufein.valkyr.ui.components.PanelTone
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.formatBytes
import com.tufein.valkyr.ui.components.rememberFocusSource
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.ui.components.stripExtension
import com.tufein.valkyr.ui.theme.accentInk
import com.tufein.valkyr.ui.theme.tabular
import com.tufein.valkyr.util.VersionCompare
import com.tufein.valkyr.util.VersionCompare.Category
import com.tufein.valkyr.util.VersionCompare.Dump
import com.tufein.valkyr.util.VersionCompare.Field
import com.tufein.valkyr.util.VersionCompare.ReasonKind
import com.tufein.valkyr.util.VersionCompare.Release
import com.tufein.valkyr.util.VersionCompare.Side
import com.tufein.valkyr.util.VersionPicker
import com.tufein.valkyr.util.VersionPreference
import com.tufein.valkyr.util.VersionPreferences

/*
 * 2.4.0: the words and pieces of the version comparison: a ranking reason as a phrase and a chip,
 * "why this one" / "why lower" lines, the side-by-side table and the "prefer versions like this"
 * dialog. The ranking itself is [VersionCompare]; the Versions tab (GamePage.kt) and the Version
 * preference screen put these together.
 */

// ---- Reasons as words ------------------------------------------------------------------------------

@Composable
private fun languageNames(codes: List<String>): String {
    val locale = LocalConfiguration.current.locales[0]
    return codes.joinToString(", ") { VersionPreferences.languageName(it, locale) }
}

/** A reason as a short phrase in the user's language ("Dutch + English", "Europe release", "Final, not a beta"). */
@Composable
internal fun reasonText(reason: VersionCompare.Reason): String {
    val locale = LocalConfiguration.current.locales[0]
    val p = reason.params
    val original = stringResource(R.string.compare24_r_original)
    return when (reason.kind) {
        ReasonKind.LANGUAGE_MATCH, ReasonKind.LANGUAGE_ASSUMED -> {
            val names = p.joinToString(" + ") { VersionPreferences.languageName(it, locale) }
            val ranked = if (reason.position <= 1) names else stringResource(R.string.compare24_r_language_n, names, reason.position)
            if (reason.kind == ReasonKind.LANGUAGE_ASSUMED) stringResource(R.string.compare24_r_language_assumed, ranked) else ranked
        }
        ReasonKind.LANGUAGE_UNREAD -> stringResource(R.string.compare24_r_language_unread, languageNames(p))
        ReasonKind.LANGUAGE_NONE -> stringResource(R.string.compare24_r_language_none)
        ReasonKind.REGION_MATCH ->
            if (reason.position <= 1) stringResource(R.string.compare24_r_region, p.firstOrNull().orEmpty())
            else stringResource(R.string.compare24_r_region_n, p.firstOrNull().orEmpty(), reason.position)
        ReasonKind.REGION_NONE -> stringResource(R.string.compare24_r_region_none)
        ReasonKind.REVISION_NEWEST -> stringResource(R.string.compare24_r_rev_newest, p.firstOrNull().orEmpty())
        ReasonKind.REVISION_NEWER -> stringResource(R.string.compare24_r_rev_newer, p.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: original)
        ReasonKind.REVISION_OLDER -> stringResource(R.string.compare24_r_rev_older, p.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: original)
        ReasonKind.FINAL_RELEASE -> stringResource(R.string.compare24_r_final)
        ReasonKind.PRE_RELEASE -> stringResource(if (p.firstOrNull() == Release.PROTO.name) R.string.compare24_r_proto else R.string.compare24_r_beta)
        ReasonKind.DEMO -> stringResource(R.string.compare24_r_demo)
        ReasonKind.DUMP_VERIFIED -> stringResource(R.string.compare24_r_verified)
        ReasonKind.DUMP_GOOD -> stringResource(R.string.compare24_r_dump_good)
        ReasonKind.DUMP_BAD -> stringResource(if (p.firstOrNull() == Dump.OVERDUMP.name) R.string.compare24_r_overdump else R.string.compare24_r_dump_bad)
        ReasonKind.OFFICIAL -> stringResource(R.string.compare24_r_official)
        ReasonKind.HACK -> stringResource(R.string.compare24_r_hack)
        ReasonKind.TRANSLATION -> stringResource(R.string.compare24_r_translation)
        ReasonKind.UNLICENSED -> stringResource(R.string.compare24_r_unlicensed)
        ReasonKind.ALTERNATE -> stringResource(R.string.compare24_r_alternate)
        ReasonKind.SIZE_SMALLER -> stringResource(R.string.compare24_r_smaller, formatBytes(p.firstOrNull()?.toLongOrNull() ?: 0L))
        ReasonKind.SIZE_LARGER -> stringResource(R.string.compare24_r_larger, formatBytes(p.firstOrNull()?.toLongOrNull() ?: 0L))
    }
}

private fun reasonTone(reason: VersionCompare.Reason): PillTone = when {
    reason.positive -> PillTone.Success
    reason.negative -> PillTone.Danger
    reason.category == Category.SIZE -> PillTone.Info
    reason.kind == ReasonKind.FINAL_RELEASE || reason.kind == ReasonKind.DUMP_GOOD || reason.kind == ReasonKind.OFFICIAL -> PillTone.Success
    else -> PillTone.Neutral
}

private fun reasonIcon(reason: VersionCompare.Reason): Int? = when {
    reason.negative -> R.drawable.ic_warning
    reasonTone(reason) == PillTone.Success -> R.drawable.ic_check
    else -> null
}

/** The reasons as chips: the good ones first, then the neutral ones, then what counts against. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReasonPills(reasons: List<VersionCompare.Reason>, modifier: Modifier = Modifier) {
    val ordered = remember(reasons) { reasons.sortedWith(compareBy({ if (it.negative) 2 else if (reasonTone(it) == PillTone.Neutral) 1 else 0 }, { -it.weight })) }
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ordered.forEach { Pill(reasonText(it), tone = reasonTone(it), icon = reasonIcon(it)) }
    }
}

/** "Why this one: Europe release, Dutch + English, Final, not a beta" for the best version; or why nothing separates it. */
@Composable
internal fun becauseLine(explanation: VersionCompare.Explanation): String {
    val tie = explanation.tieBreak
    return when {
        explanation.runnerUp == null -> stringResource(R.string.compare24_only_one)
        explanation.deciding.isNotEmpty() -> {
            val parts = explanation.deciding.mapNotNull { it.winner }.map { reasonText(it) }
            stringResource(R.string.compare24_because, parts.joinToString(", "))
        }
        tie != null -> stringResource(R.string.compare24_tie_with, reasonText(tie))
        else -> stringResource(R.string.compare24_tie_name)
    }
}

/** Why a version ranks below the best: what it has that is worse, or what it lacks. */
@Composable
internal fun whyNotLine(why: VersionCompare.WhyNot): String {
    val loser = why.gap?.loser
    val winner = why.gap?.winner
    val tie = why.tieBreak
    return when {
        loser != null -> stringResource(R.string.compare24_lower, reasonText(loser))
        winner != null -> stringResource(R.string.compare24_lower_missing, reasonText(winner))
        tie != null -> stringResource(R.string.compare24_tie_with, reasonText(tie))
        else -> stringResource(R.string.compare24_lower_tie)
    }
}

// ---- Side by side -------------------------------------------------------------------------------------

@Composable
private fun fieldLabel(field: Field): String = stringResource(
    when (field) {
        Field.LANGUAGE -> R.string.compare24_f_language
        Field.REGION -> R.string.compare24_f_region
        Field.REVISION -> R.string.compare24_f_revision
        Field.RELEASE -> R.string.compare24_f_release
        Field.DUMP -> R.string.compare24_f_dump
        Field.SIZE -> R.string.compare24_f_size
        Field.FORMAT -> R.string.compare24_f_format
    }
)

/** The raw values of one side of a [VersionCompare.Line] as words. */
@Composable
private fun lineValue(field: Field, values: List<String>): String {
    val none = stringResource(R.string.compare24_v_none)
    return when (field) {
        Field.LANGUAGE -> languageNames(values).ifEmpty { none }
        Field.REGION -> values.joinToString(", ").ifEmpty { none }
        Field.REVISION -> values.firstOrNull() ?: stringResource(R.string.compare24_v_original)
        Field.RELEASE -> stringResource(
            when (values.firstOrNull()) {
                Release.BETA.name -> R.string.compare24_v_beta
                Release.PROTO.name -> R.string.compare24_v_proto
                Release.DEMO.name -> R.string.compare24_v_demo
                else -> R.string.compare24_v_final
            }
        )
        Field.DUMP -> values.map { name ->
            stringResource(
                when (name) {
                    Dump.VERIFIED.name -> R.string.compare24_r_verified
                    Dump.BAD.name -> R.string.compare24_r_dump_bad
                    Dump.OVERDUMP.name -> R.string.compare24_r_overdump
                    VersionCompare.Unofficial.HACK.name -> R.string.compare24_r_hack
                    VersionCompare.Unofficial.TRANSLATION.name -> R.string.compare24_r_translation
                    VersionCompare.Unofficial.UNLICENSED.name -> R.string.compare24_r_unlicensed
                    VersionCompare.Unofficial.ALTERNATE.name -> R.string.compare24_r_alternate
                    else -> R.string.compare24_v_good
                }
            )
        }.joinToString(", ")
        Field.SIZE -> values.firstOrNull()?.toLongOrNull()?.let { formatBytes(it) } ?: stringResource(R.string.compare24_v_unknown)
        Field.FORMAT -> values.firstOrNull() ?: none
    }
}

/** Two versions line by line: language, region, revision, release, dump, size, format; the better side of each line is marked. */
@Composable
internal fun CompareTable(a: VersionCompare.Ranked, b: VersionCompare.Ranked, pref: VersionPreference, onClear: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ink = accentInk()
    val lines = remember(a, b, pref) { VersionCompare.compareLines(a, b, pref) }
    Panel(modifier = Modifier.fillMaxWidth(), tone = PanelTone.Raised, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.compare24_compare_title), style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, modifier = Modifier.weight(1f))
            ActionPill(stringResource(R.string.compare24_compare_clear), onClick = onClear, icon = R.drawable.ic_close)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.weight(0.8f))
            CompareHead(stringResource(R.string.compare24_version_a), a, Modifier.weight(1f))
            CompareHead(stringResource(R.string.compare24_version_b), b, Modifier.weight(1f))
        }
        lines.forEach { line ->
            Row(modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 28.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Text(fieldLabel(line.field), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.weight(0.8f))
                CompareCell(lineValue(line.field, line.a), line.winner == Side.A, Modifier.weight(1f))
                CompareCell(lineValue(line.field, line.b), line.winner == Side.B, Modifier.weight(1f))
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.weight(0.8f))
            Text(stringResource(R.string.compare24_score_points, a.score), style = MaterialTheme.typography.labelMedium.tabular(), color = if (a.score > b.score) ink else scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.compare24_score_points, b.score), style = MaterialTheme.typography.labelMedium.tabular(), color = if (b.score > a.score) ink else scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun CompareHead(label: String, r: VersionCompare.Ranked, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val name = remember(r.version.name) { stripExtension(VersionPicker.readable(r.version.name)) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = accentInk())
        Text(name, style = MaterialTheme.typography.labelMedium, color = scheme.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CompareCell(text: String, wins: Boolean, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val ink = accentInk()
    Row(modifier = modifier, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (wins) FontWeight.SemiBold else FontWeight.Normal,
            color = if (wins) ink else scheme.onSurface,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (wins) Icon(painterResource(R.drawable.ic_check_circle), contentDescription = stringResource(R.string.compare24_compare_winner), tint = ink, modifier = Modifier.size(14.dp))
    }
}

// ---- Prefer versions like this ---------------------------------------------------------------------

/** "Regions: World, Europe, USA. Languages: Dutch, English." */
@Composable
private fun preferenceText(p: VersionPreference): String {
    val regions = p.regions.take(4).joinToString(", ")
    val languages = languageNames(p.languages.take(4))
    return if (languages.isEmpty()) stringResource(R.string.compare24_prefer_text_regions, regions)
    else stringResource(R.string.compare24_prefer_text, regions, languages)
}

/** A whole-row checkbox: the row takes the focus and A / a tap toggles it (the box itself is only a picture). */
@Composable
private fun CheckRow(text: String, checked: Boolean, onToggle: () -> Unit) {
    val source = rememberFocusSource()
    Row(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).focusRing(source, 10.dp).clickable(interactionSource = source, indication = null, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.size(40.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * "Prefer versions like this": puts [target]'s regions first and adds its languages after the
 * user's own, for all consoles or only for this one. The preview and the stored result both come
 * from [VersionCompare.planPin] on [prefs]. When this console's own order would hide a change for all
 * consoles, the dialog says so and offers to clear that part of it. The revision rule is global, so
 * it is only offered for all consoles.
 */
@Composable
internal fun PreferLikeDialog(
    target: VersionCompare.Ranked,
    ranked: List<VersionCompare.Ranked>,
    prefs: VersionPrefs,
    consoleName: String,
    onDismiss: () -> Unit,
    onConfirm: (forConsole: Boolean, followRevision: Boolean, clearOverride: Boolean) -> Unit
) {
    var forConsole by remember { mutableStateOf(false) }
    var followRule by remember { mutableStateOf(false) }
    var clearOverride by remember { mutableStateOf(false) }
    val newest = remember(target, ranked) { VersionCompare.isNewestRevision(target, ranked) }
    // Following the revision rule only changes something when the version is not the newest, or the rule is off now.
    val ruleDiffers = VersionCompare.hasRevisionChoice(ranked) && (!newest || !prefs.global.preferLatestRevision)
    val hides = remember(target, prefs) { VersionCompare.planPin(target.facts, prefs.global, prefs.override, false, false, newest, false).hiddenByOverride }
    val plan = remember(target, prefs, forConsole, followRule, clearOverride, newest) {
        VersionCompare.planPin(target.facts, prefs.global, prefs.override, forConsole, followRule && !forConsole, newest, clearOverride && !forConsole)
    }
    val cancelFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_compare), contentDescription = null, tint = accentInk()) },
        title = { Text(stringResource(R.string.compare24_prefer_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionPill(
                        stringResource(R.string.compare24_prefer_all), onClick = { forConsole = false },
                        icon = if (!forConsole) R.drawable.ic_check else null, tone = if (!forConsole) ActionTone.Accent else ActionTone.Neutral
                    )
                    ActionPill(
                        stringResource(R.string.compare24_prefer_console, consoleName), onClick = { forConsole = true },
                        icon = if (forConsole) R.drawable.ic_check else null, tone = if (forConsole) ActionTone.Accent else ActionTone.Neutral
                    )
                }
                Text(preferenceText(plan.shown))
                if (!forConsole && ruleDiffers) {
                    CheckRow(
                        stringResource(R.string.compare24_prefer_revision, stringResource(if (newest) R.string.compare24_rev_newest else R.string.compare24_rev_any)),
                        followRule
                    ) { followRule = !followRule }
                }
                if (!forConsole && hides) {
                    Text(stringResource(R.string.compare24_prefer_hidden, consoleName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    CheckRow(stringResource(R.string.compare24_prefer_clear_override, consoleName), clearOverride) { clearOverride = !clearOverride }
                }
            }
        },
        confirmButton = { DialogButton(text = stringResource(R.string.dialog_save), onClick = { onConfirm(forConsole, followRule && !forConsole, clearOverride && !forConsole && hides) }) },
        dismissButton = { DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss, initialFocus = cancelFocus) }
    )
}
