package com.tufein.valkyr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.tufein.valkyr.data.model.SortOption
import com.tufein.valkyr.ui.screens.home.LibrarySearchPanel
import com.tufein.valkyr.ui.screens.home.QuickFilterRow
import com.tufein.valkyr.ui.screens.home.components.SearchField
import com.tufein.valkyr.util.LibraryBrowse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LibrarySearchPanelTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @OptIn(ExperimentalTestApi::class)
    @Test fun foldingWrappedControlsGivesGamesMoreRoomAndControllerCanReopenWithoutClearingFilters() {
        compose.setContent {
            var expanded by rememberSaveable { mutableStateOf(true) }
            var query by rememberSaveable { mutableStateOf("Naomi") }
            var favourites by rememberSaveable { mutableStateOf(true) }
            val toggleFocus = remember { FocusRequester() }
            MaterialTheme {
                Column(Modifier.width(420.dp).height(360.dp)) {
                    LibrarySearchPanel(
                        expanded, query, 2, { expanded = it }, toggleFocus,
                        search = { toggle ->
                            SearchField(query, { query = it }, false, {}, {}, trailing = toggle, height = 52.dp)
                        },
                        controls = {
                            QuickFilterRow(
                                LibraryBrowse.ViewMode.entries, LibraryBrowse.ViewMode.COVERS, {},
                                LibraryBrowse.Ownership.DOWNLOADED, {}, favourites, { favourites = it },
                                SortOption.NAME_ASC, {}, null
                            )
                        }
                    )
                    Box(Modifier.weight(1f).fillMaxSize().testTag("games")) { Text("Game results") }
                }
            }
        }
        val heightBefore = compose.onNodeWithTag("games").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithContentDescription(context.getString(R.string.library_search_hide)).performClick()
        compose.onNodeWithText(context.getString(R.string.lib52_chip_downloaded)).assertDoesNotExist()
        compose.onNodeWithText("Naomi").assertIsDisplayed()
        val heightAfter = compose.onNodeWithTag("games").fetchSemanticsNode().boundsInRoot.height
        assertTrue("Collapsing must free the wrapped controls' height for the games", heightAfter > heightBefore + 80)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithContentDescription(context.getString(R.string.library_search_show))
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithText(context.getString(R.string.lib52_chip_downloaded)).assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.lib52_chip_favourites)).assertIsSelected()
        compose.onNodeWithText("Naomi").assertIsDisplayed()
    }
}
