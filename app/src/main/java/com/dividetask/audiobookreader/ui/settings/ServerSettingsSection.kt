package com.dividetask.audiobookreader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.dividetask.audiobookreader.remote.BackupEngine
import com.dividetask.audiobookreader.remote.BackupFrequency
import com.dividetask.audiobookreader.remote.BackupPhase
import com.dividetask.audiobookreader.remote.BackupScheduler
import com.dividetask.audiobookreader.remote.ServerAddress
import com.dividetask.audiobookreader.remote.ServerSettings
import com.dividetask.audiobookreader.remote.RemoteStorage
import com.dividetask.audiobookreader.remote.S3Client
import com.dividetask.audiobookreader.remote.SftpClient
import com.dividetask.audiobookreader.remote.StorageKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class ServerSettingsViewModel @Inject constructor(
    private val settings: ServerSettings,
    private val storage: RemoteStorage,
    private val scheduler: BackupScheduler,
    private val websiteNotifier: com.dividetask.audiobookreader.remote.WebsiteBackupNotifier,
    engine: BackupEngine
) : ViewModel() {

    var host by mutableStateOf(settings.host)
    var port by mutableStateOf(settings.port.toString())
    var username by mutableStateOf(settings.username)
    var password by mutableStateOf(settings.password)
    var folder by mutableStateOf(settings.folder)
    var kind by mutableStateOf(settings.storageKind)
    var s3Endpoint by mutableStateOf(settings.s3Endpoint)
    var s3Region by mutableStateOf(settings.s3Region)
    var s3AccessKey by mutableStateOf(settings.s3AccessKey)
    var s3SecretKey by mutableStateOf(settings.s3SecretKey)
    var s3Bucket by mutableStateOf(settings.s3Bucket)
    var syncWifiOnly by mutableStateOf(settings.syncWifiOnly)
        private set
    var testStatus by mutableStateOf("")
        private set
    var testing by mutableStateOf(false)
        private set
    var backupEnabled by mutableStateOf(settings.backupEnabled)
        private set
    var frequency by mutableStateOf(settings.frequency)
        private set
    var hostKey by mutableStateOf(settings.hostKey)
        private set
    var failedCount by mutableStateOf(failedBooks())
        private set

    val status = engine.status
    val backupNowPending = scheduler.backupNowPending
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val audibleBackupState = scheduler.audibleBackupState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val localBackupState = scheduler.localBackupState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val lastRun: String
        get() = if (settings.lastRunAt == 0L) "" else
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(settings.lastRunAt)) +
                " — " + settings.lastRunMessage

    val hostKeyFingerprint: String
        get() = hostKey.substringAfter(' ', "").takeIf { it.isNotEmpty() }?.let {
            try { SftpClient.fingerprint(it) } catch (_: Exception) { null }
        } ?: ""

    private fun failedBooks() = settings.failures.count { it.value >= BackupEngine.MAX_FAILURES }

    val hasUnsavedChanges: Boolean
        get() = kind != settings.storageKind || folder.trim() != settings.folder || when (kind) {
            StorageKind.SFTP -> host.trim() != settings.host || port != settings.port.toString() ||
                username.trim() != settings.username || password != settings.password
            StorageKind.S3 -> s3Endpoint.trim() != settings.s3Endpoint || s3Region.trim() != settings.s3Region ||
                s3AccessKey.trim() != settings.s3AccessKey || s3SecretKey.trim() != settings.s3SecretKey ||
                s3Bucket.trim() != settings.s3Bucket
        }

    /** Enough filled in to try connecting. */
    val canTest: Boolean
        get() = when (kind) {
            StorageKind.SFTP -> host.isNotBlank() && username.isNotBlank()
            StorageKind.S3 -> s3Endpoint.isNotBlank() && s3AccessKey.isNotBlank() && s3SecretKey.isNotBlank() && s3Bucket.isNotBlank()
        }

    /** The region the app will use when the Region field is left blank. */
    val guessedRegion: String
        get() = try {
            S3Client.guessRegion(java.net.URL(S3Client.normalizeEndpoint(s3Endpoint)).host)
        } catch (_: Exception) { "" }

    /** Splits a typed URL (sftp://user@host:port/path) into the separate fields, then saves. */
    fun save() {
        settings.storageKind = kind
        settings.s3Endpoint = s3Endpoint.trim()
        settings.s3Region = s3Region.trim()
        settings.s3AccessKey = s3AccessKey.trim()
        settings.s3SecretKey = s3SecretKey.trim()
        settings.s3Bucket = s3Bucket.trim()
        if (kind == StorageKind.SFTP) ServerAddress.parse(host)?.let { addr ->
            host = addr.host
            addr.port?.let { port = it.toString() }
            addr.user?.let { username = it }
            addr.path?.let { folder = it }
        }
        settings.host = host.trim()
        settings.port = port.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 22
        port = settings.port.toString()
        settings.username = username.trim()
        settings.password = password
        settings.folder = folder.trim().ifEmpty { "Audiobooks" }
        folder = settings.folder
        scheduler.applySchedule()
    }

    fun testConnection() {
        save()
        testing = true
        testStatus = "Connecting..."
        viewModelScope.launch(Dispatchers.IO) {
            testStatus = try {
                val result = storage.use { conn -> conn.root to conn.listAudio().size }
                "Connected — ${result.second} audio files in ${result.first}"
            } catch (e: Exception) {
                "Failed: ${e.message}"
            }
            hostKey = settings.hostKey
            testing = false
        }
    }

    fun forgetHostKey() {
        settings.hostKey = ""
        hostKey = ""
    }

    fun updateBackupEnabled(enabled: Boolean) {
        settings.backupEnabled = enabled
        backupEnabled = enabled
        scheduler.applySchedule()
    }

    var websiteBackup by mutableStateOf(settings.websiteBackup)
        private set

    fun updateWebsiteBackup(value: Boolean) {
        settings.websiteBackup = value
        websiteBackup = value
    }

    /** "Paused until …" while the website refused a download in the last day; null otherwise. */
    val websitePaused: String?
        get() = if (websiteNotifier.isBlocked) "Paused until ${websiteNotifier.blockedUntilText()}" +
            settings.websiteBlockedReason.takeIf { it.isNotEmpty() }?.let { " — $it" }.orEmpty() else null

    fun updateFrequency(value: BackupFrequency) {
        settings.frequency = value
        frequency = value
        scheduler.applySchedule()
    }

    fun updateSyncWifiOnly(value: Boolean) {
        settings.syncWifiOnly = value
        syncWifiOnly = value
        scheduler.networkRuleChanged()
    }

    fun backupNow() = scheduler.backupNow()

    fun cancelBackupNow() = scheduler.cancelBackupNow()

    fun retryFailed() {
        settings.failures = emptyMap()
        failedCount = 0
    }
}

@Composable
fun ServerSettingsSection(onWebsiteBackup: () -> Unit = {}, viewModel: ServerSettingsViewModel = hiltViewModel()) {
    val status by viewModel.status.collectAsState()
    val backupNowPending by viewModel.backupNowPending.collectAsState()
    val audibleBackupState by viewModel.audibleBackupState.collectAsState()
    val localBackupState by viewModel.localBackupState.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Text("Server sync", style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(8.dp))

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StorageKind.entries.forEach { k ->
                    FilterChip(selected = viewModel.kind == k, onClick = { viewModel.kind = k }, label = { Text(k.label) })
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            when (viewModel.kind) {
                StorageKind.SFTP -> {
                    OutlinedTextField(
                        value = viewModel.host, onValueChange = { viewModel.host = it },
                        label = { Text("Server address") },
                        placeholder = { Text("192.168.1.10 or sftp://example.com:2222") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )
                    Text("IP address, hostname or URL — port, user and folder in a URL fill the fields below",
                        style = MaterialTheme.typography.bodySmall, color = muted)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row {
                        OutlinedTextField(
                            value = viewModel.username, onValueChange = { viewModel.username = it },
                            label = { Text("Username") }, singleLine = true, modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedTextField(
                            value = viewModel.port, onValueChange = { viewModel.port = it.filter(Char::isDigit) },
                            label = { Text("Port") }, singleLine = true, modifier = Modifier.width(96.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = viewModel.password, onValueChange = { viewModel.password = it },
                        label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                }
                StorageKind.S3 -> {
                    OutlinedTextField(
                        value = viewModel.s3Endpoint, onValueChange = { viewModel.s3Endpoint = it },
                        label = { Text("Endpoint") },
                        placeholder = { Text("s3.region.example.com") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )
                    Text("Your provider's S3 endpoint for the bucket. Works with any S3-compatible storage.",
                        style = MaterialTheme.typography.bodySmall, color = muted)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row {
                        OutlinedTextField(
                            value = viewModel.s3Bucket, onValueChange = { viewModel.s3Bucket = it },
                            label = { Text("Bucket") }, singleLine = true, modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedTextField(
                            value = viewModel.s3Region, onValueChange = { viewModel.s3Region = it },
                            label = { Text("Region") }, singleLine = true, modifier = Modifier.width(140.dp),
                            placeholder = { Text(viewModel.guessedRegion.ifEmpty { "auto" }) }
                        )
                    }
                    Text("Leave Region blank to use the one in the endpoint",
                        style = MaterialTheme.typography.bodySmall, color = muted)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = viewModel.s3AccessKey, onValueChange = { viewModel.s3AccessKey = it },
                        label = { Text("Access key ID") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = viewModel.s3SecretKey, onValueChange = { viewModel.s3SecretKey = it },
                        label = { Text("Secret key") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = viewModel.folder, onValueChange = { viewModel.folder = it },
                label = { Text("Folder") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Text(
                (if (viewModel.kind == StorageKind.SFTP) "Relative to your home directory unless it starts with /. "
                else "Folder inside the bucket. ") + "Books go in <Folder>/<Author>/<Title>.m4b",
                style = MaterialTheme.typography.bodySmall, color = muted
            )

            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.save() }, enabled = viewModel.hasUnsavedChanges, modifier = Modifier.weight(1f)) {
                    Text("Save")
                }
                OutlinedButton(onClick = { viewModel.testConnection() }, enabled = !viewModel.testing && viewModel.canTest,
                    modifier = Modifier.weight(1f)) {
                    Text("Test connection")
                }
            }
            if (viewModel.testStatus.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(viewModel.testStatus, style = MaterialTheme.typography.bodySmall,
                    color = if (viewModel.testStatus.startsWith("Failed")) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary)
            }
            if (viewModel.kind == StorageKind.SFTP && viewModel.hostKeyFingerprint.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Server key: ${viewModel.hostKeyFingerprint}", style = MaterialTheme.typography.bodySmall,
                        color = muted, modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.forgetHostKey() }) { Text("Forget") }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = viewModel.syncWifiOnly, onCheckedChange = { viewModel.updateSyncWifiOnly(it) })
                Column(modifier = Modifier.weight(1f)) {
                    Text("Sync only over WiFi", style = MaterialTheme.typography.bodyMedium)
                    Text("Uploads, backups and Server-tab downloads wait for WiFi",
                        style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = viewModel.backupEnabled, onCheckedChange = { viewModel.updateBackupEnabled(it) })
                Column(modifier = Modifier.weight(1f)) {
                    Text("Back up books to server", style = MaterialTheme.typography.bodyMedium)
                    Text("Books on this phone are uploaded whenever there's a connection (WiFi, if set above). " +
                        "Audible books that aren't on the phone are downloaded, converted and uploaded one at a " +
                        "time while charging and idle, then the phone copy is deleted.",
                        style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
            if (viewModel.backupEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Download one Audible book per", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BackupFrequency.entries.forEach { f ->
                        FilterChip(selected = viewModel.frequency == f, onClick = { viewModel.updateFrequency(f) },
                            label = { Text(f.label) })
                    }
                }
                if (localBackupState == WorkInfo.State.ENQUEUED) {
                    Text("Phone copies waiting for a connection", style = MaterialTheme.typography.bodySmall, color = muted)
                }
                if (audibleBackupState == WorkInfo.State.ENQUEUED) {
                    Text("Next Audible book waiting for charging and idle",
                        style = MaterialTheme.typography.bodySmall, color = muted)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = viewModel.websiteBackup, onCheckedChange = { viewModel.updateWebsiteBackup(it) })
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Use the Audible website when Audible limits downloads", style = MaterialTheme.typography.bodyMedium)
                        Text("A notification asks you to sign in to the Audible website. Every book not on the " +
                            "server is then downloaded there, uploaded and removed from the phone, one after another. " +
                            "If the website refuses a download, it stops for a day.",
                            style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                }
                if (viewModel.websiteBackup) {
                    val paused = viewModel.websitePaused
                    if (paused != null) {
                        Text(paused, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    } else {
                        OutlinedButton(onClick = onWebsiteBackup, modifier = Modifier.fillMaxWidth()) {
                            Text("Back up everything through the website now")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            if (backupNowPending && status.phase == BackupPhase.IDLE) {
                OutlinedButton(onClick = { viewModel.cancelBackupNow() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Waiting for network — cancel")
                }
            } else {
                Button(onClick = { viewModel.backupNow() }, enabled = !backupNowPending && status.phase == BackupPhase.IDLE,
                    modifier = Modifier.fillMaxWidth()) {
                    Text("Back up next Audible book now")
                }
            }

            if (status.phase != BackupPhase.IDLE) {
                Spacer(modifier = Modifier.height(8.dp))
                val action = when (status.phase) {
                    BackupPhase.DOWNLOADING -> "Downloading"
                    BackupPhase.CONVERTING -> "Converting"
                    BackupPhase.UPLOADING -> "Uploading"
                    BackupPhase.CANCELLING -> "Cancelling unfinished upload"
                    else -> ""
                }
                Text(if (action.isEmpty()) status.title else "$action: ${status.title}",
                    style = MaterialTheme.typography.bodySmall)
                if (status.remaining > 0) {
                    Text("${status.remaining} books left", style = MaterialTheme.typography.bodySmall, color = muted)
                }
                Spacer(modifier = Modifier.height(4.dp))
                if (status.total > 0) {
                    LinearProgressIndicator(progress = { status.bytes.toFloat() / status.total }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            } else if (viewModel.lastRun.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Last run: ${viewModel.lastRun}", style = MaterialTheme.typography.bodySmall, color = muted)
            }

            if (viewModel.failedCount > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${viewModel.failedCount} books skipped after repeated failures",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.retryFailed() }) { Text("Retry") }
                }
            }
        }
    }
}
