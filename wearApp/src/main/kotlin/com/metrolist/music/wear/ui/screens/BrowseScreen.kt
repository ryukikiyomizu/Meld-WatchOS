/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.wear.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.metrolist.music.bridge.WearMediaRow
import com.metrolist.music.wear.R
import com.metrolist.music.wear.bridge.MeldWear
import com.metrolist.music.wear.ui.WearRoute
import com.metrolist.music.wear.ui.WearRouter
import com.metrolist.music.wear.ui.components.LoadingBlock
import com.metrolist.music.wear.ui.components.MediaRow
import com.metrolist.music.wear.ui.components.ScreenHeader
import com.metrolist.music.wear.ui.components.SquareButton
import com.metrolist.music.wear.ui.components.StateMessage
import com.metrolist.music.wear.ui.components.WearList
import com.metrolist.music.wear.ui.theme.LocalWearSettings
import com.metrolist.music.wear.ui.util.rememberResumed
import kotlinx.coroutines.launch

/**
 * One container of the phone's library: a playlist, liked songs, an album, an artist, or the
 * downloaded songs. Rows that are browsable dive deeper, rows that are playable start playback and
 * drop you back on the player.
 */
@Composable
fun BrowseScreen(
    parentId: String,
    label: String,
    router: WearRouter,
    removable: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val settings = LocalWearSettings.current
    val resumed = rememberResumed()
    var rows by remember(parentId) { mutableStateOf<List<WearMediaRow>?>(null) }
    var loading by remember(parentId) { mutableStateOf(true) }
    var error by remember(parentId) { mutableStateOf<String?>(null) }
    var pendingRemove by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        val loaded = MeldWear.browse(parentId)
        if (loaded != null) {
            rows = loaded
            error = null
        } else {
            error = MeldWear.lastError.value ?: "Phone did not answer"
        }
        loading = false
    }

    LaunchedEffect(parentId, resumed) {
        if (!resumed) return@LaunchedEffect
        load()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        ScreenHeader(
            title = label,
            onBack = { router.pop() },
        )
        val items = rows
        val playable = items?.count { it.playable && !it.mediaId.endsWith(SHUFFLE_SUFFIX) } ?: 0
        when {
            items == null && loading -> LoadingBlock()
            items.isNullOrEmpty() ->
                StateMessage(
                    text =
                        when {
                            removable -> stringResource(R.string.no_downloads_yet)
                            else -> error?.takeIf { it.isNotBlank() } ?: stringResource(R.string.nothing_to_show)
                        },
                    hint = stringResource(R.string.open_meld_on_phone),
                    actionLabel = stringResource(R.string.retry),
                    onAction = {
                        scope.launch {
                            MeldWear.reconnect()
                            load()
                        }
                    },
                )
            else ->
                WearList(
                    state = rememberSaveable(saver = LazyListState.Saver) { LazyListState() },
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 8.dp),
                ) {
                    if (!removable && playable > 0) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                            ) {
                                SquareButton(
                                    label = stringResource(R.string.play),
                                    modifier = Modifier.width(74.dp),
                                    onClick = { scope.launch { MeldWear.playContainer(parentId, shuffle = false) } },
                                )
                                SquareButton(
                                    label = stringResource(R.string.shuffle),
                                    modifier = Modifier.width(74.dp),
                                    onClick = { scope.launch { MeldWear.playContainer(parentId, shuffle = true) } },
                                )
                            }
                        }
                    }
                    items(
                        count = items.size,
                        contentType = { index -> items[index].kind },
                    ) { index ->
                        val row = items[index]
                        val isShuffleAction = row.mediaId.endsWith(SHUFFLE_SUFFIX)
                        MediaRow(
                            row = row.copy(title = if (isShuffleAction) stringResource(R.string.shuffle) else row.title),
                            showArtwork = settings.showArtwork && !isShuffleAction,
                            onClick = {
                                when {
                                    row.browsable ->
                                        router.push(
                                            WearRoute.Browse(
                                                parentId = row.mediaId,
                                                label = row.title,
                                            ),
                                        )
                                    row.playable ->
                                        scope.launch {
                                            if (MeldWear.play(row.mediaId)) router.popTo(WearRoute.Player)
                                        }
                                }
                            },
                            trailing =
                                if (removable && row.mediaId.substringAfterLast('/').length == SONG_ID_LENGTH) {
                                    {
                                        val songId = row.mediaId.substringAfterLast('/')
                                        RemoveButton(
                                            busy = pendingRemove == row.mediaId,
                                            onClick = {
                                                scope.launch {
                                                    pendingRemove = row.mediaId
                                                    MeldWear.download(songId, add = false)
                                                    pendingRemove = null
                                                    load()
                                                }
                                            },
                                        )
                                    }
                                } else {
                                    null
                                },
                        )
                    }
                    if (removable) {
                        item {
                            Text(
                                text = stringResource(R.string.tap_cross_to_remove),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
        }
    }
}

@Composable
private fun RemoveButton(
    busy: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.remove_download),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

private const val SHUFFLE_SUFFIX = "/__shuffle__"
private const val SONG_ID_LENGTH = 11
