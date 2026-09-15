package com.metrolist.music.playback

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Bridge between the wearable remote-control protocol and the local player.
 * Phone side: [playerConnection] is set by MainActivity and used to execute
 * commands arriving from the watch. Watch side: [remoteState] mirrors the
 * phone's playback state pushed over the wearable channel.
 */
object PlaybackRemote {
    var playerConnection: PlayerConnection? = null

    data class State(
        val id: String,
        val title: String,
        val artists: String,
        val artUrl: String?,
        val isPlaying: Boolean,
        val position: Long,
        val duration: Long,
    )

    val remoteState = MutableStateFlow<State?>(null)
}
