package com.glomopay.sdk.android.analytics

internal interface AnalyticsTracker {
    fun track(event: String, properties: Map<String, Any?> = emptyMap())

    fun updateFlowType(flowType: String)
}

internal object NoOpAnalyticsTracker : AnalyticsTracker {
    override fun track(event: String, properties: Map<String, Any?>) = Unit

    override fun updateFlowType(flowType: String) = Unit
}

internal object AnalyticsEvents {
    const val SDK_LOADED = "sdk_loaded"
    const val SDK_PAGE_VIEWED = "sdk_page_viewed"
    const val SDK_API_BACKEND = "sdk_api_backend"
    const val SDK_ACTION_SUBMITTED = "sdk_action_submitted"
    const val SDK_ACTION_FAILED = "sdk_action_failed"
    const val SDK_CTA_CLICKED = "sdk_cta_clicked"
    const val SDK_CONTROL_CHANGED = "sdk_control_changed"
    const val SDK_CHECKOUT_FAILED = "sdk_checkout_failed"
    const val SDK_CHECKOUT_CLOSED = "sdk_checkout_closed"
}

internal enum class AnalyticsPage { LOADING, CHECKOUT, BANK_REDIRECT, EDUCATION_CAROUSEL, ERROR }

internal enum class AnalyticsAction { BANK_REDIRECT, DOCUMENT_UPLOAD, DEVICE_PERMISSION, WEBVIEW_LOAD, NAVIGATION, EDUCATION_CAROUSEL }

internal enum class ActionErrorCode {
    PICKER_ERROR,
    PERMISSION_DENIED,
    CONNECTION_ERROR,
    HTTP_ERROR,
    WEBVIEW_ERROR,
    CONTENT_PROCESS_TERMINATED,
    NON_HTTP_SCHEME,
    INVALID_WINDOW_OPEN_URL,
    RENDER_FAILED,
    OTHER,
}

internal enum class AnalyticsCta { RETRY, BACK_TO_CHECKOUT }

internal enum class CheckoutFailure {
    INVALID_INIT_PARAMS,
    DEPENDENCIES_FAILED_TO_LOAD,
    COMPLIANCE_BLOCKED,
    ORDER_FETCH_FAILED,
    OPEN_TIMEOUT,
    CONNECTION_ERROR,
}

internal enum class CloseTrigger { USER_DISMISS, MERCHANT, SDK }

internal enum class PaymentEvent { PAYMENT_SUCCESS, PAYMENT_FAILURE, BANK_TRANSFER_SUBMITTED }

internal enum class ApiErrorCode { TIMEOUT, NETWORK, HTTP_ERROR, INVALID_RESPONSE }

internal val Enum<*>.analyticsValue: String
    get() = name.lowercase()
