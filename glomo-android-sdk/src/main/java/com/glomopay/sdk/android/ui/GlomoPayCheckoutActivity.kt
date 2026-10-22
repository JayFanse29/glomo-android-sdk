package com.glomopay.sdk.android.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.view.ViewGroup
import android.webkit.ValueCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.glomopay.sdk.android.ConfigManager
import com.glomopay.sdk.android.ConnectionError
import com.glomopay.sdk.android.ConnectionErrorType
import com.glomopay.sdk.android.GlomoPayApiClient
import com.glomopay.sdk.android.GlomoPayConfig
import com.glomopay.sdk.android.GlomoPayResult
import com.glomopay.sdk.android.GlomoPayHttpStatusError
import com.glomopay.sdk.android.GlomoPayMalformedResponse
import com.glomopay.sdk.android.GlomoPayRequestTimeout
import com.glomopay.sdk.android.GlomoPayTransportError
import com.glomopay.sdk.android.toSdkError
import com.glomopay.sdk.android.R
import com.glomopay.sdk.android.CheckoutSessionRegistry
import com.glomopay.sdk.android.SdkError
import com.glomopay.sdk.android.SdkErrorType
import com.glomopay.sdk.android.bridge.GlomoPayEventRouter
import com.glomopay.sdk.android.bridge.GlomoPayInjectionScripts
import com.glomopay.sdk.android.bridge.GlomoPayJavaScriptBridge
import com.glomopay.sdk.android.analytics.AnalyticsEvents
import com.glomopay.sdk.android.analytics.AnalyticsSanitizer
import com.glomopay.sdk.android.analytics.AnalyticsTracker
import com.glomopay.sdk.android.analytics.complianceAnalyticsProperties
import com.glomopay.sdk.android.analytics.GlomoPayLogger
import com.glomopay.sdk.android.analytics.NoOpAnalyticsTracker
import com.glomopay.sdk.android.carousel.EducationCarouselContract
import com.glomopay.sdk.android.carousel.EducationCarouselState
import com.glomopay.sdk.android.monitoring.NoOpSdkErrorReporter
import com.glomopay.sdk.android.monitoring.SdkErrorReporter
import com.glomopay.sdk.android.Validator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.glomopay.sdk.android.security.CompliancePolicy
import com.glomopay.sdk.android.security.DeviceComplianceChecker
import com.glomopay.sdk.android.state.CheckoutUiState
import com.glomopay.sdk.android.state.withLoadingProgress
import com.glomopay.sdk.android.webview.CheckoutWebViewClient
import com.glomopay.sdk.android.webview.CheckoutWebViewFactory

/** Native main checkout host. JS events are attached in the bridge phase. */
public class GlomoPayCheckoutActivity : Activity() {
    private lateinit var webView: android.webkit.WebView
    private lateinit var loadingLabel: TextView
    private lateinit var config: GlomoPayConfig
    private var sessionId: String? = null
    private var listener: com.glomopay.sdk.android.GlomoPayListener? = null
    private var analytics: AnalyticsTracker = NoOpAnalyticsTracker
    private var errorReporter: SdkErrorReporter = NoOpSdkErrorReporter
    private lateinit var eventRouter: GlomoPayEventRouter
    private lateinit var rootView: FrameLayout
    private var flowWebView: android.webkit.WebView? = null
    private var flowOverlay: FrameLayout? = null
    private var flowLoadingLabel: TextView? = null
    private var flowCarouselContainer: FrameLayout? = null
    private var flowPaymentContainer: FrameLayout? = null
    private var carouselWebView: android.webkit.WebView? = null
    private var carouselState: EducationCarouselState = EducationCarouselState.PENDING
    private var currentOrderType: String = "standard"
    private var mainErrorPanel: View? = null
    private var currentUrl: String? = null
    private var lastMainAnalyticsUrl: String? = null
    private var lastRedirectAnalyticsUrl: String? = null
    private var uiState: CheckoutUiState = CheckoutUiState.Loading
    // Opened first thing in onCreate: that sweeps captures left by a crashed or killed checkout
    // before this one can take a photo, whether or not it ever opens the picker.
    private lateinit var captureStore: CaptureStore
    private val filePicker by lazy {
        CheckoutFilePicker(
            activity = this,
            captures = captureStore,
            scope = checkoutScope,
            onError = { reason ->
                analytics.track(AnalyticsEvents.FILE_PICKER_ERROR, mapOf("reason" to reason))
                listener?.onEvent("glomo_android_sdk.file.error", mapOf("reason" to reason))
                listener?.onSdkError(listOf(SdkError(SdkErrorType.UNKNOWN, "Unable to select upload: $reason")))
            },
            onPermissionRefused = { permission ->
                analytics.track(AnalyticsEvents.DEVICE_PERMISSION_REFUSED, mapOf("permission" to permission))
                listener?.onEvent("glomo_android_sdk.permission.refused", mapOf("permission" to permission))
                listener?.onUserRefusedDevicePermissions(permission)
            },
        )
    }
    private val checkoutScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var finished = false
    private var connectionFailureVisible = false
    private var openFunnel = com.glomopay.sdk.android.state.CheckoutOpenFunnel()
    private var openStartedAt = 0L
    private var openTimeout: kotlinx.coroutines.Job? = null
    private var renderTimeout: kotlinx.coroutines.Job? = null
    private var supportsDocumentStartInjection = false
    private var mainDocumentStartInjectionInstalled = false
    private var flowErrorPanel: View? = null
    private var backCallback: android.window.OnBackInvokedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureStore = CaptureStore.open(java.io.File(cacheDir, CaptureStore.DIRECTORY))
        config = configFromIntent(intent)
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        val checkoutSession = CheckoutSessionRegistry.get(sessionId)
        analytics = checkoutSession?.analytics ?: NoOpAnalyticsTracker
        errorReporter = checkoutSession?.errorReporter ?: NoOpSdkErrorReporter
        // Resolved after the reporter, so a throwing merchant callback is reportable.
        listener = checkoutSession?.listener?.let {
            com.glomopay.sdk.android.GuardedGlomoPayListener(it, errorReporter)
        }

        // A lost JS/native session cannot safely resume a payment. Do not reload it.
        if (checkoutSession == null || savedInstanceState != null) {
            listener?.onSdkError(listOf(SdkError(SdkErrorType.UNKNOWN, getString(R.string.glomopay_session_interrupted))))
            finishWith(GlomoPayResult.Failure("Checkout session interrupted", "SESSION_INTERRUPTED"))
            return
        }
        checkoutSession.attach(this)
        if (finished) return
        val publicKeyError = if (Validator.isValidPublicKey(config.publicKey)) null else "Invalid Public Key format"
        val identifierError = Validator.validateCheckoutIdentifier(config.orderId, config.subscriptionId)
        if (publicKeyError != null || identifierError.isNotEmpty()) {
            val message = publicKeyError ?: identifierError
            analytics.track(AnalyticsEvents.SDK_VALIDATION_FAILED, mapOf(
                "failure_reason" to validationFailureReason(publicKeyError, identifierError),
                "error_message" to message,
            ))
            trackSdkError(SdkError(SdkErrorType.VALIDATION_ERROR, message))
            listener?.onSdkError(listOf(SdkError(SdkErrorType.VALIDATION_ERROR, message)))
            finishWith(GlomoPayResult.Failure(message, "VALIDATION_ERROR"))
            return
        }
        eventRouter = GlomoPayEventRouter(
            listener = listener,
            devMode = com.glomopay.sdk.android.BuildConfig.GLOMO_INTERNAL_BUILD,
            onComplete = { finishWith(it) },
            onWindowOpen = ::showFlow,
            onWindowClose = ::hideFlow,
            analytics = analytics,
            errorReporter = errorReporter,
            onBridgeReady = ::markBridgeReady,
            onDependenciesFailed = {
                openTimeout?.cancel()
                renderTimeout?.cancel()
                connectionFailureVisible = false
                mainErrorPanel?.visibility = View.GONE
                loadingLabel.visibility = View.GONE
            },
        )
        val strictCompliance = CompliancePolicy.requiresStrictCheck(config)
        val compliance = DeviceComplianceChecker.check(this, strictCompliance)
        analytics.track(
            AnalyticsEvents.DEVICE_COMPLIANCE_CHECKED,
            complianceAnalyticsProperties(compliance),
        )
        if (!compliance.isCompliant) {
            analytics.track(AnalyticsEvents.DEVICE_COMPLIANCE_BLOCKED, mapOf("block_reason" to "root_detected"))
            val error = SdkError(
                type = SdkErrorType.DEVICE_FORBIDDEN,
                message = "Device is rooted or jailbroken.",
            )
            trackSdkError(error)
            listener?.onSdkError(listOf(error))
            finishWith(GlomoPayResult.Failure("Device does not meet security requirements", "DEVICE_NON_COMPLIANT"))
            return
        }

        buildContentView()
        startOpenWatchdog()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val callback = android.window.OnBackInvokedCallback { handleCheckoutBack() }
            backCallback = callback
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback,
            )
        }
        loadCheckout()
    }

    private fun buildContentView() {
        supportsDocumentStartInjection =
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        webView = CheckoutWebViewFactory.create(this).apply {
            webViewClient = CheckoutWebViewClient(
                onPageStartedCallback = { url ->
                    currentUrl = url
                    advanceOpenStep(com.glomopay.sdk.android.state.CheckoutOpenStep.NAVIGATION_STARTED)
                    analytics.track(AnalyticsEvents.NAVIGATION_STARTED, navigationProperties(url))
                    mainErrorPanel?.visibility = View.GONE
                    updateState(CheckoutUiState.Loading)
                    if (!mainDocumentStartInjectionInstalled) evaluateInjection()
                },
                onPageFinishedCallback = { url ->
                    currentUrl = url
                    advanceOpenStep(com.glomopay.sdk.android.state.CheckoutOpenStep.NAVIGATION_FINISHED)
                    analytics.track(AnalyticsEvents.NAVIGATION_FINISHED, navigationProperties(url))
                    updateState(CheckoutUiState.Content)
                    if (!mainDocumentStartInjectionInstalled) evaluateInjection()
                },
                onUrlChangedCallback = { url ->
                    currentUrl = url
                    if (lastMainAnalyticsUrl != url) {
                        lastMainAnalyticsUrl = url
                        analytics.track(AnalyticsEvents.NAVIGATION_URL_CHANGE, navigationProperties(url))
                    }
                },
                onErrorCallback = ::handleConnectionError,
            )
            addJavascriptInterface(GlomoPayJavaScriptBridge { raw ->
                runOnUiThread { eventRouter.handle(raw) }
            }, "GlomoPayBridge")
        }

        rootView = FrameLayout(this)
        applySystemBarInsets(rootView)
        rootView.addView(webView, FrameLayout.LayoutParams(-1, -1))

        loadingLabel = TextView(this).apply {
            text = getString(R.string.glomopay_loading_checkout)
            setTextColor(Color.DKGRAY)
            setBackgroundColor(Color.WHITE)
            gravity = Gravity.CENTER
            visibility = View.VISIBLE
        }
        rootView.addView(loadingLabel, FrameLayout.LayoutParams(-1, -1))

        mainErrorPanel = createErrorPanel()
        rootView.addView(mainErrorPanel, FrameLayout.LayoutParams(-1, -1))

        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(view: android.webkit.WebView?, newProgress: Int) {
                updateState(uiState.withLoadingProgress(newProgress))
            }

            override fun onShowFileChooser(
                view: android.webkit.WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean = openFileChooser(filePathCallback, fileChooserParams)
        }
        setContentView(rootView)
    }

    private fun applySystemBarInsets(view: View) {
        val startLeft = view.paddingLeft
        val startTop = view.paddingTop
        val startRight = view.paddingRight
        val startBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            target.setPadding(
                startLeft + bars.left,
                startTop + bars.top,
                startRight + bars.right,
                startBottom + bars.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    private fun loadCheckout() {
        val rawRequestedType = intent.getStringExtra(EXTRA_ORDER_TYPE) ?: "auto"
        val requestedType = rawRequestedType.trim().lowercase()
        if (requestedType !in SUPPORTED_ORDER_TYPES) {
            analytics.track(
                AnalyticsEvents.UNSUPPORTED_FUNCTIONALITY_USED,
                mapOf("name" to "orderType:$rawRequestedType"),
            )
            openCheckout("standard")
            return
        }
        val orderId = config.orderId
        if (requestedType != "auto" || config.isSubscription || orderId.isNullOrEmpty()) {
            openCheckout(requestedType.takeUnless { it == "auto" } ?: "standard")
            return
        }

        checkoutScope.launch {
            analytics.track(AnalyticsEvents.ORDER_TYPE_DETECTION_STARTED)
            try {
                val order = withContext(Dispatchers.IO) {
                    GlomoPayApiClient(config.publicKey).fetchOrder(orderId)
                }
                if (isFinishing || isDestroyed) return@launch
                val detectedType = ConfigManager.detectOrderType(order)
                analytics.updateFlowType(detectedType)
                errorReporter.updateFlowType(detectedType)
                analytics.track(AnalyticsEvents.ORDER_TYPE_RESOLVED, mapOf("resolved_type" to detectedType))
                openCheckout(detectedType)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                analytics.track(AnalyticsEvents.ORDER_TYPE_DETECTION_FAILED, mapOf(
                    "error" to error.javaClass.simpleName,
                ))
                errorReporter.capture(
                    operation = "order_type_detection",
                    error = error,
                    context = mapOf("failure_type" to error.javaClass.simpleName),
                )
                handleOrderTypeDetectionFailure(error)
            }
        }
    }

    private fun handleOrderTypeDetectionFailure(error: Exception) {
        when (error) {
            is GlomoPayRequestTimeout -> {
                val connectionError = ConnectionError(
                    type = ConnectionErrorType.TIMEOUT,
                    message = "Order request timed out.",
                    shouldAutoClose = true,
                )
                failCheckoutForConnectionError(connectionError)
            }
            is GlomoPayTransportError -> {
                val connectionError = ConnectionError(
                    type = ConnectionErrorType.NO_INTERNET,
                    message = "Unable to fetch order. Please check your connection.",
                    shouldAutoClose = true,
                )
                failCheckoutForConnectionError(connectionError)
            }
            is GlomoPayHttpStatusError -> {
                failCheckoutForSdkError(error.toSdkError())
            }
            is GlomoPayMalformedResponse -> {
                val sdkError = SdkError(
                    type = SdkErrorType.UNKNOWN,
                    message = "Malformed order response.",
                )
                failCheckoutForSdkError(sdkError)
            }
            else -> {
                val sdkError = SdkError(
                    type = SdkErrorType.UNKNOWN,
                    message = "Unable to determine checkout type.",
                )
                failCheckoutForSdkError(sdkError)
            }
        }
    }

    private fun failCheckoutForConnectionError(error: ConnectionError) {
        analytics.track(AnalyticsEvents.CONNECTION_ERROR, mapOf(
            "error_code" to (error.errorCode ?: error.statusCode)?.toString(),
            "error_description" to error.message,
            "is_recoverable" to error.isRecoverable,
        ))
        listener?.onConnectionError(error)
        listener?.onPaymentTerminate(com.glomopay.sdk.android.TerminationSource.CONNECTION_ERROR)
        finishWith(GlomoPayResult.Failure(error.message, error.type.name))
    }

    /**
     * The backend answered, so this is not a connectivity fault: onSdkError carries the
     * cause and is the only callback for it. Reporting CONNECTION_ERROR here told a host
     * the network had failed when a 500 or a broken contract was the actual cause.
     */
    private fun failCheckoutForSdkError(error: SdkError) {
        trackSdkError(error)
        listener?.onSdkError(listOf(error))
        finishWith(GlomoPayResult.Failure(error.message, error.type.name))
    }

    private fun openCheckout(orderType: String) {
        currentOrderType = orderType.lowercase()
        prepareEducationCarousel(currentOrderType)
        val url = ConfigManager.getCheckoutUrl(config, currentOrderType, sessionId)
        analytics.updateFlowType(currentOrderType)
        errorReporter.updateFlowType(currentOrderType)
        analytics.updateCheckoutUrl(url)

        analytics.track(AnalyticsEvents.CHECKOUT_STARTED)
        currentUrl = url
        advanceOpenStep(com.glomopay.sdk.android.state.CheckoutOpenStep.URL_RESOLVED)
        installMainDocumentStartInjection(url)
        startRenderWatchdog()
        webView.loadUrl(url)
    }

    private fun updateState(state: CheckoutUiState) {
        uiState = state
        when (state) {
            CheckoutUiState.Loading -> {
                loadingLabel.text = getString(R.string.glomopay_loading_checkout)
                loadingLabel.visibility = View.VISIBLE
            }
            is CheckoutUiState.LoadingProgress -> {
                loadingLabel.text = if (state.progress > 0) {
                    getString(R.string.glomopay_loading_checkout_progress, state.progress)
                } else {
                    getString(R.string.glomopay_loading_checkout)
                }
                loadingLabel.visibility = View.VISIBLE
            }
            CheckoutUiState.Content -> {
                loadingLabel.visibility = View.GONE
            }
        }
    }

    private fun handleConnectionError(error: ConnectionError) {
        if (finished) return
        openTimeout?.cancel()
        renderTimeout?.cancel()
        analytics.track(AnalyticsEvents.CONNECTION_ERROR, mapOf(
            "error_code" to (error.errorCode ?: error.statusCode)?.toString(),
            "error_description" to error.message,
            "url" to error.failedUrl?.let(AnalyticsSanitizer::navigationUrl),
            "is_recoverable" to error.isRecoverable,
        ))
        error.statusCode?.let { statusCode ->
            analytics.track(AnalyticsEvents.WEBVIEW_HTTP_ERROR, mapOf(
                "status_code" to statusCode,
                "url" to error.failedUrl?.let(AnalyticsSanitizer::navigationUrl),
                "webview_type" to "main",
            ))
        }
        trackWebViewError(error, "main")
        errorReporter.capture(
            operation = "main_webview_connection",
            error = IllegalStateException(error.type.name),
            context = mapOf(
                "error_type" to error.type.name,
                "status_code" to error.statusCode,
                "webview_type" to "main",
            ),
        )
        listener?.onConnectionError(error)
        if (finished) return
        if (error.shouldAutoClose) {
            terminateCheckout(com.glomopay.sdk.android.TerminationSource.CONNECTION_ERROR)
            return
        }
        connectionFailureVisible = true
        mainErrorPanel?.visibility = View.VISIBLE
        loadingLabel.visibility = View.GONE
    }

    private fun advanceOpenStep(step: com.glomopay.sdk.android.state.CheckoutOpenStep) {
        if (!openFunnel.advance(step)) return
        val event = when (step) {
            com.glomopay.sdk.android.state.CheckoutOpenStep.WEB_VIEW_CREATED -> AnalyticsEvents.CHECKOUT_WEBVIEW_CREATED
            com.glomopay.sdk.android.state.CheckoutOpenStep.URL_RESOLVED -> AnalyticsEvents.CHECKOUT_URL_RESOLVED
            com.glomopay.sdk.android.state.CheckoutOpenStep.NAVIGATION_STARTED -> AnalyticsEvents.CHECKOUT_NAVIGATION_STARTED
            com.glomopay.sdk.android.state.CheckoutOpenStep.NAVIGATION_FINISHED -> AnalyticsEvents.CHECKOUT_NAVIGATION_FINISHED
            com.glomopay.sdk.android.state.CheckoutOpenStep.BRIDGE_READY -> AnalyticsEvents.CHECKOUT_BRIDGE_READY
        }
        analytics.track(event, mapOf("step" to step.value))
    }

    private fun startOpenWatchdog() {
        openStartedAt = android.os.SystemClock.elapsedRealtime()
        advanceOpenStep(com.glomopay.sdk.android.state.CheckoutOpenStep.WEB_VIEW_CREATED)
        openTimeout?.cancel()
        openTimeout = checkoutScope.launch {
            kotlinx.coroutines.delay(OPEN_TIMEOUT_MS)
            reportOpenTimeout("watchdog", OPEN_TIMEOUT_MS)
        }
    }

    private fun reportOpenTimeout(reason: String, threshold: Long) {
        val lastStep = openFunnel.timeout() ?: return
        analytics.track(AnalyticsEvents.CHECKOUT_OPEN_TIMEOUT, mapOf(
            "last_step" to lastStep, "elapsed_ms" to (android.os.SystemClock.elapsedRealtime() - openStartedAt),
            "timeout_ms" to threshold, "reason" to reason,
        ))
    }

    private fun startRenderWatchdog() {
        renderTimeout?.cancel()
        renderTimeout = checkoutScope.launch {
            kotlinx.coroutines.delay(RENDER_TIMEOUT_MS)
            if (openFunnel.lastStep == com.glomopay.sdk.android.state.CheckoutOpenStep.BRIDGE_READY) return@launch
            reportOpenTimeout("render_timeout", RENDER_TIMEOUT_MS)
            handleConnectionError(ConnectionError(ConnectionErrorType.TIMEOUT,
                getString(R.string.glomopay_taking_longer), shouldAutoClose = false))
        }
    }

    private fun markBridgeReady() {
        advanceOpenStep(com.glomopay.sdk.android.state.CheckoutOpenStep.BRIDGE_READY)
        openTimeout?.cancel()
        renderTimeout?.cancel()
        connectionFailureVisible = false
        mainErrorPanel?.visibility = View.GONE
        updateState(CheckoutUiState.Content)
    }

    private fun installMainDocumentStartInjection(checkoutUrl: String) {
        if (mainDocumentStartInjectionInstalled || !supportsDocumentStartInjection) return
        val originRules = ConfigManager.checkoutDocumentStartOriginRules(checkoutUrl)
        if (originRules.isEmpty()) return
        WebViewCompat.addDocumentStartJavaScript(
            webView,
            mainInjectionScript(),
            originRules,
        )
        mainDocumentStartInjectionInstalled = true
    }

    private fun evaluateInjection() {
        webView.evaluateJavascript(mainInjectionScript(), null)
    }

    private fun mainInjectionScript(): String =
        "window.__glomoDevMode__ = ${com.glomopay.sdk.android.BuildConfig.GLOMO_INTERNAL_BUILD};" +
            GlomoPayInjectionScripts.main()

    private fun prepareEducationCarousel(orderType: String) {
        destroyEducationCarousel()
        currentOrderType = orderType.lowercase()
        if (currentOrderType != "lrs" || config.isSubscription) return

        val carousel = CheckoutWebViewFactory.create(this)
        carousel.webViewClient = CheckoutWebViewClient(
            onPageStartedCallback = {
                carousel.evaluateJavascript(GlomoPayInjectionScripts.carousel(), null)
            },
            onPageFinishedCallback = {
                carousel.evaluateJavascript(GlomoPayInjectionScripts.carousel(), null)
            },
            onUrlChangedCallback = {},
            onErrorCallback = { error ->
                analytics.track(
                    AnalyticsEvents.EDUCATION_STEPS_FAILED,
                    mapOf("reason" to "webview_error"),
                )
                errorReporter.capture(
                    operation = "education_carousel_webview",
                    error = IllegalStateException(error.type.name),
                    context = mapOf("error_type" to error.type.name),
                )
            },
        )
        carousel.addJavascriptInterface(
            GlomoPayJavaScriptBridge { raw ->
                runOnUiThread { handleEducationCarouselMessage(raw) }
            },
            "GlomoCarousel",
        )
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                carousel,
                GlomoPayInjectionScripts.carousel(),
                setOf("*"),
            )
        }
        carouselWebView = carousel
        carousel.loadUrl(ConfigManager.getCarouselUrl(config))
    }

    // Only the show signal changes anything. No signal leaves the carousel pending and hidden.
    private fun handleEducationCarouselMessage(rawMessage: String) {
        if (EducationCarouselContract.isShowSignal(rawMessage)) showEducationCarousel()
    }

    private fun showEducationCarousel() {
        if (carouselState == EducationCarouselState.HAS_CONTENT) return
        carouselState = EducationCarouselState.HAS_CONTENT
        analytics.track(AnalyticsEvents.EDUCATION_STEPS_SHOWN, mapOf("source" to "carousel"))
        applyEducationCarouselLayout()
    }

    private fun applyEducationCarouselLayout() {
        val layout = EducationCarouselContract.layout(
            state = carouselState,
            isLrsOrder = currentOrderType == "lrs",
            isSubscription = config.isSubscription,
        )
        val carouselContainer = flowCarouselContainer ?: return
        val paymentContainer = flowPaymentContainer ?: return

        carouselContainer.visibility = if (layout.showCarousel) View.VISIBLE else View.GONE
        (carouselContainer.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.weight = layout.carouselWeight
            carouselContainer.layoutParams = params
        }
        (paymentContainer.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.weight = layout.paymentWeight
            paymentContainer.layoutParams = params
        }
        carouselContainer.requestLayout()
        paymentContainer.requestLayout()
    }

    private fun destroyEducationCarousel() {
        carouselState = EducationCarouselState.PENDING
        applyEducationCarouselLayout()
        carouselWebView?.let { carousel ->
            (carousel.parent as? ViewGroup)?.removeView(carousel)
            carousel.stopLoading()
            carousel.removeJavascriptInterface("GlomoCarousel")
            carousel.destroy()
        }
        carouselWebView = null
    }

    private fun createErrorPanel(
        retryAction: () -> Unit = ::retryMainCheckout,
        cancelAction: () -> Unit = ::cancelCheckout,
        messageResource: Int = R.string.glomopay_taking_longer,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(24), dp(24), dp(24))
        setBackgroundColor(Color.WHITE)
        visibility = View.GONE

        val title = TextView(this@GlomoPayCheckoutActivity).apply {
            text = getString(R.string.glomopay_connection_error)
            textSize = 20f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
        }
        val message = TextView(this@GlomoPayCheckoutActivity).apply {
            text = getString(messageResource)
            textSize = 14f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(24))
        }
        val actions = LinearLayout(this@GlomoPayCheckoutActivity).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
        }
        val retry = Button(this@GlomoPayCheckoutActivity).apply {
            text = getString(R.string.glomopay_retry)
            setOnClickListener { retryAction() }
        }
        val cancel = Button(this@GlomoPayCheckoutActivity).apply {
            text = getString(R.string.glomopay_cancel)
            setOnClickListener { cancelAction() }
        }
        actions.addView(retry)
        actions.addView(cancel)
        addView(title)
        addView(message)
        addView(actions)
    }

    private fun retryMainCheckout() {
        connectionFailureVisible = false
        openFunnel = com.glomopay.sdk.android.state.CheckoutOpenFunnel()
        startOpenWatchdog()
        advanceOpenStep(com.glomopay.sdk.android.state.CheckoutOpenStep.URL_RESOLVED)
        startRenderWatchdog()
        mainErrorPanel?.visibility = View.GONE
        updateState(CheckoutUiState.Loading)
        prepareEducationCarousel(currentOrderType)
        val retryUrl = ConfigManager.getRetryUrl(config, currentOrderType, sessionId, currentUrl)
        installMainDocumentStartInjection(retryUrl)
        webView.loadUrl(retryUrl)
    }

    private fun cancelCheckout() {
        terminateCheckout(if (connectionFailureVisible) com.glomopay.sdk.android.TerminationSource.CONNECTION_ERROR
            else com.glomopay.sdk.android.TerminationSource.USER_DISMISS)
    }

    internal fun closeProgrammatically() {
        terminateCheckout(com.glomopay.sdk.android.TerminationSource.PROGRAMMATIC)
    }

    private fun terminateCheckout(source: com.glomopay.sdk.android.TerminationSource) {
        if (finished) return
        // Mark terminal before calling merchant code, which may re-enter close().
        finished = true
        analytics.track(AnalyticsEvents.PAYMENT_TERMINATED, mapOf("termination_source" to source.name.lowercase()))
        listener?.onPaymentTerminate(source)
        finishWith(GlomoPayResult.Cancelled, alreadyClaimed = true)
    }

    private fun showFlow(url: String) {
        if (!Validator.isValidUrl(url)) {
            onFlowNavigationBlocked(url)
            return
        }
        hideFlow()
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            elevation = dp(8).toFloat()
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val back = ImageButton(this).apply {
            setImageResource(R.drawable.glomopay_ic_chevron_back)
            contentDescription = getString(R.string.glomopay_back)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { handleFlowBack() }
        }
        toolbar.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        layout.addView(toolbar, LinearLayout.LayoutParams(-1, dp(48)))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val carouselContainer = FrameLayout(this).apply {
            visibility = View.GONE
        }
        val paymentContainer = FrameLayout(this)
        flowCarouselContainer = carouselContainer
        flowPaymentContainer = paymentContainer
        carouselWebView?.let { carousel ->
            (carousel.parent as? ViewGroup)?.removeView(carousel)
            carouselContainer.addView(carousel, FrameLayout.LayoutParams(-1, -1))
        }
        content.addView(carouselContainer, LinearLayout.LayoutParams(-1, 0, 0f))
        content.addView(paymentContainer, LinearLayout.LayoutParams(-1, 0, 100f))
        val flow = CheckoutWebViewFactory.create(this)
        val flowLoading = TextView(this).apply {
            text = getString(R.string.glomopay_opening_secure_page)
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setBackgroundColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        flowWebView = flow
        flowLoadingLabel = flowLoading
        flow.webViewClient = CheckoutWebViewClient(
            onNavigationBlocked = ::onFlowNavigationBlocked,
            onPageStartedCallback = { pageUrl ->
                flow.evaluateJavascript(GlomoPayInjectionScripts.flow() + GlomoPayInjectionScripts.bankViewportFit(), null)
                analytics.track(AnalyticsEvents.REDIRECT_PAGE_STARTED, bankNavigationProperties(pageUrl))
                listener?.onEvent("glomo_android_sdk.flow.pageStarted", mapOf("url" to pageUrl))
                flowErrorPanel?.visibility = View.GONE
                flowLoading.text = getString(R.string.glomopay_opening_secure_page)
                flowLoading.visibility = View.VISIBLE
            },
            onPageFinishedCallback = { pageUrl ->
                analytics.track(AnalyticsEvents.REDIRECT_PAGE_FINISHED, bankNavigationProperties(pageUrl))
                listener?.onEvent("glomo_android_sdk.flow.pageFinished", mapOf("url" to pageUrl))
                flowLoading.visibility = View.GONE
                flow.evaluateJavascript("window.__glomoDevMode__ = ${com.glomopay.sdk.android.BuildConfig.GLOMO_INTERNAL_BUILD};", null)
                flow.evaluateJavascript(GlomoPayInjectionScripts.flow() + GlomoPayInjectionScripts.bankViewportFit(), null)
            },
            onUrlChangedCallback = { pageUrl ->
                if (lastRedirectAnalyticsUrl != pageUrl) {
                    lastRedirectAnalyticsUrl = pageUrl
                    analytics.track(AnalyticsEvents.REDIRECT_URL_CHANGE, bankNavigationProperties(pageUrl))
                }
                listener?.onEvent("glomo_android_sdk.flow.urlChange", mapOf("url" to pageUrl))
            },
            onErrorCallback = { error ->
                analytics.track(AnalyticsEvents.CONNECTION_ERROR, mapOf(
                    "error_code" to (error.errorCode ?: error.statusCode)?.toString(),
                    "error_description" to error.message,
                    "url" to error.failedUrl?.let(AnalyticsSanitizer::bankRedirectUrl),
                    "is_recoverable" to error.isRecoverable,
                ))
                error.statusCode?.let { statusCode ->
                    analytics.track(AnalyticsEvents.WEBVIEW_HTTP_ERROR, mapOf(
                        "status_code" to statusCode,
                        "url" to error.failedUrl?.let(AnalyticsSanitizer::bankRedirectUrl),
                        "webview_type" to "flow",
                    ))
                }
                trackWebViewError(error, "flow")
                errorReporter.capture(
                    operation = "redirect_webview_connection",
                    error = IllegalStateException(error.type.name),
                    context = mapOf(
                        "error_type" to error.type.name,
                        "status_code" to error.statusCode,
                        "webview_type" to "flow",
                    ),
                )
                flowLoading.visibility = View.GONE
                flowErrorPanel?.visibility = View.VISIBLE
                listener?.onEvent("glomo_android_sdk.flow.error", mapOf(
                    "type" to error.type.toString(),
                    "message" to error.message,
                    "errorCode" to error.errorCode,
                ))
            },
        )
        flow.addJavascriptInterface(GlomoPayJavaScriptBridge { raw ->
            runOnUiThread { eventRouter.handle(raw, "flow") }
        }, "GlomoPayFlowBridge")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(flow, GlomoPayInjectionScripts.flow() + GlomoPayInjectionScripts.bankViewportFit(), setOf("*"))
        }
        flow.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(view: android.webkit.WebView?, newProgress: Int) {
                val progress = newProgress.coerceIn(0, 100)
                flowLoading.text = if (progress > 0) {
                    getString(R.string.glomopay_opening_secure_page_progress, progress)
                } else {
                    getString(R.string.glomopay_opening_secure_page)
                }
            }

            override fun onShowFileChooser(
                view: android.webkit.WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean = openFileChooser(filePathCallback, fileChooserParams)
        }
        paymentContainer.addView(flow, FrameLayout.LayoutParams(-1, -1))
        paymentContainer.addView(flowLoading, FrameLayout.LayoutParams(-1, -1))
        flowErrorPanel = createErrorPanel(
            retryAction = { flowErrorPanel?.visibility = View.GONE; flowLoading.visibility = View.VISIBLE; flow.reload() },
            cancelAction = ::handleFlowBack,
            messageResource = R.string.glomopay_connection_message,
        ).also { paymentContainer.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        layout.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        overlay.addView(layout, FrameLayout.LayoutParams(-1, -1))
        flowOverlay = overlay
        rootView.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        applyEducationCarouselLayout()
        flow.loadUrl(url)
    }

    private fun openFileChooser(
        callback: ValueCallback<Array<Uri>>?,
        params: android.webkit.WebChromeClient.FileChooserParams?
    ): Boolean = filePicker.open(callback, params)

    @Deprecated("Compatibility callback for external picker activities")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        filePicker.onResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        filePicker.onPermissionResult(requestCode, grantResults)
    }
    private fun hideFlow() {
        if (flowWebView != null) filePicker.cancel()
        carouselWebView?.let { carousel ->
            (carousel.parent as? ViewGroup)?.removeView(carousel)
        }
        flowOverlay?.let { rootView.removeView(it) }
        flowWebView?.let {
            CheckoutWebViewFactory.clearSession(it)
            it.destroy()
        }
        flowOverlay = null
        flowWebView = null
        flowLoadingLabel = null
        flowErrorPanel = null
        flowCarouselContainer = null
        flowPaymentContainer = null
        lastRedirectAnalyticsUrl = null
    }

    @Suppress("DEPRECATION")
    private fun handleFlowBack() {
        if (flowWebView == null) return
        hideFlow()
        analytics.track(AnalyticsEvents.REDIRECT_CLOSED, mapOf("source" to "flow"))
        listener?.onEvent("glomo_android_sdk.redirect.completed", emptyMap())
    }

    @Suppress("DEPRECATION")
    @Deprecated("Use OnBackInvokedDispatcher on newer Android versions")
    override fun onBackPressed() {
        handleCheckoutBack()
    }

    private fun handleCheckoutBack() {
        if (isFinishing) return
        if (flowWebView != null) {
            handleFlowBack()
        } else {
            terminateCheckout(if (connectionFailureVisible) com.glomopay.sdk.android.TerminationSource.CONNECTION_ERROR
                else com.glomopay.sdk.android.TerminationSource.BACK_BUTTON)
        }
    }

    override fun onDestroy() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        checkoutScope.cancel()
        filePicker.destroy()
        destroyEducationCarousel()
        if (::webView.isInitialized) {
            hideFlow()
            CheckoutWebViewFactory.clearSession(webView)
            webView.destroy()
        }
        CheckoutSessionRegistry.get(sessionId)?.detach()
        if (!isChangingConfigurations) {
            if (!finished) listener?.onPaymentTerminate(com.glomopay.sdk.android.TerminationSource.USER_DISMISS)
            CheckoutSessionRegistry.remove(sessionId)
        }
        listener = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("glomopay_session_started", true)
        super.onSaveInstanceState(outState)
    }

    private fun finishWith(result: GlomoPayResult, alreadyClaimed: Boolean = false) {
        if (finished && !alreadyClaimed) return
        finished = true
        if (::eventRouter.isInitialized) eventRouter.stop()
        checkoutScope.cancel()
        CheckoutSessionRegistry.remove(sessionId)
        destroyEducationCarousel()
        val completed = result is GlomoPayResult.Success || result is GlomoPayResult.JourneyCompleted
        setResult(if (completed) RESULT_OK else RESULT_CANCELED)
        finish()
    }

    private fun navigationProperties(url: String): Map<String, Any?> =
        mapOf("url" to AnalyticsSanitizer.navigationUrl(url))

    private fun onFlowNavigationBlocked(url: String) {
        val scheme = url.substringBefore(':', "").lowercase()
        analytics.track("Non-HTTP Navigation", mapOf("scheme" to scheme, "webview_type" to "flow"))
        listener?.onEvent("glomo_android_sdk.flow.navigation_blocked", mapOf("scheme" to scheme))
    }

    private fun bankNavigationProperties(url: String): Map<String, Any?> =
        mapOf("url" to AnalyticsSanitizer.bankRedirectUrl(url))

    private fun trackWebViewError(error: ConnectionError, webViewType: String) {
        if (error.type != ConnectionErrorType.WEB_RESOURCE_ERROR &&
            error.type != ConnectionErrorType.UNKNOWN
        ) {
            return
        }
        analytics.track(AnalyticsEvents.WEBVIEW_ERROR, mapOf(
            "error_type" to error.type.name.lowercase(),
            "error_message" to error.message,
            "webview_type" to webViewType,
        ))
    }

    private fun trackSdkError(error: SdkError) {
        val serializedError = JSONObject(mapOf(
            "type" to error.type.toString(),
            "message" to AnalyticsSanitizer.text(error.message, 500),
        )).toString()
        analytics.track(AnalyticsEvents.SDK_ERROR, mapOf(
            "error_count" to 1,
            "errors" to "[$serializedError]",
        ))
    }

    private fun validationFailureReason(publicKeyError: String?, identifierError: String): String = when {
        publicKeyError != null -> "invalid_public_key"
        identifierError.contains("both", ignoreCase = true) -> "both_ids_provided"
        identifierError.contains("subscription", ignoreCase = true) -> "invalid_subscription_id"
        else -> "missing_order_id"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun configFromIntent(intent: Intent): GlomoPayConfig = GlomoPayConfig(
        publicKey = requireNotNull(intent.getStringExtra(EXTRA_PUBLIC_KEY)),
        orderId = intent.getStringExtra(EXTRA_ORDER_ID),
        subscriptionId = intent.getStringExtra(EXTRA_SUBSCRIPTION_ID),
        server = intent.getStringExtra(EXTRA_SERVER),
    )

    public companion object {
        public const val EXTRA_PUBLIC_KEY: String = "com.glomopay.sdk.android.PUBLIC_KEY"
        public const val EXTRA_ORDER_ID: String = "com.glomopay.sdk.android.ORDER_ID"
        public const val EXTRA_SUBSCRIPTION_ID: String = "com.glomopay.sdk.android.SUBSCRIPTION_ID"
        public const val EXTRA_SERVER: String = "com.glomopay.sdk.android.SERVER"
        public const val EXTRA_ORDER_TYPE: String = "com.glomopay.sdk.android.ORDER_TYPE"
        public const val EXTRA_SESSION_ID: String = "com.glomopay.sdk.android.SESSION_ID"
        private val SUPPORTED_ORDER_TYPES = setOf("auto", "standard", "lrs")
        private const val RENDER_TIMEOUT_MS = 15_000L
        private val OPEN_TIMEOUT_MS =
            GlomoPayApiClient.totalRequestTimeoutMs + RENDER_TIMEOUT_MS + 5_000L

        public fun createIntent(
            context: Context,
            config: GlomoPayConfig,
            orderType: String = "auto",
        ): Intent = Intent(context, GlomoPayCheckoutActivity::class.java).apply {
            putExtra(EXTRA_PUBLIC_KEY, config.publicKey)
            putExtra(EXTRA_ORDER_ID, config.orderId)
            putExtra(EXTRA_SUBSCRIPTION_ID, config.subscriptionId)
            putExtra(EXTRA_SERVER, config.server)
            putExtra(EXTRA_ORDER_TYPE, orderType)
        }
    }
}
