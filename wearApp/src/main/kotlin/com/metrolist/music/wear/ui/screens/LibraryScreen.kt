/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.wear.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import com.metrolist.music.bridge.WearBridge
import com.metrolist.music.bridge.WearMediaRow
import com.metrolist.music.wear.R
import com.metrolist.music.wear.bridge.MeldWear
import com.metrolist.music.wear.ui.WearRoute
import com.metrolist.music.wear.ui.WearRouter
import com.metrolist.music.wear.ui.components.LoadingBlock
import com.metrolist.music.wear.ui.components.MediaRow
import com.metrolist.music.wear.ui.components.ScreenHeader
import com.metrolist.music.wear.ui.components.StateMessage
import com.metrolist.music.wear.ui.components.WearList
import com.metrolist.music.wear.ui.theme.LocalWearSettings
import com.metrolist.music.wear.ui.util.rememberResumed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Entry point into the phone's library. The rows come straight from the browse tree Meld already
 * publishes for Android Auto, so "what the phone can show a car, the watch can show too" holds.
 */
@Composable
fun LibraryScreen(router: WearRouter) {
    val scope = rememberCoroutineScope()
    val resumed = rememberResumed()
    val settings = LocalWearSettings.current
    var rows by remember { mutableStateOf<List<WearMediaRow>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(resumed) {
        if (!resumed) return@LaunchedEffect
        while (true) {
            loading = rows == null
            val loaded = MeldWear.browse(WearBridge.ROOT_ID)
            if (loaded != null) {
                rows = loaded
                error = null
            } else {
                error = MeldWear.lastError.value
            }
            loading = false
            delay(REFRESH_MS)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        ScreenHeader(
            title = stringResource(R.string.library),
            onBack = { router.pop() },
            action = {
                IconButton(
                    onClick = { router.push(WearRoute.Search) },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = stringResource(R.string.search),
                        modifier = Modifier.size(16.dp),
                    )
                }
            },
        )
        val items = rows
        when {
            items == null && loading -> LoadingBlock()
            items.isNullOrEmpty() ->
                StateMessage(
                    text = error?.takeIf { it.isNotBlank() } ?: stringResource(R.string.nothing_to_show),
                    hint = stringResource(R.string.open_meld_on_phone),
                    actionLabel = stringResource(R.string.retry),
                    onAction = { scope.launch { MeldWear.reconnect() } },
                )
            else ->
                WearList(
                    state = rememberSaveable(saver = LazyListState.Saver) { LazyListState() },
                    contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 30.dp),
                ) {
                    // Downloaded songs are always one tap away, whether or not the phone's Auto
                    // section list happens to include them.
                    item {
                        MediaRow(
                            row = WearMediaRow(
                                mediaId = WearBridge.downloadedContainer(),
                                title = stringResource(R.string.downloaded),
                                subtitle = null,
                                kind = WearBridge.KIND_PLAYLIST,
                                browsable = true,
                            ),
                            showArtwork = false,
                            onClick = {
                                router.push(
                                    WearRoute.Browse(
                                        parentId = WearBridge.downloadedContainer(),
                                        label = stringResource(R.string.downloaded),
                                    ),
                                )
                            },
                            trailing = {
                                Icon(
                                    imageVector = Icons.Filled.Download,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                    items(
                        count = items.size,
                    ) { index ->
                        val row = items[index]
                        MediaRow(
                            row = row,
                            showArtwork = settings.showArtwork,
                            onClick = {
                                when {
                                    row.browsable ->
                                        router.push(
                                            WearRoute.Browse(
                                                parentId = row.mediaId,
                                                label = row.title,
                                            ),
                                        )
                                    row.playable -> scope.launch { MeldWear.play(row.mediaId) }
                                }
                            },
                        )
                    }
                    item { Spacer(Modifier.height(6.dp)) }
                }
        }
    }
}

private const val REFRESH_MS = 20_000L
