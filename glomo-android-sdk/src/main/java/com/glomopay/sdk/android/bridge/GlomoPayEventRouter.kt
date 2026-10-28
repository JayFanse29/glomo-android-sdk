package com.glomopay.sdk.android.bridge

import com.glomopay.sdk.android.GlomoPayPayload
import com.glomopay.sdk.android.GlomoPayResult
import com.glomopay.sdk.android.GlomoPayListener
import com.glomopay.sdk.android.SdkError
import com.glomopay.sdk.android.SdkErrorType
import com.glomopay.sdk.android.TerminationSource
import com.glomopay.sdk.android.analytics.CheckoutAnalytics
import com.glomopay.sdk.android.analytics.CheckoutFailure
import com.glomopay.sdk.android.analytics.CloseTrigger
import com.glomopay.sdk.android.analytics.NoOpAnalyticsTracker
import com.glomopay.sdk.android.analytics.PaymentEvent
import com.glomopay.sdk.android.carousel.EducationCarouselContract
import com.glomopay.sdk.android.monitoring.NoOpSdkErrorReporter
import com.glomopay.sdk.android.monitoring.SdkErrorReporter
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

internal class GlomoPayEventRouter(
    listener: GlomoPayListener?,
    private val devMode: Boolean,
    private val onComplete: (GlomoPayResult) -> Unit,
    private val onWindowOpen: (String) -> Unit = {},
    private val onWindowClose: () -> Unit = {},
    private val analytics: CheckoutAnalytics = CheckoutAnalytics(NoOpAnalyticsTracker),
    private val errorReporter: SdkErrorReporter = NoOpSdkErrorReporter,
    private val onBridgeReady: () -> Unit = {},
    private val onDependenciesFailed: () -> Unit = {},
) {
    private val listener = listener?.let { com.glomopay.sdk.android.GuardedGlomoPayListener(it, errorReporter) }
    private val terminalDelivered = AtomicBoolean(false)

    fun stop() { terminalDelivered.set(true) }

    fun handle(rawMessage: String, webViewType: String = "main") {
        if (terminalDelivered.get()) return
        try {
            val envelope = JSONObject(rawMessage).toMap()
            handleEnvelope(envelope, webViewType)
        } catch (error: Exception) {
            errorReporter.capture(
                operation = "bridge_message_parse",
                error = error,
                context = mapOf("webview_type" to webViewType),
            )
            listener?.onSdkError(listOf(SdkError(SdkErrorType.UNKNOWN, error.message ?: "Unable to parse WebView event")))
        }
    }

    fun handleEnvelope(envelope: Map<String, Any?>, webViewType: String = "main") {
        if (terminalDelivered.get()) return
        try {
            when (envelope["type"]?.toString()) {
                "bridge.ready" -> if (webViewType == "main") onBridgeReady()
                "console" -> handleConsole(envelope)
                "window.open" -> {
                    val url = envelope["url"] as? String ?: return
                    if (!com.glomopay.sdk.android.Validator.isValidUrl(url)) {
                        emit("flow.navigation_blocked", mapOf("reason" to "invalid_window_open_url"))
                        analytics.invalidWindowOpenUrl(webViewType)
                        return
                    }
                    analytics.bankRedirectViewed(url)
                    onWindowOpen(url)
                    emit("redirect.started", mapOf("url" to url))
                }
                "window.close" -> {
                    analytics.bankRedirectClosedByPage()
                    onWindowClose()
                    emit("redirect.completed", emptyMap())
                }
                "message" -> handlePaymentEvent(envelope["data"] as? Map<*, *>)
                "dependencies.failed_to_load" -> emitDependencyError(envelope["message"]?.toString())
                "webview.error" -> analytics.pageScriptFailed(webViewType)
                "file.input" -> emit("file.requested", envelope)
            }
        } catch (error: Exception) {
            errorReporter.capture(
                operation = "bridge_message_route",
                error = error,
                context = mapOf("webview_type" to webViewType),
            )
            listener?.onSdkError(listOf(SdkError(SdkErrorType.UNKNOWN, error.message ?: "Unable to parse WebView event")))
        }
    }

    private fun handleConsole(envelope: Map<String, Any?>) {
        if (!devMode) return
        val level = envelope["level"]?.toString() ?: "log"
        val message = envelope["message"]?.toString() ?: ""
        android.util.Log.println(android.util.Log.DEBUG, "GlomoPayJS/$level", message)
    }

    private fun handlePaymentEvent(rawData: Map<*, *>?) {
        if (rawData == null) return
        val data = rawData.entries.associate { it.key.toString() to it.value }
        val eventName = (data["type"] as? String)?.takeIf { it.isNotEmpty() }
            ?: data["event"]?.toString()
            ?: data["status"]?.toString()
        if (eventName != null) listener?.onEvent(eventName, data)

        val payloadData = ((data["payload"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value })
            ?.let { data + it } ?: data

        when (eventName) {
            "payment.success", "success" -> {
                val payload = GlomoPayPayload.fromMap(payloadData)
                if (isValidPaymentPayload(payload)) completeSuccess(payload)
            }
            // A submitted bank transfer is a journey, not a payment: no paymentId and no
            // signature exist, so there is nothing a host's backend can verify. Reporting
            // it through onPaymentSuccess told merchants money had moved when it had not.
            "payment.bank_transfer_submitted" -> {
                val journey = com.glomopay.sdk.android.GlomoPayUserJourneyPayload.fromMap(
                    journeyType = com.glomopay.sdk.android.GlomoPayUserJourneyType.BANK_TRANSFER,
                    json = payloadData,
                )
                if (journey.orderId.isEmpty()) {
                    // Rejected, but never silently: a dropped journey must leave a trace.
                    errorReporter.capture(
                        operation = "thin_bank_transfer_payload",
                        error = IllegalStateException("Bank transfer payload carried no orderId"),
                        context = mapOf("keys" to payloadData.keys.sorted().joinToString(",")),
                    )
                    return
                }
                completeUserJourney(journey)
            }
            // Delivered on the event name alone. The previous rule also required a signature,
            // which a failure payload has never carried - that field exists so a host can
            // verify a success - so onPaymentFailure never fired for a confirmed decline in
            // any release build. Do not substitute another schema check here: replacing one
            // guess about the page's schema with another invites the same silent misroute the
            // next time that schema moves.
            "payment.failure", "payment.failed", "failed", "payment.error" -> {
                val payload = GlomoPayPayload.fromMap(payloadData)
                if (payload.orderId.isEmpty()) {
                    errorReporter.capture(
                        operation = "thin_payment_failure_payload",
                        error = IllegalStateException("Payment failure payload carried no orderId"),
                        context = mapOf("keys" to payloadData.keys.sorted().joinToString(",")),
                    )
                }
                // Delivered either way: the payload travels as-is in rawResponse.
                completeFailure(payload)
            }
            "payment.cancelled", "cancelled", "checkout.closed" -> completeCancelled(TerminationSource.USER_DISMISS)
            "lrs.has_education_steps" -> {
                if (EducationCarouselContract.isShowSignal(payloadData)) analytics.educationCarouselViewed()
            }
            "lrs.education_steps_failed", "lrs.education_steps_failed_to_show" ->
                analytics.educationCarouselFailed(payloadData["reason"]?.toString() ?: "render_failed")
            "dependencies.failed_to_load" -> emitDependencyError(data["message"]?.toString(), "postMessage")
        }
    }

    private fun completeSuccess(payload: GlomoPayPayload) {
        if (!terminalDelivered.compareAndSet(false, true)) return
        analytics.checkoutClosed(CloseTrigger.SDK, PaymentEvent.PAYMENT_SUCCESS, listener != null)
        listener?.onPaymentSuccess(payload)
        onComplete(GlomoPayResult.Success(payload.toMap()))
    }

    /**
     * Terminal like the payment callbacks - the journey is over and checkout closes -
     * but delivered through onUserJourneyCompleted, never onPaymentSuccess.
     */
    private fun completeUserJourney(payload: com.glomopay.sdk.android.GlomoPayUserJourneyPayload) {
        if (!terminalDelivered.compareAndSet(false, true)) return
        analytics.checkoutClosed(CloseTrigger.SDK, PaymentEvent.BANK_TRANSFER_SUBMITTED, listener != null)
        listener?.onUserJourneyCompleted(payload)
        onComplete(GlomoPayResult.JourneyCompleted(payload.toMap()))
    }

    private fun completeFailure(payload: GlomoPayPayload) {
        if (!terminalDelivered.compareAndSet(false, true)) return
        analytics.checkoutClosed(CloseTrigger.SDK, PaymentEvent.PAYMENT_FAILURE, listener != null)
        listener?.onPaymentFailure(payload)
        onComplete(GlomoPayResult.Failure("Payment failed"))
    }

    private fun completeCancelled(source: TerminationSource) {
        if (!terminalDelivered.compareAndSet(false, true)) return
        analytics.checkoutClosed(CloseTrigger.USER_DISMISS)
        listener?.onPaymentTerminate(source)
        onComplete(GlomoPayResult.Cancelled)
    }

    private fun emit(name: String, data: Map<String, Any?>) {
        listener?.onEvent("glomo_android_sdk.$name", data)
    }

    private fun emitDependencyError(message: String?, source: String = "bridge") {
        onDependenciesFailed()
        analytics.checkoutFailed(CheckoutFailure.DEPENDENCIES_FAILED_TO_LOAD)
        errorReporter.capture(
            operation = "checkout_dependencies",
            error = IllegalStateException("Checkout dependencies failed"),
            context = mapOf("source" to source),
        )
        emit("checkout.dependencies_failed", mapOf(
            "message" to (message ?: "Checkout dependencies failed to load"),
            "source" to source,
        ))
    }

    private fun isValidPaymentPayload(payload: GlomoPayPayload): Boolean =
        com.glomopay.sdk.android.Validator.isValidPaymentPayload(payload)

    private fun JSONObject.toMap(): Map<String, Any?> = keys().asSequence().associateWith { key ->
        when (val value = get(key)) {
            JSONObject.NULL -> null
            is JSONObject -> value.toMap()
            is JSONArray -> value.toList()
            else -> value
        }
    }

    private fun JSONArray.toList(): List<Any?> = (0 until length()).map { index ->
        when (val value = get(index)) {
            JSONObject.NULL -> null
            is JSONObject -> value.toMap()
            is JSONArray -> value.toList()
            else -> value
        }
    }
}
