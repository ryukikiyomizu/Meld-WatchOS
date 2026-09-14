/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.wear.bridge

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives replies (and any future push) from the phone and hands them to [MeldWear].
 *
 * Play Services starts this service on its own, which is why it may run with the activity long
 * gone — hence the idempotent [MeldWear.init] here.
 */
class WearBridgeListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        MeldWear.init(applicationContext)
        MeldWear.onResponse(event)
    }

    /**
     * No `onPeerAdded`/`onPeerRemoved` overrides: those NodeApi peer callbacks are legacy and have
     * been on a removal path for years. A vanished phone is noticed instead by the next failed send
     * ([MeldWear.call] drops its cached node on any transport error) and by the refresh that runs
     * every time the UI resumes.
     */
}
