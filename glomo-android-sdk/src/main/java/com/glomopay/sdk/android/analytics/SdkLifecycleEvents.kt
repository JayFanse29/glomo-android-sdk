package com.glomopay.sdk.android.analytics

import com.glomopay.sdk.android.ConnectionError
import com.glomopay.sdk.android.ConnectionErrorType
import com.glomopay.sdk.android.TerminationSource
import java.util.concurrent.atomic.AtomicBoolean

internal const val SDK_INTEGRATION = "android_sdk"

internal enum class SdkCloseTrigger(val value: String) {
    USER_DISMISS("user_dismiss"),
    MERCHANT("merchant"),
    PAGE("page"),
    COMPLETED("completed"),
    ERROR("error"),
}

internal enum class SdkFailureCode(val value: String) {
    OPEN_TIMEOUT("open_timeout"),
    CONNECTION_ERROR("connection_error"),
    WEBVIEW_ERROR("webview_error"),
    HTTP_ERROR("http_error"),
    VALIDATION_FAILED("validation_failed"),
    ORDER_TYPE_DETECTION_FAILED("order_type_detection_failed"),
    COMPLIANCE_BLOCKED("compliance_blocked"),
    DEPENDENCIES_FAILED("dependencies_failed"),
    START_FAILED("start_failed"),
}

internal class SdkLifecycleEvents(private val analytics: AnalyticsTracker) {
    private val closed = AtomicBoolean(false)
    private val failing = AtomicBoolean(false)
    private val loaded = AtomicBoolean(false)

    fun failed(code: SdkFailureCode, message: String? = null) {
        if (loaded.get() || !failing.compareAndSet(false, true)) return
        analytics.track(AnalyticsEvents.SDK_FAILED, buildMap {
            put("integration", SDK_INTEGRATION)
            put("error_code", code.value)
            if (!message.isNullOrBlank()) put("error_message", AnalyticsSanitizer.text(message, MAX_MESSAGE_LENGTH))
        })
    }

    fun recovered() {
        failing.set(false)
    }

    fun loaded() {
        loaded.set(true)
    }

    fun closed(trigger: SdkCloseTrigger) {
        if (!closed.compareAndSet(false, true)) return
        analytics.track(AnalyticsEvents.SDK_CLOSED, mapOf(
            "integration" to SDK_INTEGRATION,
            "trigger" to trigger.value,
        ))
    }

    private companion object {
        const val MAX_MESSAGE_LENGTH = 200
    }
}

internal fun TerminationSource.toCloseTrigger(): SdkCloseTrigger = when (this) {
    TerminationSource.USER_DISMISS, TerminationSource.BACK_BUTTON -> SdkCloseTrigger.USER_DISMISS
    TerminationSource.PROGRAMMATIC -> SdkCloseTrigger.MERCHANT
    TerminationSource.CONNECTION_ERROR -> SdkCloseTrigger.ERROR
}

internal fun ConnectionError.toFailureCode(): SdkFailureCode = when {
    statusCode != null ||
        type == ConnectionErrorType.HTTP_CLIENT_ERROR ||
        type == ConnectionErrorType.HTTP_SERVER_ERROR -> SdkFailureCode.HTTP_ERROR
    type == ConnectionErrorType.WEB_RESOURCE_ERROR || type == ConnectionErrorType.UNKNOWN -> SdkFailureCode.WEBVIEW_ERROR
    else -> SdkFailureCode.CONNECTION_ERROR
}
