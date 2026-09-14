/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.wear

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.android.gms.wearable.Wearable
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.BuildConfig
import com.metrolist.music.R
import com.metrolist.music.bridge.WearBridge
import com.metrolist.music.bridge.WearMediaRow
import com.metrolist.music.bridge.WearPlayerState
import com.metrolist.music.constants.MediaSessionConstants
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.di.ApplicationScope
import com.metrolist.music.playback.DownloadUtil
import com.metrolist.music.playback.ExoDownloadService
import com.metrolist.music.playback.MusicService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import timber.log.Timber

/**
 * Answers Wear OS companion requests on behalf of [MusicService].
 *
 * Deliberately thin: it does not re-implement browsing, search or playback resolution. It connects
 * Meld's *own* [androidx.media3.session.MediaLibraryService] and rides the exact code paths Android
 * Auto uses (`MediaLibrarySessionCallback`): a [MediaBrowser] for the library tree and a
 * [MediaController] for playback. Whatever a car dashboard can show and play, the watch can too — and
 * the two cannot diverge. Browsing and driving stay separate on purpose: a browser is granted the
 * library commands, while only a controller is guaranteed the player ones.
 *
 * The watch drives all traffic: it asks while it is in the foreground and goes quiet when the
 * screen turns off, so this class is normally idle and the connection is released.
 */
@Singleton
class WearPhoneBridge
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
        private val database: MusicDatabase,
        private val downloadUtil: DownloadUtil,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        private val connectMutex = Mutex()

        /** Library tree only: `getChildren` needs a browser, and a browser is not a controller. */
        private var browser: MediaBrowser? = null

        /** Everything that changes playback — media3 drops a command the client was not granted. */
        private var controller: MediaController? = null
        private var idleReleaseJob: Job? = null

        /**
         * Deadline (in [SystemClock.elapsedRealtime]) of a sleep timer started *from the watch*.
         * `SleepTimer` lives inside the service and publishes no state of its own, so the bridge
         * reports only the timer it started rather than guessing about timers started on the phone.
         */
        @Volatile
        private var watchSleepEndsAtMs = 0L

        fun onRequest(
            nodeId: String,
            payload: ByteArray,
        ) {
            scope.launch {
                val requestId = runCatching { JSONObject(String(payload)).optString(WearBridge.KEY_ID, "") }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                val reply =
                    runCatching { withTimeoutOrNull(DISPATCH_TIMEOUT_MS) { dispatch(payload) } }
                        .getOrElse { error ->
                            Timber.tag(TAG).w(error, "Wear request failed")
                            WearBridge.buildError(
                                requestId,
                                WearBridge.CODE_FAILED,
                                error.message ?: appContext.getString(R.string.wear_bridge_request_failed),
                            )
                        }
                        ?: WearBridge.buildError(requestId, WearBridge.CODE_FAILED, appContext.getString(R.string.wear_bridge_timeout))
                replyMessage(nodeId, reply)
            }
        }

        private suspend fun dispatch(payload: ByteArray): ByteArray {
            val request = JSONObject(String(payload))
            val requestId = request.optString(WearBridge.KEY_ID, "").takeIf { it.isNotEmpty() }
            if (request.optInt(WearBridge.KEY_VERSION, 0) > WearBridge.PROTOCOL_VERSION) {
                return WearBridge.buildError(
                    requestId,
                    WearBridge.CODE_UNSUPPORTED_VERSION,
                    appContext.getString(R.string.wear_bridge_protocol_mismatch, WearBridge.PROTOCOL_VERSION),
                )
            }
            val action = request.optString(WearBridge.KEY_ACTION)
            val args = request.optJSONObject(WearBridge.KEY_ARGS) ?: JSONObject()
            return runCatching {
                WearBridge.buildResponse(requestId, handle(action, args))
            }.getOrElse { error ->
                Timber.tag(TAG).w(error, "Action '$action' failed")
                WearBridge.buildError(
                    requestId,
                    WearBridge.CODE_FAILED,
                    error.message ?: appContext.getString(R.string.wear_bridge_request_failed),
                )
            }
        }

        private suspend fun handle(
            action: String,
            args: JSONObject,
        ): JSONObject =
            when (action) {
                WearBridge.ACTION_PING -> ping()
                WearBridge.ACTION_STATE -> snapshot(requireController())
                WearBridge.ACTION_TRANSPORT -> transport(args)
                WearBridge.ACTION_TOGGLE -> toggle(args)
                WearBridge.ACTION_QUEUE -> queue()
                WearBridge.ACTION_QUEUE_OP -> queueOp(args)
                WearBridge.ACTION_BROWSE -> browse(args)
                WearBridge.ACTION_SEARCH -> search(args)
                WearBridge.ACTION_PLAY -> play(args)
                WearBridge.ACTION_PLAY_CONTAINER -> playContainer(args)
                WearBridge.ACTION_SLEEP -> sleep(args)
                WearBridge.ACTION_VOLUME -> volume(args)
                WearBridge.ACTION_DOWNLOAD -> download(args)
                else -> throw IllegalArgumentException("Unsupported action '$action'")
            }

        // --- Actions ---------------------------------------------------------------------------

        private fun ping(): JSONObject {
            val (percent, charging) = batteryLevel()
            return JSONObject()
                .put(WearBridge.KEY_PHONE_VERSION, BuildConfig.VERSION_NAME)
                .put(WearBridge.KEY_PHONE_BATTERY, percent)
                .put(WearBridge.KEY_PHONE_CHARGING, charging)
                .put(WearBridge.KEY_PROTOCOL, WearBridge.PROTOCOL_VERSION)
        }

        private suspend fun transport(args: JSONObject): JSONObject {
            val player = requireController()
            when (args.optString(WearBridge.KEY_OP)) {
                WearBridge.OP_PLAY_PAUSE -> if (player.isPlaying) player.pause() else player.play()
                WearBridge.OP_NEXT -> player.seekToNextMediaItem()
                WearBridge.OP_PREVIOUS ->
                    // Mirrors the phone UI: early in a track, "previous" restarts it instead.
                    if (player.currentPosition > REWIND_THRESHOLD_MS) {
                        player.seekTo(0L)
                    } else {
                        player.seekToPreviousMediaItem()
                    }
                WearBridge.OP_SKIP_FORWARD ->
                    player.seekTo(
                        (player.currentPosition + args.optLong(WearBridge.KEY_DELTA_MS, SKIP_STEP_MS))
                            .coerceAtMost(player.duration.coerceAtLeast(0L)),
                    )
                WearBridge.OP_SKIP_BACK ->
                    player.seekTo(
                        (player.currentPosition - args.optLong(WearBridge.KEY_DELTA_MS, SKIP_STEP_MS)).coerceAtLeast(0L),
                    )
                WearBridge.OP_SEEK -> player.seekTo(args.optLong(WearBridge.KEY_POSITION_MS, 0L).coerceAtLeast(0L))
                else -> throw IllegalArgumentException("Unsupported transport op '${args.optString(WearBridge.KEY_OP)}'")
            }
            return snapshot(player)
        }

        private suspend fun toggle(args: JSONObject): JSONObject {
            val player = requireController()
            when (val op = args.optString(WearBridge.KEY_OP)) {
                WearBridge.OP_SHUFFLE -> player.shuffleModeEnabled = !player.shuffleModeEnabled
                WearBridge.OP_REPEAT ->
                    player.repeatMode =
                        when (player.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                // Like and radio mutate app state the controller cannot write directly, so they go
                // through the session's custom commands — the same ones the notification uses.
                WearBridge.OP_LIKE -> sendCommand(MediaSessionConstants.CommandToggleLike, Bundle.EMPTY)
                WearBridge.OP_RADIO -> sendCommand(MediaSessionConstants.CommandToggleStartRadio, Bundle.EMPTY)
                else -> throw IllegalArgumentException("Unsupported toggle op '$op'")
            }
            // Give the custom command a beat to land before we read the state back.
            delay(COMMAND_SETTLE_MS)
            return snapshot(player)
        }

        private suspend fun queue(): JSONObject {
            val player = requireController()
            val timeline = player.currentTimeline
            val rows =
                (0 until timeline.windowCount).mapIndexedNotNull { index, _ ->
                    runCatching { player.getMediaItemAt(index) }.getOrNull()?.toWearRow(index = index)
                }
            return JSONObject()
                .put(WearBridge.KEY_ITEMS, WearBridge.itemsJson(rows))
                .put(WearBridge.KEY_CURRENT_INDEX, player.currentMediaItemIndex)
                .put(WearBridge.KEY_TITLE, player.playlistMetadata.title?.toString())
                .put(WearBridge.KEY_QUEUE_SIZE, timeline.windowCount)
        }

        private suspend fun queueOp(args: JSONObject): JSONObject {
            val player = requireController()
            val index = args.optInt(WearBridge.KEY_INDEX, -1)
            val inRange = index >= 0 && index < player.currentTimeline.windowCount
            when (args.optString(WearBridge.KEY_OP)) {
                WearBridge.OP_JUMP -> {
                    require(inRange) { "Queue index out of range" }
                    player.seekTo(index, 0L)
                }
                WearBridge.OP_REMOVE -> {
                    require(inRange) { "Queue index out of range" }
                    require(index != player.currentMediaItemIndex) { appContext.getString(R.string.wear_bridge_remove_playing) }
                    player.removeMediaItem(index)
                }
                else -> throw IllegalArgumentException("Unsupported queue op")
            }
            return snapshot(player)
        }

        private suspend fun browse(args: JSONObject): JSONObject {
            val parentId = args.optString(WearBridge.KEY_PARENT_ID, WearBridge.ROOT_ID)
            val children = childrenOf(parentId).audioOnly()
            return JSONObject()
                .put(WearBridge.KEY_PARENT_ID, parentId)
                .put(WearBridge.KEY_ITEMS, WearBridge.itemsJson(children.map { it.toWearRow() }))
        }

        /**
         * One screen of results, read from the phone's own database plus a YouTube song search.
         *
         * Not the session's `search()`: that protocol pages through a result set whose size the service
         * announces first (Meld announces one, for Auto), while the watch only ever shows a single list.
         * Songs keep the `search/<query>/<id>` id so the service expands a tap into the same queue the
         * phone would have built; albums, artists and playlists point at the browse tree.
         */
        private suspend fun search(args: JSONObject): JSONObject {
            val query = args.optString(WearBridge.KEY_QUERY).trim()
            if (query.isEmpty()) {
                return JSONObject()
                    .put(WearBridge.KEY_QUERY, "")
                    .put(WearBridge.KEY_ITEMS, WearBridge.itemsJson(emptyList()))
            }
            val rows = mutableListOf<WearMediaRow>()
            val seen = mutableSetOf<String>()

            fun addRow(row: WearMediaRow) {
                if (seen.add(row.mediaId) && rows.size < MAX_LIST_ITEMS) rows += row
            }

            await(database.searchSongs(query, SEARCH_SONG_LIMIT))
                .orEmpty()
                .filterNot { it.song.isVideo }
                .forEach { song ->
                    addRow(
                        WearMediaRow(
                            mediaId = "${MusicService.SEARCH}/$query/${song.id}",
                            title = song.title,
                            subtitle = song.orderedArtists.joinToString { artist -> artist.name },
                            artworkUri = song.thumbnailUrl?.takeIf { it.startsWith("http") },
                            kind = WearBridge.KIND_SONG,
                            playable = true,
                            durationMs = song.song.duration.coerceAtLeast(0) * 1000L,
                            downloaded =
                                downloadUtil
                                    .downloads.value[song.id]
                                    ?.state == Download.STATE_COMPLETED,
                        ),
                    )
                }

            await(database.searchAlbums(query, SEARCH_CONTAINER_LIMIT))
                .orEmpty()
                .forEach { album ->
                    addRow(
                        WearMediaRow(
                            mediaId = "${MusicService.ALBUM}/${album.id}",
                            title = album.title,
                            subtitle = album.artists.joinToString { artist -> artist.name },
                            artworkUri = album.album.thumbnailUrl?.takeIf { it.startsWith("http") },
                            kind = WearBridge.KIND_ALBUM,
                            browsable = true,
                        ),
                    )
                }

            await(database.searchPlaylists(query, SEARCH_CONTAINER_LIMIT))
                .orEmpty()
                .forEach { playlist ->
                    addRow(
                        WearMediaRow(
                            mediaId = "${MusicService.PLAYLIST}/${playlist.id}",
                            title = playlist.title,
                            subtitle = songCount(playlist.songCount),
                            artworkUri = playlist.playlist.thumbnailUrl?.takeIf { it.startsWith("http") },
                            kind = WearBridge.KIND_PLAYLIST,
                            browsable = true,
                        ),
                    )
                }

            await(database.searchArtists(query, SEARCH_CONTAINER_LIMIT))
                .orEmpty()
                .forEach { artist ->
                    addRow(
                        WearMediaRow(
                            mediaId = "${MusicService.ARTIST}/${artist.id}",
                            title = artist.title,
                            subtitle = songCount(artist.songCount),
                            artworkUri = artist.thumbnailUrl?.takeIf { it.startsWith("http") },
                            kind = WearBridge.KIND_ARTIST,
                            browsable = true,
                        ),
                    )
                }

            // Online songs come last: a slow network then only costs the rows nobody had reached yet,
            // and the wait is bounded because the library is worth showing on its own.
            val online =
                withTimeoutOrNull(ONLINE_SEARCH_TIMEOUT_MS) {
                    YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                }?.items.orEmpty().filterIsInstance<SongItem>()
            online.forEach { item ->
                if (item.isVideoSong) return@forEach // audio only, same rule as the tree
                addRow(
                    WearMediaRow(
                        mediaId = "${MusicService.SEARCH}/$query/${item.id}",
                        title = item.title,
                        subtitle = item.artists.joinToString { artist -> artist.name },
                        artworkUri = item.thumbnail.takeIf { it.startsWith("http") },
                        kind = WearBridge.KIND_SONG,
                        playable = true,
                        downloaded =
                            downloadUtil
                                .downloads.value[item.id]
                                ?.state == Download.STATE_COMPLETED,
                    ),
                )
            }

            return JSONObject()
                .put(WearBridge.KEY_QUERY, query)
                .put(WearBridge.KEY_ITEMS, WearBridge.itemsJson(rows))
        }

        /** "%1$d songs", in the phone's language: the row is built here, not on the watch. */
        private fun songCount(count: Int): String =
            appContext.resources.getQuantityString(R.plurals.wear_bridge_n_songs, count, count)

        private suspend fun play(args: JSONObject): JSONObject {
            val mediaId = args.optString(WearBridge.KEY_MEDIA_ID)
            require(mediaId.isNotEmpty()) { "Missing mediaId" }
            val player = requireController()
            // Only the id travels. `onSetMediaItems` on the service side expands it into the real
            // queue (playlist/album/search context), exactly like Android Auto does.
            player.setMediaItems(listOf(MediaItem.Builder().setMediaId(mediaId).build()), 0, C.TIME_UNSET)
            player.play()
            awaitQueue(player, mediaId)
            return snapshot(player)
        }

        /**
         * The service owns the queue, and a `search/...` id costs it a library scan plus a YouTube
         * lookup before anything starts, so wait for the items to land. A command the client was not
         * granted is dropped silently by media3 rather than throwing, and a watch that says "done"
         * while nothing happened is worse than one that admits the phone never started.
         */
        private suspend fun awaitQueue(
            player: Player,
            mediaId: String,
        ) {
            val deadline = SystemClock.elapsedRealtime() + PLAY_ACCEPT_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                if (player.mediaItemCount > 0) return
                delay(80L)
            }
            Timber.tag(TAG).w("Meld did not accept playback of '%s'", mediaId)
            throw IllegalStateException(appContext.getString(R.string.wear_bridge_play_not_accepted))
        }

        private suspend fun playContainer(args: JSONObject): JSONObject {
            val parentId = args.optString(WearBridge.KEY_PARENT_ID)
            require(parentId.isNotEmpty()) { "Missing parentId" }
            val shuffleWanted = args.optBoolean(WearBridge.KEY_SHUFFLE, false)
            val children = childrenOf(parentId).audioOnly()
            val shuffleSuffix = "/$SHUFFLE_ACTION"
            val playable = children.filterNot { it.mediaId?.endsWith(shuffleSuffix) == true }
            // `playlist/<id>/__shuffle__` is a synthetic row the service adds that starts the
            // container shuffled from track one; anything else starts at that track.
            val target =
                if (shuffleWanted) {
                    children.firstOrNull { it.mediaId?.endsWith(shuffleSuffix) == true } ?: playable.firstOrNull()
                } else {
                    playable.firstOrNull()
                } ?: throw NoSuchElementException(appContext.getString(R.string.wear_bridge_nothing_to_play))
            return play(JSONObject().put(WearBridge.KEY_MEDIA_ID, target.mediaId.orEmpty()))
        }

        private suspend fun sleep(args: JSONObject): JSONObject {
            val op = args.optString(WearBridge.KEY_OP)
            if (op == WearBridge.OP_CLEAR) {
                sendCommand(MediaSessionConstants.CommandClearSleepTimer, Bundle.EMPTY)
                watchSleepEndsAtMs = 0L
                return JSONObject().put(WearBridge.KEY_SLEEP_REMAINING_MS, 0L)
            }
            val minutes = args.optInt(WearBridge.KEY_MINUTES, 0)
            if (minutes == SLEEP_AT_END_OF_SONG) {
                // -1 means "pause when the current song ends" in SleepTimer's own vocabulary.
                sendCommand(
                    MediaSessionConstants.CommandSetSleepTimer,
                    Bundle().apply {
                        putInt(MediaSessionConstants.EXTRA_SLEEP_TIMER_MINUTES, -1)
                        putBoolean(MediaSessionConstants.EXTRA_SLEEP_TIMER_FADE, false)
                    },
                )
                watchSleepEndsAtMs = 0L
                return JSONObject().put(WearBridge.KEY_SLEEP_REMAINING_MS, 0L)
            }
            require(minutes in MIN_SLEEP_MINUTES..MAX_SLEEP_MINUTES) {
                appContext.getString(R.string.wear_bridge_sleep_range, MIN_SLEEP_MINUTES, MAX_SLEEP_MINUTES)
            }
            sendCommand(
                MediaSessionConstants.CommandSetSleepTimer,
                Bundle().apply {
                    putInt(MediaSessionConstants.EXTRA_SLEEP_TIMER_MINUTES, minutes)
                    putBoolean(MediaSessionConstants.EXTRA_SLEEP_TIMER_FADE, true)
                },
            )
            watchSleepEndsAtMs = SystemClock.elapsedRealtime() + minutes * 60_000L
            return JSONObject().put(WearBridge.KEY_SLEEP_REMAINING_MS, minutes * 60_000L)
        }

        private suspend fun volume(args: JSONObject): JSONObject {
            val player = requireController()
            if (args.has(WearBridge.KEY_VALUE)) {
                player.volume = args.optDouble(WearBridge.KEY_VALUE, 1.0).toFloat().coerceIn(0f, 1f)
            }
            return JSONObject().put(WearBridge.KEY_VALUE, player.volume.toDouble())
        }

        private suspend fun download(args: JSONObject): JSONObject {
            val songId = args.optString(WearBridge.KEY_SONG_ID)
            require(songId.isNotEmpty()) { "Missing songId" }
            when (val op = args.optString(WearBridge.KEY_OP)) {
                WearBridge.OP_REMOVE -> {
                    DownloadService.sendRemoveDownload(appContext, ExoDownloadService::class.java, songId, false)
                    return JSONObject().put(WearBridge.KEY_DOWNLOADED, false)
                }
                WearBridge.OP_ADD -> {
                    val song = await(database.song(songId))
                        ?: throw NoSuchElementException(appContext.getString(R.string.wear_bridge_download_needs_library))
                    val request =
                        DownloadRequest
                            .Builder(song.id, song.id.toUri())
                            .setCustomCacheKey(song.id)
                            .setData(song.song.title.toByteArray())
                            .build()
                    DownloadService.sendAddDownload(appContext, ExoDownloadService::class.java, request, false)
                    return JSONObject().put(WearBridge.KEY_DOWNLOADED, true)
                }
                else -> throw IllegalArgumentException("Unsupported download op '$op'")
            }
        }

        // --- Plumbing --------------------------------------------------------------------------

        private suspend fun childrenOf(parentId: String): List<MediaItem> =
            requireBrowser()
                .getChildren(parentId, 0, MAX_LIST_ITEMS, null)
                .await()
                .value
                .orEmpty()

        /**
         * Audio only, always. Video-backed entries (`SongEntity.isVideo`) need the phone's video
         * playback path and can fail there outright, so they are never offered to the watch — no
         * browse folder, no search hit. This ignores the phone's own "hide video songs" preference
         * on purpose: on the wrist there is nothing to hide and nothing to hide it with.
         */
        private suspend fun List<MediaItem>.audioOnly(): List<MediaItem> {
            val songIds =
                mapNotNull {
                    it.mediaId
                        ?.substringAfterLast('/')
                        ?.takeIf { id -> id.length == SONG_ID_LENGTH }
                }
            if (songIds.isEmpty()) return this
            val videos =
                database
                    .getSongsByIds(songIds.distinct())
                    .filter { it.song.isVideo }
                    .mapTo(mutableSetOf()) { it.song.id }
            if (videos.isEmpty()) return this
            return filterNot { item ->
                item.mediaId?.substringAfterLast('/')?.let { id -> id in videos } == true
            }
        }

        private suspend fun requireBrowser(): MediaBrowser {
            keepWarm()
            connectMutex.withLock {
                browser?.takeIf { it.isConnected }?.let { return it }
                val connected =
                    withContext(Dispatchers.Main) {
                        runCatching {
                            val token = SessionToken(appContext, ComponentName(appContext, MusicService::class.java))
                            MediaBrowser.Builder(appContext, token).buildAsync().await()
                        }.onFailure {
                            Timber.tag(TAG).w(it, "Could not connect to Meld's media session")
                        }.getOrNull()
                    } ?: throw IllegalStateException(appContext.getString(R.string.wear_bridge_unreachable))
                browser = connected
                return connected
            }
        }

        /**
         * The playback connection. Separate from [requireBrowser] on purpose: a browser's player
         * commands are whatever the session decided to grant, and every write here (queue, play or
         * pause, seek, volume) needs commands a `MediaController` is granted by default.
         */
        private suspend fun requireController(): MediaController {
            keepWarm()
            connectMutex.withLock {
                controller?.takeIf { it.isConnected }?.let { return it }
                val connected =
                    withContext(Dispatchers.Main) {
                        runCatching {
                            val token = SessionToken(appContext, ComponentName(appContext, MusicService::class.java))
                            MediaController.Builder(appContext, token).buildAsync().await()
                        }.onFailure {
                            Timber.tag(TAG).w(it, "Could not connect to Meld's media session for playback")
                        }.getOrNull()
                    } ?: throw IllegalStateException(appContext.getString(R.string.wear_bridge_unreachable))
                controller = connected
                return connected
            }
        }

        private suspend fun sendCommand(
            command: SessionCommand,
            args: Bundle,
        ) {
            val client = requireController()
            runCatching { client.sendCustomCommand(command, args).await() }
                .onFailure { Timber.tag(TAG).w(it, "Custom command '${command.customAction}' failed") }
        }

        /**
         * Holds the session open while the watch is being looked at, then lets go so a watch that
         * was merely glanced at doesn't pin Meld's service in the background forever.
         */
        private fun keepWarm() {
            idleReleaseJob?.cancel()
            idleReleaseJob =
                scope.launch {
                    delay(BROWSER_IDLE_TIMEOUT_MS)
                    connectMutex.withLock {
                        controller?.takeIf { !it.isPlaying }?.let { playing ->
                            runCatching { playing.release() }
                            controller = null
                        }
                        controller?.let { return@withLock } // still playing: keep browsing warm too
                        browser?.let { browsing ->
                            runCatching { browsing.release() }
                            browser = null
                        }
                    }
                }
        }

        private fun replyMessage(
            nodeId: String,
            payload: ByteArray,
        ) {
            runCatching {
                Wearable
                    .getMessageClient(appContext)
                    .sendMessage(nodeId, WearBridge.PATH_RESPONSE, payload)
            }.onFailure {
                Timber.tag(TAG).w(it, "Could not reply to the watch")
            }
        }

        private suspend fun snapshot(player: Player): JSONObject {
            val item = player.currentMediaItem
            val metadata = item?.mediaMetadata
            val songId = item?.mediaId?.takeIf { it.length == SONG_ID_LENGTH }
            val liked = songId?.let { await(database.song(it)) }?.song?.liked == true
            val downloaded = songId?.let { downloadUtil.downloads.value[it]?.state } == Download.STATE_COMPLETED
            val remainingSleep =
                watchSleepEndsAtMs
                    .takeIf { it > 0L }
                    ?.let { (it - SystemClock.elapsedRealtime()).coerceAtLeast(0L) }
                    ?: 0L
            if (remainingSleep == 0L) watchSleepEndsAtMs = 0L
            val (batteryPercent, charging) = batteryLevel()

            return WearPlayerState(
                hasSession = item != null,
                isPlaying = player.isPlaying,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                isEnded = player.playbackState == Player.STATE_ENDED,
                title = metadata?.title?.toString().orEmpty(),
                artist =
                    metadata?.artist?.toString().orEmpty().ifEmpty {
                        metadata?.subtitle?.toString().orEmpty()
                    },
                album = metadata?.albumTitle?.toString(),
                artworkUri = metadata?.artworkUri?.toString()?.takeIf { it.startsWith("http") },
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = player.duration.takeIf { it > 0L } ?: 0L,
                mediaId = item?.mediaId,
                shuffleEnabled = player.shuffleModeEnabled,
                repeatMode = player.repeatMode,
                liked = liked,
                downloaded = downloaded,
                queueTitle = player.playlistMetadata.title?.toString()?.takeIf { it.isNotBlank() },
                queueSize = player.currentTimeline.windowCount,
                sleepRemainingMs = remainingSleep,
                phoneVersion = BuildConfig.VERSION_NAME,
                phoneBatteryPercent = batteryPercent,
                phoneCharging = charging,
            ).toJson().put(WearBridge.KEY_VALUE, player.volume.toDouble())
        }

        /**
         * The tree's own playable/browsable flags are the source of truth, but a row with neither would
         * be a dead tap on the watch, so the media id's shape — which the service defines — fills in
         * whatever the metadata left out.
         */
        private fun MediaItem.toWearRow(index: Int = -1): WearMediaRow {
            val meta = mediaMetadata
            val id = mediaId.orEmpty()
            val path = id.split('/')
            val songId = path.last().takeIf { it.length == SONG_ID_LENGTH }
            // A song row is the one whose last path segment is a song id. `search/<query>` looks the
            // same when the query happens to be 11 characters long, so it is excluded by name.
            val isSong = songId != null && (path.size != 2 || path.first() != MusicService.SEARCH)
            val isContainer = !isSong && path.size < 3
            return WearMediaRow(
                mediaId = id,
                title = meta.title?.toString().orEmpty().ifEmpty { id },
                subtitle =
                    listOfNotNull(
                        meta.subtitle?.toString()?.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                artworkUri = meta.artworkUri?.toString()?.takeIf { it.startsWith("http") },
                kind =
                    when {
                        isSong -> WearBridge.KIND_SONG
                        meta.mediaType == MediaMetadata.MEDIA_TYPE_ALBUM -> WearBridge.KIND_ALBUM
                        meta.mediaType == MediaMetadata.MEDIA_TYPE_ARTIST -> WearBridge.KIND_ARTIST
                        meta.mediaType == MediaMetadata.MEDIA_TYPE_PLAYLIST -> WearBridge.KIND_PLAYLIST
                        else -> WearBridge.KIND_FOLDER
                    },
                playable = meta.isPlayable == true || isSong,
                browsable = meta.isBrowsable == true || isContainer,
                downloaded = songId?.let { downloadUtil.downloads.value[it]?.state == Download.STATE_COMPLETED } == true,
                index = index,
            )
        }

        private fun batteryLevel(): Pair<Int, Boolean> {
            val status =
                runCatching {
                    appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                }.getOrNull() ?: return -1 to false
            val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val percent = if (level >= 0) level * 100 / scale else -1
            val charging =
                status.getIntExtra(BatteryManager.EXTRA_STATUS, -1) in
                    listOf(
                        BatteryManager.BATTERY_STATUS_CHARGING,
                        BatteryManager.BATTERY_STATUS_FULL,
                    )
            return percent to charging
        }

        /** DB reads on the watch's behalf must never be what makes the watch time out. */
        private suspend fun <T> await(flow: Flow<T?>): T? = withTimeoutOrNull(DB_TIMEOUT_MS) { flow.first() }

        private companion object {
            const val TAG = "WearPhoneBridge"
            const val DISPATCH_TIMEOUT_MS = 25_000L
            const val DB_TIMEOUT_MS = 1_200L
            const val BROWSER_IDLE_TIMEOUT_MS = 90_000L
            const val COMMAND_SETTLE_MS = 120L
            const val PLAY_ACCEPT_TIMEOUT_MS = 12_000L
            const val ONLINE_SEARCH_TIMEOUT_MS = 6_000L
            const val MAX_LIST_ITEMS = 80
            const val SEARCH_SONG_LIMIT = 40
            const val SEARCH_CONTAINER_LIMIT = 6
            const val SKIP_STEP_MS = 10_000L
            const val REWIND_THRESHOLD_MS = 3_000L
            const val SONG_ID_LENGTH = 11
            const val MIN_SLEEP_MINUTES = 1
            const val MAX_SLEEP_MINUTES = 240
            const val SLEEP_AT_END_OF_SONG = -1
            const val SHUFFLE_ACTION = "__shuffle__"
        }
    }
