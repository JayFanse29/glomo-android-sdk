package com.glomopay.sdk.android

import com.glomopay.sdk.android.analytics.AnalyticsEvent
import com.glomopay.sdk.android.analytics.AnalyticsPage
import com.glomopay.sdk.android.analytics.AnalyticsTransport
import com.glomopay.sdk.android.analytics.CheckoutAnalytics
import com.glomopay.sdk.android.analytics.CheckoutFailure
import com.glomopay.sdk.android.analytics.CloseTrigger
import com.glomopay.sdk.android.analytics.MixpanelAnalyticsTracker
import com.glomopay.sdk.android.analytics.PaymentEvent
import com.glomopay.sdk.android.bridge.GlomoPayEventRouter
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertTrue

class AnalyticsCatalogueTest {
    private val events = mutableListOf<AnalyticsEvent>()
    private val tracker = MixpanelAnalyticsTracker(
        config = GlomoPayConfig(publicKey = "test_public_key", orderId = "order_123"),
        sessionId = "journey-1",
        sdkVersion = "test",
        initialFlowType = "lrs",
        transport = AnalyticsTransport { events += it },
        executor = java.util.concurrent.Executor(Runnable::run),
        deviceProperties = { emptyMap() },
    )

    @Test
    fun every_emitted_event_is_in_the_catalogue_with_listed_values_and_no_free_text() {
        emitEverySdkSideEvent()
        emitEveryBridgeEvent()

        assertTrue(events.isNotEmpty())
        assertTrue(events.map { it.name }.toSet().containsAll(NAMES - "sdk_loaded"), events.map { it.name }.toString())
        events.forEach { event ->
            val properties = event.properties
            assertTrue(event.name in NAMES, event.name)
            properties.keys.forEach { key ->
                assertTrue(KEY_PATTERN.matches(key), "${event.name}: $key")
                assertTrue(key !in FREE_TEXT_KEYS, "${event.name}: $key")
            }
            val errorCode = properties["error_code"] as String?
            when (event.name) {
                "sdk_page_viewed" -> {
                    assertListed(event, "page_name", PAGE_NAMES)
                    if (properties["page_name"] == "error") assertListed(event, "error_code", ERROR_PAGE_CODES)
                }
                "sdk_api_backend" -> {
                    assertTrue(properties["api_path"] == "/api/public/v1/order/:order_id", event.toString())
                    assertListed(event, "status", setOf("succeeded", "failed"))
                    if (errorCode != null) assertListed(event, "error_code", API_ERROR_CODES)
                }
                "sdk_action_submitted" -> assertListed(event, "action_name", setOf("bank_redirect"))
                "sdk_action_failed" -> {
                    val action = properties["action_name"] as String
                    assertListed(event, "error_code", requireNotNull(ACTION_ERROR_CODES[action]) { action })
                }
                "sdk_cta_clicked" -> {
                    assertListed(event, "cta_name", setOf("retry", "back_to_checkout"))
                    assertListed(event, "page_name", PAGE_NAMES + "loading")
                }
                "sdk_control_changed" -> assertListed(event, "control_name", setOf("color_scheme"))
                "sdk_checkout_failed" -> {
                    val details = requireNotNull(CHECKOUT_FAILURES[errorCode]) { event.toString() }
                    if (details.isEmpty()) {
                        assertTrue("error_detail" !in properties, event.toString())
                    } else {
                        assertListed(event, "error_detail", details)
                    }
                }
                "sdk_checkout_closed" -> {
                    assertListed(event, "trigger", setOf("user_dismiss", "merchant", "sdk"))
                    assertListed(event, "page_name", PAGE_NAMES + "loading")
                    if ("payment_event" in properties) {
                        assertListed(
                            event,
                            "payment_event",
                            setOf("payment_success", "payment_failure", "payment_pending", "bank_transfer_submitted"),
                        )
                    }
                }
            }
        }
    }

    private fun emitEverySdkSideEvent() {
        val analytics = CheckoutAnalytics(tracker)
        val bank = "https://bank.example/acs"
        analytics.webViewLoadFailed(ConnectionError.fromWebResourceError("offline", -2), "main", null)
        analytics.webViewLoadFailed(ConnectionError.fromHttpStatus(503), "main", null)
        analytics.checkoutFailed(CheckoutFailure.INVALID_INIT_PARAMS, "invalid_public_key")
        analytics.checkoutFailed(CheckoutFailure.COMPLIANCE_BLOCKED, "root_detected")
        analytics.checkoutFailed(CheckoutFailure.DEPENDENCIES_FAILED_TO_LOAD)
        analytics.openTimedOut("navigation_started")
        analytics.errorViewed("open_timeout")
        analytics.retryClicked(AnalyticsPage.LOADING)
        analytics.orderFetchSucceeded(200)
        listOf(
            GlomoPayRequestTimeout(SocketTimeoutException()),
            GlomoPayTransportError(IOException()),
            GlomoPayHttpStatusError(500),
            GlomoPayMalformedResponse(),
            IllegalStateException(),
        ).forEach { CheckoutAnalytics(tracker).orderFetchFailed(it) }
        analytics.checkoutViewed()
        analytics.educationCarouselViewed()
        analytics.educationCarouselViewed()
        analytics.bankRedirectViewed(bank)
        analytics.bankRedirectClosedByPage()
        analytics.bankRedirectViewed(bank)
        analytics.errorViewed(analytics.webViewLoadFailed(ConnectionError.fromHttpStatus(404, bank), "flow", "https://bank.example"))
        analytics.retryClicked(AnalyticsPage.BANK_REDIRECT)
        analytics.backToCheckoutClicked()
        listOf(
            ConnectionError.fromWebResourceError("dns", -105),
            ConnectionError.fromWebResourceError("failed", -1),
            ConnectionError(ConnectionErrorType.UNKNOWN, "renderer crashed"),
        ).forEach { analytics.errorViewed(analytics.webViewLoadFailed(it, "main", null)) }
        analytics.navigationBlocked("intent", "flow")
        analytics.invalidWindowOpenUrl("main")
        analytics.documentUploadFailed("image/*,application/pdf")
        analytics.permissionRefused(android.Manifest.permission.CAMERA)
        analytics.permissionRefused("com.example.CUSTOM")
        listOf("webview_error", "render_failed", "user@example.com", null).forEach(analytics::educationCarouselFailed)
        analytics.colorSchemeChanged("dark", "light", "auto")
        analytics.checkoutClosed(CloseTrigger.MERCHANT)
        CheckoutAnalytics(tracker).checkoutClosed(CloseTrigger.USER_DISMISS)
        PaymentEvent.entries.forEach { CheckoutAnalytics(tracker).checkoutClosed(CloseTrigger.SDK, it, true) }
    }

    private fun emitEveryBridgeEvent() {
        val router = GlomoPayEventRouter(
            listener = null,
            devMode = false,
            onComplete = {},
            analytics = CheckoutAnalytics(tracker),
        )
        listOf(
            mapOf("type" to "bridge.ready"),
            mapOf("type" to "console", "level" to "log", "message" to "user@example.com 9876543210"),
            mapOf("type" to "window.open", "url" to "https://bank.example/verify?phone=9876543210"),
            mapOf("type" to "window.close"),
            mapOf("type" to "window.open", "url" to "javascript:alert(1)"),
            mapOf("type" to "webview.error", "errorType" to "js_error", "message" to "user@example.com"),
            mapOf("type" to "file.input", "accept" to "image/*"),
            mapOf("type" to "dependencies.failed_to_load", "message" to "boom"),
            mapOf("type" to "unknown", "data" to "user@example.com"),
            message("payment.pending", "paymentId" to "pay_1"),
            message("glomoCheckoutJourneyTerminate", "status" to "done"),
            message("lrs.has_education_steps", "value" to true),
            message("lrs.education_steps_failed", "reason" to "free text from the page"),
            message("payment.failed", "payload" to mapOf("orderId" to "order_1", "reason" to "issuer said no")),
        ).forEach { router.handleEnvelope(it) }
        router.handle("{not-json user@example.com")
    }

    private fun message(type: String, vararg data: Pair<String, Any?>): Map<String, Any?> =
        mapOf("type" to "message", "data" to mapOf("type" to type) + data)

    private fun assertListed(event: AnalyticsEvent, key: String, allowed: Set<String>) {
        assertTrue(event.properties[key] in allowed, "${event.name}.$key=${event.properties[key]}")
    }

    private companion object {
        val NAMES = setOf(
            "sdk_loaded",
            "sdk_page_viewed",
            "sdk_api_backend",
            "sdk_action_submitted",
            "sdk_action_failed",
            "sdk_cta_clicked",
            "sdk_control_changed",
            "sdk_checkout_failed",
            "sdk_checkout_closed",
        )
        val PAGE_NAMES = setOf("checkout", "bank_redirect", "education_carousel", "error")
        val WEBVIEW_LOAD_CODES = setOf("connection_error", "http_error", "webview_error", "content_process_terminated")
        val ERROR_PAGE_CODES = WEBVIEW_LOAD_CODES + "open_timeout"
        val API_ERROR_CODES = setOf("timeout", "network", "http_error", "invalid_response")
        val ACTION_ERROR_CODES = mapOf(
            "document_upload" to setOf("picker_error"),
            "device_permission" to setOf("permission_denied"),
            "webview_load" to WEBVIEW_LOAD_CODES,
            "navigation" to setOf("non_http_scheme", "external_host", "invalid_window_open_url"),
            "education_carousel" to setOf("webview_error", "content_process_terminated", "render_failed", "other"),
        )
        val CHECKOUT_FAILURES = mapOf(
            "invalid_init_params" to setOf("invalid_public_key", "missing_order_id", "invalid_order_id", "invalid_server", "other"),
            "dependencies_failed_to_load" to emptySet(),
            "compliance_blocked" to setOf("jailbreak_detected", "root_detected", "debugger_attached"),
            "order_fetch_failed" to setOf("timeout", "http_4xx", "http_5xx", "network", "invalid_response", "other"),
            "open_timeout" to emptySet(),
            "connection_error" to setOf("http_4xx", "http_5xx", "network"),
        )
        val FREE_TEXT_KEYS = setOf(
            "error_message", "error_description", "reason", "message", "data", "url", "errors", "level", "payment_id",
        )
        val KEY_PATTERN = Regex("^\\$?[a-z0-9_]+$")
    }
}
