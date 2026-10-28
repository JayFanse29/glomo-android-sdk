package com.glomopay.sdk.android.analytics

import android.Manifest
import com.glomopay.sdk.android.ConnectionError
import com.glomopay.sdk.android.ConnectionErrorType
import com.glomopay.sdk.android.GlomoPayHttpStatusError
import com.glomopay.sdk.android.GlomoPayMalformedResponse
import com.glomopay.sdk.android.GlomoPayRequestTimeout
import com.glomopay.sdk.android.GlomoPayTransportError

internal class CheckoutAnalytics(private val tracker: AnalyticsTracker) {
    private val reportedFailures = mutableSetOf<CheckoutFailure>()
    private var closed = false
    private var educationCarouselViewed = false
    private var checkoutVisible = false
    private var bankRedirectOrigin: String? = null
    private var currentPage = AnalyticsPage.LOADING

    fun updateFlowType(flowType: String) = tracker.updateFlowType(flowType)

    fun checkoutViewed() {
        currentPage = AnalyticsPage.CHECKOUT
        if (checkoutVisible) return
        checkoutVisible = true
        pageViewed(AnalyticsPage.CHECKOUT)
    }

    fun bankRedirectViewed(url: String) {
        bankRedirectOrigin = AnalyticsSanitizer.bankRedirectUrl(url)
        currentPage = AnalyticsPage.BANK_REDIRECT
        pageViewed(AnalyticsPage.BANK_REDIRECT, propertiesOf("url_origin" to bankRedirectOrigin))
    }

    fun educationCarouselViewed() {
        if (educationCarouselViewed) return
        educationCarouselViewed = true
        pageViewed(AnalyticsPage.EDUCATION_CAROUSEL)
    }

    fun errorViewed(errorCode: String) {
        currentPage = AnalyticsPage.ERROR
        pageViewed(AnalyticsPage.ERROR, propertiesOf("error_code" to errorCode))
    }

    fun bankRedirectClosedByPage() {
        actionSubmitted(AnalyticsAction.BANK_REDIRECT, propertiesOf("url_origin" to bankRedirectOrigin))
        currentPage = mainPage()
    }

    fun backToCheckoutClicked() {
        ctaClicked(AnalyticsCta.BACK_TO_CHECKOUT)
        currentPage = mainPage()
    }

    fun retryClicked(resumedPage: AnalyticsPage) {
        ctaClicked(AnalyticsCta.RETRY)
        currentPage = resumedPage
    }

    fun orderFetchSucceeded(httpStatus: Int) {
        tracker.track(AnalyticsEvents.SDK_API_BACKEND, ORDER_API + mapOf("status" to "succeeded", "http_status" to httpStatus))
    }

    fun orderFetchFailed(error: Exception) {
        val httpStatus = (error as? GlomoPayHttpStatusError)?.statusCode
        val errorCode = when (error) {
            is GlomoPayRequestTimeout -> ApiErrorCode.TIMEOUT
            is GlomoPayTransportError -> ApiErrorCode.NETWORK
            is GlomoPayHttpStatusError -> ApiErrorCode.HTTP_ERROR
            is GlomoPayMalformedResponse -> ApiErrorCode.INVALID_RESPONSE
            else -> null
        }
        tracker.track(
            AnalyticsEvents.SDK_API_BACKEND,
            ORDER_API + propertiesOf(
                "status" to "failed",
                "http_status" to httpStatus,
                "error_code" to errorCode?.analyticsValue,
            ),
        )
        val detail = when (errorCode) {
            ApiErrorCode.HTTP_ERROR -> httpStatusDetail(httpStatus) ?: "other"
            null -> "other"
            else -> errorCode.analyticsValue
        }
        checkoutFailed(CheckoutFailure.ORDER_FETCH_FAILED, detail)
    }

    fun webViewLoadFailed(error: ConnectionError, webViewType: String, urlOrigin: String?): String {
        val errorCode = loadErrorCode(error)
        if (webViewType == MAIN_WEBVIEW && !checkoutVisible) {
            checkoutFailed(CheckoutFailure.CONNECTION_ERROR, httpStatusDetail(error.statusCode) ?: "network")
        } else {
            actionFailed(
                AnalyticsAction.WEBVIEW_LOAD,
                errorCode,
                propertiesOf(
                    "webview_type" to webViewType,
                    "http_status" to error.statusCode,
                    "is_recoverable" to error.isRecoverable,
                    "url_origin" to urlOrigin,
                ),
            )
        }
        return errorCode.analyticsValue
    }

    fun navigationBlocked(scheme: String, webViewType: String) {
        actionFailed(
            AnalyticsAction.NAVIGATION,
            ActionErrorCode.NON_HTTP_SCHEME,
            mapOf("scheme" to scheme, "webview_type" to webViewType),
        )
    }

    fun invalidWindowOpenUrl(webViewType: String) {
        actionFailed(AnalyticsAction.NAVIGATION, ActionErrorCode.INVALID_WINDOW_OPEN_URL, mapOf("webview_type" to webViewType))
    }

    fun documentUploadFailed(acceptTypes: String?) {
        actionFailed(AnalyticsAction.DOCUMENT_UPLOAD, ActionErrorCode.PICKER_ERROR, propertiesOf("accept_types" to acceptTypes))
    }

    fun permissionRefused(permission: String) {
        actionFailed(
            AnalyticsAction.DEVICE_PERMISSION,
            ActionErrorCode.PERMISSION_DENIED,
            mapOf("permission" to permissionName(permission), "request_source" to "file_upload"),
        )
    }

    fun educationCarouselFailed(reason: String?) {
        val errorCode = CAROUSEL_ERROR_CODES.firstOrNull { it.analyticsValue == reason } ?: ActionErrorCode.OTHER
        actionFailed(AnalyticsAction.EDUCATION_CAROUSEL, errorCode)
    }

    fun colorSchemeChanged(newValue: String, previousValue: String, appColorScheme: String) {
        tracker.track(
            AnalyticsEvents.SDK_CONTROL_CHANGED,
            mapOf(
                "control_name" to "color_scheme",
                "new_value" to newValue,
                "previous_value" to previousValue,
                "app_color_scheme" to appColorScheme,
            ),
        )
    }

    fun openTimedOut(lastStep: String) {
        checkoutFailed(CheckoutFailure.OPEN_TIMEOUT, extra = mapOf("last_step" to lastStep))
    }

    fun checkoutFailed(failure: CheckoutFailure, detail: String? = null, extra: Map<String, Any?> = emptyMap()) {
        if (!reportedFailures.add(failure)) return
        tracker.track(
            AnalyticsEvents.SDK_CHECKOUT_FAILED,
            propertiesOf("error_code" to failure.analyticsValue, "error_detail" to detail) + extra,
        )
    }

    fun checkoutClosed(trigger: CloseTrigger, paymentEvent: PaymentEvent? = null, hadListener: Boolean? = null) {
        if (closed) return
        closed = true
        tracker.track(
            AnalyticsEvents.SDK_CHECKOUT_CLOSED,
            propertiesOf(
                "trigger" to trigger.analyticsValue,
                "page_name" to currentPage.analyticsValue,
                "payment_event" to paymentEvent?.analyticsValue,
                "had_listener" to hadListener,
            ),
        )
    }

    private fun pageViewed(page: AnalyticsPage, properties: Map<String, Any?> = emptyMap()) {
        tracker.track(AnalyticsEvents.SDK_PAGE_VIEWED, mapOf("page_name" to page.analyticsValue) + properties)
    }

    private fun actionSubmitted(action: AnalyticsAction, properties: Map<String, Any?> = emptyMap()) {
        tracker.track(AnalyticsEvents.SDK_ACTION_SUBMITTED, mapOf("action_name" to action.analyticsValue) + properties)
    }

    private fun actionFailed(
        action: AnalyticsAction,
        errorCode: ActionErrorCode,
        properties: Map<String, Any?> = emptyMap(),
    ) {
        tracker.track(
            AnalyticsEvents.SDK_ACTION_FAILED,
            mapOf("action_name" to action.analyticsValue, "error_code" to errorCode.analyticsValue) + properties,
        )
    }

    private fun ctaClicked(cta: AnalyticsCta) {
        tracker.track(
            AnalyticsEvents.SDK_CTA_CLICKED,
            mapOf("cta_name" to cta.analyticsValue, "page_name" to currentPage.analyticsValue),
        )
    }

    private fun mainPage(): AnalyticsPage = if (checkoutVisible) AnalyticsPage.CHECKOUT else AnalyticsPage.LOADING

    private fun loadErrorCode(error: ConnectionError): ActionErrorCode = when {
        error.statusCode != null -> ActionErrorCode.HTTP_ERROR
        error.type == ConnectionErrorType.UNKNOWN -> ActionErrorCode.CONTENT_PROCESS_TERMINATED
        error.type == ConnectionErrorType.WEB_RESOURCE_ERROR -> ActionErrorCode.WEBVIEW_ERROR
        else -> ActionErrorCode.CONNECTION_ERROR
    }

    private fun httpStatusDetail(statusCode: Int?): String? = when (statusCode) {
        in 400..499 -> "http_4xx"
        in 500..599 -> "http_5xx"
        else -> null
    }

    private fun permissionName(permission: String): String = when (permission) {
        Manifest.permission.CAMERA -> "camera"
        Manifest.permission.RECORD_AUDIO -> "microphone"
        Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE -> "storage"
        Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO -> "photos"
        else -> "other"
    }

    private fun propertiesOf(vararg pairs: Pair<String, Any?>): Map<String, Any?> =
        pairs.filter { it.second != null }.toMap()

    private companion object {
        const val MAIN_WEBVIEW = "main"
        val ORDER_API: Map<String, Any?> = mapOf(
            "api_path" to "/api/public/v1/order/:order_id",
            "http_method" to "GET",
        )
        val CAROUSEL_ERROR_CODES = listOf(
            ActionErrorCode.WEBVIEW_ERROR,
            ActionErrorCode.CONTENT_PROCESS_TERMINATED,
            ActionErrorCode.RENDER_FAILED,
            ActionErrorCode.OTHER,
        )
    }
}
