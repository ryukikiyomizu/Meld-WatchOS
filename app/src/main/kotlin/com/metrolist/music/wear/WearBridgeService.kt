/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.wear

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.metrolist.music.bridge.WearBridge
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import timber.log.Timber

/**
 * Entry point for the Wear OS companion on the phone side.
 *
 * Started by Play Services only when the watch actually sends something on
 * [WearBridge.PATH_REQUEST], so an unworn app costs nothing. All real work — and everything that
 * could throw — lives in [WearPhoneBridge], which answers with a JSON error envelope instead of
 * crashing the service.
 */
@AndroidEntryPoint
class WearBridgeService : WearableListenerService() {
    @Inject
    lateinit var bridge: WearPhoneBridge

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearBridge.PATH_REQUEST) {
            super.onMessageReceived(event)
            return
        }
        Timber.tag(TAG).d("Watch request on %s (%d bytes)", event.path, event.data.size)
        bridge.onRequest(event.sourceNodeId, event.data)
    }

    private companion object {
        const val TAG = "WearBridgeService"
    }
}
