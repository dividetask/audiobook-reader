package com.dividetask.audiobookreader.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.dividetask.audiobookreader.MainActivity
import com.dividetask.audiobookreader.debug.AppDebugLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Website backup: when Audible's download limit stops the backup, books are downloaded
 * through the Audible website instead (the user signs in once in the in-app browser).
 * This posts the notifications for it and keeps the one-day pause after the website refuses.
 */
@Singleton
class WebsiteBackupNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: ServerSettings,
    private val debugLog: AppDebugLog
) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var lastPromptAt = 0L

    init {
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Website backup", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    val isBlocked: Boolean get() = System.currentTimeMillis() < settings.websiteBlockedUntil

    /** Website backup is on and not paused. */
    val isActive: Boolean get() = settings.websiteBackup && !isBlocked

    fun blockedUntilText(): String =
        SimpleDateFormat("EEE MMM d, h:mm a", Locale.getDefault()).format(Date(settings.websiteBlockedUntil))

    /** Audible's API turned the backup away: ask the user to continue through the website. */
    fun promptThrottled(title: String?) {
        if (!isActive) return
        // One prompt every few hours is enough; each backup attempt would otherwise post again
        val now = System.currentTimeMillis()
        if (now - lastPromptAt < PROMPT_INTERVAL_MS) return
        lastPromptAt = now
        debugLog.log("WEBBACKUP", "Audible is limiting downloads — asking to back up through the website" +
            (title?.let { " (next: $it)" } ?: ""))
        post(PROMPT_ID, "Audible is limiting downloads",
            "Tap to sign in to the Audible website and keep backing up your books to the server.")
    }

    /** The website refused a download: stop website backups for a day and tell the user. */
    fun block(reason: String) {
        settings.websiteBlockedUntil = System.currentTimeMillis() + BLOCK_MS
        settings.websiteBlockedReason = reason
        debugLog.log("WEBBACKUP", "Audible website refused a download: $reason — paused until ${blockedUntilText()}")
        notificationManager.cancel(PROMPT_ID)
        post(BLOCKED_ID, "Audible website refused a download",
            "Website backup paused until ${blockedUntilText()}. $reason", openOnTap = false)
    }

    fun clearPrompt() = notificationManager.cancel(PROMPT_ID)

    /** A website download the user started stopped and needs a new sign-in: tapping reopens it. */
    fun promptSignIn(asin: String, title: String) {
        debugLog.log("WEBBACKUP", "Asking to sign in again to continue \"$title\"")
        post(SIGN_IN_ID, "Download of \"$title\" stopped",
            "Tap to sign in to the Audible website again — the download continues where it stopped.", openAsin = asin)
    }

    /** The website backup stopped because the website session ended. */
    fun promptBackupSignIn(title: String) {
        debugLog.log("WEBBACKUP", "Website backup stopped on \"$title\" — asking to sign in again")
        post(PROMPT_ID, "Website backup stopped",
            "Tap to sign in to the Audible website again — \"$title\" continues where it stopped.")
    }

    private fun post(id: Int, title: String, text: String, openOnTap: Boolean = true, openAsin: String? = null) {
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (openAsin != null) putExtra(EXTRA_ASIN, openAsin) else if (openOnTap) putExtra(EXTRA_OPEN, true)
        }
        builder.setContentIntent(PendingIntent.getActivity(context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        try {
            notificationManager.notify(id, builder.build())
        } catch (e: SecurityException) {
            debugLog.log("WEBBACKUP", "Notification not allowed: ${e.message}")
        }
    }

    companion object {
        /** Intent extra: open the website backup screen. */
        const val EXTRA_OPEN = "open_website_backup"
        /** Intent extra: open the website download of this ASIN. */
        const val EXTRA_ASIN = "open_website_download"
        private const val SIGN_IN_ID = 4203
        private const val CHANNEL = "website_backup"
        private const val PROMPT_ID = 4201
        private const val BLOCKED_ID = 4202
        private const val PROMPT_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val BLOCK_MS = 24 * 60 * 60 * 1000L
    }
}
