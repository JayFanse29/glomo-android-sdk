package com.glomopay.sdk.android

import android.app.Activity
import com.glomopay.sdk.android.analytics.AnalyticsEvents
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class NativeControlsAnalyticsTest {
    private val host = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val config = GlomoPayConfig(publicKey = "test_public_key", orderId = "order_123")

    @AfterTest
    fun tearDown() {
        RecordedCheckout.reset()
    }

    @Test
    fun the_retry_screen_is_an_error_page_view_and_retry_is_a_cta() {
        val checkout = RecordedCheckout.open(host, config)

        checkout.timeOut()
        checkout.tap("Retry")

        val failed = checkout.named(AnalyticsEvents.SDK_CHECKOUT_FAILED).single()
        assertEquals("open_timeout", failed.properties["error_code"])
        assertEquals("url_resolved", failed.properties["last_step"])
        val error = checkout.named(AnalyticsEvents.SDK_PAGE_VIEWED).single()
        assertEquals("error", error.properties["page_name"])
        assertEquals("open_timeout", error.properties["error_code"])
        val retry = checkout.named(AnalyticsEvents.SDK_CTA_CLICKED).single()
        assertEquals("retry", retry.properties["cta_name"])
        assertEquals("error", retry.properties["page_name"])
    }

    @Test
    @Suppress("DEPRECATION")
    fun native_back_from_the_bank_page_is_a_back_to_checkout_cta() {
        val checkout = RecordedCheckout.open(host, config)
        checkout.post(mapOf("type" to "bridge.ready"))
        checkout.post(mapOf("type" to "window.open", "url" to "https://bank.example/acs?token=abc"))

        checkout.activity.onBackPressed()

        val bankPage = checkout.named(AnalyticsEvents.SDK_PAGE_VIEWED).last()
        assertEquals(mapOf("page_name" to "bank_redirect", "url_origin" to "https://bank.example"), bankPage.properties.filterKeys(PAGE_KEYS::contains))
        val back = checkout.named(AnalyticsEvents.SDK_CTA_CLICKED).single()
        assertEquals("back_to_checkout", back.properties["cta_name"])
        assertEquals("bank_redirect", back.properties["page_name"])
        assertEquals(emptyList(), checkout.named(AnalyticsEvents.SDK_CHECKOUT_CLOSED))
    }

    @Test
    fun the_page_closing_the_bank_page_is_a_submitted_bank_redirect() {
        val checkout = RecordedCheckout.open(host, config)
        checkout.post(mapOf("type" to "bridge.ready"))
        checkout.post(mapOf("type" to "window.open", "url" to "https://bank.example/acs"))

        checkout.post(mapOf("type" to "window.close"))

        val submitted = checkout.named(AnalyticsEvents.SDK_ACTION_SUBMITTED).single()
        assertEquals("bank_redirect", submitted.properties["action_name"])
        assertEquals("https://bank.example", submitted.properties["url_origin"])
        assertEquals(emptyList(), checkout.named(AnalyticsEvents.SDK_CTA_CLICKED))
    }

    private companion object {
        val PAGE_KEYS = setOf("page_name", "url_origin")
    }
}
