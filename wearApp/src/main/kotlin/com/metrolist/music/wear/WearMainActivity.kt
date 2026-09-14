/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.metrolist.music.wear.bridge.MeldWear
import com.metrolist.music.wear.ui.WearAppRoot
import com.metrolist.music.wear.ui.theme.MeldWearTheme
import kotlinx.coroutines.launch

/**
 * Single activity for the whole Wear UI.
 *
 * No Hilt on purpose: the watch side needs one application context and one bridge object, and
 * wiring a DI framework in would mean running KSP in this module for no gain.
 */
class WearMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MeldWear.init(applicationContext)
        setContent {
            MeldWearTheme {
                WearAppRoot()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // As soon as the wrist turns, ask for state: the first frame should never be stale.
        lifecycleScope.launch { MeldWear.refresh() }
    }
}
