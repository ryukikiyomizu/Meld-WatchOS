/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.wear.ui.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Shapes
import androidx.wear.compose.material3.Typography
import androidx.wear.compose.material3.dynamicColorScheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.wearSettingsStore: DataStore<Preferences> by preferencesDataStore(name = "wear_settings")

/** Accent choice for the watch UI. `DYNAMIC` follows the watch face / wallpaper Material You color. */
enum class WearAccent(val storageKey: String) {
    DYNAMIC("dynamic"),
    OCEAN("ocean"),
    FOREST("forest"),
    SUNSET("sunset"),
    MONO("mono"),
    ;

    companion object {
        fun fromKey(key: String?): WearAccent = entries.firstOrNull { it.storageKey == key } ?: DYNAMIC
    }
}

@Immutable
data class WearSettings(
    val accent: WearAccent = WearAccent.DYNAMIC,
    val keepScreenOn: Boolean = false,
    val showArtwork: Boolean = true,
    val seekStepSeconds: Int = 10,
)

val LocalWearSettings: ProvidableCompositionLocal<WearSettings> =
    staticCompositionLocalOf { WearSettings() }

/**
 * The few settings the watch owns. Everything about *playback* lives on the phone; this store only
 * holds what makes the watch UI comfortable (contrast, always-on, how coarse the skip buttons are).
 */
object WearPrefs {
    private val AccentKey = stringPreferencesKey("wear_accent")
    private val KeepScreenOnKey = booleanPreferencesKey("wear_keep_screen_on")
    private val ShowArtworkKey = booleanPreferencesKey("wear_show_artwork")
    private val SeekStepKey = intPreferencesKey("wear_seek_step_seconds")
    private val RecentKey = stringPreferencesKey("wear_recent_searches")
    private const val MAX_RECENTS = 8

    val Defaults = WearSettings()

    fun observe(context: Context): Flow<WearSettings> =
        context.wearSettingsStore.data
            .catch { emit(emptyPreferences()) }
            .map { prefs ->
                Defaults.copy(
                    accent = WearAccent.fromKey(prefs[AccentKey]),
                    keepScreenOn = prefs[KeepScreenOnKey] ?: false,
                    showArtwork = prefs[ShowArtworkKey] ?: true,
                    seekStepSeconds = prefs[SeekStepKey]?.coerceIn(5, 60) ?: 10,
                )
            }

    suspend fun setAccent(
        context: Context,
        accent: WearAccent,
    ) = context.wearSettingsStore.edit { it[AccentKey] = accent.storageKey }

    suspend fun setKeepScreenOn(
        context: Context,
        enabled: Boolean,
    ) = context.wearSettingsStore.edit { it[KeepScreenOnKey] = enabled }

    suspend fun setShowArtwork(
        context: Context,
        enabled: Boolean,
    ) = context.wearSettingsStore.edit { it[ShowArtworkKey] = enabled }

    suspend fun setSeekStep(
        context: Context,
        seconds: Int,
    ) = context.wearSettingsStore.edit { it[SeekStepKey] = seconds.coerceIn(5, 60) }

    /**
     * Recent queries live on the watch, not the phone: the watch is where typing hurts, so the
     * history that saves keystrokes belongs to the device that needed saving.
     */
    suspend fun recentSearches(context: Context): List<String> =
        runCatching { context.wearSettingsStore.data.first()[RecentKey] }
            .getOrNull()
            .orEmpty()
            .split('\n')
            .map(String::trim)
            .filter(String::isNotEmpty)

    suspend fun rememberSearch(
        context: Context,
        query: String,
    ) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val existing = recentSearches(context).filterNot { it.equals(trimmed, ignoreCase = true) }
        context.wearSettingsStore.edit { it[RecentKey] = (listOf(trimmed) + existing).take(MAX_RECENTS).joinToString("\n") }
    }
}

@Composable
fun rememberWearSettings(): WearSettings {
    val context = LocalContext.current
    return remember { WearPrefs.observe(context) }
        .collectAsStateWithLifecycle(initialValue = WearPrefs.Defaults)
        .value
}

/**
 * Wear Material 3 theme for Meld.
 *
 * Default is Material You from the wallpaper (`dynamicColorScheme`), which is what makes the app
 * look native on One UI Watch. The presets exist for two real reasons: on AMOLED they let you pick
 * a low-saturation scheme, and `MONO` doubles as the high-contrast option for bright sunlight.
 */
@Composable
fun MeldWearTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val settings = rememberWearSettings()
    val base = dynamicColorScheme(context) ?: ColorScheme()
    val scheme = remember(base, settings.accent) { base.applyAccent(settings.accent) }

    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(),
        shapes = Shapes(),
        content = {
            CompositionLocalProvider(LocalWearSettings provides settings) {
                content()
            }
        },
    )
}

private fun ColorScheme.applyAccent(accent: WearAccent): ColorScheme =
    when (accent) {
        WearAccent.DYNAMIC -> this
        WearAccent.OCEAN ->
            copy(
                primary = Color(0xFF5EA2FF),
                primaryDim = Color(0xFF9EC8FF),
                onPrimary = Color(0xFF06284F),
                secondary = Color(0xFF6DB5FF),
                background = Color(0xFF0B131F),
                onBackground = Color(0xFFE1EDFA),
                onSurface = Color(0xFFE1EDFA),
                onSurfaceVariant = Color(0xFFB5C7DE),
            )
        WearAccent.FOREST ->
            copy(
                primary = Color(0xFF4FD483),
                primaryDim = Color(0xFF9BE7B9),
                onPrimary = Color(0xFF04240F),
                secondary = Color(0xFF77C98B),
                background = Color(0xFF0A1410),
                onBackground = Color(0xFFE2F3E6),
                onSurface = Color(0xFFE2F3E6),
                onSurfaceVariant = Color(0xFFB2CCB9),
            )
        WearAccent.SUNSET ->
            copy(
                primary = Color(0xFFFF9C52),
                primaryDim = Color(0xFFFFC396),
                onPrimary = Color(0xFF3A1A03),
                secondary = Color(0xFFFFB06A),
                background = Color(0xFF170F0A),
                onBackground = Color(0xFFF7E8DE),
                onSurface = Color(0xFFF7E8DE),
                onSurfaceVariant = Color(0xFFD6BCA9),
            )
        WearAccent.MONO ->
            copy(
                primary = Color(0xFFEAEAEA),
                primaryDim = Color(0xFFB8B8B8),
                onPrimary = Color(0xFF121212),
                secondary = Color(0xFFCFCFCF),
                background = Color(0xFF000000),
                onBackground = Color(0xFFF2F2F2),
                onSurface = Color(0xFFF2F2F2),
                onSurfaceVariant = Color(0xFFB7B7B7),
            )
    }
