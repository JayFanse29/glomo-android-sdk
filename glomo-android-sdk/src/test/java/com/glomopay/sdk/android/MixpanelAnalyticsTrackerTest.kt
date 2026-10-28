package com.glomopay.sdk.android

import com.glomopay.sdk.android.analytics.AnalyticsEvent
import com.glomopay.sdk.android.analytics.AnalyticsEvents
import com.glomopay.sdk.android.analytics.AnalyticsTransport
import com.glomopay.sdk.android.analytics.MixpanelAnalyticsTracker
import com.glomopay.sdk.android.analytics.MixpanelEventEncoder
import com.glomopay.sdk.android.analytics.MIXPANEL_TRACK_ENDPOINT
import com.glomopay.sdk.android.analytics.validateMixpanelResponse
import com.glomopay.sdk.android.monitoring.SdkErrorReporter
import org.json.JSONArray
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class MixpanelAnalyticsTrackerTest {
    @Test
    fun standard_properties_are_on_every_event_and_dropped_keys_are_gone() {
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
                "\$os" to "Android",
                "\$os_version" to "14",
                "\$model" to "Pixel 8",
                "\$device" to "shiba",
                "\$wifi_enabled" to true,
                "\$cellular_enabled" to false,
                "\$app_namespace" to "com.example.merchant",
                "merchant_target_sdk_version" to 36,
            ) },
        )

        tracker.track(AnalyticsEvents.SDK_PAGE_VIEWED, mapOf("page_name" to "checkout"))
        tracker.updateFlowType("lrs")
        tracker.track(AnalyticsEvents.SDK_PAGE_VIEWED, mapOf("page_name" to "checkout"))

        val (beforeResolve, properties) = events.map { it.properties }
        assertFalse(beforeResolve.containsKey("journey"))
        assertEquals("lrs", properties["journey"])
        assertEquals("android_sdk", properties["integration"])
        assertEquals("android", properties["platform"])
        assertEquals("1.0.0", properties["sdk_version"])
        assertEquals("test_public_key", properties["public_key"])
        assertEquals(BuildConfig.GLOMO_INTERNAL_BUILD, properties["dev_mode"])
        assertEquals("test", properties["key_mode"])
        assertEquals("order_123", properties["resource_id"])
        assertEquals("\$device:session-uuid", properties["distinct_id"])
        assertEquals("session-uuid", properties["\$device_id"])
        assertEquals("session-uuid", properties["journey_id"])
        assertTrue(properties["\$insert_id"] is String)
        assertEquals(1_723_620_000_000L, properties["time"])
        assertEquals("Pixel 8", properties["\$model"])
        assertEquals(true, properties["\$wifi_enabled"])
        assertEquals(36, properties["merchant_target_sdk_version"])
        events.forEach { event ->
            DROPPED_KEYS.forEach { assertFalse(it in event.properties, it) }
            event.properties.keys.forEach { assertTrue(KEY_PATTERN.matches(it), it) }
        }
    }

    @Test
    fun subscription_journey_uses_the_subscription_as_the_resource() {
        val events = mutableListOf<AnalyticsEvent>()
        val tracker = MixpanelAnalyticsTracker(
            config = GlomoPayConfig(publicKey = "live_public_key", subscriptionId = "sub_123"),
            sessionId = "session-uuid",
            sdkVersion = "1.0.0",
            initialFlowType = "standard",
            transport = AnalyticsTransport { events += it },
            executor = java.util.concurrent.Executor(Runnable::run),
            deviceProperties = { emptyMap() },
        )

        tracker.track(AnalyticsEvents.SDK_LOADED)

        val properties = events.single().properties
        assertEquals("sub_123", properties["resource_id"])
        assertEquals("subscription", properties["journey"])
        assertEquals("live", properties["key_mode"])
        assertEquals("\$device:session-uuid", properties["distinct_id"])
    }

    @Test
    fun standard_properties_win_over_call_site_properties() {
        val events = mutableListOf<AnalyticsEvent>()
        val sessionId = UUID.randomUUID().toString()
        val tracker = tracker(GlomoPayConfig(publicKey = "test_public_key", orderId = "order_123"), sessionId) {
            events += it
        }

        tracker.track(
            AnalyticsEvents.SDK_PAGE_VIEWED,
            mapOf(
                "journey_id" to "overridden",
                "distinct_id" to "overridden",
                "\$device_id" to "overridden",
                "page_name" to "checkout",
            ),
        )

        val properties = events.single().properties
        assertEquals(sessionId, properties["journey_id"])
        assertEquals("\$device:$sessionId", properties["distinct_id"])
        assertEquals(sessionId, properties["\$device_id"])
        assertEquals("checkout", properties["page_name"])
    }

    @Test
    fun insert_id_is_unique_per_event() {
        val events = mutableListOf<AnalyticsEvent>()
        val tracker = tracker(GlomoPayConfig(publicKey = "test_public_key", orderId = "order_123")) { events += it }

        tracker.track(AnalyticsEvents.SDK_CTA_CLICKED)
        tracker.track(AnalyticsEvents.SDK_CTA_CLICKED)

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

        assertEquals(listOf(AnalyticsEvents.SDK_LOADED), events.map { it.name })
        val properties = events.single().properties
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
            AnalyticsEvent(AnalyticsEvents.SDK_LOADED, mapOf(
                "distinct_id" to "\$device:journey-1",
                "resource_id" to null,
            )),
            "project-token",
        )

        val event = JSONArray(payload).getJSONObject(0)
        assertEquals(AnalyticsEvents.SDK_LOADED, event.getString("event"))
        val properties = event.getJSONObject("properties")
        assertEquals("project-token", properties.getString("token"))
        assertTrue(properties.has("resource_id"))
        assertTrue(properties.isNull("resource_id"))
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

        tracker.track(AnalyticsEvents.SDK_LOADED)

        assertEquals("mixpanel_delivery", reporter.operation)
        assertEquals(AnalyticsEvents.SDK_LOADED, reporter.context["event_name"])
        assertEquals(listOf(AnalyticsEvents.SDK_LOADED), reporter.breadcrumbs)
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

    private companion object {
        val KEY_PATTERN = Regex("^\\$?[a-z0-9_]+$")
        val DROPPED_KEYS = listOf(
            "session_id", "order_id", "subscription_id", "flow_type", "mock_mode", "sdk_source", "surface",
            "checkout_url", "timestamp", "device_os_version",
        )
    }
}
