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
     * Both are set where both are supported, never one or the other.
     * `setAlgorithmicDarkeningAllowed` governs hosts targeting SDK 33 and above and is ignored
     * below that, where the deprecated `setForceDark` is what the platform still reads. Returning
     * after the first would leave every targetSdk < 33 host darkened by a WebView modern enough to
     * support the replacement - which is most of them. `setForceDark` being deprecated is the
     * point: minSdk 24 means those hosts are in the field.
     */
    @Suppress("DEPRECATION")
    private fun disableAlgorithmicDarkening(webView: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, false)
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
