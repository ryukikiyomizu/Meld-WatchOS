/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.metrolist.music.utils.rememberRoundScreenInsets
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation.NavController
import com.metrolist.music.BuildConfig
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.constants.MinimalModeKey
import com.metrolist.music.utils.LinkSender
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.metrolist.music.utils.rememberPreference
import androidx.compose.foundation.layout.size
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.ReleaseNotesCard
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.Updater
import androidx.compose.runtime.remember

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    latestVersionName: String,
) {
    val (minimalMode, onMinimalModeChange) = rememberPreference(MinimalModeKey, false)
    val scope = rememberCoroutineScope()

    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val isAndroid12OrLater = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val hasAndroidAuto = remember {
        try {
            context.packageManager.getPackageInfo(
                "com.google.android.projection.gearhead", 0
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Top
                )
            )
        )

        // Minimal mode (watch <-> phone head-unit mode)
        Material3SettingsGroup(
            title = stringResource(R.string.minimal_mode),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.phone),
                    title = { Text(stringResource(R.string.minimal_mode)) },
                    description = { Text(stringResource(R.string.minimal_mode_desc)) },
                    trailingContent = {
                        Switch(
                            checked = minimalMode,
                            onCheckedChange = { on ->
                                scope.launch {
                                    if (on && !LinkSender.hasConnectedNodeQuick(context)) {
                                        android.widget.Toast
                                            .makeText(context, R.string.minimal_connect_required, android.widget.Toast.LENGTH_SHORT)
                                            .show()
                                        return@launch
                                    }
                                    onMinimalModeChange(on)
                                    LinkSender.send(context, LinkSender.PATH_MINIMAL, if (on) "1" else "0")
                                }
                            },
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (minimalMode) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = {
                        scope.launch {
                            val on = !minimalMode
                            if (on && !LinkSender.hasConnectedNodeQuick(context)) {
                                android.widget.Toast
                                    .makeText(context, R.string.minimal_connect_required, android.widget.Toast.LENGTH_SHORT)
                                    .show()
                                return@launch
                            }
                            onMinimalModeChange(on)
                            LinkSender.send(context, LinkSender.PATH_MINIMAL, if (on) "1" else "0")
                        }
                    }
                )
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        // User Interface Section
        Material3SettingsGroup(
            title = stringResource(R.string.settings_section_ui),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.palette),
                    title = { Text(stringResource(R.string.appearance)) },
                    onClick = { navController.navigate("settings/appearance") }
                )
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Player & Content Section (moved up and combined with content)
        Material3SettingsGroup(
            title = stringResource(R.string.settings_section_player_content),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.play),
                    title = { Text(stringResource(R.string.player_and_audio)) },
                    onClick = { navController.navigate("settings/player") }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.language),
                    title = { Text(stringResource(R.string.content)) },
                    onClick = { navController.navigate("settings/content") }
                ),
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Android Auto Section — only shown if Android Auto is installed
        if (hasAndroidAuto) {
            Material3SettingsGroup(
                title = "Android Auto",
                items = listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.ic_android_auto),
                        title = { Text(stringResource(R.string.android_auto)) },
                        onClick = { navController.navigate("settings/android_auto") }
                    )
                )
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
        
        // Privacy & Security Section
        Material3SettingsGroup(
            title = stringResource(R.string.settings_section_privacy),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.security),
                    title = { Text(stringResource(R.string.privacy)) },
                    onClick = { navController.navigate("settings/privacy") }
                )
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Storage & Data Section
        Material3SettingsGroup(
            title = stringResource(R.string.settings_section_storage),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.storage),
                    title = { Text(stringResource(R.string.storage)) },
                    onClick = { navController.navigate("settings/storage") }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.restore),
                    title = { Text(stringResource(R.string.backup_restore)) },
                    onClick = { navController.navigate("settings/backup_restore") }
                )
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        // System & About Section
        Material3SettingsGroup(
            title = stringResource(R.string.settings_section_system),
            items = buildList {
                add(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.info),
                        title = { Text(stringResource(R.string.about)) },
                        onClick = { navController.navigate("settings/about") }
                    )
                )

            }
        )
        Spacer(modifier = Modifier.height(16.dp))
    }

    val roundInsets = rememberRoundScreenInsets()

    TopAppBar(
        title = {
            Text(
                text = stringResource(R.string.settings),
                textAlign = if (roundInsets.isRound) TextAlign.Center else TextAlign.Start,
                modifier = if (roundInsets.isRound) Modifier.fillMaxWidth() else Modifier,
            )
        },
        navigationIcon = {
            if (!roundInsets.isRound) {
                IconButton(
                    onClick = navController::navigateUp,
                    onLongClick = navController::backToMain
                ) {
                    Icon(
                        painterResource(R.drawable.arrow_back),
                        contentDescription = null
                    )
                }
            }
        }
    )
}
