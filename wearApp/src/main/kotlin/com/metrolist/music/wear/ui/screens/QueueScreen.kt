/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.wear.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.metrolist.music.bridge.WearBridge
import com.metrolist.music.bridge.WearMediaRow
import com.metrolist.music.wear.R
import com.metrolist.music.wear.bridge.MeldWear
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
 * The phone's queue: what is playing, what is next, jump or drop a song.
 *
 * Rows are addressed by index (that is what the media session understands), so the list keeps the
 * index the phone reported rather than re-deriving it after each mutation.
 */
@Composable
fun QueueScreen(router: WearRouter) {
    val scope = rememberCoroutineScope()
    val settings = LocalWearSettings.current
    val resumed = rememberResumed()
    var rows by remember { mutableStateOf<List<WearMediaRow>?>(null) }
    var currentIndex by remember { mutableStateOf(-1) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        val loaded = MeldWear.queue()
        if (loaded != null) {
            currentIndex = loaded.first
            rows = loaded.second
            error = null
        } else {
            error = MeldWear.lastError.value
        }
        loading = false
    }

    LaunchedEffect(resumed) {
        if (!resumed) return@LaunchedEffect
        load()
        while (MeldWear.state.value.isPlaying) {
            delay(REFRESH_MS)
            load()
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        ScreenHeader(
            title = stringResource(R.string.queue),
            onBack = { router.pop() },
        )
        rows?.size?.let { count ->
            Text(
                text = stringResource(R.string.queue_count, count),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        val items = rows
        when {
            items == null && loading -> LoadingBlock()
            items.isNullOrEmpty() ->
                StateMessage(
                    text = stringResource(R.string.queue_empty),
                    hint = error ?: stringResource(R.string.open_meld_on_phone),
                    actionLabel = stringResource(R.string.retry),
                    onAction = { scope.launch { load() } },
                )
            else ->
                WearList(
                    state = rememberSaveable(saver = LazyListState.Saver) { LazyListState() },
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 10.dp),
                ) {
                    item {
                        RadioRow(
                            onRadio = {
                                scope.launch {
                                    MeldWear.toggle(WearBridge.OP_RADIO)
                                    load()
                                }
                            },
                        )
                    }
                    items(count = items.size) { index ->
                        val row = items[index]
                        val current = row.index == currentIndex
                        MediaRow(
                            row = row,
                            playing = current,
                            showArtwork = settings.showArtwork,
                            onClick = {
                                if (!current) {
                                    scope.launch {
                                        MeldWear.queueOp(WearBridge.OP_JUMP, row.index)
                                        load()
                                    }
                                }
                            },
                            trailing =
                                if (!current) {
                                    {
                                        IconButton(
                                            onClick = {
                                                scope.launch {
                                                    MeldWear.queueOp(WearBridge.OP_REMOVE, row.index)
                                                    load()
                                                }
                                            },
                                            modifier = Modifier.size(28.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Close,
                                                contentDescription = stringResource(R.string.remove_from_queue),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(14.dp),
                                            )
                                        }
                                    }
                                } else {
                                    null
                                },
                        )
                    }
                }
        }
    }
}

@Composable
private fun RadioRow(
    onRadio: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.start_radio_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onRadio,
            modifier = Modifier.size(30.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.GraphicEq,
                contentDescription = stringResource(R.string.start_radio),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
