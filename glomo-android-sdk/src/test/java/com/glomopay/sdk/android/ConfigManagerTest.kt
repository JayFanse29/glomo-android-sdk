package com.glomopay.sdk.android

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigManagerTest {
    @Test
    fun custom_server_is_normalized_like_flutter() {
        assertEquals(
            "https://custom.example/",
            ConfigManager.getBaseUrl(GlomoPayConfig("live_key", orderId = "order_1", server = "https://custom.example")),
        )
        assertEquals(
            "https://custom.example/",
            ConfigManager.getBaseUrl(GlomoPayConfig("live_key", orderId = "order_1", server = "https://custom.example/")),
        )
    }

    @Test
    fun default_base_urls_follow_order_type() {
        val config = GlomoPayConfig("live_key", orderId = "order_1")

        assertEquals("https://lrs-checkout.glomopay.com/", ConfigManager.getBaseUrl(config, "lrs"))
        assertEquals("https://checkout.glomopay.com/", ConfigManager.getBaseUrl(config, "standard"))
        assertEquals("https://checkout.glomopay.com/", ConfigManager.getBaseUrl(config, "other"))
    }

    @Test
    fun checkout_url_maps_subscription_to_order_id_query_parameter() {
        val config = GlomoPayConfig("test_key", subscriptionId = "sub_123")
        val uri = URI(ConfigManager.getCheckoutUrl(config, "lrs"))

        assertEquals("sub_123", query(uri, "orderId"))
        assertEquals("test_key", query(uri, "publicKey"))
        assertEquals("mock", query(uri, "mode"))
        assertEquals("lrs-checkout.glomopay.com", uri.host)
    }

    @Test
    fun checkout_url_encodes_query_values_and_replaces_existing_query() {
        val config = GlomoPayConfig(
            "live_key with spaces",
            orderId = "order/a?b",
            server = "https://custom.example/checkout?old=value",
        )
        val uri = URI(ConfigManager.getCheckoutUrl(config))

        assertEquals("order/a?b", query(uri, "orderId"))
        assertEquals("live_key with spaces", query(uri, "publicKey"))
        assertEquals(null, query(uri, "old"))
    }

    @Test
    fun checkout_url_appends_journey_id_after_mode() {
        val config = GlomoPayConfig("test_key", orderId = "order_1")
        val url = ConfigManager.getCheckoutUrl(config, "lrs", "0f8e6c1a-2b3d-4e5f-8a9b-0c1d2e3f4a5b")

        assertEquals(
            "https://lrs-checkout.glomopay.com/?orderId=order_1&publicKey=test_key&mode=mock" +
                "&journeyId=0f8e6c1a-2b3d-4e5f-8a9b-0c1d2e3f4a5b",
            url,
        )
    }

    @Test
    fun checkout_url_omits_journey_id_when_absent_or_blank() {
        val config = GlomoPayConfig("test_key", orderId = "order_1")

        assertEquals(null, query(URI(ConfigManager.getCheckoutUrl(config)), "journeyId"))
        assertEquals(null, query(URI(ConfigManager.getCheckoutUrl(config, "standard", null)), "journeyId"))
        assertEquals(null, query(URI(ConfigManager.getCheckoutUrl(config, "standard", " ")), "journeyId"))
    }

    @Test
    fun retry_url_replaces_the_journey_id_on_a_checkout_origin_url() {
        val config = GlomoPayConfig("test_key", orderId = "order_1")

        assertEquals(
            "https://lrs-checkout.glomopay.com/review?orderId=order_1&step=2&journeyId=journey-1#top",
            ConfigManager.getRetryUrl(
                config,
                "lrs",
                "journey-1",
                "https://lrs-checkout.glomopay.com/review?orderId=order_1&journeyId=stale&step=2#top",
            ),
        )
    }

    @Test
    fun retry_url_rebuilds_the_checkout_url_off_the_checkout_origin_or_without_a_last_url() {
        val config = GlomoPayConfig("test_key", orderId = "order_1")
        val checkoutUrl = ConfigManager.getCheckoutUrl(config, "lrs", "journey-1")

        assertEquals(checkoutUrl, ConfigManager.getRetryUrl(config, "lrs", "journey-1", "https://bank.example/acs?t=1"))
        assertEquals(checkoutUrl, ConfigManager.getRetryUrl(config, "lrs", "journey-1", "about:blank"))
        assertEquals(checkoutUrl, ConfigManager.getRetryUrl(config, "lrs", "journey-1", null))
    }

    @Test
    fun checkout_document_start_origin_rules_match_the_checkout_host_only() {
        assertEquals(
            setOf("https://checkout.glomopay.com"),
            ConfigManager.getCheckoutDocumentStartOriginRules(
                GlomoPayConfig("live_key", orderId = "order_1"),
            ),
        )
        assertEquals(
            setOf("https://lrs-checkout.glomopay.com"),
            ConfigManager.getCheckoutDocumentStartOriginRules(
                GlomoPayConfig("live_key", orderId = "order_1"),
                "lrs",
            ),
        )
        assertEquals(
            setOf("https://merchant.example:8443"),
            ConfigManager.getCheckoutDocumentStartOriginRules(
                GlomoPayConfig(
                    "live_key",
                    orderId = "order_1",
                    server = "https://merchant.example:8443/checkout",
                ),
            ),
        )
    }

    @Test
    fun checkout_document_start_origin_rules_ignore_non_http_urls() {
        assertEquals(emptySet(), ConfigManager.checkoutDocumentStartOriginRules("file:///android_asset/checkout.html"))
        assertEquals(emptySet(), ConfigManager.checkoutDocumentStartOriginRules("not a url"))
    }

    @Test
    fun mode_helpers_match_flutter_prefix_rules() {
        assertEquals("live", ConfigManager.getMode("live_key"))
        assertEquals("mock", ConfigManager.getMode("test_key"))
        assertEquals("mock", ConfigManager.getMode("mock_key"))
        assertEquals("live", ConfigManager.getMode("other_key"))
        assertTrue(ConfigManager.isTestOrMock("test_key"))
        assertTrue(ConfigManager.isTestOrMock("mock_key"))
        assertFalse(ConfigManager.isTestOrMock("live_key"))
    }

    @Test
    fun carousel_url_uses_fixed_host_and_order_id_only() {
        val config = GlomoPayConfig(
            "live_key",
            orderId = "order_1",
            server = "https://custom.example/",
        )
        val uri = URI(ConfigManager.getCarouselUrl(config))

        assertEquals("glomopay-utilities.web.app", uri.host)
        assertEquals("order_1", query(uri, "orderId"))
        assertEquals("live_key", query(uri, "publicKey"))
        assertEquals(null, query(uri, "mode"))
    }

    @Test
    fun lrs_url_preserves_subscription_and_custom_server() {
        val config = GlomoPayConfig(
            publicKey = "live_key",
            subscriptionId = "sub/with spaces",
            server = "https://merchant.example/",
        )
        val uri = URI(ConfigManager.getCheckoutUrl(config, "lrs"))

        assertEquals("merchant.example", uri.host)
        assertEquals("sub/with spaces", query(uri, "orderId"))
        assertEquals("live", query(uri, "mode"))
    }

    @Test
    fun empty_custom_server_falls_back_to_default_host() {
        val config = GlomoPayConfig("test_key", orderId = "order_1", server = "")

        assertEquals("https://checkout.glomopay.com/", ConfigManager.getBaseUrl(config))
    }

    @Test
    fun order_response_detection_prefers_explicit_type_then_lrs_presence() {
        assertEquals("lrs", ConfigManager.detectOrderType(mapOf("orderType" to "lrs")))
        assertEquals("standard", ConfigManager.detectOrderType(mapOf("orderType" to "standard", "lrs" to true)))
        assertEquals("lrs", ConfigManager.detectOrderType(mapOf("lrs" to emptyMap<String, Any?>())))
        assertEquals("standard", ConfigManager.detectOrderType(mapOf("status" to "created")))
    }

    private fun query(uri: URI, name: String): String? = uri.rawQuery
        ?.split('&')
        ?.map { it.split('=', limit = 2) }
        ?.firstOrNull { it.first() == name }
        ?.getOrNull(1)
        ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
}
