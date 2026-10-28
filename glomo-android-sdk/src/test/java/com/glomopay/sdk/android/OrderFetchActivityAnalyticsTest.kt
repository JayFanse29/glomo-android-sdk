package com.glomopay.sdk.android

import android.app.Activity
import com.glomopay.sdk.android.analytics.AnalyticsEvents
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class OrderFetchActivityAnalyticsTest {
    private val host = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val config = GlomoPayConfig(publicKey = "test_public_key", orderId = "order_123")

    @AfterTest
    fun tearDown() {
        RecordedCheckout.reset()
    }

    @Test
    fun an_auto_open_records_the_order_fetch_and_carries_the_resolved_journey() {
        GlomoPayApiClient.fetchOverride = { 200 to mapOf("orderType" to "lrs") }

        val checkout = RecordedCheckout.open(host, config, orderType = "auto")
        checkout.idleUntil { checkout.named(AnalyticsEvents.SDK_API_BACKEND).isNotEmpty() }

        val api = checkout.named(AnalyticsEvents.SDK_API_BACKEND).single()
        assertEquals("/api/public/v1/order/:order_id", api.properties["api_path"])
        assertEquals("succeeded", api.properties["status"])
        assertEquals(200, api.properties["http_status"])
        assertEquals("lrs", api.properties["journey"])
        assertTrue(checkout.named(AnalyticsEvents.SDK_CHECKOUT_FAILED).isEmpty())
    }

    @Test
    fun a_failed_auto_open_records_the_fetch_the_blocked_open_and_one_close() {
        GlomoPayApiClient.fetchOverride = { throw GlomoPayHttpStatusError(404) }

        val checkout = RecordedCheckout.open(host, config, orderType = "auto")
        checkout.idleUntil { checkout.named(AnalyticsEvents.SDK_CHECKOUT_CLOSED).isNotEmpty() }

        val api = checkout.named(AnalyticsEvents.SDK_API_BACKEND).single()
        assertEquals("failed", api.properties["status"])
        assertEquals("http_error", api.properties["error_code"])
        assertEquals(404, api.properties["http_status"])
        val failed = checkout.named(AnalyticsEvents.SDK_CHECKOUT_FAILED).single()
        assertEquals("order_fetch_failed", failed.properties["error_code"])
        assertEquals("http_4xx", failed.properties["error_detail"])
        assertEquals("sdk", checkout.named(AnalyticsEvents.SDK_CHECKOUT_CLOSED).single().properties["trigger"])
    }

    @Test
    fun a_fetch_that_returns_after_the_checkout_started_finishing_is_still_recorded() {
        val released = CountDownLatch(1)
        GlomoPayApiClient.fetchOverride = {
            withContext(Dispatchers.IO) { released.await(5, TimeUnit.SECONDS) }
            200 to mapOf("orderType" to "standard")
        }

        val checkout = RecordedCheckout.open(host, config, orderType = "auto")
        checkout.activity.finish()
        released.countDown()
        checkout.idleUntil { checkout.named(AnalyticsEvents.SDK_API_BACKEND).isNotEmpty() }

        assertEquals("succeeded", checkout.named(AnalyticsEvents.SDK_API_BACKEND).single().properties["status"])
        assertEquals(null, shadowOf(checkout.webView).lastLoadedUrl)
    }
}
