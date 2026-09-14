/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.wear.ui.util

import android.app.Activity
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

/**
 * True only while the activity is resumed. Every polling loop in the app keys off this, so a watch
 * that is merely in a pocket sends nothing at all.
 */
@Composable
fun rememberResumed(): Boolean {
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> resumed = true
                    Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> resumed = false
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return resumed
}

/**
 * A 5Hz wall clock, only while [active]. Used to glide the progress ring between phone refreshes so
 * the ring animates smoothly at the cost of one round trip every couple of seconds.
 */
@Composable
fun rememberTickingClock(active: Boolean): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(active) {
        while (active) {
            now = SystemClock.elapsedRealtime()
            delay(CLOCK_TICK_MS)
        }
    }
    return now
}

/**
 * Keeps the screen awake. Held only while the caller is actually interacting (scrubbing) or while
 * the user turned the option on — never silently, because that is how a watch dies in a pocket.
 */
@Composable
fun KeepScreenOn(enabled: Boolean) {
    val window = (LocalView.current.context as? Activity)?.window
    DisposableEffect(enabled) {
        if (enabled) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

/** `3:07`, or `1:02:04` past an hour — the way a watch should show it. */
fun formatWatchTime(millis: Long): String {
    val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
    val seconds = totalSeconds % 60L
    val minutes = (totalSeconds / 60L) % 60L
    val hours = totalSeconds / 3600L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/** Compact countdown for the sleep timer, e.g. `28m`. */
fun formatCountdown(millis: Long): String {
    val totalMinutes = (millis / 60_000L).coerceAtLeast(0L)
    return when {
        totalMinutes >= 60 -> "%dh %02dm".format(totalMinutes / 60L, totalMinutes % 60L)
        totalMinutes >= 1 -> "%dm".format(totalMinutes)
        else -> "<1m"
    }
}

fun nowElapsed(): Long = SystemClock.elapsedRealtime()

private const val CLOCK_TICK_MS = 200L
