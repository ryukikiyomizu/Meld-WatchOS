package com.metrolist.music.ui.widget

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.metrolist.music.MainActivity
import com.metrolist.music.R
import com.metrolist.music.constants.AudioOutputKey
import com.metrolist.music.constants.MinimalModeKey
import com.metrolist.music.playback.PlaybackRemote
import com.metrolist.music.utils.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Wear OS tile: a glanceable mini-player.
 *
 * - Minimal mode + phone output: mirrors what the phone is playing.
 * - Otherwise: acts as a shortcut that opens the player.
 *
 * Tapping always launches MainActivity; the connection/offline guard and the
 * "connect to phone or turn off minimal mode" toast live there.
 */
class PlayerTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        Futures.immediateFuture(
            TileBuilders.Tile
                .Builder()
                .setTileTimeline(buildTimeline())
                .setResourcesVersion("1")
                .build(),
        )

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<androidx.wear.protolayout.ResourceBuilders.Resources> =
        Futures.immediateFuture(
            androidx.wear.protolayout.ResourceBuilders.Resources
                .Builder()
                .setVersion("1")
                .build(),
        )

    private fun buildTimeline(): TimelineBuilders.Timeline {
        val prefs = runBlocking { dataStore.data.first() }
        val minimal = prefs[MinimalModeKey] ?: false
        val output = prefs[AudioOutputKey] ?: "watch"
        val remote = PlaybackRemote.remoteState.value

        val remotePlayback = minimal && output == "phone"

        val title: String
        val subtitle: String
        val status: String
        if (remotePlayback) {
            title = remote?.title ?: getString(R.string.minimal_mode)
            subtitle = remote?.artists ?: getString(R.string.audio_output_phone)
            status =
                if (remote?.isPlaying == true) {
                    "\u25B6 ${getString(R.string.audio_output_phone)}"
                } else {
                    getString(R.string.audio_output_phone)
                }
        } else {
            title = getString(R.string.app_name)
            subtitle = getString(R.string.tile_open_player)
            status =
                if (minimal) {
                    getString(R.string.audio_output_watch)
                } else {
                    getString(R.string.minimal_mode)
                }
        }

        val clickable =
            ModifiersBuilders.Clickable
                .Builder()
                .setOnClick(
                    ActionBuilders.LaunchAction
                        .Builder()
                        .setAndroidActivity(
                            ActionBuilders.AndroidActivity
                                .Builder()
                                .setPackageName(packageName)
                                .setClassName(MainActivity::class.java.name)
                                .addKeyToExtraMapping(
                                    "from_tile",
                                    ActionBuilders.AndroidBooleanExtra.Builder().setValue(true).build(),
                                ).build(),
                        ).build(),
                ).build()

        val column =
            LayoutElementBuilders.Column
                .Builder()
                .setWidth(expand())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(text(title, 15f, 0xFFFFFFFF.toInt(), bold = true, maxLines = 1))
                .addContent(spacer(4f))
                .addContent(text(subtitle, 12f, 0xFFB0B0B0.toInt(), maxLines = 1))
                .addContent(spacer(10f))
                .addContent(text(status, 11f, 0xFF8AB4F8.toInt(), maxLines = 1))
                .build()

        val box =
            LayoutElementBuilders.Box
                .Builder()
                .setWidth(expand())
                .setHeight(expand())
                .setModifiers(
                    ModifiersBuilders.Modifiers
                        .Builder()
                        .setClickable(clickable)
                        .build(),
                ).addContent(column)
                .build()

        return TimelineBuilders.Timeline
            .Builder()
            .addTimelineEntry(
                TimelineBuilders.TimelineEntry
                    .Builder()
                    .setLayout(
                        LayoutElementBuilders.Layout
                            .Builder()
                            .setRoot(box)
                            .build(),
                    ).build(),
            ).build()
    }

    private fun text(
        value: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
        maxLines: Int = 1,
    ) = LayoutElementBuilders.Text
        .Builder()
        .setText(value)
        .setMultilineAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        .setMaxLines(maxLines)
        .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
        .setFontStyle(
            LayoutElementBuilders.FontStyle
                .Builder()
                .setSize(sp(sizeSp))
                .setColor(ColorBuilders.argb(color))
                .setWeight(
                    if (bold) {
                        LayoutElementBuilders.FONT_WEIGHT_BOLD
                    } else {
                        LayoutElementBuilders.FONT_WEIGHT_NORMAL
                    },
                ).build(),
        ).build()

    private fun spacer(heightDp: Float) =
        LayoutElementBuilders.Spacer
            .Builder()
            .setHeight(dp(heightDp))
            .build()
}
