/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.DisplayMetrics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Global UI scale factor used to fit the phone-oriented UI onto small
 * Wear OS watch displays (e.g. a 44mm watch with a ~450x450 px panel).
 *
 * A 44mm watch reports roughly 192-225dp of usable width, while the app's
 * layouts are designed for ~360dp and up. Scaling the display density by
 * this factor makes the app believe it has a phone-sized dp canvas while
 * drawing every dp/sp unit at 55% of its original physical size, so the
 * whole UI (navigation, player, lists, dialogs) shrinks proportionally
 * and fits the watch face. 0.55 matches the "Ultra Compact" density preset.
 */
const val WATCH_UI_SCALE = 0.55f

// Same location the "Display density" appearance setting writes to, read here
// so the user-selected density preset still applies on top of the watch base
// scale. SharedPreferences is used (instead of DataStore) because the value is
// needed synchronously in Activity.attachBaseContext.
private const val DENSITY_PREFS_FILE = "metrolist_settings"
private const val DENSITY_SCALE_KEY = "density_scale_factor"

/**
 * Returns a context whose display density is scaled down so the UI fits on a
 * watch display. The final scale is [WATCH_UI_SCALE] multiplied by the density
 * preset the user picked in the appearance settings (defaults to 1.0).
 *
 * Applied from `Activity.attachBaseContext` so the scaled density is in place
 * before anything is created and is picked up by the activity window, Compose
 * layouts, dialogs, popups and menus alike.
 */
fun Context.withWatchUiScale(): Context {
    val scale = WATCH_UI_SCALE * storedDensityScale()
    if (scale == 1f) return this

    val configuration = Configuration(resources.configuration)
    val scaledDensityDpi =
        (configuration.densityDpi * scale)
            .toInt()
            .coerceAtLeast(DisplayMetrics.DENSITY_LOW)
    if (scaledDensityDpi == configuration.densityDpi) return this

    configuration.densityDpi = scaledDensityDpi
    return createConfigurationContext(configuration)
}

/**
 * Density preset persisted by the "Display density" appearance setting
 * (1.0 when the user never changed it).
 */
private fun Context.storedDensityScale(): Float =
    try {
        getSharedPreferences(DENSITY_PREFS_FILE, Context.MODE_PRIVATE)
            .getFloat(DENSITY_SCALE_KEY, 1f)
    } catch (e: Exception) {
        1f
    }

/**
 * Proportional insets for round Wear OS displays, following the native
 * Wear OS layout ratios (Material 3 Expressive for wear / Horologist
 * responsive padding):
 *
 *  - [horizontal]      5.2% of the width, the canonical Wear list horizontal
 *                      padding; used for fixed bars so their content starts
 *                      clear of the bezel curve.
 *  - [topBarTop]       pushes a fixed top bar below the narrow top arc so
 *                      title + actions sit on a wide-enough chord.
 *  - [bottomBarBottom] lifts fixed bottom controls off the narrow bottom arc.
 *
 * Everything else (backgrounds, scrolling lists, sheets) stays full-bleed
 * and flows through the circle, getting clipped by the bezel exactly like
 * native watch apps. All values are 0 on rectangular displays.
 */
data class RoundScreenInsets(
    val isRound: Boolean,
    val horizontal: Dp,
    val topBarTop: Dp,
    val bottomBarBottom: Dp,
)

@Composable
fun rememberRoundScreenInsets(): RoundScreenInsets {
    val configuration = LocalConfiguration.current
    return remember(configuration) {
        val isRound =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                configuration.isScreenRound
        if (isRound) {
            val w = configuration.screenWidthDp.dp
            val h = configuration.screenHeightDp.dp
            RoundScreenInsets(
                isRound = true,
                horizontal = w * 0.052f,
                topBarTop = h * 0.09f,
                bottomBarBottom = h * 0.07f,
            )
        } else {
            RoundScreenInsets(false, 0.dp, 0.dp, 0.dp)
        }
    }
}
