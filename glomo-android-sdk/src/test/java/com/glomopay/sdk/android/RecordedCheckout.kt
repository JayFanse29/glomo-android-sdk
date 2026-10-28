package com.glomopay.sdk.android

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import com.glomopay.sdk.android.analytics.AnalyticsEvent
import com.glomopay.sdk.android.analytics.AnalyticsFactory
import com.glomopay.sdk.android.analytics.AnalyticsTransport
import com.glomopay.sdk.android.analytics.MixpanelAnalyticsTracker
import com.glomopay.sdk.android.bridge.GlomoPayJavaScriptBridge
import com.glomopay.sdk.android.monitoring.NoOpSdkErrorReporter
import com.glomopay.sdk.android.monitoring.SdkErrorReporterFactory
import com.glomopay.sdk.android.ui.GlomoPayCheckoutActivity
import java.time.Duration
import org.json.JSONObject
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

internal class RecordedCheckout private constructor(
    val activity: GlomoPayCheckoutActivity,
    val events: List<AnalyticsEvent>,
) {
    private val root: View = activity.findViewById(android.R.id.content)

    val webView: WebView
        get() = root.allViews().filterIsInstance<WebView>().first()

    fun named(name: String): List<AnalyticsEvent> = events.filter { it.name == name }

    fun post(message: Map<String, Any?>, bridge: String = "GlomoPayBridge") {
        val target = root.allViews().filterIsInstance<WebView>()
            .mapNotNull { shadowOf(it).getJavascriptInterface(bridge) as? GlomoPayJavaScriptBridge }
            .last()
        target.postMessage(JSONObject(message).toString())
        shadowOf(Looper.getMainLooper()).idle()
    }

    fun timeOut() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16))
    }

    fun tap(label: String) {
        root.allViews().filterIsInstance<Button>().first { it.text == label && it.isShown }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    companion object {
        fun open(
            host: Activity,
            config: GlomoPayConfig,
            listener: GlomoPayListener = NoOpListener,
        ): RecordedCheckout {
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
            GlomoPaySdk.startCheckout(host, config, listener, orderType = "lrs")
            val intent = requireNotNull(shadowOf(host).nextStartedActivity)
            val activity = Robolectric.buildActivity(GlomoPayCheckoutActivity::class.java, intent).setup().get()
            return RecordedCheckout(activity, events)
        }

        fun reset() {
            AnalyticsFactory.trackerFactory = null
            SdkErrorReporterFactory.reporterFactory = null
        }
    }

    object NoOpListener : GlomoPayListener {
        override fun onPaymentSuccess(payload: GlomoPayPayload) = Unit
        override fun onPaymentFailure(payload: GlomoPayPayload) = Unit
        override fun onSdkError(errors: List<SdkError>) = Unit
        override fun onConnectionError(error: ConnectionError) = Unit
        override fun onUserJourneyCompleted(payload: GlomoPayUserJourneyPayload) = Unit
    }
}

private fun View.allViews(): Sequence<View> = sequence {
    val view = this@allViews
    yield(view)
    if (view is ViewGroup) {
        for (index in 0 until view.childCount) yieldAll(view.getChildAt(index).allViews())
    }
}
