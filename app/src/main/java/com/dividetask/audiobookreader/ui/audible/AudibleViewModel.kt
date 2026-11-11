package com.dividetask.audiobookreader.ui.audible

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dividetask.audiobookreader.audible.api.AudibleApiClient
import com.dividetask.audiobookreader.audible.api.AudibleBook
import com.dividetask.audiobookreader.audible.auth.AudibleAuthManager
import com.dividetask.audiobookreader.audible.auth.AudibleEndpoints
import com.dividetask.audiobookreader.audible.auth.AudibleTokenStore
import com.dividetask.audiobookreader.audible.download.AudibleDownloadManager
import com.dividetask.audiobookreader.audible.download.DownloadProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AudibleViewModel @Inject constructor(
    private val apiClient: AudibleApiClient,
    private val authManager: AudibleAuthManager,
    private val tokenStore: AudibleTokenStore,
    private val downloadManager: AudibleDownloadManager,
    val requestLog: com.dividetask.audiobookreader.debug.AudibleRequestLog
) : ViewModel() {

    private val _library = MutableStateFlow<List<AudibleBook>>(emptyList())
    val library: StateFlow<List<AudibleBook>> = _library.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _debugLog = MutableStateFlow<List<String>>(emptyList())
    val debugLog: StateFlow<List<String>> = _debugLog.asStateFlow()

    private val _authComplete = MutableStateFlow(false)
    val authComplete: StateFlow<Boolean> = _authComplete.asStateFlow()

    val isAuthenticated: Boolean
        get() = tokenStore.isAuthenticated

    val downloadProgress: StateFlow<DownloadProgress?> = downloadManager.downloadProgress

    fun generateSerial(): String = AudibleEndpoints.generateDeviceSerial()

    fun generateCodeVerifier(): String = AudibleEndpoints.generateCodeVerifier()

    private fun addDebug(msg: String) {
        _debugLog.value = _debugLog.value + msg
        Log.d("AudibleVM", msg)
    }

    fun onLoginComplete(credentials: LoginCredentials) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()).launch {
            _isLoading.value = true
            _error.value = null
            _debugLog.value = emptyList()

            addDebug("=== OAuth Login Complete ===")
            addDebug("Auth code length: ${credentials.authorizationCode.length}")
            addDebug("Serial: ${credentials.serial.take(10)}...")
            addDebug("Code verifier length: ${credentials.codeVerifier.length}")

            addDebug("")
            addDebug("=== Device Registration ===")
            val result = authManager.registerDevice(
                authorizationCode = credentials.authorizationCode,
                serial = credentials.serial,
                codeVerifier = credentials.codeVerifier
            )
            _debugLog.value = _debugLog.value + result.debugLog

            if (result.success) {
                _authComplete.value = true
                addDebug("")
                loadLibrary()
            } else {
                addDebug("")
                addDebug("Registration failed — cannot proceed without device registration")
                _error.value = "Device registration failed. Check debug log for details."
                _isLoading.value = false
                _authComplete.value = true
            }
        }
    }

    fun loadLibrary() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            addDebug("=== Loading Library ===")

            try {
                val result = apiClient.getLibrary()
                _debugLog.value = _debugLog.value + result.debugLog
                _library.value = result.data ?: emptyList()

                if (result.data.isNullOrEmpty()) {
                    addDebug("Library is empty or null")
                } else {
                    addDebug("Library loaded: ${result.data.size} books")
                }
            } catch (e: Exception) {
                addDebug("EXCEPTION loading library: ${e::class.simpleName}: ${e.message}")
                _error.value = "Failed to load library: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun downloadBook(book: AudibleBook) {
        downloadManager.queueDownload(book)
    }

    fun isDownloaded(asin: String): Boolean {
        return downloadManager.isBookDownloaded(asin)
    }

    fun signOut() {
        authManager.signOut()
        _library.value = emptyList()
        _authComplete.value = false
        _debugLog.value = emptyList()
    }

    fun clearError() {
        _error.value = null
    }
}
