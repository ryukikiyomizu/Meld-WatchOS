/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.metrolist.music.utils.LinkSender
import timber.log.Timber

/**
 * Phone-side counterpart of [LinkSender]: when the watch uses "Send link to
 * phone", this service receives the message and places the link on the
 * phone's clipboard.
 */
class LinkReceiverService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != LinkSender.PATH_COPY_LINK) return
        val text = String(event.data)
        Timber.d("LinkReceiverService: received link from watch")
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Meld link", text))
    }
}
