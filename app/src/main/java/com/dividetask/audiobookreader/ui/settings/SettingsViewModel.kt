package com.dividetask.audiobookreader.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dividetask.audiobookreader.audible.auth.AudibleAuthManager
import com.dividetask.audiobookreader.audible.auth.AudibleEndpoints
import com.dividetask.audiobookreader.audible.auth.AudibleTokenStore
import com.dividetask.audiobookreader.player.PlaybackPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val tokenStore: AudibleTokenStore,
    private val authManager: AudibleAuthManager,
    private val playbackPreferences: PlaybackPreferences
) : ViewModel() {

    val skipBackSeconds: StateFlow<Int> = playbackPreferences.skipBackSeconds
    val skipForwardSeconds: StateFlow<Int> = playbackPreferences.skipForwardSeconds

    fun setSkipBackSeconds(seconds: Int) = playbackPreferences.setSkipBackSeconds(seconds)
    fun setSkipForwardSeconds(seconds: Int) = playbackPreferences.setSkipForwardSeconds(seconds)

    private var pendingSerial: String? = null
    private var pendingCodeVerifier: String? = null

    private val _manualLoginStatus = MutableStateFlow("")
    val manualLoginStatus: StateFlow<String> = _manualLoginStatus.asStateFlow()

    val isConnected: Boolean
        get() = tokenStore.isAuthenticated

    val hasRefreshToken: Boolean
        get() = !tokenStore.refreshToken.isNullOrBlank()

    val locale: String
        get() = tokenStore.locale

    var downloadQuality: String
        get() = tokenStore.downloadQuality
        set(value) { tokenStore.downloadQuality = value }

    var wifiOnlyDownloads: Boolean
        get() = tokenStore.wifiOnlyDownloads
        set(value) { tokenStore.wifiOnlyDownloads = value }

    var debugLogLevel: String
        get() = tokenStore.debugLogLevel
        set(value) { tokenStore.debugLogLevel = value }

    val tokenDebugInfo: List<String>
        get() = listOf(
            "access_token: ${if (!tokenStore.accessToken.isNullOrBlank()) "present (${tokenStore.accessToken!!.length} chars)" else "MISSING"}",
            "refresh_token: ${if (!tokenStore.refreshToken.isNullOrBlank()) "present" else "MISSING"}",
            "adp_token: ${if (!tokenStore.adpToken.isNullOrBlank()) "present" else "MISSING"}",
            "device_private_key: ${if (!tokenStore.devicePrivateKey.isNullOrBlank()) "present" else "MISSING"}",
            "device_serial: ${tokenStore.deviceSerial ?: "MISSING"}",
            "locale: ${tokenStore.locale}"
        )

    fun generateLoginUrl(): String {
        val serial = AudibleEndpoints.generateDeviceSerial()
        val codeVerifier = AudibleEndpoints.generateCodeVerifier()
        pendingSerial = serial
        pendingCodeVerifier = codeVerifier
        val locale = AudibleEndpoints.getLocale(tokenStore.locale)
        return AudibleEndpoints.loginUrl(locale, serial, codeVerifier)
    }

    fun processManualLoginUrl(urlOrText: String) {
        val serial = pendingSerial
        val codeVerifier = pendingCodeVerifier
        if (serial == null || codeVerifier == null) {
            _manualLoginStatus.value = "Error: Tap 'Open Audible Login in Chrome' first to generate a session."
            return
        }

        val authCode = extractAuthorizationCode(urlOrText.trim())
        if (authCode == null) {
            _manualLoginStatus.value = "Error: Could not find authorization_code in the URL. Make sure you copied the entire URL from Chrome's address bar after the redirect."
            return
        }

        _manualLoginStatus.value = "Registering device..."

        viewModelScope.launch {
            val result = authManager.registerDevice(
                authorizationCode = authCode,
                serial = serial,
                codeVerifier = codeVerifier
            )

            if (result.success) {
                _manualLoginStatus.value = "Success! Device registered. Go back and tap the Audible icon to browse your library."
            } else {
                val lastLog = result.debugLog.lastOrNull { it.contains("response:") || it.contains("FAILED") || it.contains("EXCEPTION") } ?: "Unknown error"
                _manualLoginStatus.value = "Registration failed: $lastLog"
            }
        }
    }

    private fun extractAuthorizationCode(input: String): String? {
        try {
            val uri = Uri.parse(input)
            val queryString = uri.encodedQuery ?: ""
            val params = queryString.split("&").associate {
                val parts = it.split("=", limit = 2)
                val key = java.net.URLDecoder.decode(parts[0], "UTF-8")
                val value = java.net.URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
                key to value
            }
            return params["openid.oa2.authorization_code"]
        } catch (e: Exception) {
            return null
        }
    }

    fun signOut() {
        tokenStore.clear()
    }
}
