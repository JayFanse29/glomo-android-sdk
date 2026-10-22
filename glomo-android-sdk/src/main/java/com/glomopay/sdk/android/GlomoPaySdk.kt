package com.glomopay.sdk.android

import android.content.Context
import com.glomopay.sdk.android.analytics.AnalyticsEvents
import com.glomopay.sdk.android.analytics.AnalyticsFactory
import com.glomopay.sdk.android.analytics.AnalyticsTracker
import com.glomopay.sdk.android.analytics.SDK_INTEGRATION
import com.glomopay.sdk.android.analytics.SdkCloseTrigger
import com.glomopay.sdk.android.analytics.SdkFailureCode
import com.glomopay.sdk.android.analytics.SdkLifecycleEvents
import com.glomopay.sdk.android.monitoring.SdkErrorReporter
import com.glomopay.sdk.android.monitoring.SdkErrorReporterFactory
import com.glomopay.sdk.android.ui.GlomoPayCheckoutActivity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Public entry point for starting the native checkout. */
public object GlomoPaySdk {
    public fun startCheckout(
        context: Context,
        config: GlomoPayConfig,
        listener: GlomoPayListener,
        orderType: String = "auto",
    ): GlomoPayCheckoutHandle {
        val sessionId = UUID.randomUUID().toString()
        val errorReporter = SdkErrorReporterFactory.create(
            context.applicationContext,
            config,
            sessionId,
            orderType,
        )
        val analytics = AnalyticsFactory.create(
            context.applicationContext,
            config,
            sessionId,
            orderType,
            errorReporter,
        )
        val lifecycleEvents = SdkLifecycleEvents(analytics)
        CheckoutSessionRegistry.put(sessionId, listener, analytics, errorReporter, lifecycleEvents)
        trackSdkOpened(analytics)
        val intent = GlomoPayCheckoutActivity.createIntent(context, config, orderType)
            .putExtra(GlomoPayCheckoutActivity.EXTRA_SESSION_ID, sessionId)
        if (context !is android.app.Activity) intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (error: Exception) {
            CheckoutSessionRegistry.remove(sessionId)
            lifecycleEvents.failed(SdkFailureCode.START_FAILED, error.javaClass.simpleName)
            lifecycleEvents.closed(SdkCloseTrigger.ERROR)
            throw error
        }
        return GlomoPayCheckoutHandle(sessionId)
    }

    internal fun trackSdkOpened(analytics: AnalyticsTracker) {
        analytics.track(AnalyticsEvents.SDK_INITIALIZED)
        analytics.track(AnalyticsEvents.SDK_LOADED, mapOf("integration" to SDK_INTEGRATION))
    }
}

/** Controls only the checkout returned by startCheckout. Safe to call from any thread. */
public class GlomoPayCheckoutHandle internal constructor(private val sessionId: String) {
    public fun close(): Unit {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            CheckoutSessionRegistry.get(sessionId)?.requestClose()
        }
    }
}

/**
 * The listener is held strongly, on purpose, for the lifetime of one checkout only:
 * the registry entry is removed on every terminal path and in onDestroy, so the
 * reference dies with the session. A WeakReference was considered and rejected -
 * hosts commonly pass an inline `object : GlomoPayListener {}` that nothing else
 * retains, and collecting it would silently drop the payment result. The Activity
 * reference below is weak, because the Activity's own lifecycle owns it.
 */
internal class CheckoutSession(
    val listener: GlomoPayListener,
    val analytics: AnalyticsTracker,
    val errorReporter: SdkErrorReporter,
    val lifecycleEvents: SdkLifecycleEvents,
) {
    private var activity = java.lang.ref.WeakReference<GlomoPayCheckoutActivity>(null)
    private var closeRequested = false

    fun attach(target: GlomoPayCheckoutActivity) {
        activity = java.lang.ref.WeakReference(target)
        if (closeRequested) target.closeProgrammatically()
    }

    fun detach() { activity.clear() }

    fun requestClose() {
        closeRequested = true
        activity.get()?.closeProgrammatically()
    }
}

internal object CheckoutSessionRegistry {
    private val sessions = ConcurrentHashMap<String, CheckoutSession>()

    fun put(
        id: String,
        listener: GlomoPayListener,
        analytics: AnalyticsTracker,
        errorReporter: SdkErrorReporter,
        lifecycleEvents: SdkLifecycleEvents,
    ) {
        sessions[id] = CheckoutSession(listener, analytics, errorReporter, lifecycleEvents)
    }

    fun get(id: String?): CheckoutSession? = id?.let { sessions[it] }

    fun remove(id: String?) {
        if (id != null) sessions.remove(id)
    }
}
