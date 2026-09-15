package com.metrolist.music.playback

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.metrolist.music.constants.AudioOutputKey
import com.metrolist.music.constants.MinimalModeKey
import com.metrolist.music.extensions.togglePlayPause
import com.metrolist.music.utils.LinkSender
import com.metrolist.music.utils.dataStore
import kotlinx.coroutines.runBlocking
import androidx.datastore.preferences.core.edit
import org.json.JSONObject
import timber.log.Timber

/**
 * Wearable endpoint for Minimal mode: syncs the mode flag both ways, executes
 * playback commands on the phone and mirrors playback state onto the watch.
 */
class RemoteControlService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            LinkSender.PATH_MINIMAL -> {
                val on = String(event.data) == "1"
                runBlocking { dataStore.edit { it[MinimalModeKey] = on } }
                Timber.d("RemoteControlService: minimal mode -> $on")
                refreshTile()
            }
            LinkSender.PATH_PLAYBACK -> handleCommand(String(event.data))
            LinkSender.PATH_PLAYBACK_STATE -> handleState(String(event.data))
            LinkSender.PATH_OUTPUT -> handleOutput(String(event.data))
            else -> return
        }
    }

    private fun handleOutput(output: String) {
        runBlocking { dataStore.edit { it[AudioOutputKey] = output } }
        Timber.d("RemoteControlService: audio output -> $output")
        val connection = PlaybackRemote.playerConnection
        AudioStreamPump.sync(applicationContext, connection?.player, connection?.mediaMetadata?.value?.id)
        refreshTile()
    }

    private fun handleCommand(cmd: String) {
        val connection = PlaybackRemote.playerConnection ?: return
        val player = connection.player
        when (cmd) {
            "toggle" -> player.togglePlayPause()
            "play" -> player.play()
            "pause" -> player.pause()
            "next" -> player.seekToNext()
            "prev" -> player.seekToPrevious()
        }
        pushState()
        refreshTile()
    }

    fun pushState() {
        val connection = PlaybackRemote.playerConnection ?: return
        val meta = connection.mediaMetadata.value
        val player = connection.player
        val json =
            JSONObject()
                .put("id", meta?.id ?: "")
                .put("title", meta?.title ?: "")
                .put("artists", meta?.artists?.joinToString(", ") ?: "")
                .put("art", meta?.thumbnailUrl ?: JSONObject.NULL)
                .put("playing", player.isPlaying)
                .put("position", player.currentPosition)
                .put("duration", if (player.duration == androidx.media3.common.C.TIME_UNSET) 0L else player.duration)
        runBlocking {
            LinkSender.send(applicationContext, LinkSender.PATH_PLAYBACK_STATE, json.toString())
        }
    }

    private fun handleState(data: String) {
        try {
            val json = JSONObject(data)
            val trackId = json.optString("id")
            val previousId = PlaybackRemote.remoteState.value?.id
            PlaybackRemote.remoteState.value =
                PlaybackRemote.State(
                    id = trackId,
                    title = json.optString("title"),
                    artists = json.optString("artists"),
                    artUrl = json.optString("art", "").takeIf { it.isNotBlank() },
                    isPlaying = json.optBoolean("playing"),
                    position = json.optLong("position"),
                    duration = json.optLong("duration"),
                )
            if (trackId.isNotEmpty() && trackId != previousId) {
                WatchStreamPlayer.restartSource(applicationContext, trackId)
            }
            refreshTile()
        } catch (e: Exception) {
            Timber.w(e, "RemoteControlService: bad state payload")
        }
    }

    private fun refreshTile() {
        try {
            androidx.wear.tiles.TileService
                .getUpdater(this)
                .requestUpdate(com.metrolist.music.ui.widget.PlayerTileService::class.java)
        } catch (_: Exception) {
        }
    }
}
