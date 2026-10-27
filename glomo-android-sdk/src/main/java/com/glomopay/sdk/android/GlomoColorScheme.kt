package com.glomopay.sdk.android

import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import java.net.URI

/**
 * The colour scheme the host app asks the checkout page to present.
 *
 * A request, not a switch: the page decides, against the merchant's own dashboard settings, so
 * [DARK] does not guarantee a dark checkout.
 *
 * Named with the `Glomo` prefix because `ColorScheme` is Compose Material's and already means a
 * palette in most host apps.
 */
public enum class GlomoColorScheme {
    /**
     * Follow the host app's appearance. The default.
     *
     * Three sources, in order: `AppCompatDelegate.getDefaultNightMode()` where the host has set
     * one, then the host theme's `isLightTheme`, then the checkout activity's [Configuration].
     *
     * The middle one is what makes a light-only app on a dark device get a light checkout. The
     * delegate alone is not enough: its default is `MODE_NIGHT_FOLLOW_SYSTEM`, which every host
     * that has never called `setDefaultNightMode` reports, and falling straight through to the
     * configuration from there reads the device rather than the app.
     *
     * What `AUTO` derives from is a per-platform decision and differs across the Glomo SDKs on
     * purpose; only [LIGHT] and [DARK] are contractual.
     */
    AUTO,

    /** Ask for light, whatever the host app is doing. */
    LIGHT,

    /** Ask for dark, whatever the host app is doing. */
    DARK,
}

/**
 * The two values the checkout page ever sees.
 *
 * A separate type from [GlomoColorScheme] so "never send `auto` on the wire" is a compile-time
 * guarantee rather than a comment: this one cannot hold a third value.
 */
internal enum class ResolvedColorScheme(val wireName: String) {
    LIGHT("light"),
    DARK("dark"),
}

/**
 * Reads the night bit out of a [Configuration.uiMode].
 *
 * Takes the raw int rather than a [Configuration] so it is reachable from a JVM unit test - the
 * mask constants are compile-time and inline, the class is not.
 */
internal fun nightModeOf(uiMode: Int): ResolvedColorScheme =
    if (uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) {
        ResolvedColorScheme.DARK
    } else {
        ResolvedColorScheme.LIGHT
    }

/**
 * The host app's appearance, from the most specific source that can answer.
 *
 * [defaultNightMode] is `AppCompatDelegate.getDefaultNightMode()`: process-wide, so it reports the
 * host's choice even though the checkout runs in this SDK's own plain Activity. From API 31 that
 * call also applies a per-app `UiModeManager` override which does reach [uiMode], but below it the
 * SDK's Activity sees only the system value.
 *
 * `MODE_NIGHT_AUTO_BATTERY` and `MODE_NIGHT_FOLLOW_SYSTEM` mean the host has not set one - and
 * `MODE_NIGHT_FOLLOW_SYSTEM` is the *default*, so it is what every host that has never called
 * `setDefaultNightMode` reports. Falling straight from there to [uiMode] reads the device, which
 * hands a light-only app on a dark phone a dark checkout. [hostThemeIsLight] is consulted first
 * for exactly that case: it is `android.R.attr.isLightTheme` resolved against the host's own
 * theme, so a `DayNight` host still tracks the device while a light-only host does not.
 *
 * [uiMode] remains the last resort, for a theme that defines no `isLightTheme` at all.
 *
 * Plain values rather than a Context so the whole chain is reachable from a JVM unit test.
 */
internal fun hostNightMode(
    defaultNightMode: Int,
    hostThemeIsLight: Boolean?,
    uiMode: Int,
): ResolvedColorScheme =
    when (defaultNightMode) {
        AppCompatDelegate.MODE_NIGHT_NO -> ResolvedColorScheme.LIGHT
        AppCompatDelegate.MODE_NIGHT_YES -> ResolvedColorScheme.DARK
        else -> when (hostThemeIsLight) {
            true -> ResolvedColorScheme.LIGHT
            false -> ResolvedColorScheme.DARK
            null -> nightModeOf(uiMode)
        }
    }

/** Resolves the merchant's setting against the host app's current appearance. */
internal fun GlomoColorScheme.resolve(
    defaultNightMode: Int,
    hostThemeIsLight: Boolean?,
    uiMode: Int,
): ResolvedColorScheme =
    when (this) {
        GlomoColorScheme.LIGHT -> ResolvedColorScheme.LIGHT
        GlomoColorScheme.DARK -> ResolvedColorScheme.DARK
        GlomoColorScheme.AUTO -> hostNightMode(defaultNightMode, hostThemeIsLight, uiMode)
    }

/** The query parameter the checkout and carousel pages read at boot. */
internal const val GLOMO_COLOR_SCHEME_PARAM: String = "colorScheme"

/**
 * Returns [url] with [GLOMO_COLOR_SCHEME_PARAM] set to [resolved].
 *
 * An upsert, and the distinction matters: the page strips the parameter with
 * `history.replaceState` once it has read it, so on most reload paths there is nothing to replace
 * and it has to be put back. Replacing in place also stops a reload appending a second copy.
 *
 * Used where a URL is being replayed rather than rebuilt - a Retry has to keep whatever the
 * current URL accumulated, and only the scheme on it is stale.
 *
 * Anything that is not http(s) is returned untouched: a parameter no page can read is worse than
 * none.
 */
internal fun withColorScheme(url: String, resolved: ResolvedColorScheme): String {
    val scheme = runCatching { URI(url) }.getOrNull()?.scheme?.lowercase() ?: return url
    if (scheme != "https" && scheme != "http") return url

    // Split and rejoin rather than re-encode: this replays a URL the SDK already built, and
    // round-tripping its parameters through a decoder would be a chance to change them.
    val fragmentStart = url.indexOf('#')
    val fragment = if (fragmentStart >= 0) url.substring(fragmentStart) else ""
    val withoutFragment = if (fragmentStart >= 0) url.substring(0, fragmentStart) else url
    val base = withoutFragment.substringBefore('?')
    val kept = withoutFragment.substringAfter('?', "")
        .split('&')
        .filter { it.isNotEmpty() && it.substringBefore('=') != GLOMO_COLOR_SCHEME_PARAM }

    val query = (kept + "$GLOMO_COLOR_SCHEME_PARAM=${resolved.wireName}").joinToString("&")
    return "$base?$query$fragment"
}

/**
 * The URL a Retry should load.
 *
 * Replays [currentUrl] with only the scheme refreshed, when it is still on the origin the SDK
 * sent the WebView to. That URL is what the checkout is actually on: it can carry a path or
 * parameters the server added after the first load, and rebuilding from config would drop them
 * without saying so.
 *
 * [rebuild] is the fallback, for when there is nothing to replay or the WebView has wandered off
 * our origin - a bank page is not ours to replay.
 *
 * Extracted from the Activity so the choice is unit-testable; the Activity has no JVM test.
 */
internal fun retryCheckoutUrl(
    currentUrl: String?,
    loadedCheckoutUrl: String?,
    resolved: ResolvedColorScheme,
    rebuild: () -> String,
): String {
    val loadedOrigin = loadedCheckoutUrl?.let(ConfigManager::originOf)
    val replayable = currentUrl?.takeIf {
        val origin = ConfigManager.originOf(it)
        origin != null && origin == loadedOrigin
    }
    return replayable?.let { withColorScheme(it, resolved) } ?: rebuild()
}
