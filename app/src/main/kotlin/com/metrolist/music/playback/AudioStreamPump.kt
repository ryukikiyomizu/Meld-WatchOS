/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context
import android.net.ConnectivityManager
import androidx.media3.common.Player
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Channel
import com.google.android.gms.wearable.Wearable
import com.metrolist.music.constants.AudioOutputKey
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.constants.AudioQualityKey
import com.metrolist.music.constants.MinimalModeKey
import com.metrolist.music.utils.YTPlayerUtils
import com.metrolist.music.utils.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Phone side of Minimal-mode "Audio Output = Watch": resolves the current
 * song's audio stream and pumps the raw bytes to the connected watch over a
 * Wearable Channel (BT-preferred byte stream). The watch plays whatever it
 * has buffered, so playback survives short link blips.
 *
 * While pumping, the phone player is muted: the phone stays the controller,
 * the watch is the speaker.
 */
object AudioStreamPump {
    const val CHANNEL_PATH = "/meld/audio"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pumpJob: Job? = null
    private var channel: Channel? = null
    private var activeVideoId: String? = null
    private var playerListener: Player.Listener? = null

    suspend fun shouldStream(context: Context): Boolean {
        val prefs = context.dataStore.data.first()
        val minimal = prefs[MinimalModeKey] ?: false
        val output = prefs[AudioOutputKey] ?: "watch"
        return minimal && output == "watch"
    }

    /** Align the pump with the queue: starts, restarts or stops as needed. */
    fun sync(
        context: Context,
        player: Player?,
        videoId: String?,
    ) {
        scope.launch {
            if (player == null || videoId == null || !shouldStream(context)) {
                stop(context, player)
                return@launch
            }
            if (activeVideoId == videoId && pumpJob?.isActive == true) return@launch
            restart(context, player, videoId)
        }
    }

    /** Phone paused: stop feeding bytes (watch keeps draining its buffer). */
    fun pause() {
        pumpJob?.cancel()
    }

    fun stop(
        context: Context,
        player: Player?,
    ) {
        pumpJob?.cancel()
        pumpJob = null
        closeChannel(context)
        player?.volume = 1f
        playerListener?.let { player?.removeListener(it) }
        playerListener = null
        activeVideoId = null
    }

    private suspend fun restart(
        context: Context,
        player: Player,
        videoId: String,
    ) {
        pumpJob?.cancel()
        closeChannel(context)
        activeVideoId = videoId
        player.volume = 0f
        ensurePlayPauseListener(context, player)

        val quality =
            try {
                val raw = context.dataStore.data.first()[AudioQualityKey] ?: "AUTO"
                AudioQuality.valueOf(raw)
            } catch (e: Exception) {
                AudioQuality.AUTO
            }

        val streamUrl =
            try {
                YTPlayerUtils
                    .playerResponseForPlayback(
                        videoId,
                        audioQuality = quality,
                        connectivityManager =
                            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager,
                    ).getOrNull()
                    ?.streamUrl
            } catch (e: Exception) {
                Timber.w(e, "AudioStreamPump: resolve failed")
                null
            }
        if (streamUrl == null) return

        val node =
            try {
                Tasks.await(Wearable.getNodeClient(context).connectedNodes).firstOrNull()
            } catch (e: Exception) {
                Timber.w(e, "AudioStreamPump: no nodes")
                null
            }
        if (node == null) return

        val opened =
            try {
                Tasks.await(Wearable.getChannelClient(context).openChannel(node.id, CHANNEL_PATH))
            } catch (e: Exception) {
                Timber.w(e, "AudioStreamPump: openChannel failed")
                null
            }
        if (opened == null) return
        channel = opened

        pumpJob =
            scope.launch {
                try {
                    val out = Tasks.await(Wearable.getChannelClient(context).getOutputStream(opened))
                    val connection = java.net.URL(streamUrl).openConnection()
                    connection.connectTimeout = 5000
                    connection.readTimeout = 20000
                    connection.getInputStream().use { input ->
                        out.use { output ->
                            input.copyTo(output, bufferSize = 64 * 1024)
                        }
                    }
                    Timber.d("AudioStreamPump: stream finished")
                } catch (e: Exception) {
                    Timber.w(e, "AudioStreamPump: pump ended")
                }
            }
    }

    /** Resume pumping when the phone player starts playing again. */
    private fun ensurePlayPauseListener(
        context: Context,
        player: Player,
    ) {
        if (playerListener != null) return
        playerListener =
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    val id = activeVideoId ?: return
                    if (isPlaying) {
                        sync(context, player, id)
                    } else {
                        pause()
                    }
                }
            }
        player.addListener(playerListener!!)
    }

    private fun closeChannel(context: Context) {
        channel?.let {
            try {
                Tasks.await(Wearable.getChannelClient(context).close(it))
            } catch (_: Exception) {
            }
        }
        channel = null
    }
}
