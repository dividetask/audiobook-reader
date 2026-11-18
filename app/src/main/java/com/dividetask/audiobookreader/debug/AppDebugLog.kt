package com.dividetask.audiobookreader.debug

import android.content.Context
import com.dividetask.audiobookreader.audible.auth.AudibleTokenStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The in-app debug log. Every entry is also written to a file as it's logged, so after a
 * crash the next run starts with the end of the previous run's log.
 */
@Singleton
class AppDebugLog @Inject constructor(
    @ApplicationContext context: Context,
    private val tokenStore: AudibleTokenStore
) {
    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val file = File(context.filesDir, "debug-log.txt")
    private var writer: Writer? = null

    init {
        try {
            if (file.exists()) {
                val previous = file.readLines().takeLast(PREVIOUS_LINES)
                if (previous.isNotEmpty()) {
                    _entries.value = listOf("=== Previous run (last ${previous.size} lines) ===") + previous +
                        listOf("", "=== This run ===")
                }
            }
            writer = file.bufferedWriter()
        } catch (e: Exception) {
            android.util.Log.e("AppDebug", "Log file unavailable: ${e.message}")
        }
    }

    @Synchronized
    private fun append(lines: List<String>) {
        _entries.value = _entries.value + lines
        try {
            writer?.apply {
                lines.forEach { write(it); write("\n") }
                // Flushed every time, so the lines just before a crash are kept
                flush()
            }
        } catch (e: Exception) {
            writer = null
        }
    }

    fun log(tag: String, message: String, level: LogLevel = LogLevel.NORMAL) {
        val configuredLevel = when (tokenStore.debugLogLevel) {
            "Minimal" -> LogLevel.IMPORTANT
            "Verbose" -> LogLevel.VERBOSE
            else -> LogLevel.NORMAL
        }
        if (level.ordinal > configuredLevel.ordinal) return

        val timestamp = timeFormat.format(Date())
        val entry = "[$timestamp] $tag: $message"
        append(listOf(entry))
        android.util.Log.d("AppDebug", entry)
    }

    fun section(title: String) {
        append(listOf("", "=== $title ==="))
    }

    /** Logs an uncaught exception, which is about to end the app. */
    fun crash(thread: Thread, e: Throwable) {
        append(listOf("", "=== CRASH in ${thread.name} ===") + e.stackTraceToString().lines().take(60))
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
    }

    fun getFullLog(): String = _entries.value.joinToString("\n")

    enum class LogLevel {
        IMPORTANT,
        NORMAL,
        VERBOSE
    }

    private companion object {
        const val PREVIOUS_LINES = 400
    }
}
