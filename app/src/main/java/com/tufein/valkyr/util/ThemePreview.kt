package com.tufein.valkyr.util

/**
 * Which mini previews Settings shows for the look of the app (6.2): one card per real look
 * (Light, Dark, True black), each drawn in the accent in use, and which of them is the one on
 * screen right now ("System" resolves to Light or Dark by the device). Pure; the drawing lives in
 * `ThemePreviews.kt`. [ThemeMode] names are used as strings so the tests need no Android classes.
 */
object ThemePreview {
    enum class Look(val modeName: String) {
        LIGHT("LIGHT"), DARK("DARK"), TRUE_BLACK("TRUE_BLACK")
    }

    /** The cards, in the order the theme row steps through them. */
    val looks: List<Look> = Look.entries

    /** The look a stored theme mode ends up as; an unknown mode behaves like "System". */
    fun resolve(modeName: String?, systemDark: Boolean): Look =
        Look.entries.firstOrNull { it.modeName == modeName } ?: if (systemDark) Look.DARK else Look.LIGHT

    /** The card to mark as in use. */
    fun isActive(look: Look, modeName: String?, systemDark: Boolean): Boolean = resolve(modeName, systemDark) == look

    /** True while the mode follows the device, so the dialog can say the choice is automatic. */
    fun followsSystem(modeName: String?): Boolean = Look.entries.none { it.modeName == modeName }
}
