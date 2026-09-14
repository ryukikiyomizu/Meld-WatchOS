/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.wear.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Timer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.metrolist.music.wear.R
import com.metrolist.music.wear.bridge.MeldWear
import com.metrolist.music.wear.ui.WearRoute
import com.metrolist.music.wear.ui.WearRouter
import com.metrolist.music.wear.ui.components.ActionRow
import com.metrolist.music.wear.ui.components.ScreenHeader
import com.metrolist.music.wear.ui.components.SquareButton
import com.metrolist.music.wear.ui.components.WearList
import com.metrolist.music.wear.ui.components.WearToggleRow
import com.metrolist.music.wear.ui.theme.LocalWearSettings
import com.metrolist.music.wear.ui.theme.WearAccent
import com.metrolist.music.wear.ui.theme.WearPrefs
import com.metrolist.music.wear.ui.util.formatCountdown
import kotlinx.coroutines.launch

/**
 * The watch's own settings: how the UI behaves on this wrist. Everything about playback, cache or
 * accounts stays on the phone where it belongs — duplicating those here would only drift.
 */
@Composable
fun SettingsScreen(router: WearRouter) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = LocalWearSettings.current
    val state by MeldWear.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxWidth()) {
        ScreenHeader(
            title = stringResource(R.string.more),
            onBack = { router.pop() },
        )
        WearList(
            state = rememberSaveable(saver = LazyListState.Saver) { LazyListState() },
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                AccentPicker(
                    selected = settings.accent,
                    onSelect = { accent -> scope.launch { WearPrefs.setAccent(context, accent) } },
                )
            }
            item {
                WearToggleRow(
                    label = stringResource(R.string.keep_screen_on),
                    caption = stringResource(R.string.keep_screen_on_desc),
                    checked = settings.keepScreenOn,
                    onCheckedChange = { enabled -> scope.launch { WearPrefs.setKeepScreenOn(context, enabled) } },
                )
            }
            item {
                WearToggleRow(
                    label = stringResource(R.string.show_artwork),
                    caption = stringResource(R.string.show_artwork_desc),
                    checked = settings.showArtwork,
                    onCheckedChange = { enabled -> scope.launch { WearPrefs.setShowArtwork(context, enabled) } },
                )
            }
            item {
                Column {
                    Text(
                        text = stringResource(R.string.seek_step),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        SEEK_STEPS.forEach { seconds ->
                            SquareButton(
                                label = "${seconds}s",
                                modifier = Modifier.width(40.dp),
                                selected = settings.seekStepSeconds == seconds,
                                onClick = { scope.launch { WearPrefs.setSeekStep(context, seconds) } },
                            )
                        }
                    }
                }
            }
            item {
                ActionRow(
                    label = stringResource(R.string.sleep_timer),
                    caption =
                        if (state.sleepRemainingMs > 0L) {
                            stringResource(R.string.sleep_stops_in, formatCountdown(state.sleepRemainingMs))
                        } else {
                            null
                        },
                    icon = Icons.Filled.Timer,
                    onClick = { router.push(WearRoute.Sleep) },
                )
            }
            item {
                ActionRow(
                    label = stringResource(R.string.downloaded),
                    icon = Icons.Filled.CloudDownload,
                    onClick = { router.push(WearRoute.Downloads) },
                )
            }
            item {
                ActionRow(
                    label = stringResource(R.string.phone),
                    caption = state.phoneVersion?.let { "Meld $it" },
                    icon = Icons.Filled.PhoneAndroid,
                    onClick = { router.push(WearRoute.Phone) },
                )
            }
            item {
                Text(
                    text = "Meld for Wear",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }
            item { Spacer(Modifier.height(6.dp)) }
        }
    }
}

@Composable
private fun AccentPicker(
    selected: WearAccent,
    onSelect: (WearAccent) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.accent),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        ) {
            WearAccent.entries.forEach { accent ->
                val isCurrent = accent == selected
                Box(
                    modifier =
                        Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(accentSwatch(accent))
                            .then(
                                if (isCurrent) {
                                    Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                } else {
                                    Modifier
                                },
                            ).clickable { onSelect(accent) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (accent == WearAccent.DYNAMIC) {
                        Text(
                            text = "M",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Black,
                        )
                    }
                }
            }
        }
    }
}

private fun accentSwatch(accent: WearAccent): Color =
    when (accent) {
        WearAccent.DYNAMIC -> Color(0xFF9C7CFF)
        WearAccent.OCEAN -> Color(0xFF5EA2FF)
        WearAccent.FOREST -> Color(0xFF4FD483)
        WearAccent.SUNSET -> Color(0xFFFF9C52)
        WearAccent.MONO -> Color(0xFFD8D8D8)
    }

private val SEEK_STEPS = listOf(5, 10, 15, 30)
