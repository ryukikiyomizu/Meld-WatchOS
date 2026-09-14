/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.wear.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.metrolist.music.bridge.WearBridge
import com.metrolist.music.wear.R
import com.metrolist.music.wear.ui.WearRouter

/**
 * Songs that are stored on the phone. Same browse container Android Auto uses, so the list is
 * exactly "downloads that finished", and tapping one plays it through the phone.
 *
 * Removing is supported (the cross on each row); starting a *new* download is only available for
 * songs already in the library, which the player screen exposes.
 */
@Composable
fun DownloadsScreen(router: WearRouter) {
    BrowseScreen(
        parentId = WearBridge.downloadedContainer(),
        label = stringResource(R.string.downloaded),
        router = router,
        removable = true,
    )
}
