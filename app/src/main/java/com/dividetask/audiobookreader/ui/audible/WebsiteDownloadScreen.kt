package com.dividetask.audiobookreader.ui.audible

import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dividetask.audiobookreader.audible.auth.AudibleEndpoints
import com.dividetask.audiobookreader.audible.auth.AudibleTokenStore
import com.dividetask.audiobookreader.audible.download.AaxImporter
import com.dividetask.audiobookreader.audible.download.AudibleDownloadManager
import com.dividetask.audiobookreader.data.db.dao.AudibleBookDao
import com.dividetask.audiobookreader.debug.AppDebugLog
import com.dividetask.audiobookreader.remote.BackupEngine
import com.dividetask.audiobookreader.remote.BackupScheduler
import com.dividetask.audiobookreader.remote.ServerSettings
import com.dividetask.audiobookreader.remote.WebsiteBackupNotifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WebsiteDownloadViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    audibleBookDao: AudibleBookDao,
    downloadManager: AudibleDownloadManager,
    tokenStore: AudibleTokenStore,
    private val aaxImporter: AaxImporter,
    private val debugLog: AppDebugLog,
    val requestLog: com.dividetask.audiobookreader.debug.AudibleRequestLog,
    private val serverSettings: ServerSettings,
    private val notifier: WebsiteBackupNotifier,
    private val backupEngine: BackupEngine,
    private val scheduler: BackupScheduler
) : ViewModel() {
    /**
     * Website backup ("website_backup" route, no ASIN): books not on the server are downloaded
     * one after another with the same sign-in, each uploaded (and its phone copy deleted)
     * before the next starts.
     */
    val chain: Boolean = savedStateHandle.get<String>("asin") == null
    var asin by mutableStateOf(savedStateHandle.get<String>("asin") ?: "")
        private set
    var title by mutableStateOf(downloadManager.pendingDownloads.value[asin]?.title ?: asin)
        private set

    /** Website backup progress, shown above the browser. */
    var chainStatus by mutableStateOf<String?>(if (chain) "Sign in to Audible if asked" else null)
        private set
    var chainDone by mutableStateOf(false)
        private set
    var backedUp by mutableStateOf(0)
        private set
    /** Set by the screen: search the website for the new [asin]. */
    var onNextBook: (() -> Unit)? = null

    private val website = "https://www.audible.${AudibleEndpoints.getLocale(tokenStore.locale).domain}"
    val libraryUrl = "$website/library/titles"
    /** The "Download" link on the website's library page. */
    val downloadUrl get() = "$website/library/download?asin=$asin&codec=AAX_44_128"

    init {
        viewModelScope.launch {
            if (chain) {
                debugLog.section("Website backup")
                notifier.clearPrompt()
                pickNext()
            } else {
                audibleBookDao.getAllOnce().firstOrNull { it.asin == asin }?.let { title = it.title }
            }
        }
    }

    private suspend fun pickNext() {
        if (serverSettings.uploadsPaused) {
            finishChain("The server stopped accepting uploads for today — continuing tomorrow")
            return
        }
        if (notifier.isBlocked) {
            finishChain("The Audible website refused a download — paused until ${notifier.blockedUntilText()}")
            return
        }
        val next = try {
            backupEngine.nextForWebsite()
        } catch (e: Exception) {
            finishChain("Can't check the server: ${e.message}")
            return
        }
        if (next == null) {
            finishChain("Every Audible book is on the server")
            return
        }
        asin = next.asin
        title = next.title
        log("Website backup: next is \"${next.title}\" (${next.asin})")
        chainStatus = "Finding \"${next.title}\"…"
        onNextBook?.invoke()
    }

    private fun finishChain(message: String) {
        log("Website backup: $message ($backedUp backed up)")
        chainStatus = message + if (backedUp > 0) " · $backedUp backed up this session" else ""
        chainDone = true
    }

    /** Waits for [bookAsin]'s download, then its upload, then moves to the next book. */
    private suspend fun followUp(bookAsin: String, bookTitle: String) {
        chainStatus = "Downloading \"$bookTitle\" from the Audible website…"
        val result = aaxImporter.websiteResults.first { it.asin == bookAsin }
        if (!result.ok) {
            serverSettings.websiteBackupAsins = serverSettings.websiteBackupAsins - bookAsin
            if (result.needsSignIn) {
                // Not a refusal: what was downloaded is kept, and signing in again continues it
                notifier.promptBackupSignIn(bookTitle)
                finishChain("Signed out of the Audible website while downloading \"$bookTitle\" — sign in again to continue")
                return
            }
            if (result.refused) {
                val reason = result.error ?: "download refused"
                requestLog.event("website-refused", "$bookAsin: $reason")
                notifier.block("\"$bookTitle\": $reason")
                finishChain("The Audible website refused \"$bookTitle\" ($reason) — paused until ${notifier.blockedUntilText()}")
                return
            }
            log("Website backup of \"$bookTitle\" failed: ${result.error} — skipping it")
            backupEngine.websiteFailed(bookAsin)
        } else {
            chainStatus = "Uploading \"$bookTitle\" to the server…"
            scheduler.enqueueLocalBackup()
            // The phone-copies backup uploads it, deletes the phone copy and clears it from the list
            while (bookAsin in serverSettings.websiteBackupAsins) {
                if (serverSettings.uploadsPaused) {
                    finishChain("The server stopped accepting uploads for today — \"$bookTitle\" and the rest continue tomorrow")
                    return
                }
                kotlinx.coroutines.delay(5_000)
            }
            backedUp++
        }
        pickNext()
    }

    /** The website library searched for the title (the website does honour searchTerm). */
    fun pageUrl(page: Int): String =
        "$libraryUrl?searchTerm=${Uri.encode(title)}&pageSize=$PAGE_SIZE&page=$page"

    fun log(message: String) = debugLog.log("WEB", message)

    /** [url] with long query values (possible signed tokens) cut, for the saved request log. */
    fun redacted(url: String): String {
        val uri = Uri.parse(url)
        val query = uri.queryParameterNames.joinToString("&") { name ->
            val value = uri.getQueryParameter(name).orEmpty()
            "$name=${if (value.length > 40) "…" else value}"
        }
        return "${uri.host}${uri.path}" + if (query.isNotEmpty()) "?$query" else ""
    }

    fun startDownload(urls: List<String>, userAgent: String?) {
        log("Downloading ${urls.size} file(s): ${urls.joinToString { it.substringBefore('?') }}")
        if (chain) {
            serverSettings.websiteBackupAsins = serverSettings.websiteBackupAsins + asin
            val bookAsin = asin
            val bookTitle = title
            // Listening before the import starts, so its result can't be missed
            viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { followUp(bookAsin, bookTitle) }
        }
        aaxImporter.importFromWebsite(urls, userAgent, asin, title, forBackup = chain)
    }

    /**
     * The book's download links from its library page, one per part in part order. The
     * same part offered in several qualities keeps only the best (highest bitrate).
     */
    fun chooseLinks(hrefs: List<String>): List<String> {
        val parts = LinkedHashMap<String, MutableList<String>>()
        for (href in hrefs) {
            val uri = Uri.parse(href)
            val key = uri.path + uri.queryParameterNames.filter { it != "codec" }.sorted()
                .joinToString("&") { "$it=${uri.getQueryParameter(it)}" }
            parts.getOrPut(key) { mutableListOf() } += href
        }
        val best = parts.values.map { links -> links.maxBy { codecRank(Uri.parse(it).getQueryParameter("codec")) } }
        // Part order from a "part…" parameter when there is one; otherwise page order (stable sort)
        return best.sortedBy { link ->
            val uri = Uri.parse(link)
            uri.queryParameterNames.firstOrNull { "part" in it.lowercase() }
                ?.let { uri.getQueryParameter(it)?.filter(Char::isDigit)?.toIntOrNull() } ?: Int.MAX_VALUE
        }
    }

    companion object {
        const val PAGE_SIZE = 50
        /**
         * The phone layout of audible.com hides Download (it only appears in the desktop
         * layout, as a menu of parts for long books), so the browser presents as desktop Chrome.
         */
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    /** "AAX_44_128" → 128 kbps at 44 kHz ranks above "AAX_22_64". */
    private fun codecRank(codec: String?): Int {
        val numbers = codec?.split('_')?.mapNotNull { it.toIntOrNull() } ?: return 0
        return (numbers.getOrNull(1) ?: 0) * 1000 + (numbers.getOrNull(0) ?: 0)
    }
}

private enum class Phase { SIGNING_IN, FINDING }

/** Collects every link on the page that downloads [asin] (as a JSON array of URLs). */
private fun findLinksScript(asin: String) = """
    (function() {
      var out = [];
      document.querySelectorAll('a[href]').forEach(function(a) {
        var h = a.href;
        if (/download/i.test(h) && h.indexOf('$asin') >= 0 && out.indexOf(h) < 0) out.push(h);
      });
      return JSON.stringify(out);
    })()
""".trimIndent()

/**
 * For working out the library page's layout: small elements that mention "download" or
 * [asin], as a JSON array of their HTML.
 */
/**
 * The website's "Part N" download rows for [asin] aren't links: the page fetches a part when
 * its row is clicked. With [part] = 0 this returns how many part rows there are; otherwise it
 * clicks that part's row ([target] 0 = the row, 1 = its download icon) and returns "clicked".
 */
private fun partScript(asin: String, part: Int, target: Int = 0) = """
    (function(n, target) {
      function parts(el) {
        return [].slice.call(el.querySelectorAll('span')).filter(function(s) {
          return /^\s*Part\s+\d+\s*$/.test(s.textContent) && s.children.length === 0;
        });
      }
      // The rows next to this book's Download button; the whole page if not found there
      var rows = [], el = document.getElementById('download-button-$asin');
      for (var i = 0; i < 12 && el && !rows.length; i++) { rows = parts(el); el = el.parentElement; }
      if (!rows.length) rows = parts(document);
      if (n === 0) return String(rows.length);
      var span = rows[n - 1];
      if (!span) return 'missing';
      var row = span.closest('.bc-row-responsive') || span.parentElement;
      var t = target === 1 ? (row.querySelector('i') || row) : row;
      ['mouseover', 'mousedown', 'mouseup', 'click'].forEach(function(type) {
        t.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, view: window }));
      });
      return 'clicked';
    })($part, $target)
""".trimIndent()

private fun describePageScript(asin: String) = """
    (function() {
      var out = [];
      document.querySelectorAll('body *').forEach(function(e) {
        if (out.length >= 60 || e.children.length > 3) return;
        var h = e.outerHTML;
        if (h.length < 800 && (/download/i.test(h) || /Part\s*\d/.test(e.textContent) || h.indexOf('$asin') >= 0)) out.push(h.replace(/\s+/g, ' '));
      });
      return JSON.stringify(out);
    })()
""".trimIndent()

/**
 * Downloads a book from the Audible website when the API's download limit blocks it.
 * The user only sees this to sign in: once the website library shows, the book's
 * download links are read from its library page (one per part for books Audible
 * splits) and handed to [AaxImporter], which downloads, converts and joins them in the
 * background.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebsiteDownloadScreen(
    onDone: () -> Unit,
    viewModel: WebsiteDownloadViewModel = hiltViewModel()
) {
    var phase by remember { mutableStateOf(Phase.SIGNING_IN) }
    var isLoading by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.chain) "Back up through Audible website" else "Download from Audible website") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                viewModel.chainStatus?.let { status ->
                    if (phase == Phase.SIGNING_IN && !viewModel.chainDone) "Sign in to Audible if asked. Books not on the server are " +
                        "then downloaded, uploaded and removed from the phone one at a time — keep this screen open."
                    else status
                } ?: when (phase) {
                    Phase.SIGNING_IN -> "Sign in to Audible if asked. \"${viewModel.title}\" will then download in the background."
                    Phase.FINDING -> "Finding \"${viewModel.title}\"…"
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

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
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.userAgentString = WebsiteDownloadViewModel.DESKTOP_USER_AGENT
                        // The desktop layout is small on a phone: allow pinch zoom (e.g. to sign in)
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false

                        // Keep cookies: the website session is reused next time
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        var handedOff = false
                        fun handOff(urls: List<String>) {
                            if (handedOff) return
                            handedOff = true
                            cookieManager.flush()
                            viewModel.startDownload(urls, settings.userAgentString)
                            if (!viewModel.chain) onDone()
                        }

                        fun noLinks(view: WebView) {
                            viewModel.log("No download links found — trying the direct link")
                            // Record what the page does have, to learn how its Download button works
                            view.evaluateJavascript(describePageScript(viewModel.asin)) { described ->
                                val elements = try {
                                    val array = org.json.JSONArray(org.json.JSONTokener(described).nextValue() as String)
                                    (0 until array.length()).map { array.getString(it) }
                                } catch (e: Exception) {
                                    listOf("(could not read the page: ${e.message})")
                                }
                                viewModel.log("Page elements mentioning download/${viewModel.asin} (${elements.size}):")
                                elements.forEach { viewModel.log("  $it") }
                                viewModel.requestLog.event("website-links", "none found; direct link")
                                handOff(listOf(viewModel.downloadUrl))
                            }
                        }

                        // Clicking a part row makes the page open its download link; that link is
                        // caught (not loaded) in shouldOverrideUrlLoading / shouldInterceptRequest
                        val partLinks = mutableListOf<String>()
                        var waitingForPart = 0
                        fun capturePart(view: WebView, part: Int, total: Int) {
                            if (part > total) {
                                waitingForPart = 0
                                viewModel.log("Part links: ${partLinks.joinToString(" ")}")
                                viewModel.requestLog.event("website-links", "parts: ${partLinks.joinToString(" ") { viewModel.redacted(it) }}")
                                handOff(partLinks.toList())
                                return
                            }
                            waitingForPart = part
                            fun check(waited: Int) {
                                if (handedOff || waitingForPart != part) return
                                if (partLinks.size >= part) {
                                    capturePart(view, part + 1, total)
                                } else if (waited == 0 || waited == 5_000) {
                                    // Click the row; if nothing happens, try its download icon
                                    view.evaluateJavascript(partScript(viewModel.asin, part, if (waited == 0) 0 else 1), null)
                                    view.postDelayed({ check(waited + 500) }, 500)
                                } else if (waited < 10_000) {
                                    view.postDelayed({ check(waited + 500) }, 500)
                                } else {
                                    waitingForPart = 0
                                    viewModel.log("Clicking part $part didn't open a download link")
                                    noLinks(view)
                                }
                            }
                            check(0)
                        }

                        /** True when [url] is a part's download link that was caught. */
                        fun catchPartLink(url: String): Boolean {
                            if (waitingForPart == 0) return false
                            val uri = Uri.parse(url)
                            val isDownload = uri.host?.startsWith("cds.audible.") == true ||
                                uri.path?.startsWith("/library/download") == true
                            if (!isDownload) return false
                            if (partLinks.size < waitingForPart && url !in partLinks) {
                                viewModel.log("Part $waitingForPart link: $url")
                                partLinks += url
                            }
                            return true
                        }

                        // The library page may fill in after it loads, so look a few times
                        val page = 1
                        var searched = false
                        var signedIn = false

                        // Website backup: search for the next book with the same sign-in
                        fun startBook(view: WebView) {
                            if (viewModel.asin.isEmpty()) return
                            handedOff = false
                            searched = false
                            partLinks.clear()
                            waitingForPart = 0
                            phase = Phase.FINDING
                            view.loadUrl(viewModel.pageUrl(page))
                        }
                        val webView = this
                        viewModel.onNextBook = { webView.post { if (signedIn) startBook(webView) } }
                        fun findLinks(view: WebView, attempt: Int) {
                            view.evaluateJavascript(findLinksScript(viewModel.asin)) { result ->
                                val hrefs = try {
                                    val array = org.json.JSONArray(org.json.JSONTokener(result).nextValue() as String)
                                    (0 until array.length()).map { array.getString(it) }
                                } catch (e: Exception) {
                                    emptyList()
                                }
                                if (hrefs.isNotEmpty()) {
                                    viewModel.log("Download links on the page: ${hrefs.joinToString(" ")}")
                                    viewModel.requestLog.event("website-links", hrefs.joinToString(" "))
                                    handOff(viewModel.chooseLinks(hrefs))
                                } else if (attempt < 3) {
                                    view.postDelayed({ findLinks(view, attempt + 1) }, 2_000)
                                } else {
                                    // Long books: "Part N" rows that download when clicked
                                    view.evaluateJavascript(partScript(viewModel.asin, 0)) { count ->
                                        val parts = count?.trim('"')?.toIntOrNull() ?: 0
                                        if (parts > 0) {
                                            viewModel.log("$parts part rows — clicking each to get its download link")
                                            capturePart(view, 1, parts)
                                        } else {
                                            noLinks(view)
                                        }
                                    }
                                }
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                                val url = request?.url?.toString() ?: return false
                                return catchPartLink(url)
                            }

                            // Also catches links the page opens in a frame or by script; runs off the main thread
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?
                            ): android.webkit.WebResourceResponse? {
                                val url = request?.url?.toString() ?: return null
                                if (waitingForPart == 0) return null
                                var caught = false
                                val latch = java.util.concurrent.CountDownLatch(1)
                                view?.post { caught = catchPartLink(url); latch.countDown() } ?: return null
                                latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
                                return if (caught) android.webkit.WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0))) else null
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                isLoading = true
                                url?.let { viewModel.requestLog.request("webview-website", "GET", it, null, null, note = "page") }
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?,
                                errorResponse: android.webkit.WebResourceResponse?
                            ) {
                                val url = request?.url?.toString() ?: return
                                if (request.isForMainFrame) {
                                    viewModel.requestLog.request("webview-website", request.method ?: "GET", url,
                                        errorResponse?.statusCode, null, note = "page error")
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                view ?: return
                                val uri = url?.let(Uri::parse) ?: return
                                val onLibrary = uri.host?.contains("audible.") == true &&
                                    uri.path?.startsWith("/library") == true
                                if (!onLibrary) return
                                when (phase) {
                                    // Signed in once the website shows the library: search it for the book
                                    Phase.SIGNING_IN -> {
                                        viewModel.log("Signed in — searching the library")
                                        signedIn = true
                                        if (viewModel.chain) {
                                            // The next book may still be being picked; it starts the search when ready
                                            if (viewModel.asin.isNotEmpty() && !viewModel.chainDone) startBook(view)
                                        } else {
                                            phase = Phase.FINDING
                                            view.loadUrl(viewModel.pageUrl(page))
                                        }
                                    }
                                    Phase.FINDING -> if (!searched && uri.getQueryParameter("page") == page.toString()) {
                                        searched = true
                                        findLinks(view, 1)
                                    }
                                }
                            }
                        }

                        // A download the page starts itself (a part's link, or the user tapped Download)
                        setDownloadListener { url, _, _, _, _ -> if (!catchPartLink(url)) handOff(listOf(url)) }

                        loadUrl(viewModel.libraryUrl)
                    }
                }
            )
        }
    }
}
