package com.glomopay.sdk.android

import android.content.res.Configuration
import com.glomopay.sdk.android.bridge.GlomoPayInjectionScripts
import com.glomopay.sdk.android.webview.ColorSchemeChangeCollector
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The night bit, as the framework packs it into `Configuration.uiMode`. */
private const val NIGHT_YES = Configuration.UI_MODE_NIGHT_YES
private const val NIGHT_NO = Configuration.UI_MODE_NIGHT_NO

private fun queryParameters(url: String): Map<String, String> =
    URI(url).query.orEmpty().split("&").filter { it.isNotEmpty() }.associate {
        val (key, value) = it.split("=", limit = 2)
        key to value
    }

class ColorSchemeResolutionTest {
    @Test
    fun explicit_schemes_ignore_the_host() {
        assertEquals(ResolvedColorScheme.LIGHT, GlomoColorScheme.LIGHT.resolve(NIGHT_YES))
        assertEquals(ResolvedColorScheme.DARK, GlomoColorScheme.DARK.resolve(NIGHT_NO))
    }

    @Test
    fun auto_follows_the_host_night_mode() {
        assertEquals(ResolvedColorScheme.DARK, GlomoColorScheme.AUTO.resolve(NIGHT_YES))
        assertEquals(ResolvedColorScheme.LIGHT, GlomoColorScheme.AUTO.resolve(NIGHT_NO))
    }

    @Test
    fun an_undefined_night_mode_is_light_not_an_error() {
        // UI_MODE_NIGHT_UNDEFINED, and anything else in the other uiMode bits, must not read as
        // dark - the page would go dark on a device that never asked.
        assertEquals(ResolvedColorScheme.LIGHT, GlomoColorScheme.AUTO.resolve(0))
        assertEquals(
            ResolvedColorScheme.LIGHT,
            GlomoColorScheme.AUTO.resolve(Configuration.UI_MODE_TYPE_CAR),
        )
    }

    @Test
    fun the_other_ui_mode_bits_do_not_disturb_the_night_bit() {
        // A car or desk dock sets UI_MODE_TYPE bits alongside the night bit; only the night mask
        // is ours to read.
        assertEquals(
            ResolvedColorScheme.DARK,
            GlomoColorScheme.AUTO.resolve(Configuration.UI_MODE_TYPE_DESK or NIGHT_YES),
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
    fun it_parks_the_value_before_calling() {
        val script = GlomoPayInjectionScripts.colorScheme(ResolvedColorScheme.DARK)
        val beforeCall = script.substringBefore("glomoSetColorScheme(")
        assertTrue("window.__glomoColorScheme__ = 'dark';" in beforeCall)
    }

    @Test
    fun it_evaluates_to_whether_the_call_took() {
        // Read back by evaluateJavascript, so the applied flag cannot race the page defining its
        // setter a moment later.
        val script = GlomoPayInjectionScripts.colorScheme(ResolvedColorScheme.DARK)
        assertTrue("return applied;" in script)
        assertTrue("applied = true;" in script)
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

class ColorSchemeChangeCollectorTest {
    private var applied: List<String>? = null
    private var unprobed: List<String>? = null
    private var completions = 0

    private fun collector(vararg targets: String) =
        ColorSchemeChangeCollector(targets.toList()) { a, u ->
            applied = a
            unprobed = u
            completions++
        }

    @Test
    fun it_reports_once_every_target_has_answered() {
        val collector = collector("main", "carousel")

        collector.record("main", true)
        assertNull(applied)

        collector.record("carousel", false)
        assertEquals(listOf("main"), applied)
        assertEquals(emptyList(), unprobed)
    }

    @Test
    fun a_page_that_refused_is_a_miss_not_an_unprobed_surface() {
        // The distinction is the whole point: a miss is a web-side slip, unprobed is our own
        // probe failing. Conflating them would let a torn-down WebView read as a site outage.
        val collector = collector("main", "carousel")

        collector.record("main", true)
        collector.record("carousel", false)

        assertEquals(listOf("main"), applied)
        assertEquals(emptyList(), unprobed)
    }

    @Test
    fun a_target_that_never_answered_is_unprobed() {
        val collector = collector("main", "carousel")

        collector.record("main", true)
        collector.timeOut()

        assertEquals(listOf("main"), applied)
        assertEquals(listOf("carousel"), unprobed)
    }

    @Test
    fun a_timeout_after_everything_answered_does_not_report_twice() {
        val collector = collector("main")

        collector.record("main", true)
        collector.timeOut()

        assertEquals(1, completions)
    }

    @Test
    fun a_late_answer_after_reporting_is_ignored() {
        val collector = collector("main", "carousel")

        collector.timeOut()
        collector.record("main", true)

        assertEquals(1, completions)
        assertEquals(emptyList(), applied)
        assertEquals(listOf("main", "carousel"), unprobed)
    }

    @Test
    fun the_report_is_built_from_what_we_asked_not_from_what_arrived() {
        // The flow WebView is never a target - it holds the bank's page. If the report were built
        // from the answers map rather than from the target list, a stray answer would put it in
        // the row and claim we themed a bank page.
        val collector = collector("main")

        collector.record("flow", true)
        collector.record("main", true)

        assertEquals(listOf("main"), applied)
        assertEquals(emptyList(), unprobed)
    }
}
