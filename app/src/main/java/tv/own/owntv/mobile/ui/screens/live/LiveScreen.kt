package tv.own.owntv.mobile.ui.screens.live

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import tv.own.owntv.mobile.ui.theme.glassClickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import java.text.DateFormat
import tv.own.owntv.core.live.ChannelNowPlaying
import tv.own.owntv.mobile.ui.components.MobileIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.core.live.LiveKey
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.CategoryPickerSheet
import tv.own.owntv.mobile.ui.components.ChannelLogoImage
import tv.own.owntv.mobile.ui.components.BrowseCategorySheet
import tv.own.owntv.mobile.ui.components.FilterChipRow
import tv.own.owntv.mobile.ui.components.MobileListRow
import tv.own.owntv.mobile.ui.components.TwoPane
import tv.own.owntv.mobile.ui.components.mobileGroupPlate
import tv.own.owntv.mobile.ui.nav.isExpandedWidth
import tv.own.owntv.mobile.ui.screens.ObeyScrollToTop
import tv.own.owntv.mobile.ui.shell.LocalStreamOnScreen
import tv.own.owntv.mobile.ui.theme.MobileDimens

/**
 * Live TV: a strip of categories, a list of channels, and a long-press menu on each one.
 *
 * The TV app shows three panels at once — rail, list, preview — because it has the width for it and
 * a remote that moves between them. A phone has neither, so the same three things become one
 * scrolling list, a chip strip above it, and a screen you open by tapping a channel.
 *
 * A tablet has the width, so at expanded size the third panel comes back: the list on the left and
 * the channel playing beside it. It starts empty rather than tuning the first channel by itself —
 * opening Live TV is not a request to watch something.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(
    scrollToTop: SharedFlow<String>,
    onOpenChannel: (channelId: Long, openCatchup: Boolean) -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Pins the list to one folder and takes the chip strip away — how the Favourites and History
     * screens show channels without a second copy of this list existing.
     */
    lockedKey: LiveKey? = null,
    vm: LiveViewModel = koinViewModel(),
) {
    // Locking and unlocking are one pair: the pin belongs to this screen's lifetime, not to the view
    // model's. On the television that view model is a single instance shared with the browse section,
    // so a pin left behind froze its category rail. `DisposableEffect` (not `LaunchedEffect`) also
    // means the pin is in place before the first frame, so the list never flashes the wrong folder.
    // Channels kept with "Add to Multiview" are spent by playing one. While any are waiting, a tap
    // plays rather than browses — see the tap handler below.
    val tuner: LiveTuner = koinInject()
    val multiviewSelection by tuner.multiviewSelection.collectAsStateWithLifecycle()
    val multiviewPending = multiviewSelection.isNotEmpty()

    if (lockedKey != null) {
        val pinned = lockedKey
        DisposableEffect(pinned) {
            vm.lock(pinned)
            onDispose { vm.unlock() }
        }
    }

    val categories by vm.categories.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val channels = vm.channels.collectAsLazyPagingItems()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val favorites by vm.favoriteIds.collectAsStateWithLifecycle()
    val providers by vm.providerNames.collectAsStateWithLifecycle()
    val showNumbers by vm.showChannelNumbers.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    listState.ObeyScrollToTop(route = "live", scrollToTop = scrollToTop)

    var menuFor by remember { mutableStateOf<ChannelEntity?>(null) }
    var categoryPicker by remember { mutableStateOf(false) }
    var categoryMenuFor by remember { mutableStateOf<LiveCategory?>(null) }
    // Survives a rotation, so turning a tablet keeps the channel you were watching beside the list.
    var previewing by rememberSaveable { mutableStateOf<Long?>(null) }
    val twoPane = isExpandedWidth()

    // While the pane is showing the channel, the picture is already on screen, so the shell must not
    // also put a floating window over it. Cleared on the way out, or leaving Live TV with a channel
    // open would take the mini player away on every other screen too.
    val streamOnScreen = LocalStreamOnScreen.current
    val paneHasChannel = twoPane && previewing != null
    DisposableEffect(paneHasChannel) {
        streamOnScreen.value = paneHasChannel
        onDispose { streamOnScreen.value = false }
    }

    // The guide is read for what is actually on screen. Watching the visible range rather than each
    // row means one batched query per scroll settle instead of one per row appearing.
    LaunchedEffect(listState, channels.itemCount, channels.itemSnapshotList) {
        snapshotFlow {
            val visibleIndices = listState.layoutInfo.visibleItemsInfo.map { it.index }
            visibleIndices.mapNotNull { index ->
                if (index in 0 until channels.itemCount) channels.peek(index) else null
            }
        }
            .distinctUntilChanged()
            .collect { visibleChannels ->
                if (visibleChannels.isNotEmpty()) {
                    vm.loadNowPlaying(visibleChannels)
                }
            }
    }

    // Changing category scrolls back to the top: the position of the old list means nothing in the new one.
    LaunchedEffect(selected) { listState.scrollToItem(0) }

    val categoryHeader = @Composable {
        if (lockedKey == null) Box(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            FilterChipRow(
                labels = categories.map { it.label() },
                selectedIndex = categories.indexOfFirst { it.key == selected },
                onSelect = { index -> categories.getOrNull(index)?.let { vm.select(it.key) } },
                modifier = Modifier.padding(end = MobileDimens.TouchTarget + 8.dp),
                onLongPress = { index ->
                    categoryMenuFor = categories.getOrNull(index)?.takeIf { it.builtIn == null }
                },
                onLongPressLabel = stringResource(R.string.settings_customize_categories),
            )
            IconButton(
                onClick = { categoryPicker = true },
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Icon(MobileIcons.Search, stringResource(R.string.content_search_categories))
            }
        }
    }

    if (categoryPicker) {
        CategoryPickerSheet(
            labels = categories.map { it.label() },
            selectedIndex = categories.indexOfFirst { it.key == selected },
            onSelect = { index -> categories.getOrNull(index)?.let { vm.select(it.key) } },
            onDismiss = { categoryPicker = false },
        )
    }

    val channelListContent = @Composable { usePlate: Boolean ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (channels.itemCount == 0) {
                EmptyChannels()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = if (usePlate) Modifier.fillMaxSize().mobileGroupPlate() else Modifier.fillMaxSize(),
                ) {
                    items(count = channels.itemCount, key = channels.itemKey { it.id }) { index ->
                        val channel = channels[index]
                        if (channel != null) {
                            ChannelRow(
                                channel = channel,
                                number = channel.number?.takeIf { showNumbers && it > 0 },
                                nowPlaying = nowPlaying[channel.id],
                                providerName = providers[channel.sourceId],
                                isFavorite = channel.id in favorites,
                                onClick = {
                                    when {
                                        multiviewPending -> {
                                            tuner.selectWithoutPlaying(channel)
                                            onOpenPlayer()
                                        }
                                        twoPane -> previewing = channel.id
                                        else -> onOpenChannel(channel.id, false)
                                    }
                                },
                                onLongClick = { menuFor = channel },
                            )
                            if (index < channels.itemCount - 1) {
                                HorizontalDivider(
                                    color = Color.White.copy(alpha = 0.08f),
                                    thickness = 1.dp,
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val listPane = @Composable {
        Column(Modifier.fillMaxSize()) {
            categoryHeader()
            Box(Modifier.weight(1f)) {
                channelListContent(true)
            }
        }
    }

    if (twoPane) {
        Column(modifier = modifier.fillMaxSize()) {
            categoryHeader()
            Spacer(Modifier.height(4.dp))
            TwoPane(
                list = { channelListContent(false) },
                detail = {
                    val channelId = previewing
                    if (channelId == null) {
                        SelectAChannel()
                    } else {
                        ChannelDetailScreen(
                            channelId = channelId,
                            openCatchup = false,
                            onFullscreen = onOpenPlayer,
                            onBack = { previewing = null },
                        )
                    }
                },
                modifier = Modifier.weight(1f),
                listShare = 0.28f,
            )
        }
    } else {
        Box(modifier) { listPane() }
    }

    menuFor?.let { channel ->
        ChannelMenu(
            channel = channel,
            selected = selected,
            isFavorite = channel.id in favorites,
            originName = categories.firstOrNull { it.key == selected }?.label().orEmpty(),
            vm = vm,
            onOpenCatchup = { onOpenChannel(channel.id, true) },
            onDismiss = { menuFor = null },
        )
    }

    categoryMenuFor?.let { category ->
        BrowseCategorySheet(
            title = category.label(),
            onHide = { vm.hideCategory(category.key) },
            onMove = { kind -> vm.moveCategory(category.key, kind) },
            onDismiss = { categoryMenuFor = null },
        )
    }
}

/** The chip's text: a translated label for the four built-in lists, the stored name otherwise. */
@Composable
private fun LiveCategory.label(): String = when (builtIn) {
    LiveCategory.BuiltIn.ALL -> stringResource(R.string.content_category_all_channels)
    LiveCategory.BuiltIn.FAVORITES -> stringResource(R.string.content_category_favorites)
    LiveCategory.BuiltIn.HISTORY -> stringResource(R.string.content_category_history)
    LiveCategory.BuiltIn.CATCHUP -> stringResource(R.string.content_catchup)
    null -> title.orEmpty()
}

/** The preview pane before a channel is picked — the television's own sentence for the same panel. */
@Composable
private fun SelectAChannel() {
    Box(Modifier.fillMaxSize().padding(MobileDimens.GapLarge), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.content_preview_select_channel),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyChannels() {
    Box(Modifier.fillMaxSize().padding(MobileDimens.GapLarge), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.content_no_channels_here),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ChannelRow(
    channel: ChannelEntity,
    number: Int?,
    nowPlaying: ChannelNowPlaying?,
    providerName: String?,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val times = rememberTimeFormat()
    val epgSubtitle = nowPlaying?.formatDisplayText(times)?.takeIf { it.isNotBlank() }
    val subtitle = epgSubtitle ?: providerName
    val progress = nowPlaying?.progressFraction ?: 0f
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .glassClickable(
                interactionSource = interactionSource,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .padding(end = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            ChannelLogoImage(
                channel = channel,
                modifier = Modifier.fillMaxSize(),
                fallback = {
                    Icon(
                        imageVector = MobileIcons.LiveTv,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                },
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (number != null) {
                    Text(
                        text = number.toString(),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.14).sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }

            if (progress > 0f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .padding(top = 2.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = Color(0xFF3B82F6),
                    trackColor = Color(0xFF2C2C2E),
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp),
        ) {
            if (channel.catchup) {
                Icon(
                    imageVector = MobileIcons.History,
                    contentDescription = stringResource(R.string.content_catchup),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (isFavorite) {
                Icon(
                    imageVector = MobileIcons.Star,
                    contentDescription = stringResource(R.string.content_category_favorites),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun rememberTimeFormat(): DateFormat {
    val locales = LocalConfiguration.current.locales
    return remember(locales) { DateFormat.getTimeInstance(DateFormat.SHORT) }
}



