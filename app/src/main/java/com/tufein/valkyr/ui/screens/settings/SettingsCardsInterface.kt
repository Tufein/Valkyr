package com.tufein.valkyr.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.NavChevron
import com.tufein.valkyr.ui.navigation.NavRoutes
import com.tufein.valkyr.ui.screens.home.labelRes
import com.tufein.valkyr.ui.screens.search.SEARCH_ALL_ROUTE
import com.tufein.valkyr.ui.screens.settings.covers.CoverSourcesRow
import com.tufein.valkyr.util.SettingKeys
import com.tufein.valkyr.util.SettingsLayout

/** The rows of the Tools card (see [SettingsLayout.groups] for their order). */
internal fun SettingsEnv.toolsCardRows(): List<SettingsRow> = listOf(
    SettingsRow(key = SettingKeys.TOOLS) {
        SettingRow(
            icon = R.drawable.ic_build,
            title = stringResource(R.string.settings_tools),
            hint = stringResource(R.string.settings_tools_hint),
            onClick = { navController.navigate(NavRoutes.Tools.route) },
        ) { NavChevron() }
    },
    SettingsRow(key = SettingKeys.SEARCH_ALL) {
        SettingRow(
            icon = R.drawable.ic_search,
            title = stringResource(R.string.find8_settings_row),
            hint = stringResource(R.string.find8_hint),
            onClick = { navController.navigate(SEARCH_ALL_ROUTE) { launchSingleTop = true } },
        ) { NavChevron() }
    }
)

/** The rows of the Look card (see [SettingsLayout.groups] for their order). */
internal fun SettingsEnv.lookCardRows(): List<SettingsRow> = listOf(
    SettingsRow(key = SettingKeys.THEME) {
        SettingRow(
            icon = R.drawable.ic_dark_mode,
            title = stringResource(R.string.settings_theme),
            hint = stringResource(R.string.settings_theme_hint),
            onClick = { cycleTheme(1) },
            onAdjust = { cycleTheme(it) }
        ) {
            SettingsStepper(themeLabel, onDecrement = { cycleTheme(-1) }, onIncrement = { cycleTheme(1) })
        }
    },
    SettingsRow(key = SettingKeys.LANGUAGE) {
        SettingRow(
            icon = R.drawable.ic_language,
            title = stringResource(R.string.settings_language),
            hint = stringResource(R.string.settings_language_hint),
            onClick = { cycleLanguage(1) },
            onAdjust = { cycleLanguage(it) }
        ) {
            SettingsStepper(stringResource(appLanguage.label), onDecrement = { cycleLanguage(-1) }, onIncrement = { cycleLanguage(1) })
        }
    },
    SettingsRow(key = SettingKeys.TEXT_SIZE) {
        SettingRow(
            icon = R.drawable.ic_text_size,
            title = stringResource(R.string.settings_text_size),
            hint = stringResource(R.string.settings_text_size_hint),
            onClick = { extra.shiftTextSize(context, 1) },
            onAdjust = { extra.shiftTextSize(context, it) }
        ) {
            SettingsStepper("${appPrefs.textSize} %", onDecrement = { extra.shiftTextSize(context, -1) }, onIncrement = { extra.shiftTextSize(context, 1) })
        }
    },
    SettingsRow(key = SettingKeys.ACCENT) {
        SettingRow(
            icon = R.drawable.ic_colorize,
            title = stringResource(R.string.settings43_accent),
            hint = stringResource(R.string.settings43_accent_hint),
            onClick = { cycleAccent(1) },
            onAdjust = { cycleAccent(it) }
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                AccentSwatch(ui.accent, if (isLandscape) 24.dp else 32.dp, onClick = { dialogs.showAccentDialog = true })
                ActionPill(stringResource(R.string.settings_change), { dialogs.showAccentDialog = true }, icon = R.drawable.ic_edit)
            }
        }
    },
    SettingsRow(key = SettingKeys.BOLD_FOCUS) {
        SettingRow(
            icon = R.drawable.ic_center_focus_strong,
            title = stringResource(R.string.settings_bold_focus),
            hint = stringResource(R.string.settings_bold_focus_hint),
            onClick = { extra.setBoldFocus(context, !appPrefs.boldFocus) },
            onAdjust = { extra.setBoldFocus(context, it > 0) }
        ) { ThemedSwitch(appPrefs.boldFocus) { extra.setBoldFocus(context, it) } }
    },
    SettingsRow(key = SettingKeys.ANIMATIONS) {
        SettingRow(
            icon = R.drawable.ic_animation,
            title = stringResource(R.string.settings_v5_animations),
            hint = stringResource(R.string.settings_v5_animations_hint),
            onClick = { extra.setAnimations(context, !look.animations) },
            onAdjust = { extra.setAnimations(context, it > 0) }
        ) { ThemedSwitch(look.animations) { extra.setAnimations(context, it) } }
    },
    SettingsRow(key = SettingKeys.GLOW) {
        SettingRow(
            icon = R.drawable.ic_blur_on,
            title = stringResource(R.string.settings_v5_glow),
            hint = stringResource(R.string.settings43_glow_hint),
            onClick = { extra.setGlow(context, !look.glow) },
            onAdjust = { extra.setGlow(context, it > 0) }
        ) { ThemedSwitch(look.glow) { extra.setGlow(context, it) } }
    },
    SettingsRow(key = SettingKeys.COMPACT) {
        SettingRow(
            icon = R.drawable.ic_list,
            title = stringResource(R.string.disc6_compact_title),
            hint = stringResource(R.string.disc6_compact_hint),
            onClick = { extra.setCompactLists(context, !look.compactLists) },
            onAdjust = { extra.setCompactLists(context, it > 0) }
        ) { ThemedSwitch(look.compactLists) { extra.setCompactLists(context, it) } }
    }
)

/** The rows of the Controls card (see [SettingsLayout.groups] for their order). */
internal fun SettingsEnv.controlsCardRows(): List<SettingsRow> = listOf(
    SettingsRow(key = SettingKeys.GAMEPAD_LAYOUT) {
        SettingRow(
            icon = R.drawable.ic_gamepad,
            title = stringResource(R.string.settings_gamepad_layout),
            hint = stringResource(R.string.settings_gamepad_layout_hint),
            onClick = { cycleGamepadLayout(1) },
            onAdjust = { cycleGamepadLayout(it) }
        ) {
            SettingsStepper(
                stringResource(ui.gamepadLayout.labelRes),
                onDecrement = { cycleGamepadLayout(-1) },
                onIncrement = { cycleGamepadLayout(1) }
            )
        }
    },
    SettingsRow(key = SettingKeys.SWAP_BUTTONS) {
        SettingRow(
            icon = R.drawable.ic_swap_horiz,
            title = stringResource(R.string.settings_swap_face_buttons),
            hint = stringResource(R.string.settings_swap_face_buttons_hint),
            onClick = { viewModel.onSwapFaceButtonsChanged(context, !ui.swapFaceButtons) },
            onAdjust = { viewModel.onSwapFaceButtonsChanged(context, it > 0) }
        ) {
            ThemedSwitch(ui.swapFaceButtons) { viewModel.onSwapFaceButtonsChanged(context, it) }
        }
    },
    SettingsRow(key = SettingKeys.SECOND_SCREEN) {
        SettingRow(
            icon = R.drawable.ic_tv,
            title = stringResource(R.string.settings_second_screen),
            hint = stringResource(R.string.settings_second_screen_hint),
            onClick = { extra.setSecondScreen(context, !appPrefs.secondScreen) },
            onAdjust = { extra.setSecondScreen(context, it > 0) }
        ) { ThemedSwitch(appPrefs.secondScreen) { extra.setSecondScreen(context, it) } }
    },
    SettingsRow(key = SettingKeys.TV_MODE) { TvModeSettingRow() }
)

/** The rows of the Library card (see [SettingsLayout.groups] for their order). */
internal fun SettingsEnv.libraryCardRows(): List<SettingsRow> = listOf(
    SettingsRow(key = SettingKeys.START_VIEW) {
        // Only the views the Library tab offers with a game page to open (all four); a stepper walks through them.
        val views = com.tufein.valkyr.util.LibraryBrowse.availableViews(true)
        fun shift(by: Int) = extra.setStartView(context, views[(views.indexOf(look.startView).coerceAtLeast(0) + by + views.size) % views.size])
        SettingRow(
            icon = R.drawable.ic_dashboard,
            title = stringResource(R.string.home60_start_view),
            hint = stringResource(R.string.home60_start_view_hint),
            onClick = { shift(1) },
            onAdjust = { shift(it) }
        ) {
            SettingsStepper(
                stringResource(look.startView.labelRes()),
                onDecrement = { shift(-1) },
                onIncrement = { shift(1) }
            )
        }
    },
    SettingsRow(key = SettingKeys.LIST_COVERS) {
        SettingRow(
            icon = R.drawable.ic_image,
            title = stringResource(R.string.settings_v5_list_covers),
            hint = stringResource(R.string.settings_v5_list_covers_hint),
            onClick = { extra.setListCovers(context, !look.listCovers) },
            onAdjust = { extra.setListCovers(context, it > 0) }
        ) { ThemedSwitch(look.listCovers) { extra.setListCovers(context, it) } }
    },
    SettingsRow(key = SettingKeys.COVERS_RETRY) {
        CoverSourcesRow(onOpen = { navController.navigate(NavRoutes.CoverSources.route) })
    },
    SettingsRow(key = SettingKeys.MAX_RESULTS) {
        SettingRow(
            icon = R.drawable.ic_format_list_numbered,
            title = stringResource(R.string.settings_max_results),
            hint = stringResource(R.string.settings_max_results_hint),
            onClick = { adjustMaxSearchResults(1) },
            onAdjust = { adjustMaxSearchResults(it) }
        ) {
            SettingsStepper(
                if (ui.maxSearchResults <= 0) stringResource(R.string.settings_unlimited) else ui.maxSearchResults.toString(),
                onDecrement = { adjustMaxSearchResults(-1) },
                onIncrement = { adjustMaxSearchResults(1) }
            )
        }
    },
    SettingsRow(key = SettingKeys.FAVORITE_LANGUAGES) {
        SettingRow(
            icon = R.drawable.ic_translate,
            title = stringResource(R.string.settings43_favorite_languages),
            hint = ui.favoriteLanguages.sorted().joinToString(" · ")
                .ifBlank { stringResource(R.string.settings_favorite_languages_hint) },
            onClick = { dialogs.showLanguagesDialog = true }
        ) {
            ActionPill(stringResource(R.string.settings_change), { dialogs.showLanguagesDialog = true }, icon = R.drawable.ic_edit)
        }
    },
    SettingsRow(key = SettingKeys.VERSION_PREF) {
        VersionPreferenceRow(onOpen = { navController.navigate(NavRoutes.VersionPreference.route) })
    },
    SettingsRow(key = SettingKeys.RECENT_MENU) { RecentGamesMenuSettingRow() },
    SettingsRow(key = SettingKeys.PROFILES) {
        SettingRow(
            icon = R.drawable.ic_account,
            title = stringResource(R.string.settings_profiles),
            hint = activeProfileName?.let { stringResource(R.string.settings_profiles_active, it) } ?: stringResource(R.string.settings_profiles_hint),
            onClick = { navController.navigate(NavRoutes.Profiles.route) },
        ) { NavChevron() }
    }
)
