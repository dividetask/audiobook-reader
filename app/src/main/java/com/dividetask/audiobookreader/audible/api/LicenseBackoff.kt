package com.dividetask.audiobookreader.audible.api

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How long to hold off download (license) requests after Audible refuses one with
 * CustomerThrottled. Each refusal doubles the wait (1, 2, 4 … 32 days). A success ends
 * the backoff but keeps the last wait, so the next refusal starts from there instead
 * of 1 day. Saved, so it survives restarts and updates.
 */
@Singleton
class LicenseBackoff @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("license_backoff", Context.MODE_PRIVATE)

    /** No automatic license requests before this time; 0 when not backing off. */
    val until: Long
        get() = if (prefs.getBoolean(KEY_ACTIVE, false)) prefs.getLong(KEY_UNTIL, 0) else 0

    /** The current (or, after a success, the last) wait in days; 0 if never refused. */
    val intervalDays: Int
        get() = prefs.getInt(KEY_DAYS, 0)

    /** Records a refusal and returns the new wait in days. */
    @Synchronized
    fun refused(): Int {
        val last = intervalDays
        val days = when {
            prefs.getBoolean(KEY_ACTIVE, false) -> (last * 2).coerceIn(1, MAX_DAYS)
            last > 0 -> last
            else -> 1
        }
        prefs.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putInt(KEY_DAYS, days)
            .putLong(KEY_UNTIL, System.currentTimeMillis() + days * DAY_MS)
            .apply()
        return days
    }

    /** Records a granted license: back to the normal schedule, keeping the last wait. */
    @Synchronized
    fun granted(): Boolean {
        val wasActive = prefs.getBoolean(KEY_ACTIVE, false)
        if (wasActive) prefs.edit().putBoolean(KEY_ACTIVE, false).apply()
        return wasActive
    }

    companion object {
        const val MAX_DAYS = 32
        const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val KEY_ACTIVE = "active"
        private const val KEY_DAYS = "interval_days"
        private const val KEY_UNTIL = "until"
    }
}
