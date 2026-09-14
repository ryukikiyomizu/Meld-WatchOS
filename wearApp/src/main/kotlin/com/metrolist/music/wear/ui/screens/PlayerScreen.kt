/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.wear.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import coil3.compose.AsyncImage
import com.metrolist.music.bridge.WearBridge
import com.metrolist.music.bridge.WearPlayerState
import com.metrolist.music.wear.R
import com.metrolist.music.wear.bridge.MeldWear
import com.metrolist.music.wear.bridge.WearLink
import com.metrolist.music.wear.ui.WearRoute
import com.metrolist.music.wear.ui.WearRouter
import com.metrolist.music.wear.ui.components.GhostIconButton
import com.metrolist.music.wear.ui.components.NudgeRow
import com.metrolist.music.wear.ui.components.SeekRing
import com.metrolist.music.wear.ui.components.StateMessage
import com.metrolist.music.wear.ui.components.TransportRow
import com.metrolist.music.wear.ui.components.WearList
import com.metrolist.music.wear.ui.theme.LocalWearSettings
import com.metrolist.music.wear.ui.util.KeepScreenOn
import com.metrolist.music.wear.ui.util.formatWatchTime
import com.metrolist.music.wear.ui.util.rememberResumed
import com.metrolist.music.wear.ui.util.rememberTickingClock
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Now playing: the screen you actually glance at on a watch.
 *
 * Sized around a 192dp round viewport, so everything important (art, title, transport) fits in the
 * first screenful and the rest scrolls with the bezel. Position is interpolated locally between
 * refreshes, which is what lets the ring move smoothly while the phone is only asked every couple
 * of seconds.
 */
@Composable
fun PlayerScreen(router: WearRouter) {
    val settings = LocalWearSettings.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val state by MeldWear.state.collectAsStateWithLifecycle()
    val stateAt by MeldWear.stateAt.collectAsStateWithLifecycle()
    val link by MeldWear.link.collectAsStateWithLifecycle()
    val busy by MeldWear.working.collectAsStateWithLifecycle()
    val error by MeldWear.lastError.collectAsStateWithLifecycle()
    val resumed = rememberResumed()

    var scrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    // Download runs on the phone and takes longer than a round trip, so the row shows intent.
    var downloadPending by remember(state.mediaId) { mutableStateOf(false) }

    KeepScreenOn(settings.keepScreenOn || scrubbing)

    LaunchedEffect(resumed) {
        if (!resumed) return@LaunchedEffect
        while (true) {
            MeldWear.refresh()
            delay(if (MeldWear.state.value.isPlaying) PLAYING_REFRESH_MS else IDLE_REFRESH_MS)
        }
    }

    LaunchedEffect(downloadPending, state.downloaded) {
        if (!downloadPending) return@LaunchedEffect
        if (state.downloaded) {
            downloadPending = false
            return@LaunchedEffect
        }
        // Downloads queue on the phone, so stop claiming progress if nothing changed.
        delay(DOWNLOAD_PENDING_TIMEOUT_MS)
        downloadPending = false
    }

    val clock = rememberTickingClock(active = state.isPlaying && !scrubbing)
    val positionMs =
        when {
            scrubbing -> (scrubFraction * state.durationMs).roundToInt().toLong()
            state.isPlaying -> (state.positionMs + (clock - stateAt)).coerceAtMost(state.durationMs).coerceAtLeast(0L)
            else -> state.positionMs
        }
    val fraction =
        if (state.durationMs > 0L) {
            (positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    // Library ids are the raw YouTube video id; container ids (playlist/…/id) end with one.
    val playableSongId = state.mediaId?.substringAfterLast('/')?.takeIf { it.length == SONG_ID_LENGTH }

    if (!state.hasMedia) {
        EmptyPlayer(link = link, error = error, router = router, onRetry = { scope.launch { MeldWear.reconnect() } })
        return
    }

    WearList(state = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }) {
        item {
            ConnectionNotice(
                link = link,
                message = error,
                onOpen = { router.push(WearRoute.Phone) },
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        item {
            SeekRing(
                fraction = fraction,
                enabled = state.durationMs > 0L,
                onScrubStart = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    scrubbing = true
                },
                onScrub = { scrubFraction = it },
                onScrubEnd = { released ->
                    scrubbing = false
                    if (released != null && state.durationMs > 0L) {
                        val target = (released * state.durationMs).roundToInt().toLong()
                        scope.launch {
                            MeldWear.transport(
                                WearBridge.OP_SEEK,
                                JSONObject().put(WearBridge.KEY_POSITION_MS, target),
                            )
                        }
                    }
                },
                modifier = Modifier.size(RING_SIZE),
                content = {
                    Artwork(
                        uri = state.artworkUri,
                        showArtwork = settings.showArtwork,
                        playing = state.isPlaying,
                        modifier = Modifier.size(RING_SIZE - 26.dp),
                    )
                },
            )
        }

        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = state.title.ifBlank { stringResource(R.string.nothing_playing) },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
                )
                Text(
                    text = state.artist,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = formatWatchTime(positionMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (scrubbing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text =
                            state.durationMs
                                .takeIf { it > 0L }
                                ?.let { "-${formatWatchTime((it - positionMs).coerceAtLeast(0L))}" }
                                ?: " ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            TransportRow(
                isPlaying = state.isPlaying,
                busy = busy || state.isBuffering,
                enabled = !busy,
                onPlayPause = { scope.launch { MeldWear.transport(WearBridge.OP_PLAY_PAUSE) } },
                onPrevious = { scope.launch { MeldWear.transport(WearBridge.OP_PREVIOUS) } },
                onNext = { scope.launch { MeldWear.transport(WearBridge.OP_NEXT) } },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToggleTile(
                    icon = Icons.Filled.Shuffle,
                    contentDescription = "Shuffle",
                    active = state.shuffleEnabled,
                    onClick = { scope.launch { MeldWear.toggle(WearBridge.OP_SHUFFLE) } },
                )
                ToggleTile(
                    icon = if (state.repeatMode == WearPlayerState.WEAR_REPEAT_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    contentDescription = "Repeat",
                    active = state.repeatMode != WearPlayerState.WEAR_REPEAT_OFF,
                    onClick = { scope.launch { MeldWear.toggle(WearBridge.OP_REPEAT) } },
                )
                ToggleTile(
                    icon = if (state.liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = "Like",
                    active = state.liked,
                    onClick = { scope.launch { MeldWear.toggle(WearBridge.OP_LIKE) } },
                )
                ToggleTile(
                    icon = if (state.downloaded) Icons.Filled.DownloadDone else Icons.Filled.Download,
                    contentDescription = "Download",
                    active = state.downloaded || downloadPending,
                    enabled = playableSongId != null,
                    onClick = {
                        val id = playableSongId ?: return@ToggleTile
                        downloadPending = !state.downloaded
                        scope.launch { MeldWear.download(id, add = !state.downloaded) }
                    },
                )
            }
        }

        item {
            NudgeRow(
                enabled = state.durationMs > 0L,
                stepSeconds = settings.seekStepSeconds,
                onBackward = {
                    scope.launch {
                        MeldWear.transport(
                            WearBridge.OP_SKIP_BACK,
                            JSONObject().put(WearBridge.KEY_DELTA_MS, settings.seekStepSeconds * 1000L),
                        )
                    }
                },
                onForward = {
                    scope.launch {
                        MeldWear.transport(
                            WearBridge.OP_SKIP_FORWARD,
                            JSONObject().put(WearBridge.KEY_DELTA_MS, settings.seekStepSeconds * 1000L),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ShortcutTile("Queue", Icons.Filled.QueueMusic, Modifier.weight(1f)) { router.push(WearRoute.Queue) }
                ShortcutTile("Search", Icons.Filled.Search, Modifier.weight(1f)) { router.push(WearRoute.Search) }
                ShortcutTile("Library", Icons.Filled.LibraryMusic, Modifier.weight(1f)) { router.push(WearRoute.Library) }
                ShortcutTile("More", Icons.Filled.MoreHoriz, Modifier.weight(1f)) { router.push(WearRoute.Settings) }
            }
        }

        item { Spacer(Modifier.height(10.dp)) }
    }
}

@Composable
private fun Artwork(
    uri: String?,
    showArtwork: Boolean,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        when {
            showArtwork && uri != null ->
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                )
            playing -> CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.dp)
            else ->
                Icon(
                    imageVector = Icons.Filled.QueueMusic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp),
                )
        }
    }
}

@Composable
private fun ToggleTile(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    GhostIconButton(
        onClick = onClick,
        enabled = enabled,
        contentDescription = contentDescription,
        modifier = Modifier.size(38.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ShortcutTile(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            modifier
                .height(50.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.65f))
                .clickable(onClick = onClick)
                .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Shown only when the link is actually broken — a working watch gets no chrome. */
@Composable
private fun ConnectionNotice(
    link: WearLink,
    message: String?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val broken = link is WearLink.NoPhone || link is WearLink.SilentPhone
    if (!broken) return
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
                .clickable(onClick = onOpen)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text =
                message
                    ?: stringResource(if (link is WearLink.NoPhone) R.string.link_no_phone else R.string.link_silent_phone),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun EmptyPlayer(
    link: WearLink,
    error: String?,
    router: WearRouter,
    onRetry: () -> Unit,
) {
    val noPhone = link is WearLink.NoPhone || link is WearLink.SilentPhone
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        if (noPhone) {
            StateMessage(
                text = error ?: stringResource(R.string.link_silent_phone),
                hint = stringResource(R.string.open_meld_on_phone),
                actionLabel = stringResource(R.string.retry),
                onAction = onRetry,
            )
        } else {
            StateMessage(
                text = stringResource(R.string.nothing_playing),
                hint = stringResource(R.string.pick_from_library),
                actionLabel = stringResource(R.string.library),
                onAction = { router.push(WearRoute.Library) },
            )
        }
    }
}

private const val PLAYING_REFRESH_MS = 2_000L
private const val IDLE_REFRESH_MS = 8_000L
private val RING_SIZE = 116.dp
private const val SONG_ID_LENGTH = 11
private const val DOWNLOAD_PENDING_TIMEOUT_MS = 30_000L
