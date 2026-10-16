package com.dividetask.audiobookreader.audible.auth

import android.util.Log
import com.dividetask.audiobookreader.debug.AppDebugLog
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

data class RegistrationResult(
    val success: Boolean,
    val debugLog: List<String>
)

@Singleton
class AudibleAuthManager @Inject constructor(
    private val tokenStore: AudibleTokenStore,
    private val httpClient: HttpClient,
    private val debugLog: AppDebugLog
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun registerDevice(
        authorizationCode: String,
        serial: String,
        codeVerifier: String
    ): RegistrationResult {
        val log = mutableListOf<String>()
        val locale = AudibleEndpoints.getLocale(tokenStore.locale)
        val clientId = AudibleEndpoints.buildClientId(serial)

        log.add("Serial: ${serial.take(10)}...")
        log.add("Client ID: ${clientId.take(20)}...")
        log.add("Code verifier length: ${codeVerifier.length}")
        log.add("Auth code length: ${authorizationCode.length}")
        log.add("Locale domain: amazon.${locale.domain}")

        val body = buildJsonObject {
            put("requested_token_type", buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("bearer"))
                add(kotlinx.serialization.json.JsonPrimitive("mac_dms"))
                add(kotlinx.serialization.json.JsonPrimitive("website_cookies"))
                add(kotlinx.serialization.json.JsonPrimitive("store_authentication_cookie"))
            })
            put("cookies", buildJsonObject {
                put("website_cookies", buildJsonArray {})
                put("domain", ".amazon.${locale.domain}")
            })
            put("registration_data", buildJsonObject {
                put("domain", "Device")
                put("app_version", "3.56.2")
                put("device_serial", serial)
                put("device_type", AudibleEndpoints.DEVICE_TYPE)
                put("device_name",
                    "%FIRST_NAME%%FIRST_NAME_POSSESSIVE_STRING%%DUPE_STRATEGY_1ST%Audible for iPhone")
                put("os_version", "15.0.0")
                put("software_version", "35602678")
                put("device_model", "iPhone")
                put("app_name", "Audible")
            })
            put("auth_data", buildJsonObject {
                put("client_id", clientId)
                put("authorization_code", authorizationCode)
                put("code_verifier", codeVerifier)
                put("code_algorithm", "SHA-256")
                put("client_domain", "DeviceLegacy")
            })
            put("requested_extensions", buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("device_info"))
                add(kotlinx.serialization.json.JsonPrimitive("customer_info"))
            })
        }

        val registerUrl = "https://api.amazon.${locale.domain}/auth/register"
        log.add("Register URL: $registerUrl")

        debugLog.section("Device Registration")
        log.forEach { debugLog.log("REG", it) }

        return try {
            val response = httpClient.post(registerUrl) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }

            val responseText = response.bodyAsText()
            log.add("Register status: ${response.status}")
            log.add("Register response length: ${responseText.length}")
            log.add("Register response: ${responseText.take(500)}")
            debugLog.log("REG", "Status: ${response.status}")
            debugLog.log("REG", "Response: ${responseText.take(300)}")

            val responseJson = json.parseToJsonElement(responseText).jsonObject
            val success = responseJson["response"]?.jsonObject
                ?.get("success")?.jsonObject

            if (success != null) {
                val tokens = success["tokens"]?.jsonObject

                val bearer = tokens?.get("bearer")?.jsonObject
                tokenStore.accessToken = bearer?.get("access_token")?.jsonPrimitive?.content
                tokenStore.refreshToken = bearer?.get("refresh_token")?.jsonPrimitive?.content

                val macDms = tokens?.get("mac_dms")?.jsonObject
                tokenStore.adpToken = macDms?.get("adp_token")?.jsonPrimitive?.content
                tokenStore.devicePrivateKey = macDms?.get("device_private_key")?.jsonPrimitive?.content

                tokenStore.deviceSerial = serial

                val extensions = success["extensions"]?.jsonObject
                val customerInfo = extensions?.get("customer_info")?.jsonObject
                tokenStore.customerId = customerInfo?.get("user_id")?.jsonPrimitive?.content
                log.add("Got customer_id: ${!tokenStore.customerId.isNullOrBlank()}")

                log.add("Registration SUCCESS")
                debugLog.log("REG", "SUCCESS — tokens saved")
                log.add("Got access_token: ${!tokenStore.accessToken.isNullOrBlank()}")
                log.add("Got refresh_token: ${!tokenStore.refreshToken.isNullOrBlank()}")
                log.add("Got adp_token: ${!tokenStore.adpToken.isNullOrBlank()}")
                log.add("Got device_private_key: ${!tokenStore.devicePrivateKey.isNullOrBlank()}")

                RegistrationResult(true, log)
            } else {
                log.add("Registration FAILED: no success block in response")
                RegistrationResult(false, log)
            }
        } catch (e: Exception) {
            log.add("Registration EXCEPTION: ${e::class.simpleName}: ${e.message}")
            debugLog.log("REG", "EXCEPTION: ${e::class.simpleName}: ${e.message}")
            Log.e("AudibleAuth", "Device registration failed", e)
            RegistrationResult(false, log)
        }
    }

    suspend fun refreshAccessToken(): Boolean {
        val locale = AudibleEndpoints.getLocale(tokenStore.locale)
        val refreshToken = tokenStore.refreshToken ?: return false

        return try {
            val body = buildJsonObject {
                put("app_name", "Audible")
                put("app_version", "3.56.2")
                put("source_token", refreshToken)
                put("requested_token_type", "access_token")
                put("source_token_type", "refresh_token")
            }

            val tokenUrl = "https://api.amazon.${locale.domain}/auth/token"
            val response = httpClient.post(tokenUrl) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }

            val responseJson = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val newAccessToken = responseJson["access_token"]?.jsonPrimitive?.content

            if (newAccessToken != null) {
                tokenStore.accessToken = newAccessToken
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e("AudibleAuth", "Token refresh failed", e)
            false
        }
    }

    fun signOut() {
        tokenStore.clear()
    }
}
