package com.metrolist.music.ui.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.EventBuilders
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
import com.metrolist.music.utils.LinkSender
import com.metrolist.music.utils.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.io.ByteArrayOutputStream

/**
 * Wear OS tile: a glanceable mini-player.
 *
 * - Minimal mode + phone output: artwork + title + working prev/play-pause/next
 *   buttons that remote-control the phone without opening the app.
 * - Otherwise: a shortcut that opens the player.
 *
 * Tapping the header always launches MainActivity; if the watch is offline with
 * phone output selected, MainActivity shows the "connect to phone or turn off
 * minimal mode" toast.
 */
class PlayerTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        Futures.immediateFuture(
            TileBuilders.Tile
                .Builder()
                .setTileTimeline(buildTimeline())
                .setResourcesVersion(resourcesVersion())
                .build(),
        )

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(buildResources())

    override fun onRecentInteractionEventsAsync(events: List<EventBuilders.TileInteractionEvent>) {
        for (event in events) {
            val cmd =
                when (event.clickable.id) {
                    ID_PREV -> "prev"
                    ID_TOGGLE -> "toggle"
                    ID_NEXT -> "next"
                    else -> continue
                }
            try {
                runBlocking {
                    LinkSender.send(applicationContext, LinkSender.PATH_PLAYBACK, cmd)
                }
            } catch (e: Exception) {
                Timber.w(e, "PlayerTile: control send failed")
            }
        }
    }

    private fun artUrl(): String? = PlaybackRemote.remoteState.value?.artUrl

    private fun resourcesVersion(): String = "art-" + (artUrl()?.hashCode() ?: 0)

    private fun buildTimeline(): TimelineBuilders.Timeline {
        val prefs = runBlocking { dataStore.data.first() }
        val minimal = prefs[MinimalModeKey] ?: false
        val output = prefs[AudioOutputKey] ?: "watch"
        val remote = PlaybackRemote.remoteState.value
        val remotePlayback = minimal && output == "phone"

        val root =
            if (remotePlayback) {
                remoteLayout(
                    title = remote?.title ?: getString(R.string.minimal_mode),
                    subtitle = remote?.artists ?: getString(R.string.audio_output_phone),
                    isPlaying = remote?.isPlaying == true,
                    hasArt = artUrl() != null,
                )
            } else {
                fallbackLayout(
                    status =
                        if (minimal) {
                            getString(R.string.audio_output_watch)
                        } else {
                            getString(R.string.minimal_mode)
                        },
                )
            }

        return TimelineBuilders.Timeline
            .Builder()
            .addTimelineEntry(
                TimelineBuilders.TimelineEntry
                    .Builder()
                    .setLayout(
                        LayoutElementBuilders.Layout
                            .Builder()
                            .setRoot(root)
                            .build(),
                    ).build(),
            ).build()
    }

    /** Mini-player: art + titles open the app; transport buttons remote-control. */
    private fun remoteLayout(
        title: String,
        subtitle: String,
        isPlaying: Boolean,
        hasArt: Boolean,
    ): LayoutElementBuilders.LayoutElement {
        val headerTexts =
            LayoutElementBuilders.Column
                .Builder()
                .setWidth(expand())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
                .addContent(text(title, 14f, 0xFFFFFFFF.toInt(), bold = true, maxLines = 1))
                .addContent(spacer(2f))
                .addContent(text(subtitle, 11f, 0xFFB0B0B0.toInt(), maxLines = 1))
                .build()

        val headerRow =
            LayoutElementBuilders.Row
                .Builder()
                .setWidth(expand())
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .setModifiers(
                    ModifiersBuilders.Modifiers
                        .Builder()
                        .setClickable(openAppClickable())
                        .build(),
                ).apply {
                    if (hasArt) {
                        addContent(
                            LayoutElementBuilders.Image
                                .Builder()
                                .setResourceId("art")
                                .setWidth(dp(52f))
                                .setHeight(dp(52f))
                                .build(),
                        )
                        addContent(spacerWidth(10f))
                    }
                }.addContent(headerTexts)
                .build()

        val controls =
            LayoutElementBuilders.Row
                .Builder()
                .setWidth(expand())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .addContent(controlImage("ic_prev", ID_PREV, 26f))
                .addContent(spacerWidth(22f))
                .addContent(controlImage(if (isPlaying) "ic_pause" else "ic_play", ID_TOGGLE, 36f))
                .addContent(spacerWidth(22f))
                .addContent(controlImage("ic_next", ID_NEXT, 26f))
                .build()

        return LayoutElementBuilders.Column
            .Builder()
            .setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(headerRow)
            .addContent(spacer(12f))
            .addContent(controls)
            .build()
    }

    /** Simple shortcut tile when remote playback is not active. */
    private fun fallbackLayout(status: String): LayoutElementBuilders.LayoutElement {
        val column =
            LayoutElementBuilders.Column
                .Builder()
                .setWidth(expand())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(text(getString(R.string.app_name), 15f, 0xFFFFFFFF.toInt(), bold = true, maxLines = 1))
                .addContent(spacer(4f))
                .addContent(text(getString(R.string.tile_open_player), 12f, 0xFFB0B0B0.toInt(), maxLines = 1))
                .addContent(spacer(10f))
                .addContent(text(status, 11f, 0xFF8AB4F8.toInt(), maxLines = 1))
                .build()

        return LayoutElementBuilders.Box
            .Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(
                ModifiersBuilders.Modifiers
                    .Builder()
                    .setClickable(openAppClickable())
                    .build(),
            ).addContent(column)
            .build()
    }

    private fun openAppClickable(): ModifiersBuilders.Clickable =
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

    private fun controlImage(
        resourceId: String,
        clickableId: String,
        sizeDp: Float,
    ) = LayoutElementBuilders.Image
        .Builder()
        .setResourceId(resourceId)
        .setWidth(dp(sizeDp))
        .setHeight(dp(sizeDp))
        .setModifiers(
            ModifiersBuilders.Modifiers
                .Builder()
                .setClickable(
                    ModifiersBuilders.Clickable
                        .Builder()
                        .setId(clickableId)
                        .build(),
                ).build(),
        ).build()

    private fun buildResources(): ResourceBuilders.Resources {
        val builder =
            ResourceBuilders.Resources
                .Builder()
                .setVersion(resourcesVersion())
                .addIdToImageMapping("ic_prev", inlineResource(R.drawable.ic_widget_skip_previous, 64))
                .addIdToImageMapping("ic_play", inlineResource(R.drawable.ic_widget_play, 96))
                .addIdToImageMapping("ic_pause", inlineResource(R.drawable.ic_widget_pause, 96))
                .addIdToImageMapping("ic_next", inlineResource(R.drawable.ic_widget_skip_next, 64))

        artUrl()?.let { url ->
            val png = downloadImage(url) ?: rasterize(R.drawable.ic_launcher_foreground, 128)
            builder.addIdToImageMapping(
                "art",
                ResourceBuilders.ImageResource
                    .Builder()
                    .setInlineImageResource(
                        ResourceBuilders.InlineImageResource
                            .Builder()
                            .setData(png)
                            .build(),
                    ).build(),
            )
        }

        return builder.build()
    }

    private fun inlineResource(
        resId: Int,
        sizePx: Int,
    ): ResourceBuilders.ImageResource =
        ResourceBuilders.ImageResource
            .Builder()
            .setInlineImageResource(
                ResourceBuilders.InlineImageResource
                    .Builder()
                    .setData(rasterize(resId, sizePx))
                    .build(),
            ).build()

    private fun rasterize(
        resId: Int,
        sizePx: Int,
    ): ByteArray =
        try {
            val drawable = ContextCompat.getDrawable(this, resId)!!
            drawable.setBounds(0, 0, sizePx, sizePx)
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            drawable.draw(Canvas(bitmap))
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        } catch (e: Exception) {
            Timber.w(e, "PlayerTile: drawable rasterize failed")
            ByteArray(0)
        }

    private fun downloadImage(url: String): ByteArray? =
        try {
            val connection = java.net.URL(url).openConnection()
            connection.connectTimeout = 2500
            connection.readTimeout = 2500
            val bytes = connection.getInputStream().use { it.readBytes() }
            if (bytes.isEmpty() || bytes.size > 512 * 1024) null else bytes
        } catch (e: Exception) {
            Timber.w(e, "PlayerTile: art download failed")
            null
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

    private fun spacerWidth(widthDp: Float) =
        LayoutElementBuilders.Spacer
            .Builder()
            .setWidth(dp(widthDp))
            .build()

    private companion object {
        const val ID_PREV = "meld_tile_prev"
        const val ID_TOGGLE = "meld_tile_toggle"
        const val ID_NEXT = "meld_tile_next"
    }
}
