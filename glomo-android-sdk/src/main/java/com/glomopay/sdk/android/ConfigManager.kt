package com.glomopay.sdk.android

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** URL and environment behavior mirrored from the Flutter ConfigManager. */
public object ConfigManager {
    private const val DEFAULT_LRS_BASE_URL = "https://lrs-checkout.glomopay.com/"
    private const val DEFAULT_STANDARD_BASE_URL = "https://checkout.glomopay.com/"
    private const val CAROUSEL_BASE_URL = "https://glomopay-utilities.web.app/lrs-education-carousel/"

    public fun getBaseUrl(config: GlomoPayConfig, orderType: String = "standard"): String {
        val customServer = config.server
        if (!customServer.isNullOrEmpty()) {
            return if (customServer.endsWith('/')) customServer else "$customServer/"
        }
        return if (orderType.lowercase() == "lrs") DEFAULT_LRS_BASE_URL else DEFAULT_STANDARD_BASE_URL
    }

    public fun getCheckoutUrl(config: GlomoPayConfig, orderType: String = "standard"): String =
        getCheckoutUrl(config, orderType, journeyId = null)

    internal fun getCheckoutUrl(config: GlomoPayConfig, orderType: String, journeyId: String?): String {
        val checkoutId = config.checkoutId ?: ""
        return withQuery(
            getBaseUrl(config, orderType),
            listOfNotNull(
                "orderId" to checkoutId,
                "publicKey" to config.publicKey,
                "mode" to getMode(config.publicKey),
                journeyId?.takeIf(String::isNotBlank)?.let { "journeyId" to it },
            ),
        )
    }

    internal fun getRetryUrl(
        config: GlomoPayConfig,
        orderType: String,
        journeyId: String?,
        lastUrl: String?,
    ): String {
        val checkoutUrl = getCheckoutUrl(config, orderType, journeyId)
        if (lastUrl == null) return checkoutUrl
        if (checkoutDocumentStartOriginRules(lastUrl) != checkoutDocumentStartOriginRules(checkoutUrl)) return checkoutUrl
        return journeyId?.takeIf(String::isNotBlank)?.let { withJourneyId(lastUrl, it) } ?: lastUrl
    }

    internal fun getCheckoutDocumentStartOriginRules(
        config: GlomoPayConfig,
        orderType: String = "standard",
    ): Set<String> = checkoutDocumentStartOriginRules(getCheckoutUrl(config, orderType))

    internal fun checkoutDocumentStartOriginRules(url: String): Set<String> {
        val uri = runCatching { URI(url) }.getOrNull() ?: return emptySet()
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase() ?: return emptySet()
        if (scheme != "https" && scheme != "http") return emptySet()
        val port = uri.port.takeIf { it >= 0 }?.let { ":$it" } ?: ""
        return setOf("$scheme://$host$port")
    }

    internal fun getCarouselUrl(config: GlomoPayConfig): String =
        withQuery(
            CAROUSEL_BASE_URL,
            listOf("orderId" to (config.orderId ?: ""), "publicKey" to config.publicKey),
        )

    public fun getMode(publicKey: String): String = when {
        publicKey.startsWith("test_") || publicKey.startsWith("mock_") -> "mock"
        else -> "live"
    }

    public fun isTestOrMock(publicKey: String): Boolean =
        publicKey.startsWith("test_") || publicKey.startsWith("mock_")

    /** Detects checkout type using the same order response heuristic as Flutter. */
    public fun detectOrderType(orderData: Map<String, Any?>): String {
        val explicit = orderData["orderType"]?.toString()?.takeIf { it.isNotBlank() }
        if (explicit != null) return explicit
        return if (orderData.containsKey("lrs") && orderData["lrs"] != null) "lrs" else "standard"
    }

    private fun withQuery(baseUrl: String, parameters: List<Pair<String, String>>): String {
        val query = parameters.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        val fragmentStart = baseUrl.indexOf('#')
        val fragment = if (fragmentStart >= 0) baseUrl.substring(fragmentStart) else ""
        val withoutFragment = if (fragmentStart >= 0) baseUrl.substring(0, fragmentStart) else baseUrl
        val withoutQuery = withoutFragment.substringBefore('?')
        return "$withoutQuery?$query$fragment"
    }

    private fun withJourneyId(url: String, journeyId: String): String {
        val fragmentStart = url.indexOf('#')
        val fragment = if (fragmentStart >= 0) url.substring(fragmentStart) else ""
        val withoutFragment = if (fragmentStart >= 0) url.substring(0, fragmentStart) else url
        val parameters = withoutFragment.substringAfter('?', "")
            .split('&')
            .filter { it.isNotEmpty() && it.substringBefore('=') != "journeyId" }
        val query = (parameters + "journeyId=${encode(journeyId)}").joinToString("&")
        return "${withoutFragment.substringBefore('?')}?$query$fragment"
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}
