package com.tufein.valkyr.ui.screens.home.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tufein.valkyr.R
import com.tufein.valkyr.data.model.DownloadableFileWithTags
import com.tufein.valkyr.ui.components.GameCover
import com.tufein.valkyr.ui.components.SectionTitle
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.rememberFocusSource
import com.tufein.valkyr.ui.components.stripExtension
import com.tufein.valkyr.util.ConsoleFormatter
import com.tufein.valkyr.util.SimilarGames

/**
 * "More like this" in the details card: a row of cover cards (D-pad left / right, A opens that game's
 * details). Hidden when [items] is empty. Ranking is local (see [com.tufein.valkyr.util.SimilarGames]).
 */
@Composable
fun SimilarSection(
    items: List<DownloadableFileWithTags>,
    onOpen: (DownloadableFileWithTags) -> Unit,
    modifier: Modifier = Modifier,
    why: Map<Long, SimilarGames.Why> = emptyMap()
) {
    if (items.isEmpty()) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.disc6_similar_title), icon = R.drawable.ic_sparkle)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items.forEach { SimilarCard(it, why[it.file.id]) { onOpen(it) } }
        }
    }
}

@Composable
private fun SimilarCard(item: DownloadableFileWithTags, why: SimilarGames.Why?, onClick: () -> Unit) {
    val source = rememberFocusSource()
    val file = item.file
    val title = stripExtension(file.name)
    val shape = RoundedCornerShape(10.dp)
    val description = stringResource(R.string.disc6_similar_open, title)
    Column(
        modifier = Modifier
            .width(96.dp)
            .focusRing(source, 10.dp)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        GameCover(
            consoleId = file.consoleId,
            fileName = file.fileName,
            title = file.name,
            modifier = Modifier.width(88.dp).height(116.dp),
            shape = shape
        )
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.defaultMinSize(minHeight = 32.dp)
        )
        Text(
            ConsoleFormatter.getConsoleShortName(file.consoleId),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        why?.let {
            Text(
                stringResource(
                    when (it) {
                        SimilarGames.Why.SAME_SERIES -> R.string.disc79_why_series
                        SimilarGames.Why.SAME_DEVELOPER -> R.string.disc79_why_developer
                        SimilarGames.Why.SHARED_GENRE -> R.string.disc79_why_genre
                        SimilarGames.Why.SAME_CONSOLE -> R.string.disc79_why_console
                    }
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
