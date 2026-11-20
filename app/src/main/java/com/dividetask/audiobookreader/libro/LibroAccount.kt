package com.dividetask.audiobookreader.libro

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** The Libro.fm sign-in: email and access token, encrypted at rest. The password isn't kept. */
@Singleton
class LibroAccount @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = createPrefs()

    private fun createPrefs(): SharedPreferences {
        fun create(): SharedPreferences {
            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(
                context, "libro_account", masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
        return try {
            create()
        } catch (e: Exception) {
            Log.e("LibroAccount", "EncryptedSharedPreferences failed, clearing and retrying", e)
            try {
                File(context.filesDir.parent, "shared_prefs/libro_account.xml").delete()
                create()
            } catch (e2: Exception) {
                context.getSharedPreferences("libro_account_plain", Context.MODE_PRIVATE)
            }
        }
    }

    private fun str(key: String) = try { prefs.getString(key, "") ?: "" } catch (_: Exception) { "" }
    private fun put(key: String, value: String) { try { prefs.edit().putString(key, value).apply() } catch (_: Exception) {} }

    private val _signedIn = MutableStateFlow(str("token").isNotEmpty())
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    val isSignedIn: Boolean get() = token.isNotEmpty()

    val email: String get() = str("email")

    val token: String get() = str("token")

    fun save(email: String, token: String) {
        put("email", email)
        put("token", token)
        _signedIn.value = token.isNotEmpty()
    }

    fun signOut() {
        put("token", "")
        _signedIn.value = false
    }
}
