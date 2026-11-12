package com.tufein.valkyr.ui.screens.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.rememberFocusSource
import com.tufein.valkyr.ui.theme.LocalDogmatixTokens
import com.tufein.valkyr.util.GamePageModel

/**
 * 5.2: the game page's sticky bottom bar, in reach of the thumb whatever the page's length: the ONE
 * primary action large and to the right (Download / Play / Resume / Retry by state, see
 * [GamePageModel.primary]) with two icon buttons beside it, more and favourite. The page's
 * content scrolls above it. [primaryModifier] carries the focus requester that makes the primary
 * action the gamepad's first stop.
 */
@Composable
fun GameActionBar(
    primary: GamePageModel.Primary,
    favourite: Boolean,
    onPrimary: () -> Unit,
    onFavourite: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    primaryModifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    // The bar's own width decides: upright phones are 360 dp and wider, the landscape page gives it only its
    // left column (~260-300 dp on a handheld), where 56 dp buttons and the usual padding left the label ~20 dp.
    BoxWithConstraints(modifier = modifier.fillMaxWidth().background(scheme.background)) {
        val narrow = maxWidth < NarrowBarDp.dp
        val side = if (narrow) 10.dp else 16.dp
        val gap = if (narrow) 8.dp else 10.dp
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(color = scheme.outlineVariant, thickness = 1.dp)
            Row(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = side, vertical = if (narrow) 8.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(gap)
            ) {
                BarIconButton(
                    icon = R.drawable.ic_more_vert,
                    description = stringResource(R.string.gamepage38_more_actions),
                    onClick = onMore,
                    size = if (narrow) 48.dp else 56.dp
                )
                BarIconButton(
                    icon = if (favourite) R.drawable.ic_star_fill else R.drawable.ic_star,
                    description = stringResource(if (favourite) R.string.home_details_unfavourite else R.string.home_details_favourite),
                    onClick = onFavourite,
                    selected = favourite,
                    size = if (narrow) 48.dp else 56.dp
                )
                GamePrimaryAction(primary = primary, onClick = onPrimary, modifier = Modifier.weight(1f).then(primaryModifier), compact = narrow)
            }
        }
    }
}

/** A bar narrower than this (dp) uses the compact sizes. */
private const val NarrowBarDp = 340

/** A 56 dp (48 dp in a narrow bar) square icon button next to the primary action (as high as it, so the row reads as one). */
@Composable
private fun BarIconButton(icon: Int, description: String, onClick: () -> Unit, selected: Boolean = false, size: Dp = 56.dp) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = size, minHeight = size)
            .focusRing(source, cornerRadius = 18.dp)
            .clip(shape)
            .background(if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh)
            .border(1.dp, LocalDogmatixTokens.current.hairline, shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .semantics { role = Role.Button; contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
            modifier = Modifier.size(26.dp)
        )
    }
}
