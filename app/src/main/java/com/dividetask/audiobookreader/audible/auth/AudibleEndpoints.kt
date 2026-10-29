package com.dividetask.audiobookreader.audible.auth

import java.security.MessageDigest
import java.util.UUID

object AudibleEndpoints {
    const val DEVICE_TYPE = "A2CZJZGLK2JJVM"

    data class Locale(
        val countryCode: String,
        val domain: String,
        val marketPlaceId: String,
        val apiDomain: String
    )

    val LOCALES = mapOf(
        "us" to Locale("US", "com", "AF2M0KC94RCEA", "api.audible.com"),
        "uk" to Locale("GB", "co.uk", "A2I9A3Q2GNFNGQ", "api.audible.co.uk"),
        "de" to Locale("DE", "de", "AN7V1F1VY261K", "api.audible.de"),
        "fr" to Locale("FR", "fr", "A2728XDNODOQ8T", "api.audible.fr"),
        "au" to Locale("AU", "com.au", "AN7EY7DTAW63G", "api.audible.com.au"),
        "ca" to Locale("CA", "ca", "A2CQZ5RBY40XE", "api.audible.ca"),
        "in" to Locale("IN", "in", "AJO3FBRUE6J4S", "api.audible.in"),
        "it" to Locale("IT", "it", "A2N7FU2W2BU2ZC", "api.audible.it"),
        "jp" to Locale("JP", "co.jp", "A1QAP3MOU4173J", "api.audible.co.jp"),
        "es" to Locale("ES", "es", "ALMIKO4SZCSAR", "api.audible.es")
    )

    fun getLocale(code: String): Locale = LOCALES[code] ?: LOCALES["us"]!!

    fun generateDeviceSerial(): String = UUID.randomUUID().toString().replace("-", "").uppercase()

    fun buildClientId(serial: String): String {
        val raw = "$serial#$DEVICE_TYPE"
        return raw.toByteArray(Charsets.US_ASCII).joinToString("") { "%02x".format(it) }
    }

    fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )
    }

    fun buildCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return android.util.Base64.encodeToString(
            digest,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )
    }

    fun loginUrl(locale: Locale, serial: String, codeVerifier: String): String {
        val codeChallenge = buildCodeChallenge(codeVerifier)
        val clientId = buildClientId(serial)
        val returnTo = "https://www.amazon.${locale.domain}/ap/maplanding"
        val assocHandle = "amzn_audible_ios_${locale.countryCode.lowercase()}"

        return "https://www.amazon.${locale.domain}/ap/signin?" +
                "openid.oa2.response_type=code&" +
                "openid.oa2.code_challenge_method=S256&" +
                "openid.oa2.code_challenge=$codeChallenge&" +
                "openid.return_to=${java.net.URLEncoder.encode(returnTo, "UTF-8")}&" +
                "openid.assoc_handle=$assocHandle&" +
                "openid.identity=http://specs.openid.net/auth/2.0/identifier_select&" +
                "pageId=amzn_audible_ios&" +
                "accountStatusPolicy=P1&" +
                "openid.claimed_id=http://specs.openid.net/auth/2.0/identifier_select&" +
                "openid.mode=checkid_setup&" +
                "openid.ns.oa2=http://www.amazon.com/ap/ext/oauth/2&" +
                "openid.oa2.client_id=device:$clientId&" +
                "openid.ns.pape=http://specs.openid.net/extensions/pape/1.0&" +
                "marketPlaceId=${locale.marketPlaceId}&" +
                "openid.oa2.scope=device_auth_access&" +
                "forceMobileLayout=true&" +
                "openid.ns=http://specs.openid.net/auth/2.0&" +
                "openid.pape.max_auth_age=0"
    }

    fun libraryUrl(locale: Locale): String =
        "https://${locale.apiDomain}/1.0/library"

    fun contentUrl(locale: Locale, asin: String): String =
        "https://${locale.apiDomain}/1.0/content/$asin/licenserequest"

    fun contentMetadataUrl(locale: Locale, asin: String): String =
        "https://${locale.apiDomain}/1.0/content/$asin/metadata"
}
