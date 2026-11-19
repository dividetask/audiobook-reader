package com.dividetask.audiobookreader.remote

import com.dividetask.audiobookreader.debug.AppDebugLog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CancellationException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Any S3-compatible object storage, using path-style URLs and AWS Signature Version 4. Large files go up as
 * multipart uploads, so an interrupted upload continues where it stopped.
 */
@Singleton
class S3Client @Inject constructor(
    private val settings: ServerSettings,
    private val debugLog: AppDebugLog
) {
    /** Runs [block] against the configured bucket. Blocking — call from Dispatchers.IO. */
    fun <T> use(block: (S3Connection) -> T): T {
        if (!settings.s3Configured) throw ServerException("Storage not set up — add it in Settings", settingsProblem = true)
        val base = try {
            URL(normalizeEndpoint(settings.s3Endpoint))
        } catch (e: Exception) {
            throw logged(ServerException("Invalid endpoint \"${settings.s3Endpoint}\"", settingsProblem = true))
        }
        val region = settings.s3Region.trim().ifEmpty { guessRegion(base.host) }
        debugLog.log("S3", "Using ${base.host} bucket ${settings.s3Bucket} (region $region)")
        val conn = S3Connection(base, region, settings.s3AccessKey.trim(), settings.s3SecretKey.trim(),
            settings.s3Bucket.trim(), settings.folder.trim().trim('/'), settings) { debugLog.log("S3", it) }
        return try {
            block(conn)
        } catch (e: ServerException) {
            throw logged(e)
        } catch (e: UnknownHostException) {
            throw logged(ServerException("Unknown host \"${base.host}\"", e))
        } catch (e: ConnectException) {
            throw logged(ServerException("Could not connect to ${base.host}", e))
        } catch (e: SocketTimeoutException) {
            throw logged(ServerException("Timed out talking to ${base.host}", e))
        } catch (e: javax.net.ssl.SSLException) {
            throw logged(ServerException("Secure connection to ${base.host} failed: ${e.message}", e))
        }
    }

    private fun logged(e: ServerException): ServerException {
        debugLog.log("S3", "Failed: ${e.message}")
        return e
    }

    companion object {
        /** "s3.region.example.com" → "https://s3.region.example.com". */
        fun normalizeEndpoint(input: String): String {
            val s = input.trim().trimEnd('/')
            return if (s.contains("://")) s else "https://$s"
        }

        /**
         * The region from the common "s3.<region>.<domain>" / "s3-<region>.<domain>" endpoint
         * form; "us-east-1" (the S3 default) when the name doesn't carry one.
         */
        fun guessRegion(host: String): String =
            Regex("""^s3[.-]([a-z0-9-]+)\.[^.]+\.[^.]+""").find(host)?.groupValues?.get(1) ?: "us-east-1"
    }
}

class S3Connection internal constructor(
    private val base: URL,
    private val region: String,
    private val accessKey: String,
    private val secretKey: String,
    private val bucket: String,
    /** Folder inside the bucket, without slashes; "" for the top. */
    private val prefix: String,
    private val settings: ServerSettings,
    private val log: (String) -> Unit
) : StorageConnection {

    override val root: String get() = if (prefix.isEmpty()) bucket else "$bucket/$prefix"

    private fun keyFor(rel: String) = if (prefix.isEmpty()) rel else "$prefix/$rel"

    override fun listAudio(): List<RemoteFile> {
        val out = mutableListOf<RemoteFile>()
        val listPrefix = if (prefix.isEmpty()) "" else "$prefix/"
        var token: String? = null
        var objects = 0
        val skipped = mutableListOf<String>()
        do {
            val query = sortedMapOf("list-type" to "2", "prefix" to listPrefix)
            token?.let { query["continuation-token"] = it }
            val xml = request("GET", null, query).text()
            for (m in Regex("<Contents>(.*?)</Contents>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
                val item = m.groupValues[1]
                val rel = tag(item, "Key")?.removePrefix(listPrefix) ?: continue
                objects++
                val name = rel.substringAfterLast('/')
                if (name.startsWith(".") || name.substringAfterLast('.', "").lowercase() !in AUDIO_EXTENSIONS) {
                    if (skipped.size < 3) skipped.add(rel)
                    continue
                }
                val size = tag(item, "Size")?.toLongOrNull() ?: 0
                val modified = tag(item, "LastModified")?.let {
                    try { Instant.parse(it).toEpochMilli() } catch (_: Exception) { 0L }
                } ?: 0L
                out.add(RemoteFile(rel, size, modified))
            }
            token = if (tag(xml, "IsTruncated") == "true") tag(xml, "NextContinuationToken") else null
        } while (token != null)
        log("List \"$bucket\" folder \"${listPrefix.ifEmpty { "(top)" }}\": $objects objects, ${out.size} audio" +
            if (skipped.isNotEmpty()) "; not audio e.g. ${skipped.joinToString(", ")}" else "")
        if (objects == 0 && listPrefix.isNotEmpty()) logBucketSample()
        return out.sortedBy { it.relPath.lowercase() }
    }

    /** Nothing under the folder: log a few keys from the top of the bucket to show where the files are. */
    private fun logBucketSample() {
        try {
            val xml = request("GET", null, sortedMapOf("list-type" to "2", "max-keys" to "5")).text()
            val keys = Regex("<Key>(.*?)</Key>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { unescape(it.groupValues[1]) }.toList()
            log(if (keys.isEmpty()) "Bucket \"$bucket\" is empty" else "Bucket \"$bucket\" starts with: ${keys.joinToString(", ")}")
        } catch (e: Exception) {
            log("Bucket sample failed: ${e.message}")
        }
    }

    override fun upload(local: File, rel: String, progress: (Long, Long) -> Boolean) {
        val key = keyFor(rel)
        val size = local.length()
        if (size <= PART_SIZE) {
            progress(0, size)
            request("PUT", key, sortedMapOf(), local.readBytes())
            if (!progress(size, size)) throw CancellationException("Upload stopped")
            return
        }

        // Continue an earlier multipart upload of this key if the bucket still has it
        var uploadId = settings.s3UploadId(key)
        val done = sortedMapOf<Int, String>()
        if (uploadId != null) {
            try {
                done.putAll(listParts(key, uploadId))
            } catch (e: ServerException) {
                uploadId = null
            }
        }
        if (uploadId == null) {
            uploadId = tag(request("POST", key, sortedMapOf("uploads" to "")).text(), "UploadId")
                ?: throw ServerException("Storage didn't start the upload")
            settings.setS3UploadId(key, uploadId)
        }
        val id: String = uploadId

        val parts = ((size + PART_SIZE - 1) / PART_SIZE).toInt()
        RandomAccessFile(local, "r").use { raf ->
            for (n in 1..parts) {
                val offset = (n - 1) * PART_SIZE
                val length = minOf(PART_SIZE, size - offset).toInt()
                if (n !in done) {
                    val buffer = ByteArray(length)
                    raf.seek(offset)
                    raf.readFully(buffer)
                    val response = request("PUT", key, sortedMapOf("partNumber" to "$n", "uploadId" to id), buffer)
                    done[n] = response.header("ETag") ?: throw ServerException("Storage gave no ETag for part $n")
                }
                if (!progress(offset + length, size)) throw CancellationException("Upload stopped")
            }
        }

        val body = buildString {
            append("<CompleteMultipartUpload>")
            done.forEach { (n, etag) -> append("<Part><PartNumber>$n</PartNumber><ETag>$etag</ETag></Part>") }
            append("</CompleteMultipartUpload>")
        }
        val result = request("POST", key, sortedMapOf("uploadId" to id), body.toByteArray()).text()
        // Completion can fail with 200 and an <Error> body
        if (result.contains("<Error>")) {
            throw ServerException(errorMessage(200, result), settingsProblem = tag(result, "Code") in SETTINGS_ERRORS)
        }
        settings.setS3UploadId(key, null)
    }

    override fun download(rel: String, local: File, total: Long, progress: (Long, Long) -> Boolean) {
        val existing = if (local.exists()) local.length() else 0L
        if (total in 1..existing) return
        val headers = if (existing > 0) mapOf("Range" to "bytes=$existing-") else emptyMap()
        request("GET", keyFor(rel), sortedMapOf(), extraHeaders = headers) { input, code ->
            val resumed = code == 206
            var read = if (resumed) existing else 0L
            FileOutputStream(local, resumed).use { out ->
                val buffer = ByteArray(65536)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    read += n
                    if (!progress(read, total)) throw CancellationException("Download stopped")
                }
            }
        }
    }

    private fun listParts(key: String, uploadId: String): Map<Int, String> {
        val xml = request("GET", key, sortedMapOf("uploadId" to uploadId)).text()
        return Regex("<Part>(.*?)</Part>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
            val n = tag(m.groupValues[1], "PartNumber")?.toIntOrNull() ?: return@mapNotNull null
            val etag = tag(m.groupValues[1], "ETag") ?: return@mapNotNull null
            n to etag
        }.toMap()
    }

    // ------------------------------------------------------------------ HTTP + SigV4

    private class Response(val code: Int, private val headers: Map<String?, List<String>>, val body: ByteArray) {
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
        fun text() = String(body)
    }

    /**
     * One signed request. [sink] receives the body of a successful response as a stream
     * (for downloads); otherwise the body is read into memory.
     */
    private fun request(
        method: String,
        key: String?,
        query: java.util.SortedMap<String, String>,
        payload: ByteArray? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        sink: ((InputStream, Int) -> Unit)? = null
    ): Response {
        val path = "/" + uriEncode(bucket, true) + (key?.let { "/" + uriEncode(it, false) } ?: "")
        val queryString = query.entries.joinToString("&") { "${uriEncode(it.key, true)}=${uriEncode(it.value, true)}" }
        val url = URL(base.protocol, base.host, base.port, path + if (queryString.isEmpty()) "" else "?$queryString")

        val now = ZonedDateTime.now(ZoneOffset.UTC)
        val amzDate = now.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
        val date = amzDate.substring(0, 8)
        val payloadHash = hex(sha256(payload ?: ByteArray(0)))
        val defaultPort = if (base.protocol == "https") 443 else 80
        val host = if (base.port == -1 || base.port == defaultPort) base.host else "${base.host}:${base.port}"

        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val canonicalRequest = listOf(
            method, path, queryString,
            "host:$host\nx-amz-content-sha256:$payloadHash\nx-amz-date:$amzDate\n",
            signedHeaders, payloadHash
        ).joinToString("\n")
        val scope = "$date/$region/s3/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n${hex(sha256(canonicalRequest.toByteArray()))}"
        var signingKey = hmac("AWS4$secretKey".toByteArray(), date)
        signingKey = hmac(signingKey, region)
        signingKey = hmac(signingKey, "s3")
        signingKey = hmac(signingKey, "aws4_request")
        val signature = hex(hmac(signingKey, stringToSign))

        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("x-amz-date", amzDate)
            conn.setRequestProperty("x-amz-content-sha256", payloadHash)
            conn.setRequestProperty("Authorization",
                "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, SignedHeaders=$signedHeaders, Signature=$signature")
            extraHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (payload != null || method == "PUT" || method == "POST") {
                val bytes = payload ?: ByteArray(0)
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val error = conn.errorStream?.use { it.readBytes() }?.let { String(it) } ?: ""
                if (tag(error, "Code") == "SignatureDoesNotMatch") {
                    // Compare what we signed with what the server says it expected
                    val oneLine = { t: String -> t.replace("\n", " | ") }
                    log("Signature mismatch on $method ${url.path}${url.query?.let { "?$it" } ?: ""} (HTTP $code)")
                    log("We signed: ${oneLine(canonicalRequest)}")
                    tag(error, "CanonicalRequest")?.let { log("Server expected: ${oneLine(it)}") }
                    tag(error, "StringToSign")?.let { log("Server string to sign: ${oneLine(it)}") }
                    tag(error, "Message")?.let { log("Server message: $it") }
                }
                throw ServerException(errorMessage(code, error), settingsProblem = tag(error, "Code") in SETTINGS_ERRORS)
            }
            if (sink != null) {
                conn.inputStream.use { sink(it, code) }
                return Response(code, conn.headerFields, ByteArray(0))
            }
            val body = conn.inputStream.use { it.readBytes() }
            return Response(code, conn.headerFields, body)
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMessage(status: Int, xml: String): String {
        val code = tag(xml, "Code")
        val message = tag(xml, "Message")
        return when (code) {
            "InvalidAccessKeyId", "SignatureDoesNotMatch" -> "Wrong access key ID or secret key ($code)"
            "NoSuchBucket" -> "Bucket \"$bucket\" not found"
            "AccessDenied" -> "Access denied — check the key is allowed to use bucket \"$bucket\""
            "AuthorizationHeaderMalformed" -> "Wrong region — ${message ?: "check the Region field"}"
            null -> "Storage error (HTTP $status)"
            else -> "Storage error $code${message?.let { ": $it" } ?: ""}"
        }
    }

    companion object {
        /** S3 error codes that mean the bucket, key or region in Settings is wrong. */
        private val SETTINGS_ERRORS = setOf(
            "InvalidAccessKeyId", "SignatureDoesNotMatch", "NoSuchBucket", "AccessDenied", "AuthorizationHeaderMalformed"
        )

        /** 16 MB parts: well above S3's 5 MB minimum, small enough to hold in memory. */
        private const val PART_SIZE = 16L * 1024 * 1024

        private fun tag(xml: String, name: String): String? =
            Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.let(::unescape)

        private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

        /** RFC 3986 encoding as SigV4 wants it; '/' kept in object keys. */
        private fun uriEncode(s: String, encodeSlash: Boolean): String {
            val sb = StringBuilder()
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt().toChar()
                when {
                    c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~' -> sb.append(c)
                    c == '/' && !encodeSlash -> sb.append(c)
                    else -> sb.append('%').append(String.format("%02X", b.toInt() and 0xFF))
                }
            }
            return sb.toString()
        }

        private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

        private fun hmac(key: ByteArray, data: String): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray())

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
    }
}
