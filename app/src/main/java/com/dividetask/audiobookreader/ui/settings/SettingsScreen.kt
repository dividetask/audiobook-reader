package com.dividetask.audiobookreader.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dividetask.audiobookreader.player.PlaybackPreferences

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAudibleLogin: () -> Unit,
    onDebugLog: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    var showConfirmDialog by remember { mutableStateOf(false) }
    var showManualLogin by remember { mutableStateOf(false) }
    var tokenInput by remember { mutableStateOf("") }
    val manualLoginStatus by viewModel.manualLoginStatus.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // === Audible Account ===
            Text("Audible Account", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (viewModel.isConnected) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = if (viewModel.isConnected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                if (viewModel.isConnected) "Connected" else "Not connected",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            if (viewModel.isConnected) {
                                Text("Region: ${viewModel.locale.uppercase()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    if (viewModel.hasRefreshToken) "Persistent session (device registered)"
                                    else "Temporary session (may need to re-login)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (viewModel.hasRefreshToken) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (viewModel.isConnected) {
                        Button(
                            onClick = { showConfirmDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Sign Out & Forget Tokens")
                        }
                    } else {
                        Button(
                            onClick = onAudibleLogin,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Sign In (In-App)")
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = {
                                val loginUrl = viewModel.generateLoginUrl()
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(loginUrl))
                                context.startActivity(intent)
                                showManualLogin = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Sign In (Browser)")
                        }

                        if (showManualLogin) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "After signing in, copy the entire URL from the browser's address bar and paste it here:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = tokenInput,
                                onValueChange = { tokenInput = it },
                                label = { Text("Paste URL") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3, maxLines = 5
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { viewModel.processManualLoginUrl(tokenInput) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = tokenInput.isNotBlank()
                            ) {
                                Text("Connect")
                            }
                            if (manualLoginStatus.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(manualLoginStatus, style = MaterialTheme.typography.bodySmall,
                                    color = if (manualLoginStatus.contains("Success")) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // === Playback ===
            Text("Playback", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            val skipBack by viewModel.skipBackSeconds.collectAsState()
            val skipForward by viewModel.skipForwardSeconds.collectAsState()

            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SecondsField(
                        label = "Skip back",
                        seconds = skipBack,
                        onChange = viewModel::setSkipBackSeconds,
                        modifier = Modifier.weight(1f)
                    )
                    SecondsField(
                        label = "Skip forward",
                        seconds = skipForward,
                        onChange = viewModel::setSkipForwardSeconds,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // === Download Settings ===
            Text("Download Settings", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Audio Quality", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("High", "Normal").forEach { quality ->
                            FilterChip(
                                selected = viewModel.downloadQuality == quality,
                                onClick = { viewModel.downloadQuality = quality },
                                label = { Text(quality) }
                            )
                        }
                    }
                    Text(
                        when (viewModel.downloadQuality) {
                            "High" -> "128 kbps stereo (best available)"
                            else -> "64 kbps (smaller files)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("WiFi only", style = MaterialTheme.typography.bodyMedium)
                            Text("Pause downloads on cellular data",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = viewModel.wifiOnlyDownloads,
                            onCheckedChange = { viewModel.wifiOnlyDownloads = it }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // === Server (SFTP) ===
            ServerSettingsSection()

            Spacer(modifier = Modifier.height(24.dp))

            // === Debug ===
            Text("Debug", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Log Verbosity", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Minimal", "Normal", "Verbose").forEach { level ->
                            FilterChip(
                                selected = viewModel.debugLogLevel == level,
                                onClick = { viewModel.debugLogLevel = level },
                                label = { Text(level) }
                            )
                        }
                    }
                    Text(
                        when (viewModel.debugLogLevel) {
                            "Minimal" -> "Errors and completions only"
                            "Verbose" -> "Everything including download progress"
                            else -> "Standard detail level"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(onClick = onDebugLog, modifier = Modifier.fillMaxWidth()) {
                        Text("View Debug Log")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // === Token Debug Info ===
            Text("Token Info", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    viewModel.tokenDebugInfo.forEach { line ->
                        Text(text = line, style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                }
            }
        }
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("Sign Out of Audible") },
            text = { Text("This will delete all stored authentication tokens. You will need to sign in again. Downloaded books will not be removed.") },
            confirmButton = {
                TextButton(onClick = { viewModel.signOut(); showConfirmDialog = false }) {
                    Text("Sign Out", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) { Text("Cancel") }
            }
        )
    }
}

/** Number field that saves whenever it holds a valid number of seconds. */
@Composable
private fun SecondsField(
    label: String,
    seconds: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf(seconds.toString()) }
    val value = text.toIntOrNull()
    val valid = value != null && value in PlaybackPreferences.MIN_SKIP..PlaybackPreferences.MAX_SKIP

    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter { it.isDigit() }.take(4)
            text.toIntOrNull()
                ?.takeIf { it in PlaybackPreferences.MIN_SKIP..PlaybackPreferences.MAX_SKIP }
                ?.let(onChange)
        },
        label = { Text(label) },
        suffix = { Text("sec") },
        isError = !valid,
        supportingText = if (valid) null else {
            { Text("${PlaybackPreferences.MIN_SKIP}–${PlaybackPreferences.MAX_SKIP}") }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}
