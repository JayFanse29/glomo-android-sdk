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
        getCheckoutUrl(config, orderType, colorScheme = null)

    /**
     * [colorScheme] is the already-resolved scheme; `AUTO` never reaches here. Omitted when null,
     * which leaves the page on its own default.
     *
     * Internal because the resolved type is: resolving needs the activity's configuration, so no
     * caller outside this SDK is in a position to supply one. A scheme change mid-checkout must
     * NOT rebuild this URL - a reload destroys the payment. `GlomoPayInjectionScripts.colorScheme`
     * is the live path.
     */
    internal fun getCheckoutUrl(
        config: GlomoPayConfig,
        orderType: String,
        colorScheme: ResolvedColorScheme?,
    ): String {
        val checkoutId = config.checkoutId ?: ""
        return withQuery(
            getBaseUrl(config, orderType),
            buildList {
                add("orderId" to checkoutId)
                add("publicKey" to config.publicKey)
                add("mode" to getMode(config.publicKey))
                // Three names for one concept, by design: the property is `appColorScheme`, the
                // wire is `colorScheme`, the page hook is `glomoSetColorScheme`. The README says
                // so, so nobody assumes the wire was renamed with the property.
                if (colorScheme != null) add("colorScheme" to colorScheme.wireName)
            },
        )
    }

    internal fun getCheckoutDocumentStartOriginRules(
        config: GlomoPayConfig,
        orderType: String = "standard",
    ): Set<String> = checkoutDocumentStartOriginRules(getCheckoutUrl(config, orderType))

    internal fun checkoutDocumentStartOriginRules(url: String): Set<String> =
        originOf(url)?.let { setOf(it) } ?: emptySet()

    /** `scheme://host[:port]`, or null for anything that is not an http(s) URL. */
    internal fun originOf(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        val port = uri.port.takeIf { it >= 0 }?.let { ":$it" } ?: ""
        return "$scheme://$host$port"
    }

    /** [colorScheme] carries the same resolved value on the same param name - the carousel is on
     * the same contract as the checkout pages. */
    internal fun getCarouselUrl(
        config: GlomoPayConfig,
        colorScheme: ResolvedColorScheme? = null,
    ): String =
        withQuery(
            CAROUSEL_BASE_URL,
            buildList {
                add("orderId" to (config.orderId ?: ""))
                add("publicKey" to config.publicKey)
                if (colorScheme != null) add("colorScheme" to colorScheme.wireName)
            },
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

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}
