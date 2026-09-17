/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import timber.log.Timber

/**
 * Watch side endpoint for the Minimal-mode audio stream: receives the channel
 * opened by the phone and hands its byte stream to [WatchStreamPlayer].
 */
class AudioStreamReceiverService : WearableListenerService() {
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != AudioStreamPump.CHANNEL_PATH) return
        try {
            val input = Tasks.await(Wearable.getChannelClient(this).getInputStream(channel))
            StreamBridge.audioInput = input
            WatchStreamPlayer.start(applicationContext)
            Timber.d("AudioStreamReceiver: channel opened, stream player started")
        } catch (e: Exception) {
            Timber.w(e, "AudioStreamReceiver: open failed")
        }
    }

    override fun onChannelClosed(
        channel: ChannelClient.Channel,
        closeReason: Int,
        appSpecificErrorCode: Int,
    ) {
        if (channel.path != AudioStreamPump.CHANNEL_PATH) return
        Timber.d("AudioStreamReceiver: channel closed ($closeReason)")
    }
}
