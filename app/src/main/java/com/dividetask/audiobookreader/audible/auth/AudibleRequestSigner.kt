package com.dividetask.audiobookreader.audible.auth

import android.util.Base64
import android.util.Log
import com.dividetask.audiobookreader.debug.AppDebugLog
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.HttpStatusCode
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPrivateCrtKeySpec
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudibleRequestSigner @Inject constructor(
    private val tokenStore: AudibleTokenStore,
    private val debugLog: AppDebugLog
) {
    fun signRequest(method: String, path: String, body: String = ""): Map<String, String> {
        val adpToken = tokenStore.adpToken ?: return emptyMap()
        val privateKeyPem = tokenStore.devicePrivateKey ?: return emptyMap()

        val date = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())

        val data = "$method\n$path\n$date\n$body\n$adpToken"

        val signature = try {
            val privateKey = parsePkcs1PrivateKey(privateKeyPem)
                ?: throw Exception("Failed to parse private key")

            val sig = Signature.getInstance("SHA256withRSA")
            sig.initSign(privateKey)
            sig.update(data.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(sig.sign(), Base64.NO_WRAP)
        } catch (e: Exception) {
            debugLog.log("SIGN", "Signing failed: ${e::class.simpleName}: ${e.message}")
            Log.e("AudibleSigner", "Signing failed", e)
            return emptyMap()
        }

        return mapOf(
            "x-adp-token" to adpToken,
            "x-adp-alg" to "SHA256withRSA:1.0",
            "x-adp-signature" to "$signature:$date"
        )
    }

    private fun parsePkcs1PrivateKey(pem: String): java.security.PrivateKey? {
        val keyBytes = pem
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
            .let { Base64.decode(it, Base64.DEFAULT) }

        return try {
            // Try PKCS#8 first
            val pkcs8Spec = java.security.spec.PKCS8EncodedKeySpec(keyBytes)
            KeyFactory.getInstance("RSA").generatePrivate(pkcs8Spec)
        } catch (_: Exception) {
            try {
                // Parse PKCS#1 ASN.1 DER format
                parseDerRsaPrivateKey(keyBytes)
            } catch (e: Exception) {
                debugLog.log("SIGN", "Key parse failed: ${e.message}")
                null
            }
        }
    }

    private fun parseDerRsaPrivateKey(der: ByteArray): java.security.PrivateKey {
        val parser = DerParser(der)
        parser.readSequence()
        parser.readInteger() // version
        val modulus = parser.readBigInteger()
        val publicExponent = parser.readBigInteger()
        val privateExponent = parser.readBigInteger()
        val primeP = parser.readBigInteger()
        val primeQ = parser.readBigInteger()
        val primeExponentP = parser.readBigInteger()
        val primeExponentQ = parser.readBigInteger()
        val crtCoefficient = parser.readBigInteger()

        val keySpec = RSAPrivateCrtKeySpec(
            modulus, publicExponent, privateExponent,
            primeP, primeQ, primeExponentP, primeExponentQ, crtCoefficient
        )
        return KeyFactory.getInstance("RSA").generatePrivate(keySpec)
    }
}

private class DerParser(private val data: ByteArray) {
    private var pos = 0

    fun readSequence() {
        check(data[pos++].toInt() == 0x30) { "Expected SEQUENCE" }
        readLength()
    }

    fun readInteger(): Int {
        check(data[pos++].toInt() and 0xFF == 0x02) { "Expected INTEGER" }
        val len = readLength()
        var value = 0
        for (i in 0 until len) {
            value = (value shl 8) or (data[pos++].toInt() and 0xFF)
        }
        return value
    }

    fun readBigInteger(): BigInteger {
        check(data[pos++].toInt() and 0xFF == 0x02) { "Expected INTEGER" }
        val len = readLength()
        val bytes = data.copyOfRange(pos, pos + len)
        pos += len
        return BigInteger(bytes)
    }

    private fun readLength(): Int {
        val first = data[pos++].toInt() and 0xFF
        if (first < 0x80) return first
        val numBytes = first and 0x7F
        var length = 0
        for (i in 0 until numBytes) {
            length = (length shl 8) or (data[pos++].toInt() and 0xFF)
        }
        return length
    }
}

@Singleton
class ActivationBytesProvider @Inject constructor(
    private val tokenStore: AudibleTokenStore,
    private val signer: AudibleRequestSigner,
    private val httpClient: HttpClient,
    private val debugLog: AppDebugLog
) {
    suspend fun getActivationBytes(): String? {
        val cached = tokenStore.activationBytes
        if (!cached.isNullOrBlank()) return cached

        debugLog.section("Activation Bytes")
        debugLog.log("AUTH", "Fetching activation bytes...")

        val locale = AudibleEndpoints.getLocale(tokenStore.locale)
        val path = "/license/token"
        val url = "https://www.audible.${locale.domain}$path"

        val params = "player_manuf=Audible%2CiPhone&action=register&player_model=iPhone"
        val fullPath = "$path?$params"
        val headers = signer.signRequest("GET", fullPath)

        if (headers.isEmpty()) {
            debugLog.log("AUTH", "ERROR: Cannot sign request (missing adp_token or private key)")
            return null
        }

        debugLog.log("AUTH", "Signed request for: GET $fullPath")

        return try {
            val response = httpClient.get(url) {
                parameter("player_manuf", "Audible,iPhone")
                parameter("action", "register")
                parameter("player_model", "iPhone")
                headers.forEach { (k, v) -> header(k, v) }
            }

            debugLog.log("AUTH", "Status: ${response.status}")

            if (response.status != HttpStatusCode.OK) {
                debugLog.log("AUTH", "Failed: ${response.bodyAsText().take(200)}")
                return null
            }

            val responseBytes = response.readBytes()
            debugLog.log("AUTH", "Response: ${responseBytes.size} bytes")

            val responseText = String(responseBytes, Charsets.ISO_8859_1)
            debugLog.log("AUTH", "Response text: ${responseText.take(100)}")

            if (responseBytes.size < 0x238) {
                debugLog.log("AUTH", "Response too small for activation blob (need >= ${0x238} bytes)")
                if (responseText.contains("BAD_LOGIN") || responseText.contains("Whoops")) {
                    debugLog.log("AUTH", "Auth rejected — signing may be incorrect")
                }
                return null
            }

            val activationBytes = extractActivationBytes(responseBytes)
            if (activationBytes != null) {
                tokenStore.activationBytes = activationBytes
                debugLog.log("AUTH", "Activation bytes: $activationBytes")
            } else {
                debugLog.log("AUTH", "ERROR: Could not extract activation bytes from blob")
            }

            activationBytes
        } catch (e: Exception) {
            debugLog.log("AUTH", "EXCEPTION: ${e::class.simpleName}: ${e.message}")
            null
        }
    }

    private fun extractActivationBytes(data: ByteArray): String? {
        if (data.size < 0x238) return null

        val slice = data.copyOfRange(data.size - 0x238, data.size)

        val stripped = ByteArray(70 * 8)
        var pos = 0
        for (i in 0 until 8) {
            val offset = i * 71
            if (offset + 70 > slice.size) break
            System.arraycopy(slice, offset, stripped, pos, 70)
            pos += 70
        }

        if (stripped.size < 4) return null
        val value = (stripped[0].toInt() and 0xFF) or
                ((stripped[1].toInt() and 0xFF) shl 8) or
                ((stripped[2].toInt() and 0xFF) shl 16) or
                ((stripped[3].toInt() and 0xFF) shl 24)

        return String.format("%08x", value)
    }
}
