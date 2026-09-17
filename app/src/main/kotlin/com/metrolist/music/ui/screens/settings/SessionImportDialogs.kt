/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.utils.SessionTransfer
import kotlinx.coroutines.launch

/**
 * Self-contained "Import session" flow: chooser (file / clipboard / ADB push),
 * ADB help dialog and the import confirmation dialog. Used from both
 * Backup & Restore settings and the account screen (as the replacement for
 * the removed WebView login flow).
 */
@Composable
fun SessionImportDialogs(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showChooser by rememberSaveable { mutableStateOf(true) }
    var showAdbHelp by rememberSaveable { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<SessionTransfer.Parsed?>(null) }

    fun handleImportedSessionText(text: String?) {
        val parsed = SessionTransfer.parsePayload(text)
        if (parsed == null) {
            Toast.makeText(context, R.string.session_code_invalid, Toast.LENGTH_SHORT).show()
        } else {
            pendingImport = parsed
        }
    }

    val importSessionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val text =
                runCatching {
                    context.applicationContext.contentResolver.openInputStream(uri)?.use {
                        it.bufferedReader().readText()
                    }
                }.getOrNull()
            handleImportedSessionText(text)
        }

    if (showChooser) {
        DefaultDialog(
            onDismiss = {
                showChooser = false
                onDismiss()
            },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.restore),
                    contentDescription = null,
                )
            },
            title = { Text(stringResource(R.string.import_session)) },
            buttons = {
                TextButton(
                    onClick = {
                        showChooser = false
                        importSessionLauncher.launch(arrayOf("text/plain", "text/*", "*/*"))
                    },
                ) {
                    Text(stringResource(R.string.session_import_from_file))
                }
                TextButton(
                    onClick = {
                        showChooser = false
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                        handleImportedSessionText(text)
                    },
                ) {
                    Text(stringResource(R.string.session_import_from_clipboard))
                }
                TextButton(
                    onClick = {
                        showChooser = false
                        val file = SessionTransfer.findAdbPushedFile(context)
                        if (file != null) {
                            handleImportedSessionText(runCatching { file.readText() }.getOrNull())
                        } else {
                            showAdbHelp = true
                        }
                    },
                ) {
                    Text(stringResource(R.string.session_import_from_adb))
                }
            },
        ) {
            Text(
                text = stringResource(R.string.session_import_help),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    if (showAdbHelp) {
        DefaultDialog(
            onDismiss = {
                showAdbHelp = false
                onDismiss()
            },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.restore),
                    contentDescription = null,
                )
            },
            title = { Text(stringResource(R.string.session_import_from_adb)) },
            buttons = {
                TextButton(
                    onClick = {
                        showAdbHelp = false
                        onDismiss()
                    },
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(
                    onClick = {
                        val file = SessionTransfer.findAdbPushedFile(context)
                        if (file != null) {
                            showAdbHelp = false
                            handleImportedSessionText(runCatching { file.readText() }.getOrNull())
                        } else {
                            Toast
                                .makeText(context, R.string.session_adb_not_found, Toast.LENGTH_SHORT)
                                .show()
                        }
                    },
                ) {
                    Text(stringResource(R.string.session_adb_retry))
                }
            },
        ) {
            Text(
                text = stringResource(R.string.session_adb_help, context.packageName),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    pendingImport?.let { parsed ->
        DefaultDialog(
            onDismiss = {
                pendingImport = null
                onDismiss()
            },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.person),
                    contentDescription = null,
                )
            },
            title = { Text(stringResource(R.string.session_import_confirm_title)) },
            buttons = {
                TextButton(
                    onClick = {
                        pendingImport = null
                        onDismiss()
                    },
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            SessionTransfer.apply(context, parsed)
                            // apply() restarts the process; this line is never reached
                        }
                    },
                ) {
                    Text(stringResource(R.string.session_import))
                }
            },
        ) {
            Text(
                text = stringResource(R.string.session_import_confirm_message),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = parsed.info.accountEmail ?: parsed.info.accountName
                    ?: stringResource(R.string.session_import_unknown_account),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (parsed.info.hasYouTube) {
                Text(
                    text = stringResource(R.string.session_import_includes_youtube),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (parsed.info.hasSpotify) {
                Text(
                    text = stringResource(R.string.session_import_includes_spotify),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
