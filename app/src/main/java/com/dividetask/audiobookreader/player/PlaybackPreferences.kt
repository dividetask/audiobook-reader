package com.dividetask.audiobookreader.player

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("playback_prefs", Context.MODE_PRIVATE)

    private val _skipBackSeconds = MutableStateFlow(prefs.getInt(KEY_SKIP_BACK, DEFAULT_SKIP_BACK))
    val skipBackSeconds: StateFlow<Int> = _skipBackSeconds.asStateFlow()

    private val _skipForwardSeconds = MutableStateFlow(prefs.getInt(KEY_SKIP_FORWARD, DEFAULT_SKIP_FORWARD))
    val skipForwardSeconds: StateFlow<Int> = _skipForwardSeconds.asStateFlow()

    fun setSkipBackSeconds(seconds: Int) {
        val value = seconds.coerceIn(MIN_SKIP, MAX_SKIP)
        prefs.edit().putInt(KEY_SKIP_BACK, value).apply()
        _skipBackSeconds.value = value
    }

    fun setSkipForwardSeconds(seconds: Int) {
        val value = seconds.coerceIn(MIN_SKIP, MAX_SKIP)
        prefs.edit().putInt(KEY_SKIP_FORWARD, value).apply()
        _skipForwardSeconds.value = value
    }

    companion object {
        const val MIN_SKIP = 1
        const val MAX_SKIP = 3600
        private const val DEFAULT_SKIP_BACK = 15
        private const val DEFAULT_SKIP_FORWARD = 30
        private const val KEY_SKIP_BACK = "skip_back_seconds"
        private const val KEY_SKIP_FORWARD = "skip_forward_seconds"
    }
}
