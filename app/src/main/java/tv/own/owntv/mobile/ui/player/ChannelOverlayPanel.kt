package tv.own.owntv.mobile.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.ChannelLogoImage
import tv.own.owntv.mobile.ui.theme.LocalAccentOnVideo

/**
 * Side overlay panel (Left: Channel List / Categories, Right: Watch History) that slides over video.
 * Compact mobile design preventing text truncation.
 */
@Composable
fun ChannelOverlayPanel(
    title: String,
    channels: List<ChannelEntity>,
    currentChannelId: Long?,
    nowPlayingMap: Map<Long, String>,
    alignEnd: Boolean,
    onSelectChannel: (ChannelEntity) -> Unit,
    onDismiss: () -> Unit,
    categories: List<Pair<Long, String>> = emptyList(),
    selectedCategoryId: Long? = null,
    onSelectCategory: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var showingCategories by remember { mutableStateOf(false) }

    BackHandler(onBack = {
        if (showingCategories) {
            showingCategories = false
        } else {
            onDismiss()
        }
    })

    val listState = rememberLazyListState()
    val categoryListState = rememberLazyListState()

    val currentIndex = channels.indexOfFirst { it.id == currentChannelId }
    LaunchedEffect(currentChannelId, showingCategories) {
        if (!showingCategories && currentIndex >= 0) {
            runCatching { listState.scrollToItem(currentIndex) }
        }
    }

    val categoryIndex = categories.indexOfFirst { it.first == selectedCategoryId }
    LaunchedEffect(selectedCategoryId, showingCategories) {
        if (showingCategories && categoryIndex >= 0) {
            runCatching { categoryListState.scrollToItem(categoryIndex) }
        }
    }

    val density = LocalDensity.current
    val dragDx = remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onDismiss)
            .pointerInput(alignEnd, categories, showingCategories) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val threshold = with(density) { 40.dp.toPx() }
                        val dx = dragDx.floatValue
                        if (!alignEnd) {
                            if (dx > threshold && categories.isNotEmpty() && !showingCategories) {
                                showingCategories = true
                            } else if (dx < -threshold) {
                                if (showingCategories) {
                                    showingCategories = false
                                } else {
                                    onDismiss()
                                }
                            }
                        } else if (alignEnd && dx > threshold) {
                            onDismiss()
                        }
                        dragDx.floatValue = 0f
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        dragDx.floatValue += dragAmount
                    },
                )
            },
    ) {
        Box(
            modifier = Modifier
                .align(if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart)
                .fillMaxHeight()
                .width(300.dp)
                .padding(8.dp)
                .clickable(enabled = false) {} // Consume click inside panel
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF0F1523).copy(alpha = 0.94f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                .padding(vertical = 12.dp, horizontal = 10.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp).padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (showingCategories) stringResource(R.string.content_category_browser_title) else title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        ),
                        color = LocalAccentOnVideo.current,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (categories.isNotEmpty() && onSelectCategory != null) {
                        Text(
                            text = if (showingCategories) stringResource(R.string.content_channel_overlay_title) else stringResource(R.string.content_category_browser_title),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.12f))
                                .clickable { showingCategories = !showingCategories }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }

                if (showingCategories) {
                    if (categories.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.player_no_tracks),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.6f),
                            )
                        }
                    } else {
                        LazyColumn(
                            state = categoryListState,
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(categories, key = { it.first }) { (catId, catName) ->
                                val isSelected = catId == selectedCategoryId
                                OverlayCategoryRow(
                                    categoryName = catName,
                                    isSelected = isSelected,
                                    onClick = {
                                        onSelectCategory?.invoke(catId)
                                        showingCategories = false
                                    },
                                )
                            }
                        }
                    }
                } else {
                    if (channels.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.player_no_tracks),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.6f),
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(channels, key = { it.id }) { channel ->
                                val isSelected = channel.id == currentChannelId
                                val nowPlaying = nowPlayingMap[channel.id]

                                OverlayChannelRow(
                                    channel = channel,
                                    isSelected = isSelected,
                                    nowPlaying = nowPlaying,
                                    onClick = { onSelectChannel(channel); onDismiss() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayCategoryRow(
    categoryName: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val accentColor = LocalAccentOnVideo.current
    val shape = RoundedCornerShape(10.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (isSelected) accentColor.copy(alpha = 0.15f)
                else Color.White.copy(alpha = 0.04f),
            )
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) accentColor else Color.White.copy(alpha = 0.08f),
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = categoryName,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 12.sp,
            ),
            color = if (isSelected) accentColor else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun OverlayChannelRow(
    channel: ChannelEntity,
    isSelected: Boolean,
    nowPlaying: String?,
    onClick: () -> Unit,
) {
    val accentColor = LocalAccentOnVideo.current
    val shape = RoundedCornerShape(10.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (isSelected) accentColor.copy(alpha = 0.15f)
                else Color.White.copy(alpha = 0.04f),
            )
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) accentColor else Color.White.copy(alpha = 0.08f),
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Logo container
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.4f))
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            ChannelLogoImage(
                channel = channel,
                modifier = Modifier
                    .size(28.dp)
                    .padding(2.dp),
                fallback = {
                    Text(
                        text = channel.name.take(2).uppercase(),
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                        color = Color.White.copy(alpha = 0.8f),
                        fontWeight = FontWeight.Bold,
                    )
                },
            )
        }

        Spacer(Modifier.width(6.dp))

        // Channel Number
        channel.number?.let { num ->
            Text(
                text = num.toString(),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                ),
                color = Color.White.copy(alpha = 0.65f),
                modifier = Modifier.widthIn(min = 16.dp),
            )
            Spacer(Modifier.width(6.dp))
        }

        // Title and EPG subtitle
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 12.sp,
                ),
                color = if (isSelected) accentColor else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!nowPlaying.isNullOrBlank()) {
                Text(
                    text = nowPlaying,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = Color.White.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
