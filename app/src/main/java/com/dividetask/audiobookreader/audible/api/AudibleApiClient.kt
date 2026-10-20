package com.dividetask.audiobookreader.audible.api

import android.util.Base64
import android.util.Log
import com.dividetask.audiobookreader.audible.auth.AudibleAuthManager
import com.dividetask.audiobookreader.audible.auth.AudibleEndpoints
import com.dividetask.audiobookreader.audible.auth.AudibleRequestSigner
import com.dividetask.audiobookreader.debug.AppDebugLog
import com.dividetask.audiobookreader.audible.auth.AudibleTokenStore
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

data class ApiResult<T>(
    val data: T?,
    val debugLog: List<String>,
    /** User-facing reason when [data] is null, if known. */
    val error: String? = null
)

data class DownloadInfo(
    val url: String,
    val key: String?,
    val iv: String?
)

@Singleton
class AudibleApiClient @Inject constructor(
    private val httpClient: HttpClient,
    private val tokenStore: AudibleTokenStore,
    private val authManager: AudibleAuthManager,
    private val signer: AudibleRequestSigner,
    private val debugLog: AppDebugLog
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val canSign: Boolean
        get() = !tokenStore.adpToken.isNullOrBlank() && !tokenStore.devicePrivateKey.isNullOrBlank()

    /**
     * Signs the request with the registered device's ADP token and private key, as
     * the Audible apps and audible-cli do. Bearer-only requests appear to be treated
     * as a generic Login-with-Amazon client ("[AAA] does not has access to asin").
     * Falls back to the bearer token when signing isn't possible. Call after all
     * query parameters are set, since the signature covers the path and query.
     * Retries after a 401/403 pass sign = false, so a bad signature can't lock us out.
     */
    private fun HttpRequestBuilder.authorize(method: String, accessToken: String, body: String = "", sign: Boolean = true) {
        if (!sign) {
            header("x-amz-access-token", accessToken)
            return
        }
        val built = url.build()
        val pathAndQuery = built.encodedPath +
            if (built.encodedQuery.isNotEmpty()) "?${built.encodedQuery}" else ""
        val signed = signer.signRequest(method, pathAndQuery, body)
        if (signed.isNotEmpty()) {
            signed.forEach { (k, v) -> header(k, v) }
        } else {
            header("x-amz-access-token", accessToken)
        }
    }

    /**
     * Set when Audible answers a license request with CustomerThrottled. License
     * requests are skipped until then, since each retry can extend the throttle.
     */
    @Volatile
    var throttledUntil: Long = 0
        private set

    val isThrottled: Boolean
        get() = System.currentTimeMillis() < throttledUntil

    fun throttleMessage(): String {
        val until = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
            .format(java.util.Date(throttledUntil))
        return "Audible is limiting download requests for your account. Try again after $until."
    }

    suspend fun getLibrary(): ApiResult<List<AudibleBook>> {
        debugLog.section("Library Fetch")
        val log = mutableListOf<String>()
        val locale = AudibleEndpoints.getLocale(tokenStore.locale)
        val url = AudibleEndpoints.libraryUrl(locale)

        log.add("Locale: ${tokenStore.locale}")
        log.add("API URL: $url")
        log.add("Has access_token: ${!tokenStore.accessToken.isNullOrBlank()}")
        log.add("Has refresh_token: ${!tokenStore.refreshToken.isNullOrBlank()}")
        log.add("Has adp_token: ${!tokenStore.adpToken.isNullOrBlank()}")

        var accessToken = tokenStore.accessToken
        if (accessToken.isNullOrBlank()) {
            log.add("ERROR: No access token available")
            return ApiResult(emptyList(), log)
        }

        if (accessToken.contains("%")) {
            log.add("Token appears URL-encoded, decoding...")
            accessToken = java.net.URLDecoder.decode(accessToken, "UTF-8")
            tokenStore.accessToken = accessToken
        }

        log.add("Token prefix: ${accessToken.take(20)}...")

        val params = mapOf(
            "num_results" to "1000",
            "response_groups" to "product_desc,product_attrs,media,contributors,series",
            "image_sizes" to "500,1024",
            "sort_by" to "-PurchaseDate"
        )

        try {
            var response = httpClient.get(url) {
                params.forEach { (k, v) -> parameter(k, v) }
                authorize("GET", accessToken)
            }

            log.add("Response status: ${response.status}")

            if (response.status == HttpStatusCode.Unauthorized ||
                response.status == HttpStatusCode.Forbidden) {
                log.add("Got ${response.status}, attempting token refresh...")
                val refreshed = authManager.refreshAccessToken()
                log.add("Token refresh: ${if (refreshed) "success" else "failed"}")

                if (refreshed) {
                    val newToken = tokenStore.accessToken ?: ""
                    log.add("New token prefix: ${newToken.take(20)}...")
                    response = httpClient.get(url) {
                        params.forEach { (k, v) -> parameter(k, v) }
                        authorize("GET", newToken, sign = false)
                    }
                    log.add("Retry response status: ${response.status}")
                } else {
                    return ApiResult(emptyList(), log)
                }
            }

            val responseText = response.bodyAsText()
            log.add("Response length: ${responseText.length} chars")

            val preview = responseText.take(500)
            log.add("Response preview: $preview")

            if (response.status != HttpStatusCode.OK) {
                log.add("ERROR: Non-200 response")
                return ApiResult(emptyList(), log)
            }

            val books = json.decodeFromString<AudibleLibraryResponse>(responseText).items
            log.add("Parsed ${books.size} books")
            debugLog.log("API", "Library: ${books.size} books loaded")

            return ApiResult(books, log)
        } catch (e: Exception) {
            log.add("EXCEPTION: ${e::class.simpleName}: ${e.message}")
            Log.e("AudibleApi", "Library fetch failed", e)
            return ApiResult(emptyList(), log)
        }
    }

    suspend fun getDownloadUrl(asin: String): ApiResult<DownloadInfo> {
        debugLog.section("Download License ($asin)")
        val log = mutableListOf<String>()
        val locale = AudibleEndpoints.getLocale(tokenStore.locale)
        val accessToken = tokenStore.accessToken

        if (accessToken.isNullOrBlank()) {
            log.add("ERROR: No access token")
            return ApiResult(null, log)
        }

        if (isThrottled) {
            log.add("Skipped license request — throttled by Audible")
            return ApiResult(null, log, throttleMessage())
        }

        val url = AudibleEndpoints.contentUrl(locale, asin)
        log.add("License URL: $url")

        return try {
            val body = buildJsonObject {
                put("consumption_type", "Download")
                put("drm_type", "Adrm")
                put("quality", tokenStore.downloadQuality)
            }
            log.add("Request body: ${body}")
            log.add("Auth: ${if (canSign) "ADP-signed" else "bearer token only (no ADP token / device key)"}")

            val response = httpClient.post(url) {
                authorize("POST", accessToken, body.toString())
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }

            val responseText = response.bodyAsText()
            log.add("Status: ${response.status}")
            log.add("Response: ${responseText.take(500)}")
            debugLog.log("API", "License status: ${response.status}")
            debugLog.log("API", "License response: ${responseText.take(200)}")

            if (response.status != HttpStatusCode.OK) {
                if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) {
                    log.add("Trying token refresh...")
                    val refreshed = authManager.refreshAccessToken()
                    log.add("Refresh: ${if (refreshed) "ok" else "failed"}")
                    if (refreshed) {
                        val retryResponse = httpClient.post(url) {
                            authorize("POST", tokenStore.accessToken!!, body.toString(), sign = false)
                            contentType(ContentType.Application.Json)
                            setBody(body.toString())
                        }
                        val retryText = retryResponse.bodyAsText()
                        log.add("Retry status: ${retryResponse.status}")
                        log.add("Retry response: ${retryText.take(500)}")
                        val retryInfo = extractDownloadInfo(retryText)
                        log.add("Download URL: ${retryInfo?.url?.take(100) ?: "NOT FOUND"}")
                        return ApiResult(retryInfo, log, if (retryInfo == null) licenseError(retryText, log) else null)
                    }
                }
                return ApiResult(null, log)
            }

            val info = extractDownloadInfo(responseText)
            log.add("Download URL: ${info?.url?.take(100) ?: "NOT FOUND"}")
            if (info?.key != null) log.add("Decryption key: present")
            ApiResult(info, log, if (info == null) licenseError(responseText, log) else null)
        } catch (e: Exception) {
            log.add("EXCEPTION: ${e::class.simpleName}: ${e.message}")
            Log.e("AudibleApi", "Failed to get download URL for $asin", e)
            ApiResult(null, log)
        }
    }

    /**
     * Fetches the chapter list for [asin]. Returns null data on failure (network/auth),
     * or an empty list when Audible has no chapters for the title.
     */
    suspend fun getChapters(asin: String): ApiResult<List<AudibleChapter>> {
        val log = mutableListOf<String>()
        val locale = AudibleEndpoints.getLocale(tokenStore.locale)
        val url = AudibleEndpoints.contentMetadataUrl(locale, asin)
        val accessToken = tokenStore.accessToken
        if (accessToken.isNullOrBlank()) {
            log.add("ERROR: No access token")
            return ApiResult(null, log)
        }

        val params = mapOf(
            "response_groups" to "chapter_info",
            "chapter_titles_type" to "Flat",
            "drm_type" to "Adrm",
            "quality" to "High"
        )

        return try {
            var response = httpClient.get(url) {
                params.forEach { (k, v) -> parameter(k, v) }
                authorize("GET", accessToken)
            }
            if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) {
                log.add("Got ${response.status}, refreshing token")
                if (!authManager.refreshAccessToken()) return ApiResult(null, log)
                response = httpClient.get(url) {
                    params.forEach { (k, v) -> parameter(k, v) }
                    authorize("GET", tokenStore.accessToken ?: "", sign = false)
                }
            }
            val responseText = response.bodyAsText()
            log.add("Status: ${response.status}")
            if (response.status != HttpStatusCode.OK) {
                log.add("Response: ${responseText.take(300)}")
                return ApiResult(null, log)
            }
            val chapters = json.decodeFromString<AudibleChapterResponse>(responseText)
                .contentMetadata?.chapterInfo?.chapters.orEmpty()
            ApiResult(flattenChapters(chapters), log)
        } catch (e: Exception) {
            log.add("EXCEPTION: ${e::class.simpleName}: ${e.message}")
            Log.e("AudibleApi", "Chapter fetch failed for $asin", e)
            ApiResult(null, log)
        }
    }

    private fun flattenChapters(chapters: List<AudibleChapter>): List<AudibleChapter> =
        chapters.flatMap { listOf(it.copy(chapters = null)) + flattenChapters(it.chapters.orEmpty()) }

    private fun decryptVoucher(asin: String, encryptedVoucher: String): String? {
        val deviceSerial = tokenStore.deviceSerial ?: return null
        val customerId = tokenStore.customerId ?: return null
        val deviceType = AudibleEndpoints.DEVICE_TYPE

        val buf = "$deviceType$deviceSerial$customerId$asin"
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(buf.toByteArray(Charsets.US_ASCII))
        val aesKey = digest.copyOfRange(0, 16)
        val aesIv = digest.copyOfRange(16, 32)

        val ciphertext = Base64.decode(encryptedVoucher, Base64.DEFAULT)

        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(aesKey, "AES"),
            javax.crypto.spec.IvParameterSpec(aesIv))
        val decrypted = cipher.doFinal(ciphertext)

        val plaintext = String(decrypted, Charsets.UTF_8).trimEnd('\u0000')
        debugLog.log("API", "Decrypted voucher: ${plaintext.take(100)}")
        return plaintext
    }

    /**
     * Logs every license_denial_reasons entry and returns a user-facing message.
     * Audible lists every check that failed, so "not part of any plans" shows up
     * even for purchased books; the Ownership entry is the one that matters.
     */
    private fun licenseError(responseText: String, log: MutableList<String>): String {
        val reasons = try {
            json.parseToJsonElement(responseText).jsonObject["content_license"]?.jsonObject
                ?.get("license_denial_reasons")?.jsonArray
                ?.map { it.jsonObject }
                .orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        reasons.forEach { r ->
            val type = r["validationType"]?.jsonPrimitive?.content
            val reason = r["rejectionReason"]?.jsonPrimitive?.content
            val message = r["message"]?.jsonPrimitive?.content
            log.add("Denied [$type] $reason: $message")
            debugLog.log("API", "Denied [$type] $reason: $message")
        }
        val codes = reasons.mapNotNull { it["rejectionReason"]?.jsonPrimitive?.content }
        return when {
            "CustomerThrottled" in codes -> {
                throttledUntil = System.currentTimeMillis() + THROTTLE_COOLDOWN_MS
                debugLog.log("API", "Throttled — skipping license requests for ${THROTTLE_COOLDOWN_MS / 60_000} min")
                throttleMessage()
            }
            codes.isNotEmpty() -> "Audible refused the download: ${codes.joinToString()}"
            else -> "Audible did not return a download link"
        }
    }

    private fun extractDownloadInfo(responseText: String): DownloadInfo? {
        return try {
            val responseJson = json.parseToJsonElement(responseText).jsonObject
            val contentLicense = responseJson["content_license"]?.jsonObject

            val url = contentLicense
                ?.get("content_metadata")?.jsonObject
                ?.get("content_url")?.jsonObject
                ?.get("offline_url")?.jsonPrimitive?.content
            if (url == null) {
                debugLog.log("API", "Could not find offline_url in response")
                return null
            }

            var key: String? = null
            var iv: String? = null
            val licenseResponseEl = contentLicense?.get("license_response")
            val asin = contentLicense?.get("asin")?.jsonPrimitive?.content
            if (licenseResponseEl != null && asin != null) {
                try {
                    if (licenseResponseEl is kotlinx.serialization.json.JsonObject) {
                        key = licenseResponseEl["key"]?.jsonPrimitive?.content
                        iv = licenseResponseEl["iv"]?.jsonPrimitive?.content
                    } else {
                        val encryptedVoucher = licenseResponseEl.jsonPrimitive.content
                        debugLog.log("API", "Decrypting voucher (${encryptedVoucher.length} chars)...")
                        val decrypted = decryptVoucher(asin, encryptedVoucher)
                        if (decrypted != null) {
                            val voucherJson = json.parseToJsonElement(decrypted).jsonObject
                            key = voucherJson["key"]?.jsonPrimitive?.content
                            iv = voucherJson["iv"]?.jsonPrimitive?.content
                        }
                    }
                } catch (e: Exception) {
                    debugLog.log("API", "Voucher decrypt error: ${e::class.simpleName}: ${e.message?.take(100)}")
                }
            }

            debugLog.log("API", "Key: ${if (key != null) "${key.length} chars" else "not found"}")
            DownloadInfo(url = url, key = key, iv = iv)
        } catch (e: Exception) {
            debugLog.log("API", "Parse error: ${e::class.simpleName}: ${e.message}")
            null
        }
    }

    companion object {
        private const val THROTTLE_COOLDOWN_MS = 15 * 60_000L
    }
}
