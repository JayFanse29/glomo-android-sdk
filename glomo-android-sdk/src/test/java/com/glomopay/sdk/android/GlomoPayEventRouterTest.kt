package com.glomopay.sdk.android

import com.glomopay.sdk.android.bridge.GlomoPayEventRouter
import com.glomopay.sdk.android.bridge.GlomoPayInjectionScripts
import com.glomopay.sdk.android.analytics.AnalyticsEvent
import com.glomopay.sdk.android.analytics.AnalyticsEvents
import com.glomopay.sdk.android.analytics.AnalyticsTracker
import com.glomopay.sdk.android.analytics.CheckoutAnalytics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlomoPayEventRouterTest {
    @Test
    fun success_event_delivers_nested_payload_once() {
        val listener = RecordingListener()
        var result: GlomoPayResult? = null
        val router = GlomoPayEventRouter(listener, devMode = false, onComplete = { result = it })

        val success = mapOf<String, Any?>(
            "type" to "message",
            "data" to mapOf(
                "type" to "payment.success",
                "payload" to mapOf("orderId" to "order_1", "paymentId" to "pay_1", "signature" to "sig_1"),
            ),
        )
        router.handleEnvelope(success)
        router.handleEnvelope(success)

        assertTrue(listener.events.all { it.first == "payment.success" })
        assertEquals("order_1", listener.success.single().orderId)
        assertTrue(result is GlomoPayResult.Success)
    }

    @Test
    fun failure_is_delivered_on_the_event_name_without_a_signature() {
        // A failure payload has never carried a signature; requiring one meant
        // onPaymentFailure could not fire for a confirmed decline in any release build.
        for (eventName in listOf("payment.failure", "payment.failed", "failed", "payment.error")) {
            val listener = RecordingListener()
            val router = GlomoPayEventRouter(listener, devMode = false, onComplete = {})

            router.handleEnvelope(mapOf("type" to "message", "data" to mapOf(
                "type" to eventName,
                "payload" to mapOf("orderId" to "order_1", "reason" to "issuer_declined"),
            )))

            assertEquals("order_1", listener.failure.single().orderId)
            assertEquals(null, listener.failure.single().signature)
            assertEquals("issuer_declined", listener.failure.single().rawResponse?.get("reason"))
        }
    }

    @Test
    fun thin_failure_payload_is_still_delivered_and_captured() {
        val listener = RecordingListener()
        val reporter = RecordingReporter()
        val router = GlomoPayEventRouter(
            listener, devMode = false, onComplete = {}, errorReporter = reporter,
        )

        router.handleEnvelope(mapOf("type" to "message", "data" to mapOf("type" to "payment.failure")))

        assertEquals(1, listener.failure.size)
        assertEquals("thin_payment_failure_payload", reporter.operations.single())
    }

    @Test
    fun cancellation_and_dependency_errors_are_forwarded() {
        val listener = RecordingListener()
        val router = GlomoPayEventRouter(listener, devMode = false, onComplete = {})

        router.handleEnvelope(mapOf("type" to "dependencies.failed_to_load", "message" to "LRS unavailable"))
        router.handleEnvelope(mapOf("type" to "message", "data" to mapOf("type" to "payment.cancelled")))

        assertEquals(TerminationSource.USER_DISMISS, listener.termination.single())
        assertTrue(listener.events.any { it.first == "glomo_android_sdk.checkout.dependencies_failed" })
    }

    @Test
    fun injection_targets_the_native_bridge_and_event_envelope() {
        val script = GlomoPayInjectionScripts.main()

        assertTrue(script.contains("window.GlomoPayBridge.postMessage"))
        assertTrue(script.contains("type:'message'"))
        assertTrue(script.contains("window.fetch"))
        assertTrue(script.contains("XMLHttpRequest.prototype.open"))
        assertTrue(script.contains("type:'webview.error'"))
        assertTrue(script.contains("errorType:'js_error'"))
        assertTrue(script.contains("errorType:'unhandled_rejection'"))
    }

    @Test
    fun page_script_errors_send_no_analytics() {
        val analytics = RecordingAnalytics()
        val router = GlomoPayEventRouter(
            listener = RecordingListener(),
            devMode = false,
            onComplete = {},
            analytics = CheckoutAnalytics(analytics),
        )

        router.handleEnvelope(
            mapOf(
                "type" to "webview.error",
                "errorType" to "js_error",
                "message" to "Render failed",
            ),
            webViewType = "flow",
        )

        assertTrue(analytics.events.isEmpty())
    }

    @Test
    fun education_step_events_are_tracked_from_bridge_messages() {
        val analytics = RecordingAnalytics()
        val router = GlomoPayEventRouter(
            listener = RecordingListener(),
            devMode = false,
            onComplete = {},
            analytics = CheckoutAnalytics(analytics),
        )

        // The live page signal, then shapes that must not count.
        router.handleEnvelope(mapOf(
            "type" to "message",
            "data" to mapOf(
                "type" to "lrs.has_education_steps",
                "value" to true,
                "source" to "checkout",
            ),
        ))
        router.handleEnvelope(mapOf(
            "type" to "message",
            "data" to mapOf("type" to "lrs.has_education_steps", "value" to false),
        ))
        router.handleEnvelope(mapOf(
            "type" to "message",
            "data" to mapOf("event" to "lrs.has_education_steps", "hasContent" to true),
        ))
        router.handleEnvelope(mapOf(
            "type" to "message",
            "data" to mapOf(
                "type" to "lrs.education_steps_failed",
                "reason" to "render_failed",
            ),
        ))

        assertEquals(
            listOf(AnalyticsEvents.SDK_PAGE_VIEWED, AnalyticsEvents.SDK_ACTION_FAILED),
            analytics.events.map { it.name },
        )
        assertEquals("education_carousel", analytics.events[0].properties["page_name"])
        assertEquals("education_carousel", analytics.events[1].properties["action_name"])
        assertEquals("render_failed", analytics.events[1].properties["error_code"])
    }

    @Test
    fun flow_injection_uses_a_separate_bridge_and_is_idempotent() {
        val script = GlomoPayInjectionScripts.flow()

        assertTrue(script.contains("window.GlomoPayFlowBridge.postMessage"))
        assertTrue(script.contains("__glomo_GlomoPayFlowBridge_Injected__"))
        assertFalse(script.contains("window.GlomoPayBridge.postMessage"))
        assertTrue(script.contains("if (window[flag]) return;"))
    }

    @Test
    fun window_events_call_ui_callbacks() {
        val listener = RecordingListener()
        val uiEvents = mutableListOf<String>()
        val router = GlomoPayEventRouter(
            listener = listener,
            devMode = false,
            onComplete = {},
            onWindowOpen = { uiEvents += "open:$it" },
            onWindowClose = { uiEvents += "close" },
        )

        router.handleEnvelope(mapOf("type" to "window.open", "url" to "https://bank.example/3ds"))
        router.handleEnvelope(mapOf("type" to "window.close"))

        assertEquals(listOf("open:https://bank.example/3ds", "close"), uiEvents)
    }

    @Test
    fun pending_event_is_observed_without_finishing_checkout() {
        val analytics = RecordingAnalytics()
        var completed = 0
        val router = GlomoPayEventRouter(
            listener = RecordingListener(),
            devMode = false,
            onComplete = { completed++ },
            analytics = CheckoutAnalytics(analytics),
        )

        router.handleEnvelope(mapOf("type" to "message", "data" to mapOf("type" to "payment.pending")))

        assertTrue(analytics.events.isEmpty())
        assertEquals(0, completed)
    }

    @Test
    fun bank_transfer_is_a_user_journey_and_never_a_payment_success() {
        val listener = RecordingListener()
        var result: GlomoPayResult? = null
        val router = GlomoPayEventRouter(listener, devMode = false, onComplete = { result = it })

        router.handleEnvelope(mapOf("type" to "message", "data" to mapOf(
            "type" to "payment.bank_transfer_submitted",
            "payload" to mapOf(
                "orderId" to "order_1",
                "sender_account_number" to "000123456789",
                "transactionReference" to "utr_1",
                "status" to "submitted",
            ),
        )))

        // No money has moved: reporting this as a payment success handed the host a
        // payload with no paymentId and no signature to verify against.
        assertTrue(listener.success.isEmpty())
        val journey = listener.journeys.single()
        assertEquals(GlomoPayUserJourneyType.BANK_TRANSFER, journey.journeyType)
        assertEquals("order_1", journey.orderId)
        // Both casings are read, because the page has sent both.
        assertEquals("000123456789", journey.senderAccountNumber)
        assertEquals("utr_1", journey.transactionReference)
        assertEquals("submitted", journey.status)
        assertTrue(result is GlomoPayResult.JourneyCompleted)
    }

    @Test
    fun bank_transfer_fields_survive_non_string_values_and_camel_case() {
        val listener = RecordingListener()
        val router = GlomoPayEventRouter(listener, devMode = false, onComplete = {})

        router.handleEnvelope(mapOf("type" to "message", "data" to mapOf(
            "type" to "payment.bank_transfer_submitted",
            "payload" to mapOf(
                "order_id" to "order_2",
                "senderAccountNumber" to 123456789L,
                "transaction_reference" to 42,
            ),
        )))

        val journey = listener.journeys.single()
        assertEquals("order_2", journey.orderId)
        assertEquals("123456789", journey.senderAccountNumber)
        assertEquals("42", journey.transactionReference)
        assertEquals(null, journey.status)
    }

    @Test
    fun thin_bank_transfer_payload_is_rejected_but_leaves_a_trace() {
        val listener = RecordingListener()
        val reporter = RecordingReporter()
        var completions = 0
        val router = GlomoPayEventRouter(
            listener, devMode = false, onComplete = { completions++ }, errorReporter = reporter,
        )

        router.handleEnvelope(mapOf("type" to "message", "data" to mapOf(
            "type" to "payment.bank_transfer_submitted",
            "payload" to mapOf("status" to "submitted"),
        )))

        assertTrue(listener.journeys.isEmpty())
        assertTrue(listener.success.isEmpty())
        assertEquals(0, completions)
        assertEquals("thin_bank_transfer_payload", reporter.operations.single())
    }

    @Test
    fun malformed_envelope_reports_an_sdk_error_without_crashing() {
        val listener = RecordingListener()
        val router = GlomoPayEventRouter(listener, devMode = false, onComplete = {})

        router.handle("{not-json")

        assertEquals(1, listener.sdkErrors.size)
    }

    @Test
    fun router_maps_redirect_and_dependency_events_to_the_catalogue() {
        val analytics = RecordingAnalytics()
        val router = GlomoPayEventRouter(
            listener = RecordingListener(),
            devMode = false,
            onComplete = {},
            analytics = CheckoutAnalytics(analytics),
        )

        router.handleEnvelope(mapOf(
            "type" to "window.open",
            "url" to "https://bank.example/verify?phone=9876543210",
        ))
        router.handleEnvelope(mapOf("type" to "window.close"))
        router.handleEnvelope(mapOf("type" to "window.open", "url" to "intent://pay"))
        router.handleEnvelope(mapOf("type" to "dependencies.failed_to_load", "message" to "Unavailable"))
        router.handleEnvelope(mapOf("type" to "dependencies.failed_to_load", "message" to "Unavailable"))

        assertEquals(
            listOf(
                AnalyticsEvents.SDK_PAGE_VIEWED,
                AnalyticsEvents.SDK_ACTION_SUBMITTED,
                AnalyticsEvents.SDK_ACTION_FAILED,
                AnalyticsEvents.SDK_CHECKOUT_FAILED,
            ),
            analytics.events.map { it.name },
        )
        val (viewed, submitted, blocked, failed) = analytics.events.map { it.properties }
        assertEquals(mapOf("page_name" to "bank_redirect", "url_origin" to "https://bank.example"), viewed)
        assertEquals(mapOf("action_name" to "bank_redirect", "url_origin" to "https://bank.example"), submitted)
        assertEquals("navigation", blocked["action_name"])
        assertEquals("invalid_window_open_url", blocked["error_code"])
        assertEquals(mapOf("error_code" to "dependencies_failed_to_load"), failed)
    }

    @Test
    fun an_outcome_closes_the_checkout_once_with_its_payment_event_and_no_payment_event_of_its_own() {
        val outcomes = mapOf(
            "payment_success" to mapOf(
                "type" to "payment.success",
                "payload" to mapOf("orderId" to "order_1", "paymentId" to "pay_1", "signature" to "sig_1"),
            ),
            "payment_failure" to mapOf("type" to "payment.failed", "payload" to mapOf("orderId" to "order_1")),
            "bank_transfer_submitted" to mapOf(
                "type" to "payment.bank_transfer_submitted",
                "payload" to mapOf("orderId" to "order_1"),
            ),
        )
        outcomes.forEach { (paymentEvent, data) ->
            val analytics = RecordingAnalytics()
            val router = GlomoPayEventRouter(
                listener = RecordingListener(),
                devMode = false,
                onComplete = {},
                analytics = CheckoutAnalytics(analytics),
            )

            router.handleEnvelope(mapOf("type" to "message", "data" to data))
            router.handleEnvelope(mapOf("type" to "message", "data" to data))

            val closed = analytics.events.single()
            assertEquals(AnalyticsEvents.SDK_CHECKOUT_CLOSED, closed.name)
            assertEquals(
                mapOf("trigger" to "sdk", "page_name" to "loading", "payment_event" to paymentEvent, "had_listener" to true),
                closed.properties,
            )
        }
    }

    @Test
    fun an_outcome_without_a_listener_is_recorded_as_such() {
        val analytics = RecordingAnalytics()
        val router = GlomoPayEventRouter(
            listener = null,
            devMode = false,
            onComplete = {},
            analytics = CheckoutAnalytics(analytics),
        )

        router.handleEnvelope(mapOf("type" to "message", "data" to mapOf(
            "type" to "payment.failed",
            "payload" to mapOf("orderId" to "order_1"),
        )))

        assertEquals(false, analytics.events.single().properties["had_listener"])
    }

    @Test
    fun a_page_cancel_closes_as_a_user_dismiss() {
        val analytics = RecordingAnalytics()
        val router = GlomoPayEventRouter(
            listener = RecordingListener(),
            devMode = false,
            onComplete = {},
            analytics = CheckoutAnalytics(analytics),
        )

        router.handleEnvelope(mapOf("type" to "message", "data" to mapOf("type" to "checkout.closed")))

        assertEquals(AnalyticsEvents.SDK_CHECKOUT_CLOSED, analytics.events.single().name)
        assertEquals("user_dismiss", analytics.events.single().properties["trigger"])
    }

    @Test
    fun malformed_bridge_message_sends_no_analytics() {
        val analytics = RecordingAnalytics()
        val listener = RecordingListener()
        val router = GlomoPayEventRouter(
            listener = listener,
            devMode = false,
            onComplete = {},
            analytics = CheckoutAnalytics(analytics),
        )

        router.handle("{not-json user@example.com")
        router.handleEnvelope(mapOf("type" to "unknown.type", "data" to "user@example.com"))
        router.handleEnvelope(mapOf("type" to "console", "message" to "user@example.com"))

        assertTrue(analytics.events.isEmpty())
        assertEquals(1, listener.sdkErrors.size)
    }

    private class RecordingListener : GlomoPayListener {
        val success = mutableListOf<GlomoPayPayload>()
        val failure = mutableListOf<GlomoPayPayload>()
        val termination = mutableListOf<TerminationSource>()
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        val sdkErrors = mutableListOf<List<SdkError>>()
        val journeys = mutableListOf<GlomoPayUserJourneyPayload>()

        override fun onPaymentSuccess(payload: GlomoPayPayload) { success += payload }
        override fun onPaymentFailure(payload: GlomoPayPayload) { failure += payload }
        override fun onSdkError(errors: List<SdkError>) { sdkErrors += errors }
        override fun onUserJourneyCompleted(payload: GlomoPayUserJourneyPayload) { journeys += payload }
        override fun onConnectionError(error: ConnectionError) = Unit
        override fun onPaymentTerminate(source: TerminationSource) { termination += source }
        override fun onEvent(name: String, payload: Map<String, Any?>) { events += name to payload }
    }

    private class RecordingReporter : com.glomopay.sdk.android.monitoring.SdkErrorReporter {
        val operations = mutableListOf<String>()

        override fun addBreadcrumb(category: String, message: String, data: Map<String, Any?>) = Unit
        override fun capture(operation: String, error: Throwable, context: Map<String, Any?>) {
            operations += operation
        }
        override fun updateFlowType(flowType: String) = Unit
    }

    private class RecordingAnalytics : AnalyticsTracker {
        val events = mutableListOf<AnalyticsEvent>()

        override fun track(event: String, properties: Map<String, Any?>) {
            events += AnalyticsEvent(event, properties)
        }

        override fun updateFlowType(flowType: String) = Unit
    }
}
