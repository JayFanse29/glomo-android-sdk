package com.glomopay.sdk.android.analytics

internal interface AnalyticsTracker {
    fun track(event: String, properties: Map<String, Any?> = emptyMap())

    fun updateFlowType(flowType: String)

    fun updateCheckoutUrl(url: String)
}

internal object NoOpAnalyticsTracker : AnalyticsTracker {
    override fun track(event: String, properties: Map<String, Any?>) = Unit

    override fun updateFlowType(flowType: String) = Unit

    override fun updateCheckoutUrl(url: String) = Unit
}

internal object AnalyticsEvents {
    const val SDK_INITIALIZED = "SDK Initialized"
    const val SDK_LOADED = "sdk_loaded"
    const val SDK_VALIDATION_FAILED = "SDK Validation Failed"
    const val DEVICE_COMPLIANCE_CHECKED = "Device Compliance Checked"
    const val DEVICE_COMPLIANCE_BLOCKED = "Device Compliance Blocked"
    const val ORDER_TYPE_DETECTION_STARTED = "Order Type Detection Started"
    const val ORDER_TYPE_RESOLVED = "Order Type Resolved"
    const val ORDER_TYPE_DETECTION_FAILED = "Order Type Detection Failed"
    const val CHECKOUT_STARTED = "Checkout Started"
    const val CHECKOUT_WEBVIEW_CREATED = "Checkout WebView Created"
    const val CHECKOUT_URL_RESOLVED = "Checkout URL Resolved"
    const val CHECKOUT_NAVIGATION_STARTED = "Checkout Navigation Started"
    const val CHECKOUT_NAVIGATION_FINISHED = "Checkout Navigation Finished"
    const val CHECKOUT_BRIDGE_READY = "Checkout Bridge Ready"
    const val CHECKOUT_OPEN_TIMEOUT = "Checkout Open Timeout"
    const val NAVIGATION_STARTED = "Navigation Started"
    const val NAVIGATION_FINISHED = "Navigation Finished"
    const val NAVIGATION_URL_CHANGE = "Navigation URL Change"
    const val REDIRECT_OPENED = "Redirect Opened"
    const val REDIRECT_CLOSED = "Redirect Closed"
    const val REDIRECT_PAGE_STARTED = "Redirect Page Started"
    const val REDIRECT_PAGE_FINISHED = "Redirect Page Finished"
    const val REDIRECT_URL_CHANGE = "Redirect URL Change"
    const val PAYMENT_SUCCESS = "Payment Success"
    const val PAYMENT_FAILURE = "Payment Failure"
    const val PAYMENT_PENDING = "Payment Pending"
    const val PAYMENT_CANCELLED = "Payment Cancelled"
    const val PAYMENT_TERMINATED = "Payment Terminated"
    const val BANK_TRANSFER_SUBMITTED = "Bank Transfer Submitted"
    const val PAY_VIA_BANK_COMPLETED = "Pay Via Bank Completed"
    const val CONNECTION_ERROR = "Connection Error"
    const val WEBVIEW_HTTP_ERROR = "WebView HTTP Error"
    const val WEBVIEW_ERROR = "WebView Error"
    const val INVALID_MESSAGE_RECEIVED = "Invalid Message Received"
    const val SDK_ERROR = "SDK Error"
    const val CHECKOUT_DEPENDENCIES_FAILED = "Checkout Dependencies Failed"
    const val EDUCATION_STEPS_SHOWN = "Education Steps Shown"
    const val EDUCATION_STEPS_FAILED = "Education Steps Failed"
    const val FILE_UPLOAD_REQUESTED = "File Upload Requested"
    const val FILE_PICKER_ERROR = "File Picker Error"
    const val DEVICE_PERMISSION_REFUSED = "Device Permission Refused"
    const val CONSOLE_LOG_CAPTURED = "Console Log Captured"
    const val UNSUPPORTED_FUNCTIONALITY_USED = "Unsupported Functionality Used"
}
