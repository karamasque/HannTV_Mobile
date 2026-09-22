package tv.own.owntv.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.epg.displayLogoUrl

/**
 * A channel's logo, with fallback support when an image fails to load.
 *
 * Checks primary URL (e.g. EPG logo) and secondary URL (e.g. Playlist logo).
 * Host-wide blacklisting is disabled because a single 404/broken logo on an IPTV server
 * must not disable valid logos for all other channels on that server.
 */
@Composable
fun ChannelLogoImage(
    url: String?,
    fallback: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    secondaryUrl: String? = null,
) {
    val candidateUrls = remember(url, secondaryUrl) {
        val list = mutableListOf<String>()
        if (!url.isNullOrBlank() && url !in deadLogos) list.add(url)
        if (!secondaryUrl.isNullOrBlank() && secondaryUrl != url && secondaryUrl !in deadLogos) list.add(secondaryUrl)
        list
    }

    var currentIndex by remember(url, secondaryUrl) { mutableIntStateOf(0) }
    val activeUrl = candidateUrls.getOrNull(currentIndex)

    if (activeUrl == null) {
        fallback()
    } else {
        AsyncImage(
            model = activeUrl,
            contentDescription = null,
            contentScale = contentScale,
            modifier = modifier,
            onState = { state ->
                if (state is AsyncImagePainter.State.Error) {
                    deadLogos += activeUrl
                    currentIndex++
                }
            },
        )
    }
}

/**
 * Helper overload for [ChannelEntity] that tries EPG display logo first,
 * then falls back to playlist logo before drawing the placeholder.
 */
@Composable
fun ChannelLogoImage(
    channel: ChannelEntity,
    fallback: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    ChannelLogoImage(
        url = channel.displayLogoUrl,
        secondaryUrl = channel.logoUrl,
        fallback = fallback,
        modifier = modifier,
        contentScale = contentScale,
    )
}

/**
 * Logo URLs that have failed once this run.
 */
private val deadLogos = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
