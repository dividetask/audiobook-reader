package com.dividetask.audiobookreader.debug

import android.content.Context
import com.dividetask.audiobookreader.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Interceptor
import okhttp3.Response
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every interaction with Audible/Amazon, saved to a file that survives restarts, so we can
 * work out what makes Audible limit downloads (see docs/AUDIBLE_RATE_LIMITS.md).
 *
 * One tab-separated line per request or event:
 * `time  source  method  host  path  status  ms  bytes  note`
 * Query strings are left out (they can hold signed tokens); the path keeps the ASIN.
 */
@Singleton
class AudibleRequestLog @Inject constructor(
    @ApplicationContext context: Context
) {
    private val file = File(context.filesDir, "audible_requests.log")
    private val previous = File(context.filesDir, "audible_requests.1.log")
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    init {
        event("app-start", BuildConfig.VERSION_NAME)
    }

    fun request(
        source: String,
        method: String,
        url: String,
        status: Int?,
        durationMs: Long?,
        bytes: Long? = null,
        note: String = ""
    ) {
        val uri = android.net.Uri.parse(url)
        write(listOf(source, method, uri.host.orEmpty(), uri.path.orEmpty(),
            status?.toString().orEmpty(), durationMs?.toString().orEmpty(),
            bytes?.takeIf { it >= 0 }?.toString().orEmpty(), note))
    }

    /** Something worth seeing alongside the requests: a denial, a throttle, a retry run. */
    fun event(name: String, note: String = "") {
        write(listOf("event", name, "", "", "", "", "", note))
    }

    /** For the app's HTTP client: logs every Audible/Amazon request, including each redirect. */
    fun interceptor(source: String = "api") = Interceptor { chain ->
        val request = chain.request()
        val start = System.currentTimeMillis()
        val response: Response = try {
            chain.proceed(request)
        } catch (e: Exception) {
            if (isAudible(request.url.host)) {
                request(source, request.method, request.url.toString(), null,
                    System.currentTimeMillis() - start, note = "${e::class.simpleName}: ${e.message}")
            }
            throw e
        }
        if (isAudible(request.url.host)) {
            request(source, request.method, request.url.toString(), response.code,
                System.currentTimeMillis() - start, response.body?.contentLength())
        }
        response
    }

    /** Audible's sites and API, its download servers, and Amazon (sign-in, device registration). */
    fun isAudible(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        if ("audible" in h) return true
        return AMAZON.containsMatchIn(h) && "media-amazon" !in h && "images-amazon" !in h
    }

    @Synchronized
    private fun write(fields: List<String>) {
        try {
            if (file.length() > MAX_BYTES) {
                previous.delete()
                file.renameTo(previous)
            }
            val clean = fields.map { it.replace('\t', ' ').replace('\n', ' ') }
            file.appendText(timeFormat.format(Date()) + "\t" + clean.joinToString("\t") + "\n")
        } catch (e: Exception) {
            android.util.Log.w("AudibleRequestLog", "Could not write: ${e.message}")
        }
    }

    /** The most recent [maxLines] lines, oldest first, with a header line. */
    @Synchronized
    fun recent(maxLines: Int = 3000): String {
        val lines = listOf(previous, file).filter { it.exists() }.flatMap { it.readLines() }
        val header = "time\tsource\tmethod\thost\tpath\tstatus\tms\tbytes\tnote (${BuildConfig.VERSION_NAME}, " +
            "${TimeZone.getDefault().id})"
        return (listOf(header) + lines.takeLast(maxLines)).joinToString("\n")
    }

    fun lineCount(): Int = listOf(previous, file).filter { it.exists() }.sumOf { it.readLines().size }

    companion object {
        private const val MAX_BYTES = 2L * 1024 * 1024
        private val AMAZON = Regex("(^|\\.)amazon\\.")
    }
}
