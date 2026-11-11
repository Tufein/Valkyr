package com.tufein.valkyr.ui.screens.game

import androidx.compose.foundation.layout.defaultMinSize
import android.content.res.Configuration
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tufein.valkyr.R
import com.tufein.valkyr.data.model.DownloadableFileWithTags
import com.tufein.valkyr.data.service.GameShare
import com.tufein.valkyr.ui.common.Gamepad
import com.tufein.valkyr.ui.common.GamepadButton
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.EmptyState
import com.tufein.valkyr.ui.components.LegendEntry
import com.tufein.valkyr.ui.components.Panel
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.TagRow
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.formatBytes
import com.tufein.valkyr.ui.components.rememberFocusSource
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.ui.components.stripExtension
import com.tufein.valkyr.ui.screens.cloud.saves.CloudSavesSection
import com.tufein.valkyr.ui.screens.cloud.sections.AchievementsSection
import com.tufein.valkyr.ui.screens.cloud.sections.RommGameSection
import com.tufein.valkyr.ui.screens.manuals.ManualsSection
import com.tufein.valkyr.ui.screens.download.DownloadWhenDialog
import com.tufein.valkyr.ui.screens.home.DetailsState
import com.tufein.valkyr.ui.screens.home.components.CollectionPickerDialog
import com.tufein.valkyr.ui.screens.home.components.GameDescription
import com.tufein.valkyr.ui.screens.home.components.GameFacts
import com.tufein.valkyr.ui.screens.home.components.SimilarSection
import com.tufein.valkyr.ui.screens.tools.PublishLegend
import com.tufein.valkyr.ui.secondscreen.SecondScreenState
import com.tufein.valkyr.ui.theme.LocalReduceMotion
import com.tufein.valkyr.ui.theme.tabular
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.GameInfoText
import com.tufein.valkyr.util.GameShareText
import com.tufein.valkyr.util.DownloadCondition
import com.tufein.valkyr.util.FileParsingUtils
import com.tufein.valkyr.util.GameHeaderModel
import com.tufein.valkyr.util.GamePageModel
import com.tufein.valkyr.util.GamePageModel.Tab
import com.tufein.valkyr.util.ToastUtil
import com.tufein.valkyr.util.VersionPreferences
import java.util.Locale
import kotlinx.coroutines.launch
import android.text.format.Formatter
import androidx.compose.ui.text.AnnotatedString
import com.tufein.valkyr.data.service.GameHandler
import com.tufein.valkyr.data.service.GameLaunch
import com.tufein.valkyr.data.service.LaunchOutcome
import com.tufein.valkyr.data.service.RemovalFile
import com.tufein.valkyr.ui.screens.next.UpNextPill
import com.tufein.valkyr.ui.shortcut.PendingGameShortcut
import com.tufein.valkyr.util.EmulatorCatalog
import com.tufein.valkyr.util.GameLaunchKeys
import com.tufein.valkyr.util.VersionCompare
import kotlinx.coroutines.CancellationException

/**
 * 8.0: the full-screen page of one library game, the main way to look at a game (the library's
 * A / tap opens it). A hero header (the art over the console's colour, the cover, title, info and
 * language pills, the main button and the other actions), then tabs: About (description, facts,
 * the RomM section), Versions (every variant in the sources, each downloadable), Progress
 * (RetroAchievements and cloud saves) and More like this. Portrait scrolls as one column;
 * landscape keeps the hero on the left and the tabs on the right.
 *
 * 3.8 "covers first": the header is GameHeader.kt (a big cover, the full title, console / year /
 * size chips), then one big main button (Play / Download, focused first so A starts it), a row of
 * round quick actions (favourite, home screen, share, more) and a "More actions" card with every
 * other action; the tabs' content sits in rounded cards with 16 dp gaps.
 *
 * Gamepad: the D-pad walks the buttons (▲ ▼ scroll where nothing more can take focus), A acts,
 * B goes back, LB / RB switch tabs and Select stars the game.
 *
 * @param onOpenGame opens another game's page ("More like this").
 * @param onOpenVersionPreference opens the Version preference screen (a link in the Versions tab); left out when null.
 */
@Composable
fun GamePage(
    consoleId: String,
    fileName: String,
    onBack: () -> Unit,
    onOpenGame: (consoleId: String, fileName: String) -> Unit,
    onOpenVersionPreference: (() -> Unit)? = null,
    viewModel: GamePageViewModel = hiltViewModel(),
    onReadiness: ((String, String) -> Unit)? = null,
    /** Opens "Continue on another handheld" for the game; left out when null. */
    onHandoff: ((String, String) -> Unit)? = null
) {
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    LaunchedEffect(consoleId, fileName, activeProfile) { viewModel.load(consoleId, fileName) }
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val state by viewModel.details.collectAsStateWithLifecycle()
    val current = state
    when {
        phase == GamePagePhase.MISSING -> {
            PublishLegend(listOf(LegendEntry("A", stringResource(R.string.pad_back)), LegendEntry("B", stringResource(R.string.pad_back))))
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = stringResource(R.string.page8_missing_title),
                    message = stringResource(R.string.page8_missing_message),
                    icon = R.drawable.ic_search_off,
                    actionLabel = stringResource(R.string.page8_back),
                    onAction = onBack,
                    actionFocus = rememberInitialFocus()
                )
            }
        }
        current == null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        }
        else -> GamePageContent(current, viewModel, onBack, onOpenGame, onOpenVersionPreference, onReadiness, onHandoff)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GamePageContent(
    state: DetailsState,
    viewModel: GamePageViewModel,
    onBack: () -> Unit,
    onOpenGame: (String, String) -> Unit,
    onOpenVersionPreference: (() -> Unit)?,
    onReadiness: ((String, String) -> Unit)?,
    onHandoff: ((String, String) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val item = state.item
    val rom = item.file
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val ownedKeys by viewModel.ownedKeys.collectAsStateWithLifecycle()
    val active by viewModel.activeDownloads.collectAsStateWithLifecycle()
    val downloadStates by viewModel.downloadStates.collectAsStateWithLifecycle()
    val favouriteKeys by viewModel.favouriteKeys.collectAsStateWithLifecycle()
    val rommKeys by viewModel.rommKeys.collectAsStateWithLifecycle()
    val progressAvailable by viewModel.progressAvailable.collectAsStateWithLifecycle()
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    val ranking by viewModel.versionRanking.collectAsStateWithLifecycle()
    val owned = viewModel.isOwned(rom, ownedKeys)
    // One source for the primary action, Share / More and the header, so they never disagree about the queue.
    val queue = GamePageModel.queueOf(downloadStates[rom.fileName])
    val downloading = queue == GamePageModel.Queue.ACTIVE
    val favourite = viewModel.isFavourite(rom, favouriteKeys)
    val onRomm = viewModel.isOnRomm(rom, rommKeys)
    val rommOn by viewModel.rommEnabled.collectAsStateWithLifecycle()

    // The game on screen also goes to a second display, when there is one.
    LaunchedEffect(item) { SecondScreenState.focus(item) }

    val tabs = GamePageModel.tabs(state.versionCount, progressAvailable || state.achievements != null, state.similar.size)
    var tabName by rememberSaveable(rom.consoleId, rom.fileName) { mutableStateOf(Tab.ABOUT.name) }
    val tab = GamePageModel.visible(tabs, Tab.valueOf(tabName))

    val snackbar = remember { SnackbarHostState() }
    val showMessage: (String) -> Unit = { message ->
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message = message, duration = SnackbarDuration.Short)
        }
    }
    val startedMessage = stringResource(R.string.download_started, "%s")
    val alreadyMessage = stringResource(R.string.download_already_active, "%s")
    val favouriteAddedMessage = stringResource(R.string.favourite_added, "%s")
    val favouriteRemovedMessage = stringResource(R.string.favourite_removed, "%s")
    val deletedMessage = stringResource(R.string.owned_deleted, "%s")
    val deleteFailedMessage = stringResource(R.string.owned_delete_failed, "%s")
    val collectionAddedMessage = stringResource(R.string.collection_added, "%s")

    val download: (DownloadableFileWithTags, DownloadCondition?) -> Unit = { target, condition ->
        if (target.file.fileName in active) showMessage(alreadyMessage.format(target.file.name))
        else scope.launch { if (viewModel.download(target.file, context, condition)) showMessage(startedMessage.format(target.file.name)) }
    }
    val toggleFavourite: () -> Unit = {
        scope.launch {
            val starred = viewModel.toggleFavourite(item)
            showMessage((if (starred) favouriteAddedMessage else favouriteRemovedMessage).format(stripExtension(rom.name)))
        }
    }

    var showWhen by remember { mutableStateOf(false) }
    var showCollections by remember { mutableStateOf(false) }
    var removalPlan by remember { mutableStateOf<List<RemovalFile>?>(null) }
    var launchChoices by remember { mutableStateOf<List<GameLaunch>?>(null) }
    var emulatorChoices by remember { mutableStateOf<List<GameLaunch>?>(null) }
    val emulatorChoice by viewModel.emulatorChoice.collectAsStateWithLifecycle()
    var rememberEmulator by rememberSaveable { mutableStateOf(true) }
    var rememberForGame by rememberSaveable { mutableStateOf(false) }
    var staleChoice by remember { mutableStateOf(false) }
    // Save slot to resume (DukeStation), remembered per game; the control only shows for emulators that take one.
    var resumeSlot by remember(rom.consoleId, rom.fileName) { mutableStateOf(viewModel.gameLauncher.resumeSlot(rom.consoleId, rom.fileName)) }
    val changeResumeSlot: (Int?) -> Unit = { slot -> resumeSlot = slot; viewModel.gameLauncher.setResumeSlot(rom.consoleId, rom.fileName, slot) }
    var preparing by remember { mutableStateOf(false) }
    val planError = stringResource(R.string.recovery_action_failed)
    val missingEmulator = stringResource(R.string.play_no_handler)
    val prepareRemoval: () -> Unit = {
        if (!preparing) scope.launch {
            preparing = true
            try { removalPlan = viewModel.removalPlan(item) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { showMessage(planError) }
            finally { preparing = false }
        }
    }
    val needsExtractMessage = stringResource(R.string.play24_needs_extract, "%s")
    val launchFailedMessage = stringResource(R.string.play24_launch_failed, "%s")
    // Starts [handler]; false when it did not start (the reason is shown).
    val start: (GameLaunch, GameHandler, Boolean, Boolean) -> Boolean = { game, handler, remember, automatic ->
        when (viewModel.gameLauncher.launch(context, rom.consoleId, game, handler, remember && !rememberForGame, automatic, rom.fileName)) {
            LaunchOutcome.STARTED -> {
                if (remember && rememberForGame) viewModel.gameLauncher.setGamePreferred(rom.consoleId, rom.fileName,
                    if (automatic) GameLaunchKeys.AUTOMATIC else handler.key)
                true
            }
            LaunchOutcome.NEEDS_EXTRACT -> { showMessage(needsExtractMessage.format(handler.label)); false }
            LaunchOutcome.FAILED -> { showMessage(launchFailedMessage.format(handler.label)); false }
        }
    }
    val play: () -> Unit = {
        if (!preparing) scope.launch {
            preparing = true
            try {
                val choices = viewModel.gameLauncher.choices(rom)
                // One file, or a playlist that holds every disc: the remembered app starts it directly.
                val direct = viewModel.gameLauncher.direct(rom.consoleId, rom.fileName, choices)
                if (choices.isEmpty() || choices.all { it.handlers.isEmpty() }) showMessage(missingEmulator)
                else if (direct != null) {
                    // A remembered app that fails is no dead end: the chooser opens.
                    if (!start(direct.first, direct.second, false, false)) { staleChoice = false; launchChoices = choices }
                } else {
                    // A remembered app that is not offered any more (uninstalled): say so above the choices.
                    staleChoice = choices.any { viewModel.gameLauncher.effective(rom.consoleId, rom.fileName, it).missingGameOverride } ||
                        viewModel.gameLauncher.preferred(rom.consoleId)?.let { preferred -> choices.none { viewModel.gameLauncher.resolve(it, preferred) != null } } == true
                    launchChoices = choices
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { showMessage(planError) }
            finally { preparing = false }
        }
    }
    // 3.5: a home-screen shortcut that could not start the game by itself opened this page: Play
    // runs once the game shows as on the device (the chooser, or why it cannot start).
    val shortcutPlay by PendingGameShortcut.play.collectAsStateWithLifecycle()
    LaunchedEffect(shortcutPlay, owned, downloading, ownedKeys.isEmpty()) {
        if (shortcutPlay == null) return@LaunchedEffect
        if (owned && !downloading) {
            if (PendingGameShortcut.consumePlay(rom.consoleId, rom.fileName)) play()
            return@LaunchedEffect
        }
        // Not playable here (not on the device, or downloading) once the index knows the device:
        // drop the request, so a later visit of this page never starts the game unasked.
        if (ownedKeys.isNotEmpty() || downloading) {
            kotlinx.coroutines.delay(1_500)
            PendingGameShortcut.consumePlay(rom.consoleId, rom.fileName)
        }
    }
    if (showWhen) {
        DownloadWhenDialog(onDismiss = { showWhen = false }, onConfirm = { condition -> showWhen = false; download(item, condition) })
    }
    if (showCollections) {
        CollectionPickerDialog(collections = collections, selected = state.collectionIds,
            onToggle = { id -> scope.launch { viewModel.toggleCollection(item, id) } },
            onCreate = { name -> scope.launch { if (viewModel.createCollectionWith(item, name)) showMessage(collectionAddedMessage.format(name.trim())) } },
            onDismiss = { showCollections = false })
    }
    removalPlan?.let { plan ->
        val cancelFocus = rememberInitialFocus()
        AlertDialog(modifier = Modifier.closeOnGamepadB { removalPlan = null }, onDismissRequest = { removalPlan = null },
            title = { Text(stringResource(R.string.recovery_remove_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.recovery_remove_hint))
                plan.forEach { Text("${it.path} · ${formatBytes(it.bytes)}", style = MaterialTheme.typography.bodySmall) }
                if (plan.isEmpty()) Text(stringResource(R.string.recovery_no_files))
            } },
            confirmButton = { DialogButton(stringResource(R.string.recovery_to_trash), onClick = {
                removalPlan = null
                scope.launch {
                    try {
                        val ok = viewModel.remove(plan, rom.name, rom.consoleId, rom.fileName)
                        showMessage((if (ok) deletedMessage else deleteFailedMessage).format(rom.name))
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { showMessage(planError) }
                }
            }) },
            dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), { removalPlan = null }, initialFocus = cancelFocus) })
    }
    launchChoices?.let { choices ->
        val cancelFocus = rememberInitialFocus()
        val pick: (GameLaunch, GameHandler, Boolean) -> Unit = { choice, handler, automatic ->
            try { if (start(choice, handler, rememberEmulator, automatic)) launchChoices = null }
            catch (_: Exception) { showMessage(missingEmulator) }
        }
        AlertDialog(modifier = Modifier.closeOnGamepadB { launchChoices = null }, onDismissRequest = { launchChoices = null },
            title = { Text(stringResource(R.string.play_choose)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (staleChoice) Text(stringResource(R.string.play24_remembered_missing), style = MaterialTheme.typography.bodySmall)
                RememberCheckRow(stringResource(R.string.play26_remember_choice), rememberEmulator) { rememberEmulator = it }
                if (rememberEmulator) RememberCheckRow(stringResource(R.string.play26_remember_game), rememberForGame) { rememberForGame = it }
                if (rememberEmulator && !rememberForGame) Text(stringResource(R.string.play_remember), style = MaterialTheme.typography.bodySmall)
                ResumeSlotRow(resumeSlot, EmulatorCatalog.maxResumeSlot(choices.flatMap { c -> c.handlers.map { it.emulatorId } }), changeResumeSlot)
                // Automatic: the best app offered (a known emulator first), now and, when remembered, every time.
                val best = choices.firstOrNull { it.handlers.isNotEmpty() }
                best?.let { game -> ActionPill(stringResource(R.string.play24_automatic, game.handlers.first().label), { pick(game, game.handlers.first(), true) }, icon = R.drawable.ic_controller) }
                choices.forEach { choice ->
                    Text(choice.name, style = MaterialTheme.typography.bodyMedium)
                    choice.handlers.forEach { handler ->
                        ActionPill(handler.label, { pick(choice, handler, false) }, icon = R.drawable.ic_controller)
                    }
                }
                if (choices.any { c -> c.handlers.any { it.core != null } }) Text(stringResource(R.string.play24_core_hint), style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = {},
            dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), { launchChoices = null }, initialFocus = cancelFocus) })
    }
    emulatorChoices?.let { choices ->
        GameEmulatorChoiceDialog(choices, emulatorChoice?.gameOverride, emulatorChoice?.consoleDefault,
            onPick = { key -> viewModel.gameLauncher.setGamePreferred(rom.consoleId, rom.fileName, key); emulatorChoices = null },
            resumeSlot = resumeSlot, onResumeSlot = changeResumeSlot,
            onDismiss = { emulatorChoices = null })
    }

    // LB / RB switch tabs, Select stars the game (the shell keeps ZL / ZR for the app's sections).
    val currentTabs by rememberUpdatedState(tabs)
    val currentToggle by rememberUpdatedState(toggleFavourite)
    LaunchedEffect(Unit) {
        Gamepad.presses.collect { button ->
            when (button) {
                GamepadButton.PREV_PANEL -> tabName = GamePageModel.step(currentTabs, Tab.valueOf(tabName), -1).name
                GamepadButton.NEXT_PANEL -> tabName = GamePageModel.step(currentTabs, Tab.valueOf(tabName), 1).name
                GamepadButton.FAVOURITE -> currentToggle()
                else -> Unit
            }
        }
    }
    PublishLegend(
        listOfNotNull(
            LegendEntry("A", stringResource(R.string.pad_select)),
            LegendEntry("B", stringResource(R.string.pad_back)),
            if (tabs.size > 1) LegendEntry("LB · RB", stringResource(R.string.page8_pad_tabs)) else null,
            LegendEntry("SELECT", stringResource(R.string.pad_favourite)),
            LegendEntry("▲ ▼", stringResource(R.string.pad_scroll))
        )
    )

    // The main button takes the focus when the page opens (the back pill while it is disabled).
    val primary = GamePageModel.primary(owned, queue)
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(rom.consoleId, rom.fileName) {
        repeat(5) {
            if (runCatching { firstFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            withFrameNanos { }
        }
    }

    // 3.8: the round "more" button moves the focus to the first action of the More actions card.
    val moreFocus = remember { FocusRequester() }
    val pinToHomeScreen = rememberHomeScreenPin(rom, showMessage)
    // 6.3: "Copy game info" puts the page's facts on the clipboard as plain lines (not tested on a device).
    val clipboard = LocalClipboardManager.current
    val infoLabels = GameInfoText.Labels(
        console = stringResource(R.string.info63_label_console),
        released = stringResource(R.string.info63_label_released),
        developer = stringResource(R.string.info63_label_developer),
        genres = stringResource(R.string.info63_label_genres),
        file = stringResource(R.string.info63_label_file),
        tags = stringResource(R.string.info63_label_tags)
    )
    val infoCopiedMessage = stringResource(R.string.info63_copied)
    val copyGameInfo: () -> Unit = {
        val details = state.details
        val facts = GameInfoText.Facts(
            title = rom.name,
            console = ConsoleFormatter.getConsoleDisplayName(rom.consoleId),
            released = details?.released.orEmpty(),
            developer = details?.developer.orEmpty(),
            genres = details?.genres.orEmpty(),
            fileName = rom.fileName,
            sizeText = if (rom.fileSize > 0L) Formatter.formatShortFileSize(context, rom.fileSize) else "",
            tags = item.tags
        )
        clipboard.setText(AnnotatedString(GameInfoText.build(facts, infoLabels, GameShareText.deepLink(rom.consoleId, rom.name))))
        showMessage(infoCopiedMessage)
    }
    val header: @Composable () -> Unit = {
        GameHeader(
            state = state,
            owned = owned,
            downloading = downloading,
            favourite = favourite,
            onRomm = onRomm,
            isLandscape = isLandscape,
            onBack = onBack,
            rommOn = rommOn
        )
    }
    // 5.2: the main button and the favourite / more buttons live in the sticky bar below the page.
    val onPrimary: () -> Unit = {
        if (!preparing) when (primary) {
            GamePageModel.Primary.DOWNLOADING -> Unit
            GamePageModel.Primary.PLAY -> play()
            GamePageModel.Primary.RESUME, GamePageModel.Primary.RETRY -> {
                viewModel.resumeOrRetry(rom.fileName)
                showMessage(startedMessage.format(rom.name))
            }
            GamePageModel.Primary.DOWNLOAD, GamePageModel.Primary.DOWNLOAD_AGAIN -> download(item, null)
        }
    }
    val actionBar: @Composable () -> Unit = {
        GameActionBar(
            primary = primary,
            favourite = favourite,
            onPrimary = onPrimary,
            onFavourite = toggleFavourite,
            onMore = { runCatching { moreFocus.requestFocus() } },
            primaryModifier = Modifier.focusRequester(firstFocus)
        )
    }
    val statePreview by produceState<android.net.Uri?>(null, rom.fileName) { value = viewModel.statePreview(rom.fileName) }
    val moreActions: @Composable () -> Unit = {
        val switch = state.switch
        // The live ranking (the same as the Versions tab), so a pin or a preference change shows at once.
        val best = ranking?.pick ?: state.best
        GameMoreActions(modifier = Modifier.padding(horizontal = 16.dp)) {
            StatePreviewCard(statePreview)
            // The round buttons of 3.8 that the bar no longer carries.
            val quick = GameHeaderModel.quick(owned, downloading)
            if (GameHeaderModel.Quick.HOME_SCREEN in quick) {
                ActionPill(stringResource(R.string.gamepage38_home_screen), pinToHomeScreen, modifier = PageTarget, icon = R.drawable.ic_shortcut)
            }
            if (GameHeaderModel.Quick.SHARE in quick) {
                ActionPill(stringResource(R.string.lead6_share), { scope.launch { GameShare.share(context, rom.consoleId, rom.fileName, rom.name) } }, modifier = PageTarget, icon = R.drawable.ic_share)
            }
            ActionPill(stringResource(R.string.info63_copy), copyGameInfo, modifier = PageTarget, icon = R.drawable.ic_content_copy)
            GameHeaderModel.more(
                owned = owned,
                downloading = downloading,
                readiness = onReadiness != null,
                handoff = onHandoff != null,
                betterVersion = best != null && best.file.fileName != rom.fileName,
                updateAvailable = switch?.updateAvailable == true && switch.newestUpdate != null,
                missingDlc = switch?.missingDlc?.size ?: 0
            ).forEach { action ->
                when (action) {
                    // Always first, so the "more" button always has something to focus.
                    GameHeaderModel.More.UP_NEXT -> UpNextPill(rom.consoleId, rom.fileName, modifier = PageTarget.focusRequester(moreFocus))
                    GameHeaderModel.More.COMPLETED -> CompletedPill(rom.consoleId, rom.fileName, modifier = PageTarget)
                    GameHeaderModel.More.READINESS -> onReadiness?.let { open ->
                        ActionPill(stringResource(R.string.ready25_title), { open(rom.consoleId, rom.fileName) }, modifier = PageTarget, icon = R.drawable.ic_controller)
                    }
                    GameHeaderModel.More.HANDOFF -> onHandoff?.let { open ->
                        ActionPill(stringResource(R.string.handoff31_action), { open(rom.consoleId, rom.fileName) }, modifier = PageTarget, icon = R.drawable.ic_devices)
                    }
                    GameHeaderModel.More.DOWNLOAD_AGAIN -> ActionPill(stringResource(R.string.owned_download_again), { download(item, null) }, modifier = PageTarget, icon = R.drawable.ic_download)
                    GameHeaderModel.More.EMULATOR -> ActionPill(stringResource(R.string.play26_game_title), {
                        if (!preparing) scope.launch {
                            preparing = true
                            try { emulatorChoices = viewModel.gameLauncher.choices(rom) }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { showMessage(planError) }
                            finally { preparing = false }
                        }
                    }, modifier = PageTarget, icon = R.drawable.ic_settings)
                    GameHeaderModel.More.DOWNLOAD_BEST -> best?.let {
                        ActionPill(stringResource(R.string.details_download_best), { download(it, null) }, modifier = PageTarget, icon = R.drawable.ic_award)
                    }
                    GameHeaderModel.More.UPDATE -> switch?.newestUpdate?.first?.let { row ->
                        ActionPill(stringResource(R.string.details_switch_get_update), { download(DownloadableFileWithTags(row, emptyList()), null) }, modifier = PageTarget, icon = R.drawable.ic_sync, tone = ActionTone.Accent)
                    }
                    GameHeaderModel.More.DLC -> switch?.missingDlc?.let { rows ->
                        ActionPill(
                            stringResource(R.string.details_switch_get_dlc, rows.size),
                            { rows.forEach { row -> download(DownloadableFileWithTags(row, emptyList()), null) } },
                            modifier = PageTarget, icon = R.drawable.ic_plus, tone = ActionTone.Accent
                        )
                    }
                    GameHeaderModel.More.DOWNLOAD_WHEN -> ActionPill(stringResource(R.string.plan6_wait_for), { showWhen = true }, modifier = PageTarget, icon = R.drawable.ic_schedule)
                    GameHeaderModel.More.COLLECTIONS -> ActionPill(
                        if (state.collectionIds.isEmpty()) stringResource(R.string.details_collections)
                        else pluralStringResource(R.plurals.details_in_collections, state.collectionIds.size, state.collectionIds.size),
                        { showCollections = true },
                        modifier = PageTarget,
                        icon = R.drawable.ic_collections
                    )
                    GameHeaderModel.More.REMOVE -> ActionPill(stringResource(R.string.page8_remove), { prepareRemoval() }, modifier = PageTarget, icon = R.drawable.ic_trash, tone = ActionTone.Danger)
                }
            }
        }
    }
    val tabStrip: @Composable () -> Unit = {
        if (tabs.size > 1) TabStrip(tabs, tab) { tabName = it.name }
    }
    val content: @Composable () -> Unit = {
        TabContent(
            tab = tab,
            state = state,
            ownedOf = { viewModel.isOwned(it.file, ownedKeys) },
            downloadingOf = { it.file.fileName in active },
            onDownload = { download(it, null) },
            onOpenGame = onOpenGame,
            viewModel = viewModel,
            onOpenVersionPreference = onOpenVersionPreference
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isLandscape) {
            val heroScroll = rememberScrollState()
            val panelScroll = rememberScrollState()
            // A new tab starts at its top.
            LaunchedEffect(tab) { panelScroll.scrollTo(0) }
            Row(modifier = Modifier.fillMaxSize()) {
                // The hero scrolls above the sticky action bar, which stays under the left column.
                Column(modifier = Modifier.weight(0.42f).fillMaxHeight()) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .dpadScroll(heroScroll)
                            .verticalScroll(heroScroll)
                            .padding(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(GamePageGap)
                    ) {
                        header()
                        moreActions()
                    }
                    SnackbarHost(snackbar, modifier = Modifier.padding(horizontal = 16.dp))
                    actionBar()
                }
                Column(
                    modifier = Modifier
                        .weight(0.58f)
                        .fillMaxHeight()
                        .padding(start = 8.dp, end = 16.dp, top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    tabStrip()
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .dpadScroll(panelScroll)
                            .verticalScroll(panelScroll)
                            .padding(bottom = 16.dp)
                    ) { content() }
                }
            }
        } else {
            val scroll = rememberScrollState()
            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .dpadScroll(scroll)
                        .verticalScroll(scroll)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(GamePageGap)
                ) {
                    header()
                    moreActions()
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) { tabStrip() }
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) { content() }
                }
                // Right above the sticky bar, whatever its height.
                SnackbarHost(snackbar, modifier = Modifier.padding(horizontal = 16.dp))
                actionBar()
            }
        }
    }
}

/** The tab pills (touch, or LB / RB); the selected one is accent-filled. */
@Composable
private fun TabStrip(tabs: List<Tab>, selected: Tab, onSelect: (Tab) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tabs.forEach { t ->
            val (label, icon) = when (t) {
                Tab.ABOUT -> R.string.page8_tab_about to R.drawable.ic_info
                Tab.VERSIONS -> R.string.page8_tab_versions to R.drawable.ic_stacks
                Tab.PROGRESS -> R.string.page8_tab_progress to R.drawable.ic_trophy
                Tab.SIMILAR -> R.string.page8_tab_similar to R.drawable.ic_sparkle
            }
            ActionPill(
                stringResource(label),
                onClick = { onSelect(t) },
                modifier = PageTarget,
                icon = icon,
                tone = if (t == selected) ActionTone.Accent else ActionTone.Neutral
            )
        }
    }
}

@Composable
private fun TabContent(
    tab: Tab,
    state: DetailsState,
    ownedOf: (DownloadableFileWithTags) -> Boolean,
    downloadingOf: (DownloadableFileWithTags) -> Boolean,
    onDownload: (DownloadableFileWithTags) -> Unit,
    onOpenGame: (String, String) -> Unit,
    viewModel: GamePageViewModel,
    onOpenVersionPreference: (() -> Unit)?
) {
    val rom = state.item.file
    val scheme = MaterialTheme.colorScheme
    // 3.8: every card on the page (the shared sections draw a Panel with the theme's medium shape)
    // gets the page's rounder corners, without restyling those sections themselves.
    val cardShape = RoundedCornerShape(GamePageCardRadius)
    MaterialTheme(
        colorScheme = scheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes.copy(medium = cardShape)
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GamePageGap)) {
            when (tab) {
                Tab.ABOUT -> {
                    Panel(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        GameFacts(state)
                        GameDescription(state)
                        state.details?.let { details ->
                            Text(stringResource(R.string.details_source, details.source), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                        }
                    }
                    GameJournalSection(rom.consoleId, rom.fileName)
                    // RomM's summary, genres, screenshots, play status and rating (draws nothing without RomM).
                    RommGameSection(
                        consoleId = rom.consoleId,
                        fileName = rom.fileName,
                        showSummary = state.details?.description.isNullOrBlank()
                    )
                    // 6.0: manuals kept on the device, and the one RomM has (draws nothing without either).
                    ManualsSection(rom.consoleId, rom.fileName)
                }
                Tab.VERSIONS -> Versions(state, ownedOf, downloadingOf, onDownload, viewModel, onOpenVersionPreference)
                Tab.PROGRESS -> {
                    AchievementsSection(consoleId = rom.consoleId, fileName = rom.fileName, title = rom.name, match = state.achievements)
                    CloudSavesSection(consoleId = rom.consoleId, fileName = rom.fileName)
                    Text(stringResource(R.string.page8_progress_hint), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                Tab.SIMILAR -> SimilarSection(state.similar, onOpen = { onOpenGame(it.file.consoleId, it.file.fileName) })
            }
        }
    }
}

/**
 * Every version of the game in the sources, ranked by the user's version preference: the pick (the
 * fixed version, else the best ranked) first with why it wins, every other version with why it
 * ranks lower, its DAT result and (for this version) how RetroAchievements knows it. A downloads a
 * row; under it: fix this version for the game, put it side by side with another, or prefer
 * versions like it from now on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Versions(
    state: DetailsState,
    ownedOf: (DownloadableFileWithTags) -> Boolean,
    downloadingOf: (DownloadableFileWithTags) -> Boolean,
    onDownload: (DownloadableFileWithTags) -> Unit,
    viewModel: GamePageViewModel,
    onOpenVersionPreference: (() -> Unit)?
) {
    val preferred by viewModel.preferred.collectAsStateWithLifecycle()
    val datReports by viewModel.datReports.collectAsStateWithLifecycle()
    val ranking by viewModel.versionRanking.collectAsStateWithLifecycle()
    val prefs by viewModel.versionPrefs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val preferDoneTemplate = stringResource(R.string.compare24_prefer_done)
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val current = state.item.file.fileName
    val consoleName = ConsoleFormatter.getConsoleShortName(state.item.file.consoleId)
    Text(stringResource(R.string.page8_versions_hint), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    val r = ranking ?: return
    // Up to two versions for the side-by-side, by ranking id; the oldest pick goes first.
    var picked by remember(r.ranked.map { it.version.name }) { mutableStateOf(emptyList<String>()) }
    var preferTarget by remember { mutableStateOf<VersionCompare.Ranked?>(null) }
    val pair = picked.mapNotNull { id -> r.ranked.firstOrNull { it.version.id == id } }
    if (pair.size == 2) CompareTable(pair[0], pair[1], r.preference, onClear = { picked = emptyList() })
    else if (r.rows.size > 1) Text(stringResource(R.string.compare24_compare_hint), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
    Panel(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        r.rows.forEach { row ->
            val version = row.item
            val id = row.ranked.version.id
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                VersionRow(
                    version = version,
                    isCurrent = version.file.fileName == current,
                    isBest = row.isPick,
                    owned = ownedOf(version),
                    downloading = downloadingOf(version),
                    onClick = { if (!downloadingOf(version)) onDownload(version) }
                )
                Column(modifier = Modifier.padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReasonPills(row.ranked.reasons)
                    // Why it sits where it does: a fixed version, the best ranked one, or what it lacks against that one.
                    val why = listOfNotNull(
                        if (row.isFixed) stringResource(R.string.compare24_fixed_note) else null,
                        if (row.isTop) r.explanation?.let { becauseLine(it) } else row.whyNot?.let { whyNotLine(it) }
                    )
                    why.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = if (row.isPick) scheme.onSurface else scheme.onSurfaceVariant) }
                    if (row.isTop && !row.isPick && r.pick != null) {
                        Text(stringResource(R.string.compare24_top_but_fixed), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    val dat = datReports[version.file.consoleId]?.checks?.firstOrNull { it.first.name.equals(version.file.fileName, true) }?.second?.status
                    Text(stringResource(R.string.version_dat, dat?.name ?: stringResource(R.string.version_unknown)), style = MaterialTheme.typography.bodySmall)
                    if (version.file.fileName == current && state.achievements != null) {
                        Text(stringResource(if (state.achievements.second) R.string.version_ra_hash else R.string.version_ra_title), style = MaterialTheme.typography.bodySmall)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionPill(stringResource(if (preferred == version.file.fileName) R.string.version_unpin else R.string.version_pin),
                            { scope.launch { viewModel.setPreferred(version, preferred != version.file.fileName) } }, icon = R.drawable.ic_star)
                        if (r.rows.size > 1) {
                            val on = id in picked
                            ActionPill(
                                stringResource(if (on) R.string.compare24_compare_selected else R.string.compare24_compare),
                                onClick = { picked = if (on) picked - id else (picked + id).takeLast(2) },
                                icon = if (on) R.drawable.ic_check else R.drawable.ic_compare,
                                tone = if (on) ActionTone.Accent else ActionTone.Neutral
                            )
                        }
                        if (prefs != null) ActionPill(stringResource(R.string.compare24_prefer), onClick = { preferTarget = row.ranked }, icon = R.drawable.ic_tune)
                    }
                }
            }
        }
    }
    onOpenVersionPreference?.let { open -> ActionPill(stringResource(R.string.compare24_title), onClick = open, icon = R.drawable.ic_compare) }
    val target = preferTarget
    val base = prefs
    if (target != null && base != null) {
        PreferLikeDialog(
            target = target,
            ranked = r.ranked,
            prefs = base,
            consoleName = consoleName,
            onDismiss = { preferTarget = null },
            onConfirm = { forConsole, followRevision, clearOverride ->
                preferTarget = null
                scope.launch {
                    val now = viewModel.preferLike(target, r.ranked, forConsole, followRevision, clearOverride) ?: return@launch
                    ToastUtil.showSuccess(
                        context,
                        String.format(Locale.getDefault(), preferDoneTemplate, VersionPreferences.summary(now))
                    )
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VersionRow(
    version: DownloadableFileWithTags,
    isCurrent: Boolean,
    isBest: Boolean,
    owned: Boolean,
    downloading: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    val file = version.file
    val name = remember(file.fileName) { stripExtension(FileParsingUtils.decodeUrlEncodedFileName(file.fileName)) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusRing(source, 10.dp)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
            TagRow(
                console = ConsoleFormatter.getConsoleShortName(file.consoleId),
                tags = version.tags,
                extension = file.fileExtension,
                maxLines = 1,
                consoleId = file.consoleId
            )
            if (isCurrent || isBest || owned || downloading) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isBest) Pill(stringResource(R.string.page8_version_best), tone = PillTone.Accent, icon = R.drawable.ic_award)
                    if (isCurrent) Pill(stringResource(R.string.page8_version_this), tone = PillTone.Info, icon = R.drawable.ic_check)
                    if (downloading) Pill(stringResource(R.string.downloading_badge), tone = PillTone.Accent, icon = R.drawable.ic_download)
                    else if (owned) Pill(stringResource(R.string.home_pill_on_device), tone = PillTone.Success, icon = R.drawable.ic_check_circle)
                }
            }
        }
        if (file.fileSize > 0) {
            Text(formatBytes(file.fileSize), style = MaterialTheme.typography.labelMedium.tabular(), color = scheme.onSurfaceVariant)
        }
        Icon(painterResource(R.drawable.ic_download), contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
    }
}

/**
 * ▲ ▼ move the focus first; where it cannot move (long text below the last button) they scroll
 * [scroll] instead, so nothing on the page needs the touch screen.
 */
@Composable
private fun Modifier.dpadScroll(scroll: ScrollState): Modifier {
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val reduce = LocalReduceMotion.current
    return this.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val step = when (event.key) {
            Key.DirectionUp -> -SCROLL_STEP
            Key.DirectionDown -> SCROLL_STEP
            else -> return@onPreviewKeyEvent false
        }
        val direction = if (step < 0) FocusDirection.Up else FocusDirection.Down
        if (focusManager.moveFocus(direction)) return@onPreviewKeyEvent true
        val canScroll = if (step < 0) scroll.value > 0 else scroll.value < scroll.maxValue
        if (!canScroll) return@onPreviewKeyEvent false
        scope.launch { if (reduce) scroll.scrollBy(step) else scroll.animateScrollBy(step) }
        true
    }
}

private const val SCROLL_STEP = 160f

/** A whole-row checkbox for the launch dialog: 48 dp high with the accent focus ring, so A / a tap anywhere on it toggles. */
@Composable
private fun RememberCheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val source = rememberFocusSource()
    Row(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).focusRing(source, 10.dp)
            .toggleable(value = checked, interactionSource = source, indication = null, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked, null)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}
