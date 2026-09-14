/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.wear.ui.screens

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.metrolist.music.bridge.WearMediaRow
import com.metrolist.music.wear.R
import com.metrolist.music.wear.bridge.MeldWear
import com.metrolist.music.wear.ui.WearRoute
import com.metrolist.music.wear.ui.WearRouter
import com.metrolist.music.wear.ui.components.LoadingBlock
import com.metrolist.music.wear.ui.components.MediaRow
import com.metrolist.music.wear.ui.components.ScreenHeader
import com.metrolist.music.wear.ui.components.SquareButton
import com.metrolist.music.wear.ui.components.StateMessage
import com.metrolist.music.wear.ui.components.WearList
import com.metrolist.music.wear.ui.theme.LocalWearSettings
import com.metrolist.music.wear.ui.theme.WearPrefs
import kotlinx.coroutines.launch

/**
 * Search, built for a wrist.
 *
 * Two inputs, because those are the two that work on a watch: speech, and an A–Z pad you can thumb
 * through with the bezel. There is no live search — one query round trip makes the phone hit its
 * local library *and* YouTube, so results are fetched only when you press search.
 */
private enum class SearchStage {
    Input,
    Results
}

@Composable
fun SearchScreen(router: WearRouter) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = LocalWearSettings.current
    val haptic = LocalHapticFeedback.current
    var query by remember { mutableStateOf("") }
    var stage by remember { mutableStateOf(SearchStage.Input) }
    var results by remember { mutableStateOf<List<WearMediaRow>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var recents by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) { recents = WearPrefs.recentSearches(context) }

    fun runSearch(term: String) {
        val trimmed = term.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            loading = true
            stage = SearchStage.Results
            val found = MeldWear.search(trimmed)
            results = found
            error = if (found == null) MeldWear.lastError.value else null
            loading = false
            if (found != null) WearPrefs.rememberSearch(context, trimmed)
        }
    }

    val voiceLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { outcome ->
            val spoken =
                outcome.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                query = spoken
                runSearch(spoken)
            }
        }

    fun launchVoice() {
        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.search_hint))
        runCatching { voiceLauncher.launch(intent) }
            .onFailure { error = context.getString(R.string.voice_unavailable) }
    }

    if (stage == SearchStage.Results) {
        Column(modifier = Modifier.fillMaxWidth()) {
            ScreenHeader(
                title = query.ifBlank { stringResource(R.string.search) },
                onBack = {
                    stage = SearchStage.Input
                    error = null
                },
            )
            val found = results
            when {
                loading -> LoadingBlock()
                found.isNullOrEmpty() ->
                    StateMessage(
                        text = error ?: stringResource(R.string.no_results),
                        hint = stringResource(R.string.no_results_hint),
                    )
                else ->
                    WearList(
                        state = rememberSaveable(saver = LazyListState.Saver) { LazyListState() },
                        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 10.dp),
                    ) {
                        items(count = found.size) { index ->
                            val row = found[index]
                            MediaRow(
                                row = row,
                                showArtwork = settings.showArtwork,
                                onClick = {
                                    scope.launch {
                                        when {
                                            row.playable -> {
                                                if (MeldWear.play(row.mediaId)) {
                                                    router.popTo(WearRoute.Player)
                                                }
                                            }
                                            row.browsable ->
                                                router.push(
                                                    WearRoute.Browse(
                                                        parentId = row.mediaId,
                                                        label = row.title,
                                                    ),
                                                )
                                        }
                                    }
                                },
                            )
                        }
                    }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        ScreenHeader(
            title = stringResource(R.string.search),
            onBack = { router.pop() },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = query.ifBlank { stringResource(R.string.search_hint) },
                style = MaterialTheme.typography.labelLarge,
                color = if (query.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(end = 4.dp),
            )
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                SquareButton(
                    label = stringResource(R.string.search),
                    icon = Icons.Filled.PlayArrow,
                    modifier = Modifier.width(34.dp),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        runSearch(query)
                    },
                )
            }
        }

        // Two recent queries, no scrolling: a second rotary-scrollable list on the same screen
        // would fight the letter pad below for the bezel.
        if (recents.isNotEmpty() && query.isBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                recents.take(2).forEach { term ->
                    SquareButton(
                        label = term,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            query = term
                            runSearch(term)
                        },
                    )
                }
            }
        }

        // Letter pad: 5 per row keeps every target above 30dp on a 1.3in screen, and the bezel
        // scrolls through digits and punctuation below.
        WearList(
            state = rememberSaveable(saver = LazyListState.Saver) { LazyListState() },
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 0.dp, bottom = 44.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            KEYS.chunked(5).forEach { rowKeys ->
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    ) {
                        rowKeys.forEach { key ->
                            SquareButton(
                                label = key,
                                modifier = Modifier.width(32.dp),
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    query += key
                                },
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SquareButton(
                label = stringResource(R.string.mic),
                modifier = Modifier.weight(1f),
                onClick = { launchVoice() },
            )
            SquareButton(
                label = stringResource(R.string.clear),
                modifier = Modifier.weight(1f),
                enabled = query.isNotEmpty(),
                onClick = { query = "" },
            )
            SquareButton(
                label = stringResource(R.string.backspace),
                modifier = Modifier.weight(1f),
                enabled = query.isNotEmpty(),
                onClick = { query = query.dropLast(1) },
            )
        }
    }
}

private val KEYS: List<String> = ('a'..'z').map { it.uppercase() } + (0..9).map(Int::toString) + listOf(" ", "'")
