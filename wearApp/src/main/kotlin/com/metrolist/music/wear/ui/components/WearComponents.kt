/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.wear.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import coil3.compose.AsyncImage
import com.metrolist.music.bridge.WearBridge
import com.metrolist.music.bridge.WearMediaRow

/**
 * Scrolling list tuned for a watch: driven by the digital bezel / rotary crown, with a scroll
 * indicator on the edge so it is obvious there is more below, and padding that keeps content clear
 * of the time text on top and the curved bottom of a round screen.
 */
@Composable
fun WearList(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() },
    contentPadding: PaddingValues = PaddingValues(start = 10.dp, end = 10.dp, top = 26.dp, bottom = 30.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(6.dp),
    content: LazyListScope.() -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val rotaryBehavior = RotaryScrollableDefaults.behavior(scrollableState = state)
    // The bezel only reaches the list once it holds focus, so ask for it as soon as we compose.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .rotaryScrollable(
                        behavior = rotaryBehavior,
                        focusRequester = focusRequester,
                    ).focusRequester(focusRequester)
                    .focusable(),
            state = state,
            contentPadding = contentPadding,
            verticalArrangement = verticalArrangement,
            content = content,
        )
        // Positional args on purpose: the first parameter is the list state, the second the
        // modifier, and that keeps working across wear-compose versions that renamed it.
        ScrollIndicator(
            state,
            Modifier.align(Alignment.CenterEnd),
        )
    }
}

/**
 * Standard row for anything in the browse tree: song, playlist, artist, or a synthetic "shuffle"
 * action. Rows are ~48dp tall, the smallest height that is still reliable to hit on a 1.3in screen.
 */
@Composable
fun MediaRow(
    row: WearMediaRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    playing: Boolean = false,
    showArtwork: Boolean = true,
    downloaded: Boolean = row.downloaded,
    trailing: @Composable (() -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(16.dp)
    val container =
        when {
            playing -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            selected -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> Color.Transparent
        }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(container)
                .then(
                    if (playing || selected) {
                        Modifier.border(1.dp, MaterialTheme.colorScheme.primaryDim.copy(alpha = 0.7f), shape)
                    } else {
                        Modifier
                    },
                ).clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }.padding(start = 8.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            when {
                // Static glyph instead of a spinner: it has to survive ambient mode and it costs
                // the watch no redraws while a list is scrolling.
                playing ->
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                showArtwork && row.artworkUri != null ->
                    AsyncImage(
                        model = row.artworkUri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                else ->
                    Icon(
                        imageVector = iconForKind(row.kind),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            val hasSubtitle = !row.subtitle.isNullOrBlank()
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (hasSubtitle) 1 else 2,
                overflow = TextOverflow.Ellipsis,
                // Long titles scroll instead of truncating: there is no room for a details sheet.
                modifier = if (hasSubtitle) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier,
            )
            if (hasSubtitle) {
                Text(
                    text = row.subtitle.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            trailing != null -> trailing()
            playing -> Unit
            downloaded ->
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            row.browsable && !row.playable ->
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
        }
    }
}

private fun iconForKind(kind: String): ImageVector =
    when (kind) {
        WearBridge.KIND_SONG -> Icons.Filled.MusicNote
        WearBridge.KIND_ALBUM -> Icons.Filled.Album
        WearBridge.KIND_ARTIST -> Icons.Filled.Person
        WearBridge.KIND_PLAYLIST -> Icons.Filled.PlaylistPlay
        WearBridge.KIND_ACTION -> Icons.Filled.GraphicEq
        else -> Icons.Filled.Folder
    }

/** Screen title with the back control, sitting under the AppScaffold's time text. */
@Composable
fun ScreenHeader(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            GhostIconButton(
                onClick = onBack,
                modifier = Modifier.padding(start = 2.dp),
                diameter = 28.dp,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(2.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        action?.let {
            Box(
                modifier = Modifier.size(30.dp),
                contentAlignment = Alignment.Center,
            ) {
                it()
            }
        }
    }
}

/** Full-screen status used for empty results, offline states and errors. */
@Composable
fun StateMessage(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    hint: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.height(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        hint?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(10.dp))
            PillButton(
                label = actionLabel,
                onClick = onAction,
            )
        }
    }
}

@Composable
fun LoadingBlock(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp))
    }
}

/** Small "x" used as a row's trailing destructive control (remove from queue, drop download). */
@Composable
fun TinyCloseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GhostIconButton(
        onClick = onClick,
        modifier = modifier,
        diameter = 28.dp,
    ) {
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * Hand-rolled toggle rather than the library `Switch`: on a 38dp touch target the stock control is
 * mostly dead space, and this keeps the whole settings screen on one visual rhythm.
 */
@Composable
fun WearToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(
                    if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
                ).clickable { onCheckedChange(!checked) }
                .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
            )
            caption?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Box(
            modifier =
                Modifier
                    .size(width = 34.dp, height = 20.dp)
                    .clip(CircleShape)
                    .background(
                        if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                modifier =
                    Modifier
                        .padding(3.dp)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(
                            if (checked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
            )
        }
    }
}

/** A labelled row that navigates somewhere or fires one action. */
@Composable
fun ActionRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
    icon: ImageVector? = null,
    value: String? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f))
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            caption?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
        value?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primaryDim,
                maxLines = 1,
            )
        }
    }
}

/** A compact square button used in rows of small choices (sleep timer lengths, letter pad). */
@Composable
fun SquareButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier =
            modifier
                .height(32.dp)
                .clip(shape)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                ).clickable(enabled = enabled) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
        contentAlignment = Alignment.Center,
    ) {
        val tint =
            if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = label.takeIf { it.isNotBlank() },
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A text pill for the single action on a status screen. */
@Composable
fun PillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier =
            modifier
                .height(30.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f))
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }.padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The one filled control in the player: big, round, and unambiguous under a thumb. */
@Composable
fun FilledCircleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    diameter: Dp = 54.dp,
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier =
            modifier
                .size(diameter)
                .clip(CircleShape)
                .background(
                    if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                ).clickable(enabled = enabled) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
