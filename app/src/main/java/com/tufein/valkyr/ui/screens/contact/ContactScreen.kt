package com.tufein.valkyr.ui.screens.contact

import com.tufein.valkyr.ui.theme.accentInk
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.tufein.valkyr.BuildConfig
import com.tufein.valkyr.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.TourDialog
import com.tufein.valkyr.ui.components.IconTile
import com.tufein.valkyr.ui.components.Panel
import com.tufein.valkyr.ui.components.PanelTone
import com.tufein.valkyr.ui.components.Pill
import com.tufein.valkyr.ui.components.PillTone
import com.tufein.valkyr.ui.components.ScreenTitle
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.rememberInitialFocus
import androidx.compose.ui.focus.focusRequester
import com.tufein.valkyr.ui.components.rememberFocusSource

/** A row that opens a web page: a name and, at the end, its address or licence. */
private data class CreditLink(val iconRes: Int, val label: String, val url: String, val display: String)

/** The project on GitHub. The repository keeps its old address until it is renamed there. */
private const val PROJECT_URL = "https://github.com/Tufein/Valkyr"

/** The open-source libraries and fonts the app is built with (see gradle/libs.versions.toml and res/font). */
private val libraryLinks = listOf(
    CreditLink(R.drawable.ic_build, "Jetpack Compose · AndroidX", "https://developer.android.com/jetpack/androidx", "Apache-2.0"),
    CreditLink(R.drawable.ic_build, "Kotlin · kotlinx.coroutines", "https://github.com/Kotlin/kotlinx.coroutines", "Apache-2.0"),
    CreditLink(R.drawable.ic_build, "Dagger Hilt", "https://github.com/google/dagger", "Apache-2.0"),
    CreditLink(R.drawable.ic_build, "Gson", "https://github.com/google/gson", "Apache-2.0"),
    CreditLink(R.drawable.ic_build, "Coil", "https://github.com/coil-kt/coil", "Apache-2.0"),
    CreditLink(R.drawable.ic_build, "jsoup", "https://jsoup.org", "MIT"),
    CreditLink(R.drawable.ic_build, "ZXing", "https://github.com/zxing/zxing", "Apache-2.0"),
    CreditLink(R.drawable.ic_build, "7-Zip-JBinding", "https://github.com/borisbrodski/sevenzipjbinding", "LGPL-2.1"),
    CreditLink(R.drawable.ic_build, "libtorrent4j", "https://github.com/aldenml/libtorrent4j", "MIT"),
    CreditLink(R.drawable.ic_text_size, "Space Mono", "https://fonts.google.com/specimen/Space+Mono", "OFL-1.1"),
    CreditLink(R.drawable.ic_text_size, "Manrope", "https://fonts.google.com/specimen/Manrope", "OFL-1.1"),
)

/** Where game information, artwork, checks and achievements come from, and the services the app talks to. */
private val dataLinks = listOf(
    CreditLink(R.drawable.ic_trophy, "RetroAchievements", "https://retroachievements.org", "retroachievements.org"),
    CreditLink(R.drawable.ic_globe, "RAWG", "https://rawg.io", "rawg.io"),
    CreditLink(R.drawable.ic_globe, "TheGamesDB", "https://thegamesdb.net", "thegamesdb.net"),
    CreditLink(R.drawable.ic_image, "libretro-thumbnails", "https://github.com/libretro-thumbnails/libretro-thumbnails", "github.com/libretro-thumbnails"),
    CreditLink(R.drawable.ic_check_circle, "No-Intro", "https://no-intro.org", "no-intro.org"),
    CreditLink(R.drawable.ic_check_circle, "Redump", "http://redump.org", "redump.org"),
    CreditLink(R.drawable.ic_check_circle, "TOSEC", "https://www.tosecdev.org", "tosecdev.org"),
    CreditLink(R.drawable.ic_cloud, "RomM", "https://github.com/rommapp/romm", "github.com/rommapp/romm"),
    CreditLink(R.drawable.ic_frontends, "ES-DE", "https://es-de.org", "es-de.org"),
    CreditLink(R.drawable.ic_gamepad, "Libretro", "https://www.libretro.com", "libretro.com"),
    CreditLink(R.drawable.ic_gamepad, "ScummVM", "https://www.scummvm.org", "scummvm.org"),
    CreditLink(R.drawable.ic_gamepad, "Homebrew Hub", "https://hh.gbdev.io", "hh.gbdev.io"),
)

/**
 * About Valkyr (4.0): the app with its version and links to the project and its issues, then the
 * third-party projects it uses, each opening its own page.
 */
@Composable
fun ContactScreen(navController: NavController) {
    val isLandscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp

    // 3.9.0: the cursor starts on the first link (the screen had no focus of its own, so the
    // first D-pad press was spent finding it).
    val firstFocus = rememberInitialFocus()
    // No background of its own: the app's glow shows through, the cards sit on it.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(firstFocus)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenTitle(
            text = stringResource(R.string.brand40_about_title),
            subtitle = stringResource(R.string.brand40_about_subtitle),
            icon = R.drawable.ic_info,
            modifier = Modifier.widthIn(max = 860.dp)
        )
        AppCard(Modifier.fillMaxWidth().widthIn(max = 860.dp))
        if (isLandscape) {
            Row(
                modifier = Modifier.widthIn(max = 860.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                LibrariesCard(Modifier.weight(1f))
                DataCard(Modifier.weight(1f))
            }
        } else {
            LibrariesCard(Modifier.fillMaxWidth().widthIn(max = 860.dp))
            DataCard(Modifier.fillMaxWidth().widthIn(max = 860.dp))
        }
    }
}

/** The hero card: the mark, the name and version, what the app does, and the project links. */
@Composable
private fun AppCard(modifier: Modifier = Modifier) {
    Panel(modifier = modifier, tone = PanelTone.Accent, contentPadding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Image(
                painterResource(R.drawable.brand_mark),
                contentDescription = null,
                modifier = Modifier.size(72.dp)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Pill("v" + BuildConfig.VERSION_NAME, tone = PillTone.Strong)
                }
                Text(
                    stringResource(R.string.brand40_about_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.brand40_about_made_by),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        LinkRow(CreditLink(R.drawable.ic_github, stringResource(R.string.brand40_link_project), PROJECT_URL, PROJECT_URL.removePrefix("https://")))
        LinkRow(CreditLink(R.drawable.ic_bug, stringResource(R.string.brand40_link_issues), "$PROJECT_URL/issues", "GitHub Issues"))
        // 6.0: the first-run tour, again on request.
        var tourOpen by rememberSaveable { mutableStateOf(false) }
        ActionPill(
            label = stringResource(R.string.tour60_about_action),
            onClick = { tourOpen = true },
            icon = R.drawable.ic_info,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (tourOpen) TourDialog { tourOpen = false }
    }
}

@Composable
private fun LibrariesCard(modifier: Modifier = Modifier) = CreditCard(
    title = stringResource(R.string.brand40_libraries_title),
    note = stringResource(R.string.brand40_libraries_note),
    icon = R.drawable.ic_build,
    links = libraryLinks,
    modifier = modifier
)

@Composable
private fun DataCard(modifier: Modifier = Modifier) = CreditCard(
    title = stringResource(R.string.brand40_data_title),
    note = stringResource(R.string.brand40_data_note),
    icon = R.drawable.ic_globe,
    links = dataLinks,
    modifier = modifier
)

@Composable
private fun CreditCard(
    title: String,
    note: String,
    icon: Int,
    links: List<CreditLink>,
    modifier: Modifier = Modifier
) {
    Panel(modifier = modifier, contentPadding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(icon, size = 38.dp)
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = accentInk(),
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            text = note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp)
        )
        Spacer(Modifier.height(10.dp))
        links.forEach { LinkRow(it) }
    }
}

/** A link with its mark; opens in the browser. Both texts wrap rather than being cut off. */
@Composable
private fun LinkRow(link: CreditLink) {
    val uriHandler = LocalUriHandler.current
    val source = rememberFocusSource()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .focusRing(source, cornerRadius = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(interactionSource = source, indication = null) { uriHandler.openUri(link.url) }
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Icon(
            painter = painterResource(link.iconRes),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = link.label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f, fill = false)
        )
        Text(
            text = link.display,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
        Icon(
            painter = painterResource(R.drawable.ic_open_in_new),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
