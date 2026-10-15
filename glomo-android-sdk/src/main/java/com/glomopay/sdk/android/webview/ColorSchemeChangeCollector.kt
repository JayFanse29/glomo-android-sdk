package com.glomopay.sdk.android.webview

/**
 * Collects one answer per WebView after a colour-scheme change, then reports once.
 *
 * Exists as its own class because the three-way outcome is the whole value of the signal and is
 * easy to collapse by accident. A page that never defined `glomoSetColorScheme` is a web-side
 * slip; a WebView that never answered is our own probe failing. Folding the second into the first
 * would let a torn-down WebView read as a site-wide outage.
 *
 * Main-thread only - `evaluateJavascript` callbacks arrive there, and so does [timeOut].
 */
internal class ColorSchemeChangeCollector(
    private val targets: List<String>,
    private val onComplete: (applied: List<String>, unprobed: List<String>) -> Unit,
) {
    private val answers = LinkedHashMap<String, Boolean>()
    private var reported = false

    /**
     * Records one WebView's answer. Completes as soon as every target has answered.
     *
     * An answer from a surface that was never asked is accepted here and dropped by [complete],
     * which reports out of [targets] rather than out of what arrived. One filter, not two.
     */
    fun record(target: String, applied: Boolean) {
        if (reported) return
        answers[target] = applied
        if (answers.keys.containsAll(targets)) complete()
    }

    /** Reports with whatever arrived. Anything missing is unprobed, not a miss. */
    fun timeOut() {
        if (reported) return
        complete()
    }

    private fun complete() {
        reported = true
        onComplete(
            targets.filter { answers[it] == true },
            targets.filter { it !in answers },
        )
    }
}
