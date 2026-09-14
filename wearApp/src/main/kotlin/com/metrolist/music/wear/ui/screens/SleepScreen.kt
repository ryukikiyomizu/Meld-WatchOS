/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.wear.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.metrolist.music.wear.R
import com.metrolist.music.wear.bridge.MeldWear
import com.metrolist.music.wear.ui.WearRouter
import com.metrolist.music.wear.ui.components.ScreenHeader
import com.metrolist.music.wear.ui.components.SquareButton
import com.metrolist.music.wear.ui.components.WearList
import com.metrolist.music.wear.ui.util.formatCountdown
import com.metrolist.music.wear.ui.util.rememberTickingClock
import kotlinx.coroutines.launch

/**
 * Sleep timer. Set and cleared here; the countdown runs from the value the phone reported and ticks
 * down locally so it doesn't jitter with each round trip.
 *
 * Note the honest scope: a timer started *on the phone* isn't reported back, because the service
 * keeps that state to itself. Cancelling from the watch works either way.
 */
@Composable
fun SleepScreen(router: WearRouter) {
    val scope = rememberCoroutineScope()
    val state by MeldWear.state.collectAsStateWithLifecycle()
    val stateAt by MeldWear.stateAt.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<Int?>(null) }
    val clock = rememberTickingClock(active = state.sleepRemainingMs > 0L)
    val remaining = (state.sleepRemainingMs - (clock - stateAt)).coerceAtLeast(0L)

    Column(modifier = Modifier.fillMaxWidth()) {
        ScreenHeader(
            title = stringResource(R.string.sleep_timer),
            onBack = { router.pop() },
        )
        WearList(
            state = rememberSaveable(saver = LazyListState.Saver) { LazyListState() },
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 10.dp),
        ) {
            item {
                Text(
                    text =
                        if (remaining > 0L) {
                            stringResource(R.string.sleep_stops_in, formatCountdown(remaining))
                        } else {
                            stringResource(R.string.sleep_inactive)
                        },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (remaining > 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                )
            }
            LENGTHS.chunked(3).forEach { chunk ->
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    ) {
                        chunk.forEach { minutes ->
                            SquareButton(
                                label = "${minutes}m",
                                modifier = Modifier.width(54.dp),
                                enabled = pending == null,
                                onClick = {
                                    scope.launch {
                                        pending = minutes
                                        MeldWear.setSleepTimer(minutes)
                                        MeldWear.refresh()
                                        pending = null
                                    }
                                },
                            )
                        }
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                ) {
                    SquareButton(
                        label = stringResource(R.string.sleep_end_of_song),
                        modifier = Modifier.width(84.dp),
                        enabled = pending == null,
                        onClick = {
                            scope.launch {
                                pending = END_OF_SONG
                                MeldWear.setSleepTimer(END_OF_SONG)
                                MeldWear.refresh()
                                pending = null
                            }
                        },
                    )
                    SquareButton(
                        label = stringResource(R.string.sleep_cancel),
                        modifier = Modifier.width(72.dp),
                        enabled = pending == null,
                        onClick = {
                            scope.launch {
                                MeldWear.clearSleepTimer()
                                MeldWear.refresh()
                            }
                        },
                    )
                }
            }
            item { Spacer(Modifier.height(4.dp)) }
        }
    }
}

private val LENGTHS = listOf(5, 10, 15, 30, 45, 60)
private const val END_OF_SONG = -1
