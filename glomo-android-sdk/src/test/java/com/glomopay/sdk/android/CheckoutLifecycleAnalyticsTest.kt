package com.glomopay.sdk.android

import android.app.Activity
import com.glomopay.sdk.android.analytics.AnalyticsEvents
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class CheckoutLifecycleAnalyticsTest {
    private val host = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val config = GlomoPayConfig(publicKey = "test_public_key", orderId = "order_123")

    @AfterTest
    fun tearDown() {
        RecordedCheckout.reset()
    }

    @Test
    fun a_visible_checkout_is_one_checkout_page_view() {
        val checkout = RecordedCheckout.open(host, config)

        checkout.post(mapOf("type" to "bridge.ready"))
        checkout.post(mapOf("type" to "bridge.ready"))

        val viewed = checkout.named(AnalyticsEvents.SDK_PAGE_VIEWED).single()
        assertEquals("checkout", viewed.properties["page_name"])
        assertEquals("lrs", viewed.properties["journey"])
    }

    @Test
    @Suppress("DEPRECATION")
    fun a_retry_after_the_checkout_was_visible_does_not_send_a_second_checkout_view() {
        val checkout = RecordedCheckout.open(host, config)
        checkout.post(mapOf("type" to "bridge.ready"))

        checkout.webView.webViewClient.onReceivedError(checkout.webView, 1, "failed", "https://lrs-checkout.glomopay.com/")
        checkout.tap("Retry")
        checkout.post(mapOf("type" to "bridge.ready"))

        assertEquals(
            listOf("checkout", "error"),
            checkout.named(AnalyticsEvents.SDK_PAGE_VIEWED).map { it.properties["page_name"] },
        )
        val failed = checkout.named(AnalyticsEvents.SDK_ACTION_FAILED).single()
        assertEquals("webview_load", failed.properties["action_name"])
        assertEquals("webview_error", failed.properties["error_code"])
        assertTrue(checkout.named(AnalyticsEvents.SDK_CHECKOUT_FAILED).isEmpty())
        assertEquals("retry", checkout.named(AnalyticsEvents.SDK_CTA_CLICKED).single().properties["cta_name"])
    }

    @Test
    @Suppress("DEPRECATION")
    fun a_failure_before_the_checkout_is_visible_is_one_checkout_failure_then_one_close() {
        val checkout = RecordedCheckout.open(host, config)

        checkout.webView.webViewClient.onReceivedError(checkout.webView, -2, "offline", "https://lrs-checkout.glomopay.com/")
        checkout.webView.webViewClient.onReceivedError(checkout.webView, -2, "offline", "https://lrs-checkout.glomopay.com/")

        val failed = checkout.named(AnalyticsEvents.SDK_CHECKOUT_FAILED).single()
        assertEquals("connection_error", failed.properties["error_code"])
        assertEquals("network", failed.properties["error_detail"])
        val closed = checkout.named(AnalyticsEvents.SDK_CHECKOUT_CLOSED).single()
        assertEquals("sdk", closed.properties["trigger"])
        assertEquals("loading", closed.properties["page_name"])
        assertTrue(checkout.named(AnalyticsEvents.SDK_ACTION_FAILED).isEmpty())
    }

    @Test
    fun a_merchant_close_is_one_close_with_the_last_visible_page() {
        val checkout = RecordedCheckout.open(host, config)
        checkout.post(mapOf("type" to "bridge.ready"))

        checkout.activity.closeProgrammatically()
        checkout.activity.closeProgrammatically()

        val closed = checkout.named(AnalyticsEvents.SDK_CHECKOUT_CLOSED).single()
        assertEquals(mapOf("trigger" to "merchant", "page_name" to "checkout"), closed.properties.filterKeys(CLOSE_KEYS::contains))
    }

    @Test
    @Suppress("DEPRECATION")
    fun a_user_back_is_one_close_as_a_user_dismiss() {
        val checkout = RecordedCheckout.open(host, config)

        checkout.activity.onBackPressed()

        val closed = checkout.named(AnalyticsEvents.SDK_CHECKOUT_CLOSED).single()
        assertEquals("user_dismiss", closed.properties["trigger"])
        assertEquals("loading", closed.properties["page_name"])
    }

    @Test
    fun the_activity_going_away_without_an_end_is_one_user_dismiss_close() {
        val checkout = RecordedCheckout.open(host, config)
        checkout.post(mapOf("type" to "bridge.ready"))

        checkout.controller.pause().stop().destroy()

        val closed = checkout.named(AnalyticsEvents.SDK_CHECKOUT_CLOSED).single()
        assertEquals(mapOf("trigger" to "user_dismiss", "page_name" to "checkout"), closed.properties.filterKeys(CLOSE_KEYS::contains))
    }

    @Test
    fun an_outcome_is_one_close_with_its_payment_event_and_no_payment_event() {
        val checkout = RecordedCheckout.open(host, config)
        checkout.post(mapOf("type" to "bridge.ready"))

        checkout.post(mapOf("type" to "message", "data" to mapOf(
            "type" to "payment.success",
            "payload" to mapOf("orderId" to "order_123", "paymentId" to "pay_1", "signature" to "sig_1"),
        )))

        val closed = checkout.named(AnalyticsEvents.SDK_CHECKOUT_CLOSED).single()
        assertEquals(
            mapOf("trigger" to "sdk", "page_name" to "checkout", "payment_event" to "payment_success", "had_listener" to true),
            closed.properties.filterKeys(CLOSE_KEYS::contains),
        )
        assertEquals(
            listOf(AnalyticsEvents.SDK_LOADED, AnalyticsEvents.SDK_PAGE_VIEWED, AnalyticsEvents.SDK_CHECKOUT_CLOSED),
            checkout.events.map { it.name },
        )
    }

    @Test
    fun invalid_init_params_fail_and_close_once() {
        val checkout = RecordedCheckout.open(host, GlomoPayConfig(publicKey = "test_public_key", orderId = "bad_123"))

        val failed = checkout.named(AnalyticsEvents.SDK_CHECKOUT_FAILED).single()
        assertEquals("invalid_init_params", failed.properties["error_code"])
        assertEquals("invalid_order_id", failed.properties["error_detail"])
        assertEquals("sdk", checkout.named(AnalyticsEvents.SDK_CHECKOUT_CLOSED).single().properties["trigger"])
    }

    private companion object {
        val CLOSE_KEYS = setOf("trigger", "page_name", "payment_event", "had_listener")
    }
}
