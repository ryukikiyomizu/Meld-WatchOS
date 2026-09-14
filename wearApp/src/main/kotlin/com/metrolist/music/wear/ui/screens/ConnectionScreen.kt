/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.wear.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.metrolist.music.wear.R
import com.metrolist.music.wear.bridge.MeldWear
import com.metrolist.music.wear.bridge.WearLink
import com.metrolist.music.wear.ui.WearRouter
import com.metrolist.music.wear.ui.components.ScreenHeader
import com.metrolist.music.wear.ui.components.SquareButton
import com.metrolist.music.wear.ui.components.WearList
import com.metrolist.music.wear.ui.util.rememberResumed
import kotlinx.coroutines.launch

/**
 * Link diagnostics. A watch remote that fails silently is the worst version of this feature, so the
 * screen states which of the three failure modes is happening and lets you re-run discovery.
 */
@Composable
fun ConnectionScreen(router: WearRouter) {
    val scope = rememberCoroutineScope()
    val resumed = rememberResumed()
    val link by MeldWear.link.collectAsStateWithLifecycle()
    val state by MeldWear.state.collectAsStateWithLifecycle()
    val error by MeldWear.lastError.collectAsStateWithLifecycle()
    var checking by remember { mutableStateOf(false) }
    var latencyMs by remember { mutableLongStateOf(-1L) }

    LaunchedEffect(resumed) {
        if (resumed) MeldWear.refresh()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        ScreenHeader(
            title = stringResource(R.string.phone),
            onBack = { router.pop() },
        )
        WearList(
            state = rememberSaveable(saver = LazyListState.Saver) { LazyListState() },
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Text(
                    text =
                        when (link) {
                            is WearLink.Linked -> stringResource(R.string.link_linked)
                            WearLink.NoPhone -> stringResource(R.string.link_no_phone)
                            WearLink.SilentPhone -> stringResource(R.string.link_silent_phone)
                            WearLink.Checking -> stringResource(R.string.link_checking)
                        },
                    style = MaterialTheme.typography.labelLarge,
                    color =
                        if (link is WearLink.Linked) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                )
            }
            (link as? WearLink.Linked)?.device?.let { device ->
                item { Fact(label = stringResource(R.string.link_device), value = device) }
            }
            // Read the delegated property once: `state` is a Compose `State` delegate, so the
            // compiler cannot smart-cast the nullable field inside the `item` lambda.
            val phoneVersion = state.phoneVersion
            if (phoneVersion != null) {
                item { Fact(label = stringResource(R.string.link_meld_version), value = phoneVersion) }
            }
            if (state.phoneBatteryPercent >= 0) {
                item {
                    Fact(
                        label = stringResource(R.string.link_phone_battery),
                        value = "${state.phoneBatteryPercent}%${if (state.phoneCharging) " ⚡" else ""}",
                    )
                }
            }
            if (latencyMs >= 0L) {
                item { Fact(label = stringResource(R.string.link_latency), value = "${latencyMs}ms") }
            }
            error?.let { message ->
                if (message.isNotBlank()) {
                    item {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                ) {
                    SquareButton(
                        label = if (checking) stringResource(R.string.link_checking) else stringResource(R.string.retry),
                        icon = Icons.Filled.Refresh,
                        modifier = Modifier.width(104.dp),
                        enabled = !checking,
                        onClick = {
                            scope.launch {
                                checking = true
                                val started = SystemClock.elapsedRealtime()
                                val snapshot = MeldWear.snapshot()
                                latencyMs = SystemClock.elapsedRealtime() - started
                                checking = false
                                if (snapshot == null) latencyMs = -1L
                            }
                        },
                    )
                }
            }
            item {
                Text(
                    text = stringResource(R.string.link_advice),
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
private fun Fact(
    label: String,
    value: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
