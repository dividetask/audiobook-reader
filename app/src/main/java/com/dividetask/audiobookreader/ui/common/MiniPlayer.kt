package com.dividetask.audiobookreader.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dividetask.audiobookreader.player.PlaybackController
import com.dividetask.audiobookreader.player.PlaybackPreferences
import com.dividetask.audiobookreader.player.PlaybackState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MiniPlayerViewModel @Inject constructor(
    private val playbackController: PlaybackController,
    playbackPreferences: PlaybackPreferences
) : ViewModel() {
    val playbackState: StateFlow<PlaybackState> = playbackController.playbackState
    val skipBackSeconds: StateFlow<Int> = playbackPreferences.skipBackSeconds
    val skipForwardSeconds: StateFlow<Int> = playbackPreferences.skipForwardSeconds

    init {
        // Keep the progress bar moving on screens that don't poll the player themselves
        viewModelScope.launch {
            while (true) {
                delay(1_000)
                if (playbackController.playbackState.value.isPlaying) playbackController.updateState()
            }
        }
    }

    fun togglePlayPause() = playbackController.togglePlayPause()
    fun skipBackward() = playbackController.skipBackward()
    fun skipForward() = playbackController.skipForward()
}

/** Bottom bar shown on every screen except the full player: skip back, play/pause, skip forward. */
@Composable
fun MiniPlayerBar(
    viewModel: MiniPlayerViewModel,
    onOpenPlayer: (bookId: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.playbackState.collectAsState()
    val back by viewModel.skipBackSeconds.collectAsState()
    val forward by viewModel.skipForwardSeconds.collectAsState()
    if (state.title.isBlank() || state.bookId < 0) return

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onOpenPlayer(state.bookId) },
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 0.dp, bottomEnd = 0.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(Modifier.navigationBarsPadding()) {
            if (state.durationMs > 0) {
                LinearProgressIndicator(
                    progress = { state.currentPositionMs.toFloat() / state.durationMs.toFloat() },
                    modifier = Modifier.fillMaxWidth().height(2.dp)
                )
            }
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(state.title, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(state.author, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { viewModel.skipBackward() }) {
                    SkipSecondsIcon(seconds = back, forward = false, modifier = Modifier.size(32.dp))
                }
                IconButton(onClick = { viewModel.togglePlayPause() }) {
                    if (state.isPlaying) {
                        PauseIcon(modifier = Modifier.size(28.dp))
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play", modifier = Modifier.size(28.dp))
                    }
                }
                IconButton(onClick = { viewModel.skipForward() }) {
                    SkipSecondsIcon(seconds = forward, forward = true, modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}
