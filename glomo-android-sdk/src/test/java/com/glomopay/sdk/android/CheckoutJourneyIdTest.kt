package com.glomopay.sdk.android

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import com.glomopay.sdk.android.analytics.AnalyticsEvent
import com.glomopay.sdk.android.analytics.AnalyticsEvents
import com.glomopay.sdk.android.analytics.AnalyticsFactory
import com.glomopay.sdk.android.analytics.AnalyticsTransport
import com.glomopay.sdk.android.analytics.MixpanelAnalyticsTracker
import com.glomopay.sdk.android.monitoring.NoOpSdkErrorReporter
import com.glomopay.sdk.android.monitoring.SdkErrorReporterFactory
import com.glomopay.sdk.android.ui.GlomoPayCheckoutActivity
import java.net.URI
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class CheckoutJourneyIdTest {
    private val host = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val config = GlomoPayConfig(
        publicKey = "test_public_key",
        orderId = "order_123",
        appColorScheme = GlomoColorScheme.LIGHT,
    )

    @AfterTest
    fun tearDown() {
        AnalyticsFactory.trackerFactory = null
        SdkErrorReporterFactory.reporterFactory = null
    }

    @Test
    fun loaded_url_and_sent_events_share_one_journey_id_that_survives_retry() {
        val checkout = openCheckout()
        val journeyId = journeyIdOf(checkout.loadedUrl())

        assertNotNull(journeyId)
        assertEquals(
            listOf(AnalyticsEvents.SDK_INITIALIZED, AnalyticsEvents.SDK_LOADED),
            checkout.events.take(2).map { it.name },
        )
        checkout.events.forEach { event ->
            assertEquals(journeyId, event.properties["journey_id"], event.name)
            assertEquals(journeyId, event.properties["\$device_id"], event.name)
            assertEquals("\$device:$journeyId", event.properties["distinct_id"], event.name)
        }

        val rewrittenUrl = "https://lrs-checkout.glomopay.com/review?orderId=order_123&step=2#summary"
        checkout.webView.webViewClient.onPageStarted(checkout.webView, rewrittenUrl, null)
        checkout.timeOut()
        checkout.retry()

        assertEquals(
            "https://lrs-checkout.glomopay.com/review?orderId=order_123&step=2&colorScheme=light&journeyId=$journeyId#summary",
            checkout.loadedUrl(),
        )
        checkout.events.forEach { assertEquals(journeyId, it.properties["journey_id"], it.name) }
    }

    @Test
    fun retry_after_leaving_the_checkout_origin_reloads_checkout_with_the_same_journey_id() {
        val checkout = openCheckout()
        val journeyId = journeyIdOf(checkout.loadedUrl())

        val bankUrl = "https://bank.example/acs?token=abc"
        checkout.webView.webViewClient.onPageStarted(checkout.webView, bankUrl, null)
        checkout.timeOut()
        checkout.retry()

        assertEquals(
            ConfigManager.getCheckoutUrl(config, "lrs", ResolvedColorScheme.LIGHT, journeyId),
            checkout.loadedUrl(),
        )
    }

    @Test
    fun a_second_start_checkout_gets_a_new_journey_id() {
        val first = openCheckout()
        val second = openCheckout()
        val firstJourneyId = journeyIdOf(first.loadedUrl())
        val secondJourneyId = journeyIdOf(second.loadedUrl())

        assertNotNull(firstJourneyId)
        assertNotNull(secondJourneyId)
        assertNotEquals(firstJourneyId, secondJourneyId)
        second.events.forEach { assertEquals(secondJourneyId, it.properties["journey_id"], it.name) }
    }

    private fun openCheckout(): OpenedCheckout {
        val events = mutableListOf<AnalyticsEvent>()
        SdkErrorReporterFactory.reporterFactory = { _, _ -> NoOpSdkErrorReporter }
        AnalyticsFactory.trackerFactory = { sessionId, flowType ->
            MixpanelAnalyticsTracker(
                config = config,
                sessionId = sessionId,
                sdkVersion = "test",
                initialFlowType = flowType,
                transport = AnalyticsTransport { events += it },
                executor = java.util.concurrent.Executor(Runnable::run),
                deviceProperties = { emptyMap() },
            )
        }
        GlomoPaySdk.startCheckout(host, config, NoOpListener, orderType = "lrs")
        val intent = requireNotNull(shadowOf(host).nextStartedActivity)
        val activity = Robolectric.buildActivity(GlomoPayCheckoutActivity::class.java, intent).setup().get()
        return OpenedCheckout(activity, events)
    }

    private fun journeyIdOf(url: String?): String? =
        URI(requireNotNull(url)).rawQuery
            ?.split('&')
            ?.singleOrNull { it.startsWith("journeyId=") }
            ?.substringAfter('=')

    private class OpenedCheckout(activity: GlomoPayCheckoutActivity, val events: List<AnalyticsEvent>) {
        private val root: View = activity.findViewById(android.R.id.content)
        val webView: WebView = root.descendants().filterIsInstance<WebView>().first()

        fun loadedUrl(): String? = shadowOf(webView).lastLoadedUrl

        fun timeOut() {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16))
        }

        fun retry() {
            root.descendants().filterIsInstance<Button>().first { it.text == "Retry" && it.isShown }.performClick()
        }
    }

    private object NoOpListener : GlomoPayListener {
        override fun onPaymentSuccess(payload: GlomoPayPayload) = Unit
        override fun onPaymentFailure(payload: GlomoPayPayload) = Unit
        override fun onSdkError(errors: List<SdkError>) = Unit
        override fun onConnectionError(error: ConnectionError) = Unit
        override fun onUserJourneyCompleted(payload: GlomoPayUserJourneyPayload) = Unit
    }
}

private fun View.descendants(): Sequence<View> = sequence {
    val view = this@descendants
    yield(view)
    if (view is ViewGroup) {
        for (index in 0 until view.childCount) yieldAll(view.getChildAt(index).descendants())
    }
}
