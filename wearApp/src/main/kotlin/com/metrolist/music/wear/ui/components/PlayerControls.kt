/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.wear.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.metrolist.music.wear.R
import kotlin.math.PI
import kotlin.math.atan2

/**
 * The seek ring: a full-circle progress arc you can drag around to scrub.
 *
 * A circular scrubber is the one gesture a round watch screen gives you for free — the finger
 * angle maps straight onto track position, and the ring is far enough from the edge that the system
 * back gesture doesn't steal the touch. The centre [content] slot holds the artwork, so the ring
 * doubles as the album-art frame.
 */
@Composable
fun SeekRing(
    fraction: Float,
    enabled: Boolean,
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    /** `null` means the drag was cancelled, so the caller should drop the preview. */
    onScrubEnd: (Float?) -> Unit,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 7.dp,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    progressColor: Color = MaterialTheme.colorScheme.primary,
    content: @Composable () -> Unit = {},
) {
    Box(
        modifier =
            modifier
                .then(
                    if (enabled) {
                        Modifier.pointerInput(Unit) {
                            var latest = fraction
                            fun angleToFraction(position: Offset): Float {
                                val centre = Offset(size.width / 2f, size.height / 2f)
                                val angleRad = atan2(position.y - centre.y, position.x - centre.x)
                                // 0 at 12 o'clock, increasing clockwise.
                                val degrees = ((angleRad * 180.0 / PI) + 90.0 + 360.0) % 360.0
                                return (degrees / 360.0).toFloat().coerceIn(0f, 1f)
                            }
                            detectDragGestures(
                                onDragStart = { offset ->
                                    onScrubStart()
                                    latest = angleToFraction(offset)
                                    onScrub(latest)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    latest = angleToFraction(change.position)
                                    onScrub(latest)
                                },
                                onDragEnd = { onScrubEnd(latest) },
                                onDragCancel = { onScrubEnd(null) },
                            )
                        }
                    } else {
                        Modifier
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            val sweep = 360f * fraction.coerceIn(0f, 1f)
            if (sweep > 0.5f) {
                drawArc(
                    color = progressColor,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        content()
    }
}

/** Prev / play / next. Play is the only filled control: it is the one you hit without looking. */
@Composable
fun TransportRow(
    isPlaying: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GhostIconButton(
            onClick = onPrevious,
            enabled = enabled,
            diameter = 42.dp,
        ) {
            Icon(
                imageVector = Icons.Filled.SkipPrevious,
                contentDescription = stringResource(R.string.previous),
                modifier = Modifier.size(22.dp),
            )
        }
        FilledCircleButton(
            onClick = onPlayPause,
            enabled = enabled,
            diameter = 54.dp,
        ) {
            when {
                // No spinner here: the indicator is tinted from the theme and would vanish on the
                // filled button, and a dimmed glyph costs the watch no animation frames.
                busy ->
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) stringResource(R.string.pause) else stringResource(R.string.play),
                        tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.55f),
                        modifier = Modifier.size(26.dp),
                    )
                else ->
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) stringResource(R.string.pause) else stringResource(R.string.play),
                        modifier = Modifier.size(26.dp),
                    )
            }
        }
        GhostIconButton(
            onClick = onNext,
            enabled = enabled,
            diameter = 42.dp,
        ) {
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = stringResource(R.string.next),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** Fine-grained seek for when dragging the ring is too coarse (live albums, podcasts). */
@Composable
fun NudgeRow(
    enabled: Boolean,
    stepSeconds: Int,
    onBackward: () -> Unit,
    onForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GhostIconButton(onClick = onBackward, enabled = enabled) {
            Icon(
                imageVector = Icons.Filled.Replay10,
                contentDescription = stringResource(R.string.seek_back_seconds, stepSeconds),
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = "${stepSeconds}s",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GhostIconButton(onClick = onForward, enabled = enabled) {
            Icon(
                imageVector = Icons.Filled.Forward10,
                contentDescription = stringResource(R.string.seek_forward_seconds, stepSeconds),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * A flat circular control. Written by hand instead of using the library button so the tap target,
 * the visual circle and the pressed state are all exactly the same thing.
 */
@Composable
fun GhostIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    diameter: Dp = 38.dp,
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
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.3f)
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
