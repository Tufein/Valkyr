package com.tufein.valkyr.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.focusScale
import com.tufein.valkyr.ui.components.rememberFocusSource
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.ui.theme.AccentPresets
import com.tufein.valkyr.ui.theme.DogmatixBlack
import com.tufein.valkyr.ui.theme.DogmatixDark
import com.tufein.valkyr.ui.theme.DogmatixLight
import com.tufein.valkyr.ui.theme.OnAccent
import com.tufein.valkyr.util.ThemePreview

/** The label of a look: the same words as the theme row. */
private fun lookLabel(look: ThemePreview.Look): Int = when (look) {
    ThemePreview.Look.LIGHT -> R.string.theme_light
    ThemePreview.Look.DARK -> R.string.theme_dark
    ThemePreview.Look.TRUE_BLACK -> R.string.theme_true_black
}

/** What a mini card paints with: the look's own surfaces, never the colours of the screen around it. */
private class MiniColors(val bg: Color, val card: Color, val text: Color, val muted: Color, val line: Color)

private fun miniColors(look: ThemePreview.Look) = when (look) {
    ThemePreview.Look.LIGHT -> MiniColors(DogmatixLight.bg, DogmatixLight.card, DogmatixLight.text, DogmatixLight.muted, DogmatixLight.line)
    ThemePreview.Look.DARK -> MiniColors(DogmatixDark.bg, DogmatixDark.card, DogmatixDark.text, DogmatixDark.muted, DogmatixDark.line)
    ThemePreview.Look.TRUE_BLACK -> MiniColors(DogmatixBlack.bg, DogmatixBlack.card, DogmatixBlack.text, DogmatixBlack.muted, DogmatixBlack.line)
}

/** Material You has no fixed colour to show; its previews use the default accent. */
internal fun previewAccent(accent: Color): Color = if (accent == AccentPresets.dynamic) AccentPresets.default else accent

/**
 * A small sample of the app in [look] and [accent]: a card with a title, a button and a chip, on the
 * look's own background. Pure Compose, drawn once. [onClick] null makes it a plain sample.
 */
@Composable
internal fun MiniThemeCard(
    look: ThemePreview.Look,
    accent: Color,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val c = miniColors(look)
    val tint = previewAccent(accent)
    val dark = look != ThemePreview.Look.LIGHT
    val source = rememberFocusSource()
    val frame = modifier
        .let { if (onClick != null) it.focusScale(source, 1.04f).focusRing(source, 14.dp, onAccent = false, fill = false) else it }
        .clip(RoundedCornerShape(14.dp))
        .background(c.bg)
        .border(if (active) 2.dp else 1.dp, if (active) tint else c.line, RoundedCornerShape(14.dp))
        .let { if (onClick != null) it.clickable(interactionSource = source, indication = null, onClick = onClick) else it }
        .padding(10.dp)
    Column(modifier = frame, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(lookLabel(look)),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = c.muted
        )
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(c.card)
                .border(1.dp, c.line, RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(stringResource(R.string.themeprev62_card_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = c.text)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(tint).padding(horizontal = 10.dp, vertical = 4.dp)
                ) { Text(stringResource(R.string.themeprev62_button), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = OnAccent) }
                Box(
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(tint.copy(alpha = if (dark) 0.22f else 0.20f)).padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        stringResource(R.string.themeprev62_chip),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (dark) lerp(tint, Color.White, 0.30f) else lerp(tint, Color.Black, 0.55f)
                    )
                }
            }
        }
    }
}

/**
 * Light, Dark and True black side by side in the accent in use; picking one switches the theme.
 * [modeName] is the stored [com.tufein.valkyr.ui.theme.ThemeMode] name.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ThemePreviewsDialog(modeName: String, accent: Color, onPick: (ThemePreview.Look) -> Unit, onDismiss: () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val firstFocus = rememberInitialFocus()
    val activeLook = ThemePreview.resolve(modeName, systemDark)
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.themeprev62_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ThemePreview.looks.forEach { look ->
                        MiniThemeCard(
                            look = look,
                            accent = accent,
                            active = look == activeLook,
                            modifier = Modifier.width(140.dp).let { if (look == activeLook) it.focusRequester(firstFocus) else it },
                            onClick = { onPick(look) }
                        )
                    }
                }
                Text(
                    stringResource(if (ThemePreview.followsSystem(modeName)) R.string.themeprev62_hint_system else R.string.themeprev62_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (accent == AccentPresets.dynamic) Text(
                    stringResource(R.string.themeprev62_hint_dynamic),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { DialogButton(text = stringResource(R.string.dialog_close), onClick = onDismiss) }
    )
}
