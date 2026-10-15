package com.glomopay.sdk.android

import android.content.res.Configuration

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
     * Follow the host app's night mode. The default.
     *
     * Resolved from the checkout activity's [Configuration], so an app that overrides its own
     * night mode - through `AppCompatDelegate.setDefaultNightMode` or a per-app override - gets
     * what it set rather than what the system is on. That is usually what a merchant means.
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

/** Resolves the merchant's setting against the host app's current night mode. */
internal fun GlomoColorScheme.resolve(uiMode: Int): ResolvedColorScheme = when (this) {
    GlomoColorScheme.LIGHT -> ResolvedColorScheme.LIGHT
    GlomoColorScheme.DARK -> ResolvedColorScheme.DARK
    GlomoColorScheme.AUTO -> nightModeOf(uiMode)
}
