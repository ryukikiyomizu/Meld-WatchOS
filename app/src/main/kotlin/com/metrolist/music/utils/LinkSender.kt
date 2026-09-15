/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.wearable.Node
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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

    /** Outcome of a wearable send: how many nodes run the app vs. deliveries. */
    data class SendResult(
        val nodesFound: Int,
        val delivered: Int,
        val wearableReady: Boolean,
    )

    /**
     * Sends [text] to every connected node over the Wearable message API
     * (Bluetooth-preferred transport between a paired watch and phone).
     * The receiving device must have the same app installed (the
     * WearableListenerServices are part of the app, not of Wear OS).
     */
    suspend fun send(
        context: Context,
        path: String,
        text: String,
    ): SendResult =
        withContext(Dispatchers.IO) {
            try {
                val availability =
                    GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
                if (availability != ConnectionResult.SUCCESS) {
                    Timber.d("LinkSender: Play services unavailable ($availability)")
                    return@withContext SendResult(0, 0, false)
                }
                val nodeClient = Wearable.getNodeClient(context)
                val messageClient = Wearable.getMessageClient(context)

                // The local node proves the wearable channel itself is alive.
                val localNode =
                    try {
                        Tasks.await(nodeClient.localNode)
                    } catch (e: Exception) {
                        Timber.w(e, "LinkSender: local node unavailable")
                        null
                    }
                if (localNode == null) return@withContext SendResult(0, 0, false)

                // Peer list can lag a few seconds after pairing — poll briefly.
                var nodes: List<Node> = emptyList()
                repeat(4) {
                    if (nodes.isNotEmpty()) return@repeat
                    nodes =
                        try {
                            Tasks.await(nodeClient.connectedNodes)
                        } catch (e: Exception) {
                            Timber.w(e, "LinkSender: connectedNodes failed")
                            emptyList()
                        }
                    if (nodes.isEmpty()) delay(1500)
                }
                if (nodes.isEmpty()) return@withContext SendResult(0, 0, true)

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
                SendResult(nodes.size, sent, true)
            } catch (e: Exception) {
                Timber.w(e, "LinkSender: send failed")
                SendResult(0, 0, false)
            }
        }
}
