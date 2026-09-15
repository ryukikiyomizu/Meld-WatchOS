package com.metrolist.music.ui.widget

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.EdgeBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.protolayout.TimelineBuilders.TimelineEntry
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.ResourceBuilders
import androidx.wear.tiles.SuspendingTileService
import com.metrolist.music.MainActivity
import com.metrolist.music.R
import com.metrolist.music.constants.AudioOutputKey
import com.metrolist.music.constants.MinimalModeKey
import com.metrolist.music.playback.PlaybackRemote
import com.metrolist.music.utils.dataStore
import kotlinx.coroutines.flow.first

/**
 * Wear OS tile: a glanceable mini-player.
 *
 * - Minimal mode + phone output: mirrors what the phone is playing.
 * - Otherwise: acts as a shortcut that opens the player.
 *
 * Tapping always launches MainActivity; the connection/offline guard and the
 * "connect to phone or turn off minimal mode" toast live there.
 */
class PlayerTileService : SuspendingTileService() {
    override suspend fun onTileRequest(requestParams: RequestBuilders.TileRequest): Timeline {
        val prefs = dataStore.data.first()
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
                                    ActionBuilders.BoolExtra.Builder().setValue(true).build(),
                                ).build(),
                        ).build(),
                ).build()

        val column =
            LayoutElementBuilders.Column
                .Builder()
                .setWidth(expand())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGNMENT_CENTER)
                .addContent(
                    text(title, 15f, 0xFFFFFFFF.toInt(), bold = true, maxLines = 1),
                ).addContent(spacer(4f))
                .addContent(
                    text(subtitle, 12f, 0xFFB0B0B0.toInt(), maxLines = 1),
                ).addContent(spacer(10f))
                .addContent(
                    text(status, 11f, 0xFF8AB4F8.toInt(), maxLines = 1),
                ).build()

        val box =
            LayoutElementBuilders.Box
                .Builder()
                .setWidth(expand())
                .setHeight(expand())
                .setModifiers(
                    ModifiersBuilders.Modifiers
                        .Builder()
                        .setClickable(clickable)
                        .setPadding(
                            EdgeBuilders.Padding
                                .Builder()
                                .setAll(dp(16f))
                                .build(),
                        ).build(),
                ).addContent(column)
                .build()

        return Timeline
            .Builder()
            .addTimelineEntry(
                TimelineEntry
                    .Builder()
                    .setLayout(
                        LayoutElementBuilders.Layout
                            .Builder()
                            .setRoot(box)
                            .build(),
                    ).build(),
            ).build()
    }

    override suspend fun onTileResourcesRequest(requestParams: ResourceBuilders.ResourcesRequest): ResourceBuilders.Resources =
        ResourceBuilders.Resources
            .Builder()
            .setVersion("1")
            .build()

    private fun text(
        value: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
        maxLines: Int = 1,
    ) = LayoutElementBuilders.Text
        .Builder()
        .setText(value)
        .setMultilineAlignment(LayoutElementBuilders.HORIZONTAL_ALIGNMENT_CENTER)
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
