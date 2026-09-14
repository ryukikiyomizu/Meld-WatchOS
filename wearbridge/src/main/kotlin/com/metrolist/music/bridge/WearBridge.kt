/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.bridge

import org.json.JSONArray
import org.json.JSONObject

/**
 * Wire protocol for the phone <-> watch companion bridge.
 *
 * This file is compiled into **both** `:app` (the responder) and `:wearApp` (the UI). It is the
 * single source of truth for message paths, action names and payload keys, which is why both
 * modules share this source directory instead of keeping two copies in sync by hand: a key rename
 * here updates both sides at once, whereas duplicated constants drift silently.
 *
 * Transport is Google's Wearable Data Layer. The watch sends a request message, the phone answers
 * with a response correlated by [KEY_ID]. The watch only asks while it is in the foreground, so an
 * idle watch costs the phone (and both batteries) nothing.
 *
 * Everything crossing this bridge is audio. The phone drops video-backed entries from browse and
 * search before they reach the watch, so the protocol has no video media type and the watch UI has
 * no video surface.
 */
object WearBridge {
    const val PROTOCOL_VERSION = 1

    const val PATH_REQUEST = "/meld/companion/request"
    const val PATH_RESPONSE = "/meld/companion/response"

    // --- Message envelope ----------------------------------------------------------------------
    const val KEY_ID = "id"
    const val KEY_VERSION = "v"
    const val KEY_ACTION = "action"
    const val KEY_ARGS = "args"
    const val KEY_OK = "ok"
    const val KEY_DATA = "data"
    const val KEY_ERROR = "error"
    const val KEY_CODE = "code"

    /** Thrown by either side when the peer speaks a protocol version it cannot serve. */
    const val CODE_UNSUPPORTED_VERSION = "unsupported_version"

    // --- Actions -------------------------------------------------------------------------------
    const val ACTION_PING = "ping"
    const val ACTION_STATE = "state"
    const val ACTION_TRANSPORT = "transport"
    const val ACTION_TOGGLE = "toggle"
    const val ACTION_QUEUE = "queue"
    const val ACTION_QUEUE_OP = "queue_op"
    const val ACTION_BROWSE = "browse"
    const val ACTION_SEARCH = "search"
    const val ACTION_PLAY = "play"
    const val ACTION_PLAY_CONTAINER = "play_container"
    const val ACTION_SLEEP = "sleep"
    const val ACTION_VOLUME = "volume"
    const val ACTION_DOWNLOAD = "download"

    // Verbs for the action-shaped payloads. Kept as strings so an older phone build can answer
    // "unsupported action" instead of dying on an enum it does not have.
    const val OP_PLAY_PAUSE = "play_pause"
    const val OP_NEXT = "next"
    const val OP_PREVIOUS = "previous"
    const val OP_SKIP_FORWARD = "skip_forward"
    const val OP_SKIP_BACK = "skip_back"
    const val OP_SEEK = "seek"

    const val OP_LIKE = "like"
    const val OP_SHUFFLE = "shuffle"
    const val OP_REPEAT = "repeat"
    const val OP_RADIO = "radio"

    const val OP_JUMP = "jump"
    const val OP_REMOVE = "remove"

    const val OP_ADD = "add"
    const val OP_SET = "set"
    const val OP_CLEAR = "clear"

    // --- Errors --------------------------------------------------------------------------------
    const val CODE_UNSUPPORTED_ACTION = "unsupported_action"
    const val CODE_PHONE_UNAVAILABLE = "phone_unavailable"
    const val CODE_NO_SESSION = "no_session"
    const val CODE_NOT_FOUND = "not_found"
    const val CODE_FAILED = "failed"

    // --- Argument keys -------------------------------------------------------------------------
    const val KEY_OP = "op"
    const val KEY_QUERY = "query"
    const val KEY_PARENT_ID = "parentId"
    const val KEY_MEDIA_ID = "mediaId"
    const val KEY_SONG_ID = "songId"
    const val KEY_INDEX = "index"
    const val KEY_SHUFFLE = "shuffle"
    const val KEY_POSITION_MS = "positionMs"
    const val KEY_DELTA_MS = "deltaMs"
    const val KEY_MINUTES = "minutes"
    const val KEY_VALUE = "value"

    // --- State keys ----------------------------------------------------------------------------
    const val KEY_HAS_SESSION = "hasSession"
    const val KEY_PLAYING = "playing"
    const val KEY_BUFFERING = "buffering"
    const val KEY_ENDED = "ended"
    const val KEY_TITLE = "title"
    const val KEY_ARTIST = "artist"
    const val KEY_ALBUM = "album"
    const val KEY_ARTWORK = "artworkUri"
    const val KEY_DURATION_MS = "durationMs"
    const val KEY_REPEAT_MODE = "repeatMode"
    const val KEY_LIKED = "liked"
    const val KEY_PROTOCOL = "protocol"
    const val KEY_DOWNLOADED = "downloaded"
    const val KEY_QUEUE_TITLE = "queueTitle"
    const val KEY_QUEUE_SIZE = "queueSize"
    const val KEY_SLEEP_REMAINING_MS = "sleepRemainingMs"
    const val KEY_PHONE_VERSION = "phoneVersion"
    const val KEY_PHONE_BATTERY = "phoneBattery"
    const val KEY_PHONE_CHARGING = "phoneCharging"

    // --- List keys -----------------------------------------------------------------------------
    const val KEY_ITEMS = "items"
    const val KEY_CURRENT_INDEX = "currentIndex"
    const val KEY_BROWSABLE = "browsable"
    const val KEY_PLAYABLE = "playable"
    const val KEY_KIND = "kind"
    const val KEY_SUBTITLE = "subtitle"

    /** Row kinds the Wear UI renders. Anything unknown falls back to a folder row. */
    const val KIND_SONG = "song"
    const val KIND_ALBUM = "album"
    const val KIND_ARTIST = "artist"
    const val KIND_PLAYLIST = "playlist"
    const val KIND_FOLDER = "folder"
    const val KIND_ACTION = "action"

    /** Root of the shared browse tree — mirrors `MusicService.ROOT`. */
    const val ROOT_ID = "root"

    /** Downloaded-songs container — mirrors `PlaylistEntity.DOWNLOADED_PLAYLIST_ID`. */
    const val DOWNLOADED_PLAYLIST_ID = "LP_DOWNLOADED"

    const val LIKED_PLAYLIST_ID = "LP_LIKED"

    fun playlistContainer(playlistId: String) = "playlist/$playlistId"

    fun downloadedContainer() = playlistContainer(DOWNLOADED_PLAYLIST_ID)

    fun likedContainer() = playlistContainer(LIKED_PLAYLIST_ID)

    fun buildRequest(
        id: String,
        action: String,
        args: JSONObject? = null,
    ): ByteArray {
        val payload =
            JSONObject()
                .put(KEY_ID, id)
                .put(KEY_VERSION, PROTOCOL_VERSION)
                .put(KEY_ACTION, action)
        if (args != null) payload.put(KEY_ARGS, args)
        return payload.toString().toByteArray()
    }

    fun buildResponse(
        requestId: String?,
        data: JSONObject,
    ): ByteArray = envelope(requestId).put(KEY_OK, true).put(KEY_DATA, data).toString().toByteArray()

    fun buildError(
        requestId: String?,
        code: String,
        message: String,
    ): ByteArray =
        envelope(requestId)
            .put(KEY_OK, false)
            .put(KEY_CODE, code)
            .put(KEY_ERROR, message)
            .toString()
            .toByteArray()

    private fun envelope(requestId: String?): JSONObject {
        val json = JSONObject().put(KEY_VERSION, PROTOCOL_VERSION)
        // A missing echo id means the response cannot be correlated, so the watch drops it.
        if (requestId != null) json.put(KEY_ID, requestId)
        return json
    }

    fun itemsJson(rows: List<WearMediaRow>): JSONArray {
        val array = JSONArray()
        rows.forEach { array.put(it.toJson()) }
        return array
    }

    fun itemsFrom(json: JSONObject): List<WearMediaRow> {
        val array = json.optJSONArray(KEY_ITEMS) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(WearMediaRow::fromJson)
        }
    }
}

/**
 * Everything a player screen needs for one frame of playback state, plus a little phone context
 * (battery, app version) so the watch can explain itself when something is stale.
 */
data class WearPlayerState(
    val hasSession: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isEnded: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String? = null,
    val artworkUri: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val mediaId: String? = null,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = WEAR_REPEAT_OFF,
    val liked: Boolean = false,
    val downloaded: Boolean = false,
    val queueTitle: String? = null,
    val queueSize: Int = 0,
    val sleepRemainingMs: Long = 0L,
    val phoneVersion: String? = null,
    val phoneBatteryPercent: Int = -1,
    val phoneCharging: Boolean = false,
) {
    val progress: Float
        get() =
            if (durationMs > 0L) {
                (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }

    val hasMedia: Boolean
        get() = hasSession && (title.isNotEmpty() || mediaId != null)

    fun toJson(): JSONObject =
        JSONObject()
            .put(WearBridge.KEY_HAS_SESSION, hasSession)
            .put(WearBridge.KEY_PLAYING, isPlaying)
            .put(WearBridge.KEY_BUFFERING, isBuffering)
            .put(WearBridge.KEY_ENDED, isEnded)
            .put(WearBridge.KEY_TITLE, title)
            .put(WearBridge.KEY_ARTIST, artist)
            .put(WearBridge.KEY_ALBUM, album)
            .put(WearBridge.KEY_ARTWORK, artworkUri)
            .put(WearBridge.KEY_POSITION_MS, positionMs)
            .put(WearBridge.KEY_DURATION_MS, durationMs)
            .put(WearBridge.KEY_MEDIA_ID, mediaId)
            .put(WearBridge.KEY_SHUFFLE, shuffleEnabled)
            .put(WearBridge.KEY_REPEAT_MODE, repeatMode)
            .put(WearBridge.KEY_LIKED, liked)
            .put(WearBridge.KEY_DOWNLOADED, downloaded)
            .put(WearBridge.KEY_QUEUE_TITLE, queueTitle)
            .put(WearBridge.KEY_QUEUE_SIZE, queueSize)
            .put(WearBridge.KEY_SLEEP_REMAINING_MS, sleepRemainingMs)
            .put(WearBridge.KEY_PHONE_VERSION, phoneVersion)
            .put(WearBridge.KEY_PHONE_BATTERY, phoneBatteryPercent)
            .put(WearBridge.KEY_PHONE_CHARGING, phoneCharging)

    companion object {
        /** Mirrors `androidx.media3.common.Player.REPEAT_MODE_*` (0 off, 1 one, 2 all). */
        const val WEAR_REPEAT_OFF = 0
        const val WEAR_REPEAT_ONE = 1
        const val WEAR_REPEAT_ALL = 2

        val Empty = WearPlayerState()

        fun fromJson(json: JSONObject): WearPlayerState =
            WearPlayerState(
                hasSession = json.optBoolean(WearBridge.KEY_HAS_SESSION, false),
                isPlaying = json.optBoolean(WearBridge.KEY_PLAYING, false),
                isBuffering = json.optBoolean(WearBridge.KEY_BUFFERING, false),
                isEnded = json.optBoolean(WearBridge.KEY_ENDED, false),
                title = json.optString(WearBridge.KEY_TITLE, ""),
                artist = json.optString(WearBridge.KEY_ARTIST, ""),
                album = json.optNullableString(WearBridge.KEY_ALBUM),
                artworkUri = json.optNullableString(WearBridge.KEY_ARTWORK),
                positionMs = json.optLong(WearBridge.KEY_POSITION_MS, 0L),
                durationMs = json.optLong(WearBridge.KEY_DURATION_MS, 0L),
                mediaId = json.optNullableString(WearBridge.KEY_MEDIA_ID),
                shuffleEnabled = json.optBoolean(WearBridge.KEY_SHUFFLE, false),
                repeatMode = json.optInt(WearBridge.KEY_REPEAT_MODE, WEAR_REPEAT_OFF),
                liked = json.optBoolean(WearBridge.KEY_LIKED, false),
                downloaded = json.optBoolean(WearBridge.KEY_DOWNLOADED, false),
                queueTitle = json.optNullableString(WearBridge.KEY_QUEUE_TITLE),
                queueSize = json.optInt(WearBridge.KEY_QUEUE_SIZE, 0),
                sleepRemainingMs = json.optLong(WearBridge.KEY_SLEEP_REMAINING_MS, 0L),
                phoneVersion = json.optNullableString(WearBridge.KEY_PHONE_VERSION),
                phoneBatteryPercent = json.optInt(WearBridge.KEY_PHONE_BATTERY, -1),
                phoneCharging = json.optBoolean(WearBridge.KEY_PHONE_CHARGING, false),
            )
    }
}

/** One tappable row in a browse / search / queue list. */
data class WearMediaRow(
    val mediaId: String,
    val title: String,
    val subtitle: String? = null,
    val artworkUri: String? = null,
    val kind: String = WearBridge.KIND_FOLDER,
    val playable: Boolean = false,
    val browsable: Boolean = false,
    val durationMs: Long = 0L,
    val downloaded: Boolean = false,
    val index: Int = -1,
) {
    /** Queue rows are addressed by index; everything else by media id. */
    val rowKey: String
        get() = if (index >= 0) "$index|$mediaId" else mediaId

    fun toJson(): JSONObject =
        JSONObject()
            .put(WearBridge.KEY_MEDIA_ID, mediaId)
            .put(WearBridge.KEY_TITLE, title)
            .put(WearBridge.KEY_SUBTITLE, subtitle)
            .put(WearBridge.KEY_ARTWORK, artworkUri)
            .put(WearBridge.KEY_KIND, kind)
            .put(WearBridge.KEY_PLAYABLE, playable)
            .put(WearBridge.KEY_BROWSABLE, browsable)
            .put(WearBridge.KEY_DURATION_MS, durationMs)
            .put(WearBridge.KEY_DOWNLOADED, downloaded)
            .put(WearBridge.KEY_INDEX, index)

    companion object {
        fun fromJson(json: JSONObject): WearMediaRow =
            WearMediaRow(
                mediaId = json.optString(WearBridge.KEY_MEDIA_ID, ""),
                title = json.optString(WearBridge.KEY_TITLE, ""),
                subtitle = json.optNullableString(WearBridge.KEY_SUBTITLE),
                artworkUri = json.optNullableString(WearBridge.KEY_ARTWORK),
                kind = json.optString(WearBridge.KEY_KIND, WearBridge.KIND_FOLDER),
                playable = json.optBoolean(WearBridge.KEY_PLAYABLE, false),
                browsable = json.optBoolean(WearBridge.KEY_BROWSABLE, false),
                durationMs = json.optLong(WearBridge.KEY_DURATION_MS, 0L),
                downloaded = json.optBoolean(WearBridge.KEY_DOWNLOADED, false),
                index = json.optInt(WearBridge.KEY_INDEX, -1),
            )
    }
}

/**
 * `JSONObject.optString` reports a JSON null as the literal string `"null"`, which would paint the
 * word "null" across the UI. `put(key, null)` also *removes* the key on Android, so sparse fields
 * round-trip fine without a sentinel.
 */
fun JSONObject.optNullableString(key: String): String? =
    optString(key, "").takeIf { it.isNotEmpty() && it != "null" }
