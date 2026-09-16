/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.flow.MutableStateFlow
import timber.log.Timber
import java.io.IOException
import java.io.InputStream

/**
 * Holds the audio bytes arriving from the phone over the Wearable Channel.
 */
object StreamBridge {
    @Volatile
    var audioInput: InputStream? = null
}

/**
 * Watch side of Minimal-mode "Audio Output = Watch": a small dedicated player
 * that drinks the byte stream pumped by the phone. Controls (play/pause) are
 * local; next/prev still go to the phone, which restarts the pump and pushes
 * new state (with the new track id), at which point [restartSource] is called.
 */
object WatchStreamPlayer {
    private const val STREAM_URI = "meldstream://live"

    private var player: ExoPlayer? = null
    private var sessionTrackId: String? = null

    val isPlayingFlow = MutableStateFlow(false)

    private fun mediaSource(context: Context) =
        ProgressiveMediaSource
            .Factory(ChannelDataSource.Factory())
            .createMediaSource(MediaItem.fromUri(Uri.parse(STREAM_URI)))

    fun start(context: Context) {
        if (player != null) return
        try {
            val exo =
                ExoPlayer
                    .Builder(context)
                    .build()
            exo.addListener(
                object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        isPlayingFlow.value = isPlaying
                    }
                },
            )
            exo.setMediaSource(mediaSource(context))
            exo.prepare()
            exo.play()
            player = exo
            Timber.d("WatchStreamPlayer: started")
        } catch (e: Exception) {
            Timber.w(e, "WatchStreamPlayer: start failed")
        }
    }

    /** A new track started on the phone: re-parse the stream from here on. */
    fun restartSource(
        context: Context,
        trackId: String,
    ) {
        if (sessionTrackId == trackId) return
        sessionTrackId = trackId
        val exo = player ?: return
        try {
            exo.setMediaSource(mediaSource(context))
            exo.prepare()
            exo.play()
        } catch (e: Exception) {
            Timber.w(e, "WatchStreamPlayer: restart failed")
        }
    }

    fun toggle() {
        val exo = player ?: return
        if (exo.isPlaying) exo.pause() else exo.play()
    }

    fun stop() {
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        sessionTrackId = null
        isPlayingFlow.value = false
    }

    /**
     * Reads whatever bytes the phone is currently pumping. Not seekable; the
     * stream is live.
     */
    private class ChannelDataSource : DataSource {
        private var input: InputStream? = null

        override fun open(dataSpec: DataSpec): Long {
            input =
                StreamBridge.audioInput
                    ?: throw IOException("WatchStreamPlayer: no stream from phone yet")
            return C.LENGTH_UNSET.toLong()
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            val stream = input ?: return C.RESULT_END_OF_INPUT
            return try {
                val read = stream.read(buffer, offset, length)
                if (read == -1) C.RESULT_END_OF_INPUT else read
            } catch (e: Exception) {
                Timber.w(e, "WatchStreamPlayer: stream read ended")
                C.RESULT_END_OF_INPUT
            }
        }

        override fun close() {
            input = null
        }

        override fun getUri(): Uri = Uri.parse(STREAM_URI)

        class Factory : DataSource.Factory {
            override fun createDataSource(): DataSource = ChannelDataSource()
        }
    }
}
