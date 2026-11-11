package com.dividetask.audiobookreader.data.details

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * API credentials for book-info services (Hardcover reviews, Spotify availability).
 * Kept apart from the Audible token store so signing out of Audible doesn't wipe them.
 */
@Singleton
class ServiceKeys @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            context,
            "service_keys",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        context.getSharedPreferences("service_keys_plain", Context.MODE_PRIVATE)
    }

    private fun get(key: String): String = try { prefs.getString(key, "") ?: "" } catch (_: Exception) { "" }
    private fun set(key: String, value: String) {
        try { prefs.edit().putString(key, value.trim()).apply() } catch (_: Exception) {}
    }

    /** From hardcover.app → Settings → Hardcover API. May include the "Bearer " prefix. */
    var hardcoverToken: String
        get() = get("hardcover_token")
        set(value) = set("hardcover_token", value)

    /** From developer.spotify.com → Dashboard → your app. */
    var spotifyClientId: String
        get() = get("spotify_client_id")
        set(value) = set("spotify_client_id", value)

    var spotifyClientSecret: String
        get() = get("spotify_client_secret")
        set(value) = set("spotify_client_secret", value)
}
