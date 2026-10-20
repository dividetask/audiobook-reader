package com.dividetask.audiobookreader.remote

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class BackupFrequency(val label: String, val days: Long) {
    DAILY("Daily", 1), WEEKLY("Weekly", 7), MONTHLY("Monthly", 30)
}

/** SFTP server connection details and backup preferences (encrypted at rest). */
@Singleton
class ServerSettings @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = createPrefs()

    private fun createPrefs(): SharedPreferences {
        fun create(): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                "server_settings",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
        return try {
            create()
        } catch (e: Exception) {
            Log.e("ServerSettings", "EncryptedSharedPreferences failed, clearing and retrying", e)
            try {
                File(context.filesDir.parent, "shared_prefs/server_settings.xml").delete()
                create()
            } catch (e2: Exception) {
                Log.e("ServerSettings", "Fallback to plain SharedPreferences", e2)
                context.getSharedPreferences("server_settings_plain", Context.MODE_PRIVATE)
            }
        }
    }

    private fun str(key: String, def: String) = try { prefs.getString(key, def) ?: def } catch (_: Exception) { def }
    private fun putStr(key: String, value: String) { try { prefs.edit().putString(key, value).apply() } catch (_: Exception) {} }

    var host: String
        get() = str("host", "")
        set(value) = putStr("host", value)

    var port: Int
        get() = try { prefs.getInt("port", 22) } catch (_: Exception) { 22 }
        set(value) { try { prefs.edit().putInt("port", value).apply() } catch (_: Exception) {} }

    var username: String
        get() = str("username", "")
        set(value) = putStr("username", value)

    var password: String
        get() = str("password", "")
        set(value) = putStr("password", value)

    var folder: String
        get() = str("folder", "Audiobooks")
        set(value) = putStr("folder", value)

    var backupEnabled: Boolean
        get() = try { prefs.getBoolean("backup_enabled", false) } catch (_: Exception) { false }
        set(value) { try { prefs.edit().putBoolean("backup_enabled", value).apply() } catch (_: Exception) {} }

    var frequency: BackupFrequency
        get() = BackupFrequency.entries.find { it.name == str("frequency", "") } ?: BackupFrequency.DAILY
        set(value) = putStr("frequency", value.name)

    /** Trusted host key, stored as "host:port keyBase64" (trust on first use). */
    var hostKey: String
        get() = str("host_key", "")
        set(value) = putStr("host_key", value)

    var lastRunAt: Long
        get() = try { prefs.getLong("last_run_at", 0) } catch (_: Exception) { 0 }
        set(value) { try { prefs.edit().putLong("last_run_at", value).apply() } catch (_: Exception) {} }

    var lastRunMessage: String
        get() = str("last_run_message", "")
        set(value) = putStr("last_run_message", value)

    /** Failure count per ASIN, so a book that keeps failing doesn't block the rest. */
    var failures: Map<String, Int>
        get() = str("failures", "").split(";").filter { it.contains("=") }.associate {
            it.substringBefore("=") to (it.substringAfter("=").toIntOrNull() ?: 0)
        }
        set(value) = putStr("failures", value.entries.joinToString(";") { "${it.key}=${it.value}" })

    val isConfigured: Boolean
        get() = host.isNotBlank() && username.isNotBlank()
}

/**
 * Parsed form of whatever the user typed as the server address. Accepts a bare
 * hostname or IP ("nas.local", "203.0.113.5", "fe80::1"), host:port, or a URL
 * such as "sftp://user@example.com:2222/books". Any part not present is null.
 */
data class ServerAddress(val host: String, val port: Int?, val user: String?, val path: String?) {
    companion object {
        fun parse(input: String): ServerAddress? {
            var s = input.trim()
            if (s.isEmpty()) return null
            if (s.contains("://")) s = s.substringAfter("://")

            var path: String? = null
            val slash = s.indexOf('/')
            if (slash >= 0) {
                path = s.substring(slash).let { p ->
                    if (p.startsWith("/~/")) p.removePrefix("/~/") else p
                }.trimEnd('/').ifBlank { null }
                s = s.substring(0, slash)
            }

            var user: String? = null
            val at = s.lastIndexOf('@')
            if (at >= 0) {
                user = s.substring(0, at).substringBefore(':').ifBlank { null }
                s = s.substring(at + 1)
            }

            var port: Int? = null
            if (s.startsWith("[")) {
                val end = s.indexOf(']')
                if (end < 0) return null
                val rest = s.substring(end + 1)
                if (rest.startsWith(":")) port = rest.drop(1).toIntOrNull()
                s = s.substring(1, end)
            } else if (s.count { it == ':' } == 1) {
                port = s.substringAfter(':').toIntOrNull()
                s = s.substringBefore(':')
            }
            // More than one ':' without brackets is a bare IPv6 address

            if (s.isBlank()) return null
            return ServerAddress(s, port?.takeIf { it in 1..65535 }, user, path)
        }
    }
}
