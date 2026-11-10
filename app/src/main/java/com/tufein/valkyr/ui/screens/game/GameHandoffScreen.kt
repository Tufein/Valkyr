package com.tufein.valkyr.ui.screens.game

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.R
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.repository.DownloadableFileRepository
import com.tufein.valkyr.data.repository.SettingsRepository
import com.tufein.valkyr.data.service.CloudSavesService
import com.tufein.valkyr.data.service.GameLaunchService
import com.tufein.valkyr.data.service.LibraryIndexService
import com.tufein.valkyr.data.service.ProfileService
import com.tufein.valkyr.data.service.RommLibraryService
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.DialogButton
import com.tufein.valkyr.ui.components.Panel
import com.tufein.valkyr.ui.components.PanelTone
import com.tufein.valkyr.ui.components.IconTile
import com.tufein.valkyr.ui.components.closeOnGamepadB
import com.tufein.valkyr.ui.components.formatBytes
import com.tufein.valkyr.ui.components.rememberInitialFocus
import com.tufein.valkyr.ui.screens.sources.components.QrCodes
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.GameLaunchKeys
import com.tufein.valkyr.util.HandoffBlock
import com.tufein.valkyr.util.HandoffGuide
import com.tufein.valkyr.util.HandoffLink
import com.tufein.valkyr.util.HandoffSupport
import com.tufein.valkyr.util.JournalKey
import com.tufein.valkyr.util.SaveFolderLayout
import com.tufein.valkyr.util.SaveHandoff
import com.tufein.valkyr.util.SaveHandoffDirection
import com.tufein.valkyr.util.SaveHandoffPreview
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Row
import com.tufein.valkyr.ui.navigation.NavRoutes
import com.tufein.valkyr.util.GamePageModel

/** `game_handoff/<console>/<file>?mode=send|receive&code=…`: "Continue on another handheld" for one game. */
object GameHandoffRoute {
    const val route = "game_handoff/{consoleId}/{fileName}?mode={mode}&code={code}"
    const val RECEIVE = "receive"

    fun of(console: String, name: String, receive: Boolean = false, code: String? = null): String =
        "game_handoff/" + Uri.encode(console) + "/" + Uri.encode(name) + "?mode=" + (if (receive) RECEIVE else "send") +
            (code?.let { "&code=" + Uri.encode(it) } ?: "")
}

/** A hand-off link opened from outside (`valkyr://handoff?…`), waiting for the app to show it. */
object PendingHandoff {
    private val _request = MutableStateFlow<HandoffLink.Request?>(null)
    val request: StateFlow<HandoffLink.Request?> = _request.asStateFlow()
    fun submit(request: HandoffLink.Request) { _request.value = request }
    fun consume(): HandoffLink.Request? = _request.value.also { _request.value = null }
}

data class GameHandoffUi(
    val key: JournalKey? = null,
    val loading: Boolean = true,
    val title: String = "",
    val inLibrary: Boolean = false,
    val owned: Boolean = false,
    val source: String? = null,
    val support: HandoffSupport? = null,
    val preview: SaveHandoffPreview? = null,
    val busy: Boolean = false,
    val failed: Boolean = false,
    val tooLarge: Boolean = false
) {
    /** The code of the saves RomM holds for this game, as last checked. */
    val serverCode: String? get() = preview?.let { p -> HandoffGuide.code(p.files.mapNotNull { it.remoteSha256 }) }
}

/**
 * "Continue on another handheld": whether the game's emulator + save combination can be handed
 * over ([HandoffGuide.support]), then the checked, verified transfer of [SaveHandoff] through RomM
 * (the same as Cloud saves → Switch devices safely), then a code, QR code and link for the other
 * handheld. The receiving side runs the same transfer and compares the code.
 */
@HiltViewModel
class GameHandoffViewModel @Inject constructor(
    private val repository: DownloadableFileRepository,
    private val library: LibraryIndexService,
    private val launcher: GameLaunchService,
    private val appSettings: AppSettings,
    private val settingsRepository: SettingsRepository,
    private val rommLibrary: RommLibraryService,
    private val cloud: CloudSavesService,
    private val profiles: ProfileService
) : ViewModel() {
    val activeProfile = profiles.activeId
    private val _ui = MutableStateFlow(GameHandoffUi())
    val ui: StateFlow<GameHandoffUi> = _ui.asStateFlow()
    private var loadJob: Job? = null
    private var job: Job? = null
    private var expiry: Job? = null

    fun load(consoleId: String, fileName: String) {
        val key = JournalKey(profiles.currentIdNow(), consoleId, fileName)
        if (_ui.value.key == key && !_ui.value.loading) return
        loadJob?.cancel(); job?.cancel(); expiry?.cancel()
        _ui.value = GameHandoffUi(key)
        loadJob = viewModelScope.launch {
            val next = runCatching {
                withContext(Dispatchers.IO) {
                    val item = repository.findByFileNames(listOf(fileName)) { consoleId }[fileName]
                        ?.takeIf { it.file.consoleId == consoleId && profiles.current().allows(it.file.consoleId, it.tags) }
                    val owned = item != null && library.isOwned(item.file)
                    // The emulator Play would use: the game's own choice, else the console's.
                    var emulatorId: String? = null
                    var core: String? = null
                    var appLabel: String? = null
                    if (item != null && owned) {
                        val game = runCatching { launcher.choices(item.file) }.getOrDefault(emptyList()).firstOrNull()
                        val handler = game?.let { launcher.effective(consoleId, fileName, it).handler ?: it.handlers.singleOrNull() }
                        emulatorId = handler?.emulatorId; core = handler?.core; appLabel = handler?.label
                    } else {
                        val stored = launcher.gamePreferred(consoleId, fileName) ?: launcher.preferred(consoleId)
                        GameLaunchKeys.parseCatalogue(stored)?.let { (id, c) -> emulatorId = id; core = c }
                        appLabel = launcher.labelOf(stored)
                    }
                    val rommReady = settingsRepository.rommUrl.first().isNotBlank() && settingsRepository.rommToken.first().isNotBlank()
                    val support = HandoffGuide.support(
                        consoleId, emulatorId, core, appLabel, appSettings.saveSyncEmulatorFolders.first(),
                        settingsRepository.saveSyncSavesDir.first().isNotBlank(), rommReady, rommLibrary.gameFor(consoleId, fileName) != null
                    )
                    GameHandoffUi(
                        key, loading = false, title = item?.file?.name ?: fileName, inLibrary = item != null, owned = owned,
                        source = item?.file?.let { f -> HandoffGuide.sourceLabel(f.sourceUrl.ifBlank { f.downloadUrl }) }, support = support
                    )
                }
            }.getOrElse { e -> if (e is CancellationException) throw e; GameHandoffUi(key, loading = false, title = fileName, failed = true) }
            if (_ui.value.key == key) _ui.value = next
        }
    }

    fun expireReady() { _ui.value = _ui.value.copy(preview = _ui.value.preview?.copy(backupVerified = false)) }

    fun check() { val key = _ui.value.key ?: return; act(key) { cloud.previewHandoff(key) } }

    fun transfer(preview: SaveHandoffPreview) { if (_ui.value.preview != preview) return; act(preview.key) { cloud.transferHandoff(preview) } }

    private fun act(key: JournalKey, block: suspend () -> SaveHandoffPreview) {
        if (_ui.value.busy || _ui.value.support !is HandoffSupport.Supported) return
        expiry?.cancel()
        _ui.value = _ui.value.copy(busy = true, failed = false, tooLarge = false, preview = null)
        job = viewModelScope.launch {
            try {
                val preview = block()
                if (_ui.value.key == key) {
                    _ui.value = _ui.value.copy(preview = preview, busy = false)
                    expiry = viewModelScope.launch {
                        delay(SaveHandoff.PREVIEW_TTL_MS)
                        // A finished transfer keeps its code on screen; only an unused check expires.
                        if (_ui.value.key == key && _ui.value.preview?.ready != true) _ui.value = _ui.value.copy(preview = null)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (_ui.value.key == key) _ui.value = _ui.value.copy(busy = false, failed = true, tooLarge = e is SaveHandoff.SaveTooLargeException)
            }
        }
    }
}

/**
 * The guided hand-off of one game. The way back comes first, then whether this emulator and save
 * type can travel at all (a clear explanation instead of a button when not), then the checked
 * transfer, and finally the code, QR code and link the other handheld opens. [code] is the code a
 * link brought (receiving side), to compare with what RomM holds.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameHandoffScreen(
    consoleId: String,
    fileName: String,
    receive: Boolean,
    code: String?,
    onNavigate: (String) -> Unit,
    onSearchLibrary: (consoleId: String, fileName: String) -> Unit,
    viewModel: GameHandoffViewModel = hiltViewModel()
) {
    val profile by viewModel.activeProfile.collectAsStateWithLifecycle()
    LaunchedEffect(consoleId, fileName, profile) { viewModel.load(consoleId, fileName) }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var receiving by remember(consoleId, fileName) { mutableStateOf(receive) }
    var confirm by remember(consoleId, fileName) { mutableStateOf<SaveHandoffPreview?>(null) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { confirm = null; viewModel.expireReady() } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val firstFocus = rememberInitialFocus()
    val console = ConsoleFormatter.getConsoleDisplayName(consoleId)

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Title and game name wrap rather than cut off (long translations, long game names).
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(R.drawable.ic_devices, size = 38.dp)
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (receiving) R.string.handoff31_title_receive else R.string.handoff31_title_send), style = MaterialTheme.typography.headlineSmall)
                Text(ui.title.ifBlank { fileName }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionPill(stringResource(R.string.handoff31_mode_send), { receiving = false }, modifier = Modifier.focusRequester(firstFocus),
                icon = R.drawable.ic_share, tone = if (!receiving) ActionTone.Accent else ActionTone.Neutral)
            ActionPill(stringResource(R.string.handoff31_mode_receive), { receiving = true },
                icon = R.drawable.ic_download, tone = if (receiving) ActionTone.Accent else ActionTone.Neutral)
        }
        if (ui.loading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(modifier = Modifier.size(28.dp)) }
            return@Column
        }
        // Receiving a game this handheld does not have: say which file it needs and how to get it.
        if (receiving && (!ui.inLibrary || !ui.owned)) {
            Block(stringResource(R.string.handoff31_missing_title), tone = PanelTone.Accent) {
                Line(stringResource(R.string.handoff31_needs_file, fileName, console))
                if (ui.inLibrary) Line(stringResource(R.string.handoff31_not_owned))
                else Line(ui.source?.let { stringResource(R.string.handoff31_needs_source, it) } ?: stringResource(R.string.handoff31_needs_any_source))
                Line(stringResource(R.string.handoff31_needs_package))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ui.inLibrary) ActionPill(stringResource(R.string.handoff31_open_game), { onNavigate(GamePageModel.route(consoleId, fileName)) }, icon = R.drawable.ic_controller)
                    else ActionPill(stringResource(R.string.handoff31_missing_search), { onSearchLibrary(consoleId, fileName) }, icon = R.drawable.ic_search)
                    ActionPill(stringResource(R.string.package_transfer_title), { onNavigate(NavRoutes.PackageTransfer.route) }, icon = R.drawable.ic_stacks)
                }
            }
        }

        // 1. The way back, before anything else.
        WayBack(ui.support)

        // 2. Can this combination travel?
        val support = ui.support
        Block(stringResource(R.string.handoff31_support_title), tone = if (support is HandoffSupport.Supported) PanelTone.Normal else PanelTone.Danger) {
            when (support) {
                is HandoffSupport.Supported -> {
                    Line(stringResource(R.string.handoff31_support_yes, emulatorName(support)))
                    support.folderLabel?.let { Line(stringResource(R.string.handoff31_support_yes_folder, it)) }
                }
                is HandoffSupport.Unsupported -> Line(blockText(support))
                null -> Line(stringResource(R.string.handoff31_failed))
            }
        }

        // 3. The transfer itself, only where it works.
        if (support is HandoffSupport.Supported && (!receiving || ui.owned)) {
            val preview = ui.preview
            Block(stringResource(if (receiving) R.string.handoff31_receive_title else R.string.handoff31_send_title)) {
                Line(stringResource(if (receiving) R.string.handoff31_receive_hint else R.string.handoff31_send_hint))
                if (ui.busy) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                if (ui.failed) Line(stringResource(if (ui.tooLarge) R.string.handoff_too_large else R.string.handoff31_failed), MaterialTheme.colorScheme.error)
                preview?.let { data ->
                    if (data.files.isEmpty()) Line(stringResource(R.string.handoff31_no_saves))
                    data.files.forEach { row ->
                        val direction = when (row.direction) {
                            SaveHandoffDirection.UPLOAD -> R.string.handoff_upload
                            SaveHandoffDirection.DOWNLOAD -> R.string.handoff_download
                            SaveHandoffDirection.IDENTICAL -> R.string.handoff_identical
                            SaveHandoffDirection.CONFLICT -> R.string.handoff_conflict
                            SaveHandoffDirection.BLOCKED -> R.string.handoff_ambiguous
                        }
                        Text(row.path, style = MaterialTheme.typography.bodyMedium)
                        Text("${stringResource(direction)} · ${formatBytes(row.local?.size ?: row.remote?.size ?: 0L)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (receiving) ui.serverCode?.let { server ->
                        Line(stringResource(R.string.handoff31_server_code, server))
                        if (code != null) {
                            if (HandoffGuide.sameCode(code, server)) Line(stringResource(R.string.handoff31_code_match), MaterialTheme.colorScheme.tertiary)
                            else Line(stringResource(R.string.handoff31_code_mismatch, code), MaterialTheme.colorScheme.error)
                        }
                    }
                    when {
                        data.ready -> Line(stringResource(if (receiving) R.string.handoff31_received else R.string.handoff31_sent), MaterialTheme.colorScheme.tertiary)
                        data.canTransfer -> ActionPill(stringResource(if (receiving) R.string.handoff31_bring else R.string.handoff31_send), { confirm = data }, icon = R.drawable.ic_sync, tone = ActionTone.Accent, enabled = !ui.busy)
                        data.files.isNotEmpty() -> Line(stringResource(R.string.handoff_resolve_first), MaterialTheme.colorScheme.error)
                    }
                }
                ActionPill(stringResource(R.string.handoff31_check), viewModel::check, icon = R.drawable.ic_search, enabled = !ui.busy)
            }
            // 4. What the other handheld opens.
            if (!receiving && preview?.ready == true) ui.serverCode?.let { sent -> CodePanel(consoleId, fileName, ui.title, sent) }
        }

        // 5. The other handheld may not have the game yet.
        if (!receiving) {
            Block(stringResource(R.string.handoff31_needs_title)) {
                Line(stringResource(R.string.handoff31_needs_file, fileName, console))
                Line(ui.source?.let { stringResource(R.string.handoff31_needs_source, it) } ?: stringResource(R.string.handoff31_needs_any_source))
                Line(stringResource(R.string.handoff31_needs_package))
                ActionPill(stringResource(R.string.package_transfer_title), { onNavigate(NavRoutes.PackageTransfer.route) }, icon = R.drawable.ic_stacks)
            }
        }
    }

    confirm?.let { approved ->
        AlertDialog(
            modifier = Modifier.closeOnGamepadB { confirm = null }, onDismissRequest = { confirm = null },
            title = { Text(stringResource(if (receiving) R.string.handoff31_bring else R.string.handoff31_send)) },
            text = { Text(stringResource(R.string.handoff31_confirm, approved.files.size)) },
            confirmButton = { DialogButton(stringResource(if (receiving) R.string.handoff31_bring else R.string.handoff31_send), { confirm = null; viewModel.transfer(approved) }) },
            dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), { confirm = null }, initialFocus = rememberInitialFocus()) }
        )
    }
}

/** How to come back, shown before anything moves. */
@Composable
private fun WayBack(support: HandoffSupport?) {
    Block(stringResource(R.string.handoff31_back_title), tone = PanelTone.Accent) {
        Line(stringResource(R.string.handoff31_back_same_way))
        Line(stringResource(R.string.handoff31_back_newest))
        Line(stringResource(R.string.handoff31_back_nothing_deleted))
        if (support is HandoffSupport.Supported) {
            Line(stringResource(R.string.handoff31_back_same_emulator, emulatorName(support)))
            when (support.layout) {
                SaveFolderLayout.MEMCARDS -> Line(stringResource(R.string.handoff31_back_memcards))
                SaveFolderLayout.SAVEDATA -> Line(stringResource(R.string.handoff31_back_savedata))
                // Folders of only shared cards are never offered for a hand-off (HandoffBlock.SHARED_CARDS_ONLY).
                SaveFolderLayout.FILES, SaveFolderLayout.PS2_MEMCARDS, SaveFolderLayout.GC_MEMCARDS -> Unit
            }
        }
    }
}

/** The code, the QR code of the link and the link itself, with Share. */
@Composable
private fun CodePanel(consoleId: String, fileName: String, title: String, code: String) {
    val context = LocalContext.current
    val link = remember(consoleId, fileName, code) { HandoffLink.build(HandoffLink.Request(consoleId, fileName, code)) }
    val density = LocalDensity.current
    val maxPx = with(density) { 220.dp.roundToPx() }
    val bitmap = remember(link, maxPx) { runCatching { QrCodes.bitmap(link, maxPx).asImageBitmap() }.getOrNull() }
    val subject = stringResource(R.string.handoff31_share_subject, title)
    Block(stringResource(R.string.handoff31_code_title), tone = PanelTone.Accent) {
        Text(stringResource(R.string.handoff31_code, code), style = MaterialTheme.typography.headlineSmall)
        Line(stringResource(R.string.handoff31_code_hint))
        bitmap?.let { qr ->
            Box(Modifier.background(Color.White)) {
                Image(qr, contentDescription = null, filterQuality = FilterQuality.None, modifier = Modifier.size(with(density) { qr.width.toDp() }))
            }
        }
        Text(link, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ActionPill(stringResource(R.string.handoff31_share), {
            val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, subject); putExtra(Intent.EXTRA_TEXT, "$subject\n$link") }
            runCatching { context.startActivity(Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }, icon = R.drawable.ic_share)
    }
}

@Composable
private fun emulatorName(support: HandoffSupport.Supported): String =
    support.coreLabel?.let { "${support.emulatorLabel} ($it)" } ?: support.emulatorLabel

@Composable
private fun blockText(support: HandoffSupport.Unsupported): String {
    val app = support.emulatorLabel.orEmpty()
    return when (support.block) {
        HandoffBlock.NO_ROMM -> stringResource(R.string.handoff31_block_no_romm)
        HandoffBlock.NOT_ON_ROMM -> stringResource(R.string.handoff31_block_not_on_romm)
        HandoffBlock.NO_EMULATOR -> stringResource(R.string.handoff31_block_no_emulator)
        HandoffBlock.UNKNOWN_APP -> stringResource(R.string.handoff31_block_unknown_app, app)
        HandoffBlock.SAVES_NOT_SUPPORTED -> stringResource(R.string.handoff31_block_not_supported, app)
        HandoffBlock.NO_RETROARCH_FOLDER -> stringResource(R.string.handoff31_block_no_retroarch_folder, app)
        HandoffBlock.NO_EMULATOR_FOLDER -> stringResource(R.string.handoff31_block_no_emulator_folder, app, support.folderHint.orEmpty())
        HandoffBlock.SHARED_CARDS_ONLY -> stringResource(R.string.handoff32_block_shared_cards, app, support.folderHint.orEmpty())
    }
}

@Composable
private fun Block(title: String, tone: PanelTone = PanelTone.Normal, content: @Composable () -> Unit) {
    Panel(Modifier.fillMaxWidth(), tone = tone, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun Line(text: String, color: Color = Color.Unspecified) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color)
}
