package com.glomopay.sdk.android

import com.glomopay.sdk.android.analytics.AnalyticsEvent
import com.glomopay.sdk.android.analytics.AnalyticsEvents
import com.glomopay.sdk.android.analytics.AnalyticsTracker
import com.glomopay.sdk.android.analytics.CheckoutAnalytics
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals

class OrderFetchAnalyticsTest {
    @Test
    fun a_fetched_order_is_one_succeeded_api_event_on_the_route_template() {
        val tracker = RecordingTracker()

        CheckoutAnalytics(tracker).orderFetchSucceeded(200)

        val event = tracker.events.single()
        assertEquals(AnalyticsEvents.SDK_API_BACKEND, event.name)
        assertEquals(
            mapOf(
                "api_path" to "/api/public/v1/order/:order_id",
                "http_method" to "GET",
                "status" to "succeeded",
                "http_status" to 200,
            ),
            event.properties,
        )
    }

    @Test
    fun a_failed_fetch_is_a_failed_api_event_and_blocks_the_open() {
        val cases = listOf(
            Triple(GlomoPayRequestTimeout(SocketTimeoutException()), "timeout", "timeout"),
            Triple(GlomoPayTransportError(IOException()), "network", "network"),
            Triple(GlomoPayHttpStatusError(404), "http_error", "http_4xx"),
            Triple(GlomoPayHttpStatusError(503), "http_error", "http_5xx"),
            Triple(GlomoPayMalformedResponse(), "invalid_response", "invalid_response"),
        )

        cases.forEach { (error, errorCode, errorDetail) ->
            val tracker = RecordingTracker()

            CheckoutAnalytics(tracker).orderFetchFailed(error)

            val (api, failed) = tracker.events
            assertEquals(listOf(AnalyticsEvents.SDK_API_BACKEND, AnalyticsEvents.SDK_CHECKOUT_FAILED), tracker.events.map { it.name })
            assertEquals("/api/public/v1/order/:order_id", api.properties["api_path"])
            assertEquals("failed", api.properties["status"])
            assertEquals(errorCode, api.properties["error_code"])
            assertEquals((error as? GlomoPayHttpStatusError)?.statusCode, api.properties["http_status"])
            assertEquals(mapOf("error_code" to "order_fetch_failed", "error_detail" to errorDetail), failed.properties)
        }
    }

    @Test
    fun an_unexpected_failure_still_blocks_the_open_with_a_fixed_detail() {
        val tracker = RecordingTracker()

        CheckoutAnalytics(tracker).orderFetchFailed(IllegalStateException("order_123 user@example.com"))

        assertEquals(mapOf("error_code" to "order_fetch_failed", "error_detail" to "other"), tracker.events.last().properties)
        assertEquals(setOf("api_path", "http_method", "status"), tracker.events.first().properties.keys)
    }

    private class RecordingTracker : AnalyticsTracker {
        val events = mutableListOf<AnalyticsEvent>()

        override fun track(event: String, properties: Map<String, Any?>) {
            events += AnalyticsEvent(event, properties)
        }

        override fun updateFlowType(flowType: String) = Unit
    }
}
