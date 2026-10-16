package com.dividetask.audiobookreader.debug

import com.dividetask.audiobookreader.audible.auth.AudibleTokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppDebugLog @Inject constructor(
    private val tokenStore: AudibleTokenStore
) {
    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(tag: String, message: String, level: LogLevel = LogLevel.NORMAL) {
        val configuredLevel = when (tokenStore.debugLogLevel) {
            "Minimal" -> LogLevel.IMPORTANT
            "Verbose" -> LogLevel.VERBOSE
            else -> LogLevel.NORMAL
        }
        if (level.ordinal > configuredLevel.ordinal) return

        val timestamp = timeFormat.format(Date())
        val entry = "[$timestamp] $tag: $message"
        _entries.value = _entries.value + entry
        android.util.Log.d("AppDebug", entry)
    }

    fun section(title: String) {
        _entries.value = _entries.value + "" + "=== $title ==="
    }

    fun clear() {
        _entries.value = emptyList()
    }

    fun getFullLog(): String = _entries.value.joinToString("\n")

    enum class LogLevel {
        IMPORTANT,
        NORMAL,
        VERBOSE
    }
}
