package com.dividetask.audiobookreader.ui.audible

import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.dividetask.audiobookreader.audible.auth.AudibleEndpoints

data class LoginCredentials(
    val authorizationCode: String,
    val serial: String,
    val codeVerifier: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudibleLoginScreen(
    locale: String = "us",
    serial: String,
    codeVerifier: String,
    onLoginComplete: (LoginCredentials) -> Unit,
    onBack: () -> Unit,
    /** Records each page the sign-in browser opens (Audible request log). */
    onPage: (url: String, status: Int?) -> Unit = { _, _ -> }
) {
    val audibleLocale = AudibleEndpoints.getLocale(locale)
    val loginUrl = AudibleEndpoints.loginUrl(audibleLocale, serial, codeVerifier)
    var isLoading by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sign in to Audible") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.setSupportMultipleWindows(false)
                        settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.39 Mobile Safari/537.36"

                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)
                        cookieManager.removeAllCookies(null)

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val url = request?.url?.toString() ?: return false
                                if (url.contains("maplanding")) {
                                    val uri = Uri.parse(url)
                                    val queryString = uri.encodedQuery ?: ""
                                    val params = queryString.split("&").associate {
                                        val parts = it.split("=", limit = 2)
                                        val key = java.net.URLDecoder.decode(parts[0], "UTF-8")
                                        val value = java.net.URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
                                        key to value
                                    }

                                    val authCode = params["openid.oa2.authorization_code"]
                                    if (authCode != null) {
                                        onLoginComplete(LoginCredentials(
                                            authorizationCode = authCode,
                                            serial = serial,
                                            codeVerifier = codeVerifier
                                        ))
                                        return true
                                    }
                                }
                                return false
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                url?.let { onPage(it, null) }
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: android.webkit.WebResourceResponse?
                            ) {
                                if (request?.isForMainFrame == true) onPage(request.url.toString(), errorResponse?.statusCode)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                            }
                        }

                        loadUrl(loginUrl)
                    }
                }
            )
        }
    }
}
