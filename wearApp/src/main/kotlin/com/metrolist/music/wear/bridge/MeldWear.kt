/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.wear.bridge

import android.content.Context
import android.os.SystemClock
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.metrolist.music.bridge.WearBridge
import com.metrolist.music.bridge.WearMediaRow
import com.metrolist.music.bridge.WearPlayerState
import com.metrolist.music.wear.R
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** What the watch thinks of the link to the phone, so the UI can explain itself. */
sealed interface WearLink {
    data object Checking : WearLink

    data class Linked(
        val nodeId: String,
        val device: String?,
    ) : WearLink

    /** Nothing paired is reachable — Bluetooth off, phone asleep out of range, watch on its own. */
    data object NoPhone : WearLink

    /** A phone is linked, but Meld did not answer: not installed, or stopped by battery optimisation. */
    data object SilentPhone : WearLink
}

/** A single row of the browse tree, plus how to act on it. */
typealias WearRows = List<WearMediaRow>

/**
 * The watch's only door to the phone.
 *
 * Process-wide singleton rather than a DI graph: the Wear module is a thin remote control, and one
 * object holding a couple of state flows keeps the whole module free of a framework. Requests are
 * fire-and-correlate — a message goes out with a UUID, the reply comes back through
 * [WearBridgeListenerService] and is matched by that id, because the Data Layer has no
 * request/response primitive.
 *
 * Nothing here polls on a timer. Callers ([refresh], [snapshot]) ask when the UI is visible and
 * stop when it is not, so a watch in a pocket sends zero bytes.
 */
object MeldWear {
    private const val DEFAULT_TIMEOUT_MS = 6_000L
    private const val SEARCH_TIMEOUT_MS = 25_000L

    /**
     * Starting playback can cost the phone a library scan plus a YouTube lookup before the queue
     * exists, so this is deliberately longer than a plain round trip.
     */
    private const val PLAY_TIMEOUT_MS = 20_000L

    private var appContext: Context? = null
    private var cachedNodeId: String? = null

    private val pending = ConcurrentHashMap<String, CompletableDeferred<WearReply>>()

    private val _state = MutableStateFlow(WearPlayerState.Empty)
    val state: StateFlow<WearPlayerState> = _state

    /**
     * When [state] was last read from the phone. The UI interpolates the playback position from here
     * so the ring can glide at 60Hz while the phone is only asked every couple of seconds.
     */
    private val _stateAt = MutableStateFlow(0L)
    val stateAt: StateFlow<Long> = _stateAt

    private val _link = MutableStateFlow<WearLink>(WearLink.Checking)
    val link: StateFlow<WearLink> = _link

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    // --- Requests ------------------------------------------------------------------------------

    /** Pulls playback state. Returns false when the phone did not answer. */
    suspend fun refresh(): Boolean {
        val data = call(WearBridge.ACTION_STATE, null, DEFAULT_TIMEOUT_MS) ?: return false
        _state.value = WearPlayerState.fromJson(data)
        _stateAt.value = SystemClock.elapsedRealtime()
        return true
    }

    /** Non-mutating read used before a screen first paints, without touching the shared state. */
    suspend fun snapshot(): WearPlayerState? =
        call(WearBridge.ACTION_STATE, null, DEFAULT_TIMEOUT_MS)?.let { WearPlayerState.fromJson(it) }

    suspend fun transport(
        op: String,
        extra: JSONObject? = null,
    ): Boolean = mutate(WearBridge.ACTION_TRANSPORT, WearBridge.KEY_OP, op, extra)

    suspend fun toggle(op: String): Boolean = mutate(WearBridge.ACTION_TOGGLE, WearBridge.KEY_OP, op, null)

    private suspend fun mutate(
        action: String,
        key: String,
        value: String,
        extra: JSONObject?,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Boolean {
        val args = JSONObject().put(key, value)
        extra?.let { payload ->
            val keys = payload.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                args.put(name, payload.get(name))
            }
        }
        // Optimistically mark the player busy: the round trip plus the phone's own state read is
        // ~200ms, and without this the play/pause button looks dead for a fifth of a second.
        _working.value = true
        val data = call(action, args, timeoutMs)
        _working.value = false
        if (data == null) return false
        _state.value = WearPlayerState.fromJson(data)
        _stateAt.value = SystemClock.elapsedRealtime()
        return true
    }

    suspend fun browse(parentId: String): WearRows? {
        val data = call(WearBridge.ACTION_BROWSE, JSONObject().put(WearBridge.KEY_PARENT_ID, parentId)) ?: return null
        return WearBridge.itemsFrom(data)
    }

    suspend fun search(query: String): WearRows? {
        val data =
            call(
                WearBridge.ACTION_SEARCH,
                JSONObject().put(WearBridge.KEY_QUERY, query),
                SEARCH_TIMEOUT_MS,
            ) ?: return null
        return WearBridge.itemsFrom(data)
    }

    suspend fun play(mediaId: String): Boolean =
        mutate(WearBridge.ACTION_PLAY, WearBridge.KEY_MEDIA_ID, mediaId, null, PLAY_TIMEOUT_MS)

    suspend fun playContainer(
        parentId: String,
        shuffle: Boolean,
    ): Boolean {
        val args =
            JSONObject()
                .put(WearBridge.KEY_PARENT_ID, parentId)
                .put(WearBridge.KEY_SHUFFLE, shuffle)
        _working.value = true
        val ok = call(WearBridge.ACTION_PLAY_CONTAINER, args, PLAY_TIMEOUT_MS) != null
        _working.value = false
        return ok
    }

    suspend fun queue(): Pair<Int, WearRows>? {
        val data = call(WearBridge.ACTION_QUEUE, null) ?: return null
        return data.optInt(WearBridge.KEY_CURRENT_INDEX, -1) to WearBridge.itemsFrom(data)
    }

    suspend fun queueOp(
        op: String,
        index: Int,
    ): Boolean {
        val args =
            JSONObject()
                .put(WearBridge.KEY_OP, op)
                .put(WearBridge.KEY_INDEX, index)
        _working.value = true
        val data = call(WearBridge.ACTION_QUEUE_OP, args)
        _working.value = false
        if (data == null) return false
        _state.value = WearPlayerState.fromJson(data)
        _stateAt.value = SystemClock.elapsedRealtime()
        return true
    }

    /** `minutes = -1` asks the phone to stop when the current song ends. */
    suspend fun setSleepTimer(minutes: Int): Boolean {
        val args =
            JSONObject()
                .put(WearBridge.KEY_OP, WearBridge.OP_SET)
                .put(WearBridge.KEY_MINUTES, minutes)
        return call(WearBridge.ACTION_SLEEP, args) != null
    }

    suspend fun clearSleepTimer(): Boolean =
        call(
            WearBridge.ACTION_SLEEP,
            JSONObject().put(WearBridge.KEY_OP, WearBridge.OP_CLEAR),
        ) != null

    suspend fun setVolume(value: Float): Boolean =
        call(
            WearBridge.ACTION_VOLUME,
            JSONObject().put(WearBridge.KEY_VALUE, value.toDouble()),
        ) != null

    suspend fun download(
        songId: String,
        add: Boolean,
    ): Boolean {
        val args =
            JSONObject()
                .put(WearBridge.KEY_OP, if (add) WearBridge.OP_ADD else WearBridge.OP_REMOVE)
                .put(WearBridge.KEY_SONG_ID, songId)
        return call(WearBridge.ACTION_DOWNLOAD, args) != null
    }

    /** Forces node re-discovery, e.g. after the user taps "try again" on the empty state. */
    suspend fun reconnect() {
        cachedNodeId = null
        _link.value = WearLink.Checking
        _lastError.value = null
        if (call(WearBridge.ACTION_PING, null, DEFAULT_TIMEOUT_MS) != null) {
            refresh()
        }
    }

    // --- Transport -----------------------------------------------------------------------------

    private suspend fun call(
        action: String,
        args: JSONObject?,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): JSONObject? {
        val context = appContext ?: return null
        val nodeId = resolveNode(context) ?: return null
        val requestId = UUID.randomUUID().toString()
        val reply = CompletableDeferred<WearReply>()
        pending[requestId] = reply

        val sent =
            withContext(Dispatchers.IO) {
                runCatching {
                    sendMessage(context, nodeId, WearBridge.buildRequest(requestId, action, args))
                }.getOrDefault(false)
            }
        if (!sent) {
            pending.remove(requestId)
            cachedNodeId = null
            _link.value = WearLink.SilentPhone
            _lastError.value = text(R.string.link_send_failed)
            return null
        }

        val result = withTimeoutOrNull(timeoutMs) { reply.await() }
        pending.remove(requestId)
        return when {
            result == null -> {
                // The node is connected but nothing answered: Meld is not installed on the phone,
                // or the OEM put its background work to sleep. Both need different advice, so the
                // UI distinguishes them.
                cachedNodeId = null
                _link.value = WearLink.SilentPhone
                _lastError.value = text(R.string.link_silent_phone)
                null
            }

            result.ok -> {
                _link.value = WearLink.Linked(nodeId, null)
                _lastError.value = null
                result.data
            }

            else -> {
                _link.value = WearLink.Linked(nodeId, null)
                _lastError.value = result.message
                null
            }
        }
    }

    private suspend fun sendMessage(
        context: Context,
        nodeId: String,
        payload: ByteArray,
    ): Boolean =
        suspendCancellableCoroutine { continuation ->
            Wearable
                .getMessageClient(context)
                .sendMessage(nodeId, WearBridge.PATH_REQUEST, payload)
                .addOnSuccessListener { if (continuation.isActive) continuation.resume(true) }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume(false)
                }
        }

    private suspend fun resolveNode(context: Context): String? {
        cachedNodeId?.let { return it }
        val nodes =
            withContext(Dispatchers.IO) {
                runCatching { connectedNodes(context) }.getOrDefault(emptyList())
            }
        // Prefer a nearby node; a watch paired over Wi-Fi still lists the phone, just not nearby.
        val node = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
        return if (node == null) {
            _link.value = WearLink.NoPhone
            _lastError.value = text(R.string.link_no_phone)
            null
        } else {
            cachedNodeId = node.id
            _link.value = WearLink.Linked(node.id, node.displayName)
            node.id
        }
    }

    private suspend fun connectedNodes(context: Context): List<Node> =
        suspendCancellableCoroutine { continuation ->
            Wearable
                .getNodeClient(context)
                .connectedNodes
                .addOnSuccessListener { nodes -> if (continuation.isActive) continuation.resume(nodes ?: emptyList()) }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume(emptyList())
                }
        }

    /** Called by [WearBridgeListenerService] on a binder thread. */
    fun onResponse(event: MessageEvent) {
        if (event.path != WearBridge.PATH_RESPONSE) return
        val json = runCatching { JSONObject(String(event.data)) }.getOrNull() ?: return
        val requestId = json.optString(WearBridge.KEY_ID, "").takeIf { it.isNotEmpty() } ?: return
        val deferred = pending.remove(requestId) ?: return
        val ok = json.optBoolean(WearBridge.KEY_OK, false)
        val data =
            if (ok) {
                json.optJSONObject(WearBridge.KEY_DATA) ?: JSONObject()
            } else {
                JSONObject()
            }
        deferred.complete(
            WearReply(
                ok = ok,
                data = data,
                message = if (ok) null else json.optString(WearBridge.KEY_ERROR).ifBlank { text(R.string.request_rejected) },
            ),
        )
    }

    /** Called when the peer set changes so a vanished phone is noticed without waiting for a timeout. */
    fun invalidateNode() {
        cachedNodeId = null
    }

    private fun text(resId: Int): String = appContext?.getString(resId).orEmpty()

    private data class WearReply(
        val ok: Boolean,
        val data: JSONObject,
        val message: String?,
    )
}
