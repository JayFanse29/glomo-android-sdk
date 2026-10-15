package com.glomopay.sdk.android.webview

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

internal object CheckoutWebViewFactory {
    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    fun create(context: Context): WebView = WebView(context).apply {
        setBackgroundColor(android.graphics.Color.WHITE)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // File uploads return content:// URIs from the Android picker.
            // WebView must be allowed to read those URIs after selection.
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
        }
        disableAlgorithmicDarkening(this)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        // A fresh WebView starts with empty navigation/form state. Shared cookies,
        // WebStorage and the process-wide disk cache belong to the embedding app.
    }

    /**
     * Stops the platform recolouring a page that handles its own colours.
     *
     * The checkout page is told which scheme to present; letting WebView invert it on top gives a
     * page nobody designed. Applied to the bank-flow WebView too, and should be: we send that page
     * no scheme - it is the bank's, not ours - but it must not be algorithmically darkened either.
     *
     * Two APIs for one behaviour, because which is available depends on the WebView provider's
     * version rather than the OS: `ALGORITHMIC_DARKENING` is the modern one, `FORCE_DARK` the
     * legacy. `setForceDark` is deprecated and that is the point - it is the fallback for devices
     * whose WebView predates the replacement, and minSdk 24 means those exist in the field. If
     * neither feature is supported there is nothing to switch off.
     */
    private fun disableAlgorithmicDarkening(webView: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, false)
            return
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(webView.settings, WebSettingsCompat.FORCE_DARK_OFF)
        }
    }

    fun clearSession(webView: WebView) {
        webView.clearHistory()
        webView.clearFormData()
        webView.clearSslPreferences()
        webView.loadUrl("about:blank")
        // CookieManager and WebView's disk cache are process-wide; clearing either
        // would erase or evict merchant/bank state outside this checkout. Android
        // exposes no per-WebView cookie or resource-cache clearing API.
    }
}
