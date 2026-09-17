/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.metrolist.music.utils.LinkSender
import com.metrolist.music.utils.SessionTransfer
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/**
 * Receives a session transfer pushed from the paired device (e.g. the phone
 * app's "Send to watch") over the Wearable message API - Bluetooth-preferred,
 * so it works without internet. The payload is applied immediately and the
 * app restarts into the signed-in state.
 */
class SessionReceiverService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != LinkSender.PATH_SESSION) return
        val text = String(event.data)
        val parsed = SessionTransfer.parsePayload(text)
        if (parsed == null) {
            Timber.w("SessionReceiverService: invalid session payload ignored")
            return
        }
        Timber.d("SessionReceiverService: applying session from ${event.sourceNodeId}")
        try {
            runBlocking { SessionTransfer.apply(applicationContext, parsed) }
        } catch (e: Exception) {
            Timber.e(e, "SessionReceiverService: failed to apply pushed session")
        }
    }
}
