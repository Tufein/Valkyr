package com.tufein.valkyr.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.rememberFocusSource

/** Keeps the search controls together, leaving only one compact row while browsing games. */
@Composable
internal fun LibrarySearchPanel(
    expanded: Boolean,
    query: String,
    filterCount: Int,
    onExpandedChange: (Boolean) -> Unit,
    toggleFocus: FocusRequester,
    modifier: Modifier = Modifier,
    search: @Composable (toggle: @Composable () -> Unit) -> Unit,
    controls: @Composable () -> Unit
) {
    Column(modifier) {
        if (expanded) {
            search {
                SearchPanelToggle(true, query, filterCount, toggleFocus) { onExpandedChange(false) }
            }
            controls()
        } else {
            SearchPanelToggle(false, query, filterCount, toggleFocus, Modifier.fillMaxWidth()) {
                onExpandedChange(true)
            }
        }
    }
}

@Composable
private fun SearchPanelToggle(
    expanded: Boolean,
    query: String,
    filterCount: Int,
    focus: FocusRequester,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val source = rememberFocusSource()
    val description = stringResource(if (expanded) R.string.library_search_hide else R.string.library_search_show)
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .background(scheme.surfaceContainer, RoundedCornerShape(12.dp))
            .focusRequester(focus)
            .focusRing(source, 12.dp)
            .clickable(interactionSource = source, indication = null, role = Role.Button,
                onClickLabel = description, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!expanded) {
            Icon(painterResource(R.drawable.ic_search), null, Modifier.size(18.dp), tint = scheme.primary)
            Text(
                query.ifBlank { stringResource(R.string.library_search_controls) },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (filterCount > 0) {
                Text("${stringResource(R.string.filters)} · $filterCount", style = MaterialTheme.typography.labelMedium)
            }
        }
        Icon(
            painterResource(R.drawable.ic_keyboard_arrow_down),
            contentDescription = description,
            modifier = Modifier.size(24.dp).rotate(if (expanded) 180f else 0f)
        )
    }
}
