package com.glomopay.sdk.android

import com.glomopay.sdk.android.analytics.AnalyticsEvent
import com.glomopay.sdk.android.analytics.AnalyticsTransport
import com.glomopay.sdk.android.analytics.MixpanelAnalyticsTracker
import com.glomopay.sdk.android.analytics.MixpanelEventEncoder
import com.glomopay.sdk.android.analytics.MIXPANEL_TRACK_ENDPOINT
import com.glomopay.sdk.android.analytics.validateMixpanelResponse
import com.glomopay.sdk.android.monitoring.SdkErrorReporter
import org.json.JSONArray
import java.net.URI
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class MixpanelAnalyticsTrackerTest {
    @Test
    fun tracker_adds_the_approved_common_property_contract() {
        val events = mutableListOf<AnalyticsEvent>()
        val tracker = MixpanelAnalyticsTracker(
            config = GlomoPayConfig(
                publicKey = "test_public_key",
                orderId = "order_123",
            ),
            sessionId = "session-uuid",
            sdkVersion = "1.0.0",
            initialFlowType = "auto",
            transport = AnalyticsTransport { events += it },
            executor = java.util.concurrent.Executor(Runnable::run),
            clock = { 1_723_620_000_000L },
            deviceProperties = { mapOf(
                "device_os_version" to "14",
                "\$model" to "Pixel 8",
                "\$device" to "shiba",
                "\$wifi_enabled" to true,
                "\$cellular_enabled" to false,
                "\$app_namespace" to "com.example.merchant",
                "merchant_target_sdk_version" to 36,
            ) },
        )

        tracker.updateFlowType("lrs")
        tracker.updateCheckoutUrl("https://checkout.glomopay.com/?orderId=order_123")
        tracker.track("Checkout Started")

        val properties = events.single().properties
        assertEquals("\$device:session-uuid", properties["distinct_id"])
        assertEquals("session-uuid", properties["\$device_id"])
        assertEquals("session-uuid", properties["journey_id"])
        assertEquals("session-uuid", properties["session_id"])
        assertEquals("order_123", properties["order_id"])
        assertEquals("Pixel 8", properties["\$model"])
        assertEquals(true, properties["\$wifi_enabled"])
        assertEquals(false, properties["\$cellular_enabled"])
        assertEquals(36, properties["merchant_target_sdk_version"])
        assertEquals("lrs", properties["flow_type"])
        assertEquals(true, properties["mock_mode"])
        assertEquals(BuildConfig.GLOMO_INTERNAL_BUILD, properties["dev_mode"])
        assertEquals("android-sdk", properties["surface"])
        assertEquals(1_723_620_000_000L, properties["time"])
        assertEquals("2024-08-14T12:50:00.000+05:30", properties["timestamp"])
    }

    @Test
    fun subscription_flow_keeps_nullable_order_identity_without_fallback() {
        val events = mutableListOf<AnalyticsEvent>()
        val tracker = MixpanelAnalyticsTracker(
            config = GlomoPayConfig(publicKey = "test_public_key", subscriptionId = "sub_123"),
            sessionId = "session-uuid",
            sdkVersion = "1.0.0",
            initialFlowType = "standard",
            transport = AnalyticsTransport { events += it },
            executor = java.util.concurrent.Executor(Runnable::run),
            deviceProperties = { emptyMap() },
        )

        tracker.track("SDK Initialized")

        val properties = events.single().properties
        assertTrue(properties.containsKey("order_id"))
        assertEquals(null, properties["order_id"])
        assertEquals("\$device:session-uuid", properties["distinct_id"])
        assertEquals("sub_123", properties["subscription_id"])
        assertTrue(properties.containsKey("checkout_url"))
        assertEquals(null, properties["checkout_url"])
    }

    @Test
    fun checkout_url_is_tracked_without_its_query_string() {
        val events = mutableListOf<AnalyticsEvent>()
        val sessionId = UUID.randomUUID().toString()
        val config = GlomoPayConfig(publicKey = "test_public_key", orderId = "order_123")
        val tracker = tracker(config, sessionId) { events += it }

        tracker.updateCheckoutUrl(ConfigManager.getCheckoutUrl(config, "lrs", sessionId))
        tracker.track("Checkout Started")

        val checkoutUrl = events.single().properties["checkout_url"] as String
        assertEquals("https://lrs-checkout.glomopay.com/", checkoutUrl)
        assertEquals(null, URI(checkoutUrl).rawQuery)
        assertFalse(checkoutUrl.contains("journeyId"))
    }

    @Test
    fun insert_id_is_unique_per_event() {
        val events = mutableListOf<AnalyticsEvent>()
        val tracker = tracker(GlomoPayConfig(publicKey = "test_public_key", orderId = "order_123")) { events += it }

        tracker.track("Navigation Started")
        tracker.track("Navigation Started")

        val insertIds = events.map { it.properties["\$insert_id"] as String }
        assertEquals(2, insertIds.size)
        assertNotEquals(insertIds[0], insertIds[1])
        insertIds.forEach { assertEquals(it, UUID.fromString(it).toString()) }
    }

    @Test
    fun sdk_open_tracks_sdk_loaded_once_with_integration_and_device_properties() {
        val events = mutableListOf<AnalyticsEvent>()
        val tracker = tracker(
            GlomoPayConfig(publicKey = "test_public_key", orderId = "order_123"),
            deviceProperties = mapOf(
                "\$os" to "Android",
                "\$os_version" to "14",
                "\$model" to "Pixel 8",
            ),
        ) { events += it }

        GlomoPaySdk.trackSdkOpened(tracker)

        assertEquals(listOf("SDK Initialized", "sdk_loaded"), events.map { it.name })
        val properties = events.single { it.name == "sdk_loaded" }.properties
        assertEquals("android_sdk", properties["integration"])
        assertEquals("1.0.0", properties["sdk_version"])
        assertEquals("android", properties["platform"])
        assertEquals("Android", properties["\$os"])
        assertEquals("14", properties["\$os_version"])
        assertEquals("Pixel 8", properties["\$model"])
        assertEquals("session-uuid", properties["journey_id"])
    }

    @Test
    fun encoder_creates_mixpanel_track_array_and_injects_token() {
        val payload = MixpanelEventEncoder.encode(
            AnalyticsEvent("SDK Initialized", mapOf(
                "distinct_id" to "order_1",
                "checkout_url" to null,
            )),
            "project-token",
        )

        val event = JSONArray(payload).getJSONObject(0)
        assertEquals("SDK Initialized", event.getString("event"))
        val properties = event.getJSONObject("properties")
        assertEquals("project-token", properties.getString("token"))
        assertTrue(properties.has("checkout_url"))
        assertTrue(properties.isNull("checkout_url"))
        assertFalse(payload.contains("Authorization"))
        assertEquals("https://api.mixpanel.com/track?ip=1", MIXPANEL_TRACK_ENDPOINT)
    }

    @Test
    fun transport_response_validation_rejects_http_and_ingestion_failures() {
        validateMixpanelResponse(200, "1")

        assertFailsWith<IllegalStateException> { validateMixpanelResponse(500, "") }
        assertFailsWith<IllegalStateException> { validateMixpanelResponse(200, "0") }
    }

    @Test
    fun transport_failure_is_reported_without_escaping_the_async_boundary() {
        val reporter = RecordingErrorReporter()
        val tracker = MixpanelAnalyticsTracker(
            config = GlomoPayConfig(publicKey = "test_public_key", orderId = "order_123"),
            sessionId = "session-uuid",
            sdkVersion = "1.0.0",
            initialFlowType = "standard",
            transport = AnalyticsTransport { error("HTTP 500") },
            executor = java.util.concurrent.Executor(Runnable::run),
            deviceProperties = { emptyMap() },
            errorReporter = reporter,
        )

        tracker.track("Checkout Started")

        assertEquals("mixpanel_delivery", reporter.operation)
        assertEquals("Checkout Started", reporter.context["event_name"])
        assertEquals(listOf("Checkout Started"), reporter.breadcrumbs)
    }

    private fun tracker(
        config: GlomoPayConfig,
        sessionId: String = "session-uuid",
        deviceProperties: Map<String, Any?> = emptyMap(),
        transport: AnalyticsTransport,
    ) = MixpanelAnalyticsTracker(
        config = config,
        sessionId = sessionId,
        sdkVersion = "1.0.0",
        initialFlowType = "standard",
        transport = transport,
        executor = java.util.concurrent.Executor(Runnable::run),
        deviceProperties = { deviceProperties },
    )

    private class RecordingErrorReporter : SdkErrorReporter {
        val breadcrumbs = mutableListOf<String>()
        var operation: String? = null
        var context: Map<String, Any?> = emptyMap()

        override fun addBreadcrumb(category: String, message: String, data: Map<String, Any?>) {
            breadcrumbs += message
        }

        override fun capture(operation: String, error: Throwable, context: Map<String, Any?>) {
            this.operation = operation
            this.context = context
        }

        override fun updateFlowType(flowType: String) = Unit
    }
}
