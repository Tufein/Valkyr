package com.tufein.valkyr.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tufein.valkyr.R
import com.tufein.valkyr.util.AppTour

/**
 * 6.0: the first-run tour, one card at a time (see [AppTour]). B / Back closes it like Skip;
 * [onDone] runs for both Skip and the last card.
 */
@Composable
fun TourDialog(onDone: () -> Unit) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val card = AppTour.cards[index.coerceIn(0, AppTour.cards.lastIndex)]
    val next = AppTour.next(index)
    val previous = AppTour.previous(index)
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDone),
        onDismissRequest = onDone,
        icon = { Icon(painterResource(card.icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp)) },
        title = { Text(stringResource(card.title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(card.body), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.tour60_progress, index + 1, AppTour.cards.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            DialogButton(
                text = stringResource(if (next != null) R.string.tour60_next else R.string.tour60_done),
                onClick = { if (next != null) index = next else onDone() },
                initialFocus = rememberInitialFocus()
            )
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (previous != null) DialogButton(stringResource(R.string.tour60_back), onClick = { index = previous })
                if (next != null) DialogButton(stringResource(R.string.tour60_skip), onClick = onDone)
            }
        }
    )
}
