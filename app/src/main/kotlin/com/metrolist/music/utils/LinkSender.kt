/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import timber.log.Timber

/**
 * Sends a song link from the watch to the paired phone over the Wearable
 * message API. The phone running the same app receives it in
 * [com.metrolist.music.playback.LinkReceiverService] and copies it to the
 * phone's clipboard.
 */
object LinkSender {
    const val PATH_COPY_LINK = "/meld/copy-link"
    const val PATH_SESSION = "/meld/session"

    /**
     * @return number of connected nodes the link was delivered to
     *         (0 when no paired device or Play services are unavailable).
     */
    suspend fun sendToPhone(
        context: Context,
        text: String,
    ): Int = send(context, PATH_COPY_LINK, text)

    /**
     * Sends [text] to every connected node over the Wearable message API
     * (Bluetooth-preferred transport between a paired watch and phone).
     */
    suspend fun send(
        context: Context,
        path: String,
        text: String,
    ): Int =
        try {
            val availability =
                GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
            if (availability != ConnectionResult.SUCCESS) {
                Timber.d("LinkSender: Play services unavailable ($availability)")
                return 0
            }
            val nodeClient = Wearable.getNodeClient(context)
            val nodes = Tasks.await(nodeClient.connectedNodes)
            val messageClient = Wearable.getMessageClient(context)
            var sent = 0
            for (node in nodes) {
                try {
                    Tasks.await(
                        messageClient.sendMessage(node.id, path, text.toByteArray()),
                    )
                    sent++
                } catch (e: Exception) {
                    Timber.w(e, "LinkSender: failed to reach node ${node.displayName}")
                }
            }
            sent
        } catch (e: Exception) {
            Timber.w(e, "LinkSender: send failed")
            0
        }
}
