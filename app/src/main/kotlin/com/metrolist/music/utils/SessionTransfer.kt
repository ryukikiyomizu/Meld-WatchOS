/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.datastore.preferences.core.LongPreferencesKey
import androidx.datastore.preferences.core.StringPreferencesKey
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.AccountChannelHandleKey
import com.metrolist.music.constants.AccountEmailKey
import com.metrolist.music.constants.AccountNameKey
import com.metrolist.music.constants.DataSyncIdKey
import com.metrolist.music.constants.EnableSpotifyKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.SpotifyAccessTokenKey
import com.metrolist.music.constants.SpotifySpDcKey
import com.metrolist.music.constants.SpotifySpKeyKey
import com.metrolist.music.constants.SpotifyTokenExpiryKey
import com.metrolist.music.constants.SpotifyUserIdKey
import com.metrolist.music.constants.SpotifyUsernameKey
import com.metrolist.music.constants.VisitorDataKey
import com.metrolist.music.playback.MusicService
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Moves an authorized session (YouTube Music cookie + Spotify cookies/tokens)
 * between devices without needing a browser/WebView — e.g. from a phone to a
 * Wear OS watch, where the sign-in WebView is unavailable.
 *
 * Export produces a single shareable text token; import writes the contained
 * credentials into the local DataStore and restarts the app so every service
 * (InnerTube client, [com.metrolist.music.utils.SpotifyTokenManager]) picks
 * them up from a clean state.
 */
object SessionTransfer {
    const val PAYLOAD_PREFIX = "MELDSESSION1::"

    private val youtubeKeys =
        mapOf(
            "innerTubeCookie" to InnerTubeCookieKey,
            "visitorData" to VisitorDataKey,
            "dataSyncId" to DataSyncIdKey,
            "accountName" to AccountNameKey,
            "accountEmail" to AccountEmailKey,
            "accountChannelHandle" to AccountChannelHandleKey,
        )

    private val spotifyKeys =
        mapOf(
            "spDc" to SpotifySpDcKey,
            "spKey" to SpotifySpKeyKey,
            "accessToken" to SpotifyAccessTokenKey,
            "username" to SpotifyUsernameKey,
            "userId" to SpotifyUserIdKey,
        )

    /** Session metadata shown to the user before an import is confirmed. */
    data class Info(
        val accountName: String?,
        val accountEmail: String?,
        val hasYouTube: Boolean,
        val hasSpotify: Boolean,
    )

    /** A parsed payload: metadata for display plus the values to write. */
    data class Parsed(
        val info: Info,
        val strings: Map<StringPreferencesKey, String>,
        val longs: Map<LongPreferencesKey, Long>,
    )

    /**
     * Builds a transfer token from the currently signed-in sessions.
     * Returns null when the device has neither a YouTube nor a Spotify session.
     */
    suspend fun createPayload(context: Context): String? {
        val prefs = context.dataStore.data.first()
        val hasYouTube = prefs[InnerTubeCookieKey]?.isNotBlank() == true
        val hasSpotify = prefs[SpotifySpDcKey]?.isNotBlank() == true
        if (!hasYouTube && !hasSpotify) return null

        val root =
            buildJsonObject {
                put("v", 1)
                if (hasYouTube) {
                    put(
                        "youtube",
                        buildJsonObject {
                            youtubeKeys.forEach { (jsonKey, key) ->
                                prefs[key]?.takeIf { it.isNotBlank() }?.let { put(jsonKey, it) }
                            }
                        },
                    )
                }
                if (hasSpotify) {
                    put(
                        "spotify",
                        buildJsonObject {
                            spotifyKeys.forEach { (jsonKey, key) ->
                                prefs[key]?.takeIf { it.isNotBlank() }?.let { put(jsonKey, it) }
                            }
                            prefs[SpotifyTokenExpiryKey]?.let { put("tokenExpiry", it) }
                        },
                    )
                }
            }
        val encoded =
            Base64.encodeToString(
                root.toString().toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP,
            )
        return PAYLOAD_PREFIX + encoded
    }

    /**
     * Parses a transfer token. Tolerates surrounding whitespace/text so the
     * token can be pasted from chats or notes. Returns null when no valid
     * token is present.
     */
    fun parsePayload(raw: String?): Parsed? {
        val text = raw.orEmpty().trim()
        val start = text.indexOf(PAYLOAD_PREFIX)
        if (start < 0) return null

        val token =
            text
                .substring(start + PAYLOAD_PREFIX.length)
                .filter { !it.isWhitespace() }
        val decoded =
            try {
                String(Base64.decode(token, Base64.URL_SAFE), Charsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                return null
            }

        val root =
            try {
                Json.parseToJsonElement(decoded).jsonObject
            } catch (e: Exception) {
                return null
            }

        val strings = HashMap<StringPreferencesKey, String>()
        val longs = HashMap<LongPreferencesKey, Long>()

        root["youtube"]?.jsonObject?.let { section ->
            youtubeKeys.forEach { (jsonKey, key) ->
                section.str(jsonKey)?.let { strings[key] = it }
            }
        }
        root["spotify"]?.jsonObject?.let { section ->
            spotifyKeys.forEach { (jsonKey, key) ->
                section.str(jsonKey)?.let { strings[key] = it }
            }
            (section["tokenExpiry"] as? JsonPrimitive)?.longOrNull?.let {
                longs[SpotifyTokenExpiryKey] = it
            }
        }

        val hasYouTube = strings.containsKey(InnerTubeCookieKey)
        val hasSpotify = strings.containsKey(SpotifySpDcKey)
        if (!hasYouTube && !hasSpotify) return null

        return Parsed(
            info =
                Info(
                    accountName = strings[AccountNameKey],
                    accountEmail = strings[AccountEmailKey],
                    hasYouTube = hasYouTube,
                    hasSpotify = hasSpotify,
                ),
            strings = strings,
            longs = longs,
        )
    }

    /**
     * Writes the imported session into the DataStore and restarts the app so
     * playback services and API clients re-initialize with the new credentials
     * (same restart strategy as backup restore).
     */
    suspend fun apply(
        context: Context,
        parsed: Parsed,
    ) {
        context.dataStore.edit { prefs ->
            parsed.strings.forEach { (key, value) -> prefs[key] = value }
            parsed.longs.forEach { (key, value) -> prefs[key] = value }
            if (parsed.info.hasSpotify) {
                // The transferred Spotify session should be usable right away.
                prefs[EnableSpotifyKey] = true
            }
        }

        context.stopService(Intent(context, MusicService::class.java))
        context.filesDir.resolve(MusicService.PERSISTENT_QUEUE_FILE).delete()
        val intent =
            context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
        if (intent != null) {
            context.startActivity(intent)
        }
        Runtime.getRuntime().exit(0)
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    /** File names accepted when importing a session pushed via adb. */
    private val ADB_FILE_NAMES = listOf("session.txt", "meld-session.txt")

    /**
     * On devices without a usable browser/file picker (e.g. Wear OS), the
     * session code can be pushed with adb straight into the app's external
     * files directory, which needs no storage permission:
     *
     *   adb push meld-session.txt /sdcard/Android/data/<package>/files/session.txt
     */
    fun findAdbPushedFile(context: Context): java.io.File? {
        val dir = context.getExternalFilesDir(null) ?: return null
        return ADB_FILE_NAMES
            .map { java.io.File(dir, it) }
            .firstOrNull { it.isFile && it.length() > 0 }
    }
}
