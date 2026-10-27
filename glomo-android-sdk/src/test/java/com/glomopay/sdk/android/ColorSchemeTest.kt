package com.glomopay.sdk.android

import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import com.glomopay.sdk.android.bridge.GlomoPayInjectionScripts
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The night bit, as the framework packs it into `Configuration.uiMode`. */
private const val NIGHT_YES = Configuration.UI_MODE_NIGHT_YES
private const val NIGHT_NO = Configuration.UI_MODE_NIGHT_NO

/** The host has expressed no preference of its own, so the configuration decides. */
private const val HOST_UNSET = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM

/** The host's theme defines no `isLightTheme`, so it cannot answer either. */
private val THEME_UNSET: Boolean? = null

private fun queryParameters(url: String): Map<String, String> =
    URI(url).query.orEmpty().split("&").filter { it.isNotEmpty() }.associate {
        val (key, value) = it.split("=", limit = 2)
        key to value
    }

class ColorSchemeResolutionTest {
    @Test
    fun explicit_schemes_ignore_the_host() {
        assertEquals(ResolvedColorScheme.LIGHT, GlomoColorScheme.LIGHT.resolve(HOST_UNSET, THEME_UNSET, NIGHT_YES))
        assertEquals(ResolvedColorScheme.DARK, GlomoColorScheme.DARK.resolve(HOST_UNSET, THEME_UNSET, NIGHT_NO))
    }

    @Test
    fun auto_follows_the_host_night_mode() {
        assertEquals(ResolvedColorScheme.DARK, GlomoColorScheme.AUTO.resolve(HOST_UNSET, THEME_UNSET, NIGHT_YES))
        assertEquals(ResolvedColorScheme.LIGHT, GlomoColorScheme.AUTO.resolve(HOST_UNSET, THEME_UNSET, NIGHT_NO))
    }

    @Test
    fun an_undefined_night_mode_is_light_not_an_error() {
        // UI_MODE_NIGHT_UNDEFINED, and anything else in the other uiMode bits, must not read as
        // dark - the page would go dark on a device that never asked.
        assertEquals(ResolvedColorScheme.LIGHT, GlomoColorScheme.AUTO.resolve(HOST_UNSET, THEME_UNSET, 0))
        assertEquals(
            ResolvedColorScheme.LIGHT,
            GlomoColorScheme.AUTO.resolve(HOST_UNSET, THEME_UNSET, Configuration.UI_MODE_TYPE_CAR),
        )
    }

    @Test
    fun the_other_ui_mode_bits_do_not_disturb_the_night_bit() {
        // A car or desk dock sets UI_MODE_TYPE bits alongside the night bit; only the night mask
        // is ours to read.
        assertEquals(
            ResolvedColorScheme.DARK,
            GlomoColorScheme.AUTO.resolve(HOST_UNSET, THEME_UNSET, Configuration.UI_MODE_TYPE_DESK or NIGHT_YES),
        )
    }

    @Test
    fun a_light_only_app_on_a_dark_device_gets_a_light_checkout() {
        // The case the activity's own uiMode gets wrong below API 31: the host has called
        // AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_NO), the system is dark, and the SDK
        // runs in a plain Activity that only sees the system value.
        assertEquals(
            ResolvedColorScheme.LIGHT,
            GlomoColorScheme.AUTO.resolve(AppCompatDelegate.MODE_NIGHT_NO, THEME_UNSET, NIGHT_YES),
        )
    }

    @Test
    fun a_dark_only_app_on_a_light_device_gets_a_dark_checkout() {
        assertEquals(
            ResolvedColorScheme.DARK,
            GlomoColorScheme.AUTO.resolve(AppCompatDelegate.MODE_NIGHT_YES, THEME_UNSET, NIGHT_NO),
        )
    }

    @Test
    fun a_host_with_no_preference_falls_back_to_the_configuration() {
        // FOLLOW_SYSTEM and AUTO_BATTERY both mean the host has not picked a side. On API 31+ the
        // per-app override makes uiMode authoritative anyway; below it this is the best available.
        for (mode in listOf(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY,
            AppCompatDelegate.MODE_NIGHT_UNSPECIFIED,
        )) {
            assertEquals(ResolvedColorScheme.DARK, GlomoColorScheme.AUTO.resolve(mode, THEME_UNSET, NIGHT_YES))
            assertEquals(ResolvedColorScheme.LIGHT, GlomoColorScheme.AUTO.resolve(mode, THEME_UNSET, NIGHT_NO))
        }
    }

    @Test
    fun an_explicit_scheme_ignores_the_host_night_mode_too() {
        assertEquals(
            ResolvedColorScheme.LIGHT,
            GlomoColorScheme.LIGHT.resolve(AppCompatDelegate.MODE_NIGHT_YES, THEME_UNSET, NIGHT_YES),
        )
        assertEquals(
            ResolvedColorScheme.DARK,
            GlomoColorScheme.DARK.resolve(AppCompatDelegate.MODE_NIGHT_NO, THEME_UNSET, NIGHT_NO),
        )
    }

    @Test
    fun a_light_only_app_that_never_set_a_night_mode_still_gets_a_light_checkout() {
        // The bug this chain exists for. MODE_NIGHT_FOLLOW_SYSTEM is the *default*, so a host
        // that has never called setDefaultNightMode reports it - and falling straight through to
        // the configuration then reads the device, handing a light-only app a dark checkout.
        // The host theme's isLightTheme is what separates the two.
        assertEquals(
            ResolvedColorScheme.LIGHT,
            GlomoColorScheme.AUTO.resolve(HOST_UNSET, true, NIGHT_YES),
        )
    }

    @Test
    fun a_dark_only_app_on_a_light_device_is_the_same_case_inverted() {
        assertEquals(
            ResolvedColorScheme.DARK,
            GlomoColorScheme.AUTO.resolve(HOST_UNSET, false, NIGHT_NO),
        )
    }

    @Test
    fun a_day_night_host_still_tracks_the_device() {
        // A DayNight theme resolves isLightTheme per configuration, so it reports the device on
        // its own. Following the theme must not cost us that.
        assertEquals(
            ResolvedColorScheme.DARK,
            GlomoColorScheme.AUTO.resolve(HOST_UNSET, false, NIGHT_YES),
        )
        assertEquals(
            ResolvedColorScheme.LIGHT,
            GlomoColorScheme.AUTO.resolve(HOST_UNSET, true, NIGHT_NO),
        )
    }

    @Test
    fun an_explicit_default_night_mode_outranks_the_theme() {
        // setDefaultNightMode is the host saying it outright; the theme is an inference from it.
        assertEquals(
            ResolvedColorScheme.DARK,
            GlomoColorScheme.AUTO.resolve(AppCompatDelegate.MODE_NIGHT_YES, true, NIGHT_NO),
        )
        assertEquals(
            ResolvedColorScheme.LIGHT,
            GlomoColorScheme.AUTO.resolve(AppCompatDelegate.MODE_NIGHT_NO, false, NIGHT_YES),
        )
    }

    @Test
    fun the_wire_vocabulary_is_exactly_light_and_dark() {
        assertEquals("light", ResolvedColorScheme.LIGHT.wireName)
        assertEquals("dark", ResolvedColorScheme.DARK.wireName)
    }

    @Test
    fun the_default_is_auto() {
        assertEquals(GlomoColorScheme.AUTO, GlomoPayConfig("live_key", orderId = "order_1").appColorScheme)
    }
}

class ColorSchemeUrlTest {
    private val config = GlomoPayConfig("live_key", orderId = "order_1")

    @Test
    fun the_resolved_scheme_reaches_the_checkout_url() {
        assertEquals(
            "dark",
            queryParameters(
                ConfigManager.getCheckoutUrl(config, "standard", ResolvedColorScheme.DARK),
            )["colorScheme"],
        )
        assertEquals(
            "light",
            queryParameters(
                ConfigManager.getCheckoutUrl(config, "lrs", ResolvedColorScheme.LIGHT),
            )["colorScheme"],
        )
    }

    @Test
    fun the_carousel_takes_it_on_the_same_param() {
        assertEquals(
            "dark",
            queryParameters(ConfigManager.getCarouselUrl(config, ResolvedColorScheme.DARK))["colorScheme"],
        )
    }

    @Test
    fun it_is_omitted_when_unresolved_rather_than_sent_empty() {
        assertFalse("colorScheme" in queryParameters(ConfigManager.getCheckoutUrl(config)))
        assertFalse("colorScheme" in queryParameters(ConfigManager.getCarouselUrl(config)))
    }

    @Test
    fun it_does_not_disturb_the_params_the_page_already_reads() {
        val parameters = queryParameters(
            ConfigManager.getCheckoutUrl(config, "standard", ResolvedColorScheme.DARK),
        )
        assertEquals("order_1", parameters["orderId"])
        assertEquals("live_key", parameters["publicKey"])
        assertEquals("live", parameters["mode"])
        assertEquals("dark", parameters["colorScheme"])
    }
}

class ColorSchemeInjectionTest {
    @Test
    fun it_guards_the_setter_not_window() {
        // `window` always exists in a WebView; the setter is what is absent during load or on an
        // older merchant deploy, so `window?.` would throw where this skips.
        val script = GlomoPayInjectionScripts.colorScheme(ResolvedColorScheme.DARK)
        assertTrue("typeof window.glomoSetColorScheme === 'function'" in script)
        assertFalse("window?." in script)
    }

    @Test
    fun it_parks_the_value_before_calling_the_setter() {
        // The page seeds from the global on mount, which is what stops a scheme pushed before
        // the setter existed being lost. Before, not after: a setter already present runs
        // synchronously and would read a global not yet written.
        val script = GlomoPayInjectionScripts.colorScheme(ResolvedColorScheme.DARK)
        val parked = script.indexOf("window.__glomoColorScheme__ = 'dark'")
        val called = script.indexOf("glomoSetColorScheme('dark')")
        assertTrue(parked >= 0)
        assertTrue(called >= 0)
        assertTrue(parked < called)
    }

    @Test
    fun it_reads_nothing_back() {
        // A flag that is true whenever the setter returned says nothing about whether the page
        // changed - the page weighs the request against the merchant's dashboard settings.
        assertFalse("return" in GlomoPayInjectionScripts.colorScheme(ResolvedColorScheme.DARK))
    }

    @Test
    fun it_emits_the_wire_vocabulary() {
        assertTrue("'light'" in GlomoPayInjectionScripts.colorScheme(ResolvedColorScheme.LIGHT))
        assertTrue("'dark'" in GlomoPayInjectionScripts.colorScheme(ResolvedColorScheme.DARK))
    }

    @Test
    fun it_has_no_run_once_guard_unlike_the_injections_around_it() {
        // Re-run on every page load and every change; a guard would pin the page to whatever it
        // saw first.
        assertFalse("Injected__" in GlomoPayInjectionScripts.colorScheme(ResolvedColorScheme.DARK))
    }

    @Test
    fun it_cannot_throw_into_the_page() {
        assertTrue("catch (e) {}" in GlomoPayInjectionScripts.colorScheme(ResolvedColorScheme.DARK))
    }
}

class ColorSchemeOriginTest {
    @Test
    fun an_origin_is_scheme_host_and_port() {
        assertEquals("https://checkout.glomopay.com", ConfigManager.originOf("https://checkout.glomopay.com/?a=1"))
        assertEquals("http://localhost:4444", ConfigManager.originOf("http://localhost:4444/x"))
    }

    @Test
    fun a_different_host_is_a_different_origin() {
        // The gate on re-applying a scheme after a page load: a bank page reached by redirect is
        // not ours to theme.
        assertFalse(
            ConfigManager.originOf("https://checkout.glomopay.com/") ==
                ConfigManager.originOf("https://acs.examplebank.com/3ds"),
        )
    }

    @Test
    fun a_non_http_url_has_no_origin_rather_than_a_fabricated_one() {
        assertNull(ConfigManager.originOf("about:blank"))
        assertNull(ConfigManager.originOf("not a url"))
    }
}

class ColorSchemeUrlUpsertTest {
    private val checkout = "https://checkout.glomopay.com/?orderId=order_1&publicKey=live_k"

    @Test
    fun it_adds_the_param_when_the_page_has_stripped_it() {
        // The page removes it with history.replaceState once read, so on most reload paths there
        // is nothing to replace and it has to be put back.
        val stamped = withColorScheme(checkout, ResolvedColorScheme.DARK)
        assertEquals("dark", queryParameters(stamped)["colorScheme"])
    }

    @Test
    fun it_replaces_a_stale_value_rather_than_appending_a_second() {
        val once = withColorScheme(checkout, ResolvedColorScheme.LIGHT)
        val twice = withColorScheme(once, ResolvedColorScheme.DARK)
        assertEquals(1, Regex("colorScheme=").findAll(twice).count())
        assertEquals("dark", queryParameters(twice)["colorScheme"])
    }

    @Test
    fun it_keeps_every_other_param_as_it_found_them() {
        val params = queryParameters(withColorScheme(checkout, ResolvedColorScheme.DARK))
        assertEquals("order_1", params["orderId"])
        assertEquals("live_k", params["publicKey"])
    }

    @Test
    fun it_keeps_a_fragment_behind_the_query() {
        val stamped = withColorScheme("$checkout#step2", ResolvedColorScheme.DARK)
        assertTrue(stamped.endsWith("#step2"))
        assertTrue("colorScheme=dark" in stamped.substringBefore('#'))
    }

    @Test
    fun it_adds_a_query_to_a_url_that_had_none() {
        val stamped = withColorScheme("https://checkout.glomopay.com/", ResolvedColorScheme.LIGHT)
        assertEquals("https://checkout.glomopay.com/?colorScheme=light", stamped)
    }

    @Test
    fun it_leaves_a_non_http_url_alone() {
        // A parameter no page can read is worse than none.
        val mailto = "mailto:support@glomo.one"
        assertEquals(mailto, withColorScheme(mailto, ResolvedColorScheme.DARK))
    }
}

class RetryCheckoutUrlTest {
    private val loaded = "https://checkout.glomopay.com/?orderId=order_1&colorScheme=light"
    private val rebuilt = "https://checkout.glomopay.com/?orderId=order_1&colorScheme=dark"

    @Test
    fun a_retry_after_a_change_carries_the_new_scheme() {
        val url = retryCheckoutUrl(loaded, loaded, ResolvedColorScheme.DARK) { rebuilt }
        assertEquals("dark", queryParameters(url)["colorScheme"])
        assertEquals(1, Regex("colorScheme=").findAll(url).count())
    }

    @Test
    fun it_replays_what_the_page_navigated_to_rather_than_rebuilding() {
        // The server can move the checkout to another path or add parameters after the first
        // load. Rebuilding from config would drop all of it without saying so.
        val navigated = "https://checkout.glomopay.com/step2?orderId=order_1&session=abc"
        val url = retryCheckoutUrl(navigated, loaded, ResolvedColorScheme.DARK) { rebuilt }
        assertTrue(url.startsWith("https://checkout.glomopay.com/step2?"))
        assertEquals("abc", queryParameters(url)["session"])
        assertEquals("dark", queryParameters(url)["colorScheme"])
    }

    @Test
    fun it_rebuilds_when_the_webview_has_wandered_off_our_origin() {
        // A bank page is not ours to replay.
        val url = retryCheckoutUrl("https://bank.example.com/3ds", loaded, ResolvedColorScheme.DARK) { rebuilt }
        assertEquals(rebuilt, url)
    }

    @Test
    fun it_rebuilds_when_there_is_nothing_to_replay() {
        assertEquals(rebuilt, retryCheckoutUrl(null, loaded, ResolvedColorScheme.DARK) { rebuilt })
    }

    @Test
    fun it_rebuilds_when_the_sdk_never_recorded_what_it_loaded() {
        assertEquals(rebuilt, retryCheckoutUrl(loaded, null, ResolvedColorScheme.DARK) { rebuilt })
    }
}
