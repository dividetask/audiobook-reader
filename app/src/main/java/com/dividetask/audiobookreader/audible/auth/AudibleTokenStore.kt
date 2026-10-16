package com.dividetask.audiobookreader.audible.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudibleTokenStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = createPrefs()

    private fun createPrefs(): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                "audible_tokens",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e("AudibleTokenStore", "EncryptedSharedPreferences failed, clearing and retrying", e)
            try {
                File(context.filesDir.parent, "shared_prefs/audible_tokens.xml").delete()
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    "audible_tokens",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e2: Exception) {
                Log.e("AudibleTokenStore", "Fallback to plain SharedPreferences", e2)
                context.getSharedPreferences("audible_tokens_plain", Context.MODE_PRIVATE)
            }
        }
    }

    var accessToken: String?
        get() = try { prefs.getString("access_token", null) } catch (_: Exception) { null }
        set(value) { try { prefs.edit().putString("access_token", value).apply() } catch (_: Exception) {} }

    var refreshToken: String?
        get() = try { prefs.getString("refresh_token", null) } catch (_: Exception) { null }
        set(value) { try { prefs.edit().putString("refresh_token", value).apply() } catch (_: Exception) {} }

    var devicePrivateKey: String?
        get() = try { prefs.getString("device_private_key", null) } catch (_: Exception) { null }
        set(value) { try { prefs.edit().putString("device_private_key", value).apply() } catch (_: Exception) {} }

    var adpToken: String?
        get() = try { prefs.getString("adp_token", null) } catch (_: Exception) { null }
        set(value) { try { prefs.edit().putString("adp_token", value).apply() } catch (_: Exception) {} }

    var activationBytes: String?
        get() = try { prefs.getString("activation_bytes", null) } catch (_: Exception) { null }
        set(value) { try { prefs.edit().putString("activation_bytes", value).apply() } catch (_: Exception) {} }

    var deviceSerial: String?
        get() = try { prefs.getString("device_serial", null) } catch (_: Exception) { null }
        set(value) { try { prefs.edit().putString("device_serial", value).apply() } catch (_: Exception) {} }

    var customerId: String?
        get() = try { prefs.getString("customer_id", null) } catch (_: Exception) { null }
        set(value) { try { prefs.edit().putString("customer_id", value).apply() } catch (_: Exception) {} }

    var locale: String
        get() = try { prefs.getString("locale", "us") ?: "us" } catch (_: Exception) { "us" }
        set(value) { try { prefs.edit().putString("locale", value).apply() } catch (_: Exception) {} }

    var downloadQuality: String
        get() = try { prefs.getString("download_quality", "High") ?: "High" } catch (_: Exception) { "High" }
        set(value) { try { prefs.edit().putString("download_quality", value).apply() } catch (_: Exception) {} }

    var debugLogLevel: String
        get() = try { prefs.getString("debug_log_level", "Normal") ?: "Normal" } catch (_: Exception) { "Normal" }
        set(value) { try { prefs.edit().putString("debug_log_level", value).apply() } catch (_: Exception) {} }

    var wifiOnlyDownloads: Boolean
        get() = try { prefs.getBoolean("wifi_only_downloads", true) } catch (_: Exception) { true }
        set(value) { try { prefs.edit().putBoolean("wifi_only_downloads", value).apply() } catch (_: Exception) {} }

    val isAuthenticated: Boolean
        get() = !accessToken.isNullOrBlank()

    fun clear() {
        try { prefs.edit().clear().apply() } catch (_: Exception) {}
    }
}
