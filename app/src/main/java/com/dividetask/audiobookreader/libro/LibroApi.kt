package com.dividetask.audiobookreader.libro

import com.dividetask.audiobookreader.data.db.entity.LibroBookEntity
import com.dividetask.audiobookreader.debug.AppDebugLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

class LibroAuthException(message: String) : IOException(message)

/** One downloadable file of a book (a zip of its tracks) and the book's chapter list. */
data class LibroManifest(val parts: List<Part>, val tracks: List<Track>) {
    data class Part(val url: String, val sizeBytes: Long)
    data class Track(val number: Int, val lengthSec: Double, val title: String?)
}

/**
 * Libro.fm has no public API: these are the endpoints its Android app uses, as used by the
 * open-source downloaders (libro-client, librofm-downloader). Blocking — call from Dispatchers.IO.
 */
@Singleton
class LibroApi @Inject constructor(
    private val account: LibroAccount,
    private val debugLog: AppDebugLog
) {
    /** Signs in with email and password; returns the access token. */
    fun login(email: String, password: String): String {
        val body = JSONObject().put("grant_type", "password").put("username", email).put("password", password)
        val json = request("POST", "/oauth/token", body.toString(), auth = false)
        return json.optString("access_token").takeIf { it.isNotEmpty() }
            ?: throw LibroAuthException(json.optString("error_description").ifEmpty { "Sign-in failed" })
    }

    /** Every book in the Libro.fm library. */
    fun library(): List<LibroBookEntity> {
        val books = mutableListOf<LibroBookEntity>()
        var page = 1
        do {
            val json = request("GET", "/api/v7/library?page=$page")
            val list = json.optJSONArray("audiobooks") ?: JSONArray()
            for (i in 0 until list.length()) parseBook(list.getJSONObject(i))?.let { books += it }
            val totalPages = json.optInt("total_pages", 1)
            page++
        } while (page <= totalPages)
        return books
    }

    fun manifest(isbn: String): LibroManifest {
        val json = request("GET", "/api/v9/download-manifest?isbn=$isbn")
        val parts = json.optJSONArray("parts") ?: JSONArray()
        val tracks = json.optJSONArray("tracks") ?: JSONArray()
        return LibroManifest(
            (0 until parts.length()).map { parts.getJSONObject(it) }.map {
                LibroManifest.Part(it.getString("url"), it.optLong("size_bytes"))
            },
            (0 until tracks.length()).map { tracks.getJSONObject(it) }.map {
                LibroManifest.Track(it.optInt("number"), it.optDouble("length_sec", 0.0),
                    it.optString("chapter_title").takeIf { t -> t.isNotBlank() && t != "null" })
            }.sortedBy { it.number }
        )
    }

    /** Downloads a part to [dest], resuming a partial file. [onProgress] gets 0..1. */
    fun download(url: String, dest: File, onProgress: (Float) -> Unit) {
        val have = if (dest.exists()) dest.length() else 0L
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            // Only Libro.fm's own hosts get the sign-in; file hosts are signed URLs
            if (URL(url).host.endsWith("libro.fm")) headers(connection, auth = true)
            if (have > 0) connection.setRequestProperty("Range", "bytes=$have-")
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            val code = connection.responseCode
            debugLog.log("LIBRO", "GET ${url.substringBefore('?')}: $code")
            if (code == 401 || code == 403) throw LibroAuthException("Libro.fm refused the download (HTTP $code)")
            if (code != 200 && code != 206) throw IOException("Download failed: HTTP $code")
            val resumed = code == 206
            val total = (if (resumed) have else 0L) + connection.contentLengthLong.coerceAtLeast(0)
            var read = if (resumed) have else 0L
            connection.inputStream.use { input ->
                java.io.FileOutputStream(dest, resumed).use { out ->
                    val buffer = ByteArray(65536)
                    var lastUpdate = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        read += n
                        val now = System.currentTimeMillis()
                        if (total > 0 && now - lastUpdate > 250) {
                            lastUpdate = now
                            onProgress(read.toFloat() / total)
                        }
                    }
                }
            }
            if (total > 0 && dest.length() < total) throw IOException("Download ended early")
        } finally {
            connection.disconnect()
        }
    }

    private fun parseBook(o: JSONObject): LibroBookEntity? {
        val isbn = o.optString("isbn").takeIf { it.isNotEmpty() } ?: return null
        fun names(key: String, from: JSONObject = o): String? = when (val v = from.opt(key)) {
            is JSONArray -> (0 until v.length()).joinToString(", ") { v.optString(it) }
            is String -> v
            else -> null
        }?.takeIf { it.isNotBlank() }
        val info = o.optJSONObject("audiobook_info")
        val seriesNum = o.opt("series_num")?.takeIf { it != JSONObject.NULL }?.toString()
            ?.removeSuffix(".0")
        return LibroBookEntity(
            isbn = isbn,
            title = o.optString("title"),
            author = names("authors") ?: "Unknown Author",
            narrator = info?.let { names("narrators", it) },
            durationSec = info?.optInt("duration")?.takeIf { it > 0 },
            sizeBytes = info?.optLong("size_bytes")?.takeIf { it > 0 },
            coverUrl = o.optString("cover_url").takeIf { it.isNotBlank() && it != "null" },
            seriesTitle = o.optString("series").takeIf { it.isNotBlank() && it != "null" },
            seriesNum = seriesNum,
            addedAt = o.optJSONObject("user_metadata")?.optString("added_at")?.takeIf { it.isNotBlank() && it != "null" }
                ?: o.optString("created_at").takeIf { it.isNotBlank() && it != "null" }
        )
    }

    private fun headers(connection: HttpURLConnection, auth: Boolean) {
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("X-LibroFm-AppVer", APP_VERSION)
        if (auth) connection.setRequestProperty("Authorization", "Bearer ${account.token}")
    }

    private fun request(method: String, path: String, body: String? = null, auth: Boolean = true): JSONObject {
        val connection = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            headers(connection, auth)
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            debugLog.log("LIBRO", "$method ${path.substringBefore('?')}: $code")
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 401 || code == 403) {
                val message = try { JSONObject(text).optString("error_description") } catch (_: Exception) { "" }
                throw LibroAuthException(message.ifEmpty { if (auth) "Signed out of Libro.fm — sign in again" else "Wrong email or password" })
            }
            if (code !in 200..299) throw IOException("Libro.fm error (HTTP $code)")
            val json = JSONObject(text)
            if (json.has("error")) throw IOException("Libro.fm: ${json.optString("error_description").ifEmpty { json.optString("error") }}")
            return json
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val BASE = "https://libro.fm"
        // The official Android app's; Libro.fm rejects sign-in without the app version header
        private const val USER_AGENT = "okhttp/5.3.2"
        private const val APP_VERSION = "7.34.8"
    }
}
