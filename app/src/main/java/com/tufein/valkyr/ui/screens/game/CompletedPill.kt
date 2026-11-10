package com.tufein.valkyr.ui.screens.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.R
import com.tufein.valkyr.data.service.CompletedService
import com.tufein.valkyr.data.service.RetroAchievementsService
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.util.CompletedGames
import com.tufein.valkyr.util.GameTitleCleaner
import com.tufein.valkyr.util.RaGame
import com.tufein.valkyr.util.RaStatus
import com.tufein.valkyr.util.RaStatuses
import com.tufein.valkyr.util.ToastUtil
import com.tufein.valkyr.util.VersionPicker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.tufein.valkyr.util.RaAwards

/** Backs the completed pill, the header badges and the RA status badge of the game page and the details card. */
@HiltViewModel
class CompletedViewModel @Inject constructor(
    private val service: CompletedService,
    private val retroAchievements: RetroAchievementsService
) : ViewModel() {

    val keys: StateFlow<Set<String>> = service.keys
    val awards = retroAchievements.awards
    val raAccount = retroAchievements.accountReady

    /** Flips the flag; [onDone] gets the new state, or null when it could not be saved. */
    fun toggle(consoleId: String, fileName: String, onDone: (Boolean?) -> Unit) {
        viewModelScope.launch { onDone(service.toggle(consoleId, fileName)) }
    }

    /**
     * The status of RA game [gameId]: from the batch award list, else from a progress this session
     * already fetched for the game (no call is made here).
     */
    fun raStatus(gameId: Int, awards: RaAwards): RaStatus? =
        awards.statusOf(gameId) ?: retroAchievements.cachedProgress(gameId)?.let { RaStatuses.statusOf(it.award, it.earned) }
}

/**
 * "Mark as completed" / "Not completed any more" for the game page's More card and the details card.
 * Works on the active profile's flags, with a short confirmation.
 */
@Composable
fun CompletedPill(consoleId: String, fileName: String, modifier: Modifier = Modifier, viewModel: CompletedViewModel = hiltViewModel()) {
    val keys by viewModel.keys.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val on = CompletedGames.key(consoleId, fileName) in keys
    val title = androidx.compose.runtime.remember(fileName) { GameTitleCleaner.clean(VersionPicker.readable(fileName)).ifBlank { fileName } }
    val marked = stringResource(R.string.done55_marked, title)
    val unmarked = stringResource(R.string.done55_unmarked, title)
    val failed = stringResource(R.string.done55_save_failed)
    ActionPill(
        stringResource(if (on) R.string.done55_unmark else R.string.done55_mark),
        onClick = {
            viewModel.toggle(consoleId, fileName) { result ->
                when (result) {
                    true -> ToastUtil.showSuccess(context, marked)
                    false -> ToastUtil.showSuccess(context, unmarked)
                    null -> ToastUtil.showError(context, failed)
                }
            }
        },
        modifier = modifier,
        icon = R.drawable.ic_verified,
        tone = if (on) ActionTone.Accent else ActionTone.Neutral
    )
}

/** The "Completed" badge of the game page header; draws nothing for a game that is not marked. */
@Composable
fun CompletedBadge(consoleId: String, fileName: String, viewModel: CompletedViewModel = hiltViewModel()) {
    val keys by viewModel.keys.collectAsStateWithLifecycle()
    if (CompletedGames.key(consoleId, fileName) in keys) {
        Pill(stringResource(R.string.done55_completed), tone = PillTone.Success, icon = R.drawable.ic_verified)
    }
}

/**
 * The user's RetroAchievements status in the game as a badge (Beaten / Mastered / In progress). Nothing
 * without an RA account, for a game RA does not know, or one the user has not touched. [match] is the
 * page's RA match (by hash, or by title only).
 */
@Composable
fun RaStatusBadge(match: Pair<RaGame, Boolean>?, viewModel: CompletedViewModel = hiltViewModel()) {
    val account by viewModel.raAccount.collectAsStateWithLifecycle()
    val awards by viewModel.awards.collectAsStateWithLifecycle()
    val game = match?.first ?: return
    if (account != true) return
    when (viewModel.raStatus(game.id, awards)) {
        RaStatus.MASTERED -> Pill(stringResource(R.string.done55_ra_mastered), tone = PillTone.Warning, icon = R.drawable.ic_trophy)
        RaStatus.BEATEN -> Pill(stringResource(R.string.done55_ra_beaten), tone = PillTone.Info, icon = R.drawable.ic_trophy)
        RaStatus.IN_PROGRESS -> Pill(stringResource(R.string.done55_ra_in_progress), tone = PillTone.Neutral, icon = R.drawable.ic_trophy)
        null -> Unit
    }
}
