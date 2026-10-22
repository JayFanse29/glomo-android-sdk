package com.glomopay.sdk.android

import com.glomopay.sdk.android.analytics.AnalyticsEvent
import com.glomopay.sdk.android.analytics.AnalyticsTransport
import com.glomopay.sdk.android.analytics.MixpanelAnalyticsTracker
import com.glomopay.sdk.android.analytics.SdkCloseTrigger
import com.glomopay.sdk.android.analytics.SdkFailureCode
import com.glomopay.sdk.android.analytics.SdkLifecycleEvents
import com.glomopay.sdk.android.analytics.toCloseTrigger
import com.glomopay.sdk.android.analytics.toFailureCode
import com.glomopay.sdk.android.bridge.GlomoPayEventRouter
import java.net.URI
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SdkLifecycleEventsTest {
    private val events = mutableListOf<AnalyticsEvent>()
    private val sessionId = UUID.randomUUID().toString()
    private val config = GlomoPayConfig(publicKey = "test_public_key", orderId = "order_123")
    private val tracker = MixpanelAnalyticsTracker(
        config = config,
        sessionId = sessionId,
        sdkVersion = "1.0.0",
        initialFlowType = "standard",
        transport = AnalyticsTransport { events += it },
        executor = java.util.concurrent.Executor(Runnable::run),
        deviceProperties = { mapOf("\$os" to "Android", "\$os_version" to "14", "\$model" to "Pixel 8") },
    )

    @Test
    fun termination_sources_map_onto_close_triggers() {
        assertEquals(SdkCloseTrigger.USER_DISMISS, TerminationSource.USER_DISMISS.toCloseTrigger())
        assertEquals(SdkCloseTrigger.USER_DISMISS, TerminationSource.BACK_BUTTON.toCloseTrigger())
        assertEquals(SdkCloseTrigger.MERCHANT, TerminationSource.PROGRAMMATIC.toCloseTrigger())
        assertEquals(SdkCloseTrigger.ERROR, TerminationSource.CONNECTION_ERROR.toCloseTrigger())
    }

    @Test
    fun sdk_closed_fires_once_per_open_with_the_first_trigger() {
        val lifecycle = SdkLifecycleEvents(tracker)

        lifecycle.closed(TerminationSource.BACK_BUTTON.toCloseTrigger())
        lifecycle.closed(SdkCloseTrigger.COMPLETED)
        lifecycle.closed(SdkCloseTrigger.USER_DISMISS)

        val closed = events.single { it.name == "sdk_closed" }.properties
        assertEquals("user_dismiss", closed["trigger"])
        assertIdentity(closed)
    }

    @Test
    fun a_new_open_can_fire_sdk_closed_again() {
        SdkLifecycleEvents(tracker).closed(TerminationSource.PROGRAMMATIC.toCloseTrigger())
        SdkLifecycleEvents(tracker).closed(TerminationSource.USER_DISMISS.toCloseTrigger())

        assertEquals(
            listOf("merchant", "user_dismiss"),
            events.filter { it.name == "sdk_closed" }.map { it.properties["trigger"] },
        )
    }

    @Test
    fun page_close_wins_over_the_cancelled_completion_it_causes() {
        val lifecycle = SdkLifecycleEvents(tracker)
        val router = GlomoPayEventRouter(
            null,
            devMode = false,
            onComplete = { lifecycle.closed(SdkCloseTrigger.COMPLETED) },
            onPageClosed = { lifecycle.closed(SdkCloseTrigger.PAGE) },
        )

        router.handleEnvelope(mapOf("type" to "message", "data" to mapOf("type" to "checkout.closed")))

        assertEquals("page", events.single { it.name == "sdk_closed" }.properties["trigger"])
    }

    @Test
    fun terminal_payment_result_closes_as_completed() {
        val lifecycle = SdkLifecycleEvents(tracker)
        val router = GlomoPayEventRouter(
            null,
            devMode = false,
            onComplete = { lifecycle.closed(SdkCloseTrigger.COMPLETED) },
            onPageClosed = { lifecycle.closed(SdkCloseTrigger.PAGE) },
        )

        router.handleEnvelope(mapOf("type" to "message", "data" to mapOf(
            "type" to "payment.success",
            "payload" to mapOf("orderId" to "order_1", "paymentId" to "pay_1", "signature" to "sig_1"),
        )))
        router.handleEnvelope(mapOf("type" to "message", "data" to mapOf("type" to "checkout.closed")))

        assertEquals("completed", events.single { it.name == "sdk_closed" }.properties["trigger"])
    }

    @Test
    fun connection_errors_map_onto_failure_codes() {
        assertEquals(
            SdkFailureCode.CONNECTION_ERROR,
            ConnectionError(ConnectionErrorType.TIMEOUT, "Taking longer than expected").toFailureCode(),
        )
        assertEquals(
            SdkFailureCode.CONNECTION_ERROR,
            ConnectionError.fromWebResourceError("net::ERR_INTERNET_DISCONNECTED", -2).toFailureCode(),
        )
        assertEquals(
            SdkFailureCode.WEBVIEW_ERROR,
            ConnectionError.fromWebResourceError("net::ERR_FAILED", -1).toFailureCode(),
        )
        assertEquals(
            SdkFailureCode.HTTP_ERROR,
            ConnectionError(ConnectionErrorType.HTTP_SERVER_ERROR, "Server error", statusCode = 502).toFailureCode(),
        )
    }

    @Test
    fun sdk_failed_carries_error_code_and_the_url_journey_id() {
        GlomoPaySdk.trackSdkOpened(tracker)
        SdkLifecycleEvents(tracker).failed(SdkFailureCode.OPEN_TIMEOUT)
        SdkLifecycleEvents(tracker).failed(
            ConnectionError.fromWebResourceError("net::ERR_FAILED", -1).toFailureCode(),
            "net::ERR_FAILED",
        )
        SdkLifecycleEvents(tracker).failed(SdkFailureCode.VALIDATION_FAILED, "Invalid Public Key format")

        val failures = events.filter { it.name == "sdk_failed" }.map { it.properties }
        assertEquals(listOf("open_timeout", "webview_error", "validation_failed"), failures.map { it["error_code"] })
        assertFalse(failures[0].containsKey("error_message"))
        assertEquals("Invalid Public Key format", failures[2]["error_message"])

        val urlJourneyId = URI(ConfigManager.getCheckoutUrl(config, "lrs", sessionId)).rawQuery
            .split('&').single { it.startsWith("journeyId=") }.substringAfter('=')
        val loadedJourneyId = events.single { it.name == "sdk_loaded" }.properties["journey_id"]
        failures.forEach {
            assertEquals(urlJourneyId, it["journey_id"])
            assertEquals(loadedJourneyId, it["journey_id"])
            assertIdentity(it)
        }
    }

    @Test
    fun sdk_failed_fires_once_per_failure_until_the_open_recovers() {
        val lifecycle = SdkLifecycleEvents(tracker)

        lifecycle.failed(SdkFailureCode.OPEN_TIMEOUT)
        lifecycle.failed(SdkFailureCode.CONNECTION_ERROR)
        lifecycle.recovered()
        lifecycle.failed(SdkFailureCode.CONNECTION_ERROR)

        assertEquals(
            listOf("open_timeout", "connection_error"),
            events.filter { it.name == "sdk_failed" }.map { it.properties["error_code"] },
        )
    }

    @Test
    fun sdk_failed_stops_once_the_open_has_loaded() {
        val lifecycle = SdkLifecycleEvents(tracker)

        lifecycle.failed(SdkFailureCode.OPEN_TIMEOUT)
        lifecycle.recovered()
        lifecycle.failed(SdkFailureCode.CONNECTION_ERROR)
        lifecycle.loaded()
        lifecycle.recovered()
        lifecycle.failed(ConnectionError.fromWebResourceError("net::ERR_INTERNET_DISCONNECTED", -2).toFailureCode())

        assertEquals(
            listOf("open_timeout", "connection_error"),
            events.filter { it.name == "sdk_failed" }.map { it.properties["error_code"] },
        )
    }

    @Test
    fun only_the_main_webview_reports_dependency_failures_as_an_open_failure() {
        val lifecycle = SdkLifecycleEvents(tracker)
        val router = GlomoPayEventRouter(
            null,
            devMode = false,
            onComplete = {},
            analytics = tracker,
            onDependenciesFailed = { lifecycle.failed(SdkFailureCode.DEPENDENCIES_FAILED) },
        )

        router.handleEnvelope(mapOf("type" to "dependencies.failed_to_load"), "flow")
        router.handleEnvelope(mapOf(
            "type" to "message",
            "data" to mapOf("type" to "dependencies.failed_to_load"),
        ), "flow")

        assertEquals(2, events.count { it.name == "Checkout Dependencies Failed" })
        assertEquals(0, events.count { it.name == "sdk_failed" })

        router.handleEnvelope(mapOf("type" to "dependencies.failed_to_load"), "main")

        assertEquals("dependencies_failed", events.single { it.name == "sdk_failed" }.properties["error_code"])
    }

    @Test
    fun sdk_failed_message_is_sanitized() {
        SdkLifecycleEvents(tracker).failed(SdkFailureCode.START_FAILED, "Failed for user@example.com 9876543210")

        assertEquals(
            "Failed for [REDACTED] [REDACTED]",
            events.single { it.name == "sdk_failed" }.properties["error_message"],
        )
    }

    private fun assertIdentity(properties: Map<String, Any?>) {
        assertEquals("android_sdk", properties["integration"])
        assertEquals(sessionId, properties["journey_id"])
        assertEquals(sessionId, properties["\$device_id"])
        assertEquals("\$device:$sessionId", properties["distinct_id"])
        assertEquals("1.0.0", properties["sdk_version"])
        assertEquals("android", properties["platform"])
        assertEquals("Android", properties["\$os"])
        assertEquals("14", properties["\$os_version"])
        assertEquals("Pixel 8", properties["\$model"])
    }
}
