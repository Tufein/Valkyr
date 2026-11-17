package com.tufein.valkyr.ui.theme

import androidx.compose.ui.graphics.Color
import com.tufein.valkyr.util.ConsoleFamily

/**
 * 9.0: the palette can be switched to hues that stay distinct for common forms of colour blindness.
 * The setting is collected once in MainActivity and kept here so every chip, cover placeholder and
 * chart uses the same palette without threading a flag through every composable.
 */
object ConsolePalette {
    @Volatile
    var accessible: Boolean = false
}

/** The colour of a console's family (see [ConsoleFamily]): chips, cover placeholders, charts. */
fun consoleColor(consoleId: String): Color =
    Color(ConsoleFamily.of(consoleId).color(ConsolePalette.accessible))
