/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.wear.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.TimeText
import com.metrolist.music.wear.ui.screens.BrowseScreen
import com.metrolist.music.wear.ui.screens.ConnectionScreen
import com.metrolist.music.wear.ui.screens.DownloadsScreen
import com.metrolist.music.wear.ui.screens.LibraryScreen
import com.metrolist.music.wear.ui.screens.PlayerScreen
import com.metrolist.music.wear.ui.screens.QueueScreen
import com.metrolist.music.wear.ui.screens.SearchScreen
import com.metrolist.music.wear.ui.screens.SettingsScreen
import com.metrolist.music.wear.ui.screens.SleepScreen

/** Every screen the watch has. A hand-written stack instead of a nav library: seven destinations,
 *  no argument bundles, and the system edge-swipe gesture drives `pop()` through [BackHandler]. */
sealed interface WearRoute {
    data object Player : WearRoute

    data object Queue : WearRoute

    data object Search : WearRoute

    data object Library : WearRoute

    data object Downloads : WearRoute

    data object Sleep : WearRoute

    data object Settings : WearRoute

    data object Phone : WearRoute

    data class Browse(
        val parentId: String,
        val label: String,
    ) : WearRoute
}

@Stable
class WearRouter(start: WearRoute) {
    private val stack: SnapshotStateList<WearRoute> = mutableStateListOf(start)

    val current: WearRoute
        get() = stack.last()

    val canGoBack: Boolean
        get() = stack.size > 1

    fun push(route: WearRoute) {
        if (stack.lastOrNull() != route) stack.add(route)
    }

    fun pop() {
        if (canGoBack) stack.removeAt(stack.lastIndex)
    }

    /** Used by the player's "back to now playing" affordance and after a playback starts. */
    fun popTo(route: WearRoute) {
        val index = stack.indexOf(route)
        if (index >= 0 && index < stack.lastIndex) {
            repeat(stack.lastIndex - index) { stack.removeAt(stack.lastIndex) }
        } else if (index < 0) {
            stack.clear()
            stack.add(route)
        }
    }
}

@Composable
fun WearAppRoot() {
    val router = remember { WearRouter(WearRoute.Player) }
    BackHandler(enabled = router.canGoBack) { router.pop() }

    AppScaffold(timeText = { TimeText() }) {
        when (val route = router.current) {
            WearRoute.Player -> PlayerScreen(router)
            WearRoute.Queue -> QueueScreen(router)
            WearRoute.Search -> SearchScreen(router)
            WearRoute.Library -> LibraryScreen(router)
            WearRoute.Downloads -> DownloadsScreen(router)
            WearRoute.Sleep -> SleepScreen(router)
            WearRoute.Settings -> SettingsScreen(router)
            WearRoute.Phone -> ConnectionScreen(router)
            is WearRoute.Browse -> BrowseScreen(parentId = route.parentId, label = route.label, router = router)
        }
    }
}
