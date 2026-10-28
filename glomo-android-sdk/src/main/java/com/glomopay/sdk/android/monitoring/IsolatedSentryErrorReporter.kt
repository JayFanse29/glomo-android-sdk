package com.glomopay.sdk.android.monitoring

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.glomopay.sdk.android.GlomoPayConfig
import com.glomopay.sdk.android.analytics.GlomoPayLogger
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Reports failures Glomo code explicitly captures to Glomo's Sentry project over the envelope
 * endpoint, with no Sentry SDK on the classpath.
 *
 * Events are built from an allowlist: the fields below are the only ones ever sent. There is no
 * user object and no IP address (Sentry derives coarse location at ingest, see [sdk]), no request,
 * server name, module list, thread dump or debug-meta, device/OS context is limited to
 * [SentryContexts], and the original exception message and cause chain never leave the device.
 */
internal class IsolatedSentryErrorReporter(
    private val client: SentryEnvelopeClient,
    private val sdkVersion: String,
    private val sessionId: String,
    initialFlowType: String,
    private val devMode: Boolean,
    orderId: String? = null,
    private val contexts: SentryContexts = SentryContexts(),
    private val clock: () -> Long = System::currentTimeMillis,
) : SdkErrorReporter {
    private val breadcrumbs = ArrayDeque<JSONObject>()
    @Volatile private var flowType = initialFlowType

    // A server-issued `order_` id, sent unredacted: it is the join key to backend logs, and the
    // sanitiser's digit redaction would break it. Sentry caps tag values at 200 characters.
    private val orderIdTag = orderId?.trim()?.takeIf { it.isNotEmpty() }?.take(200)

    override fun updateFlowType(flowType: String) {
        this.flowType = flowType
    }

    override fun addBreadcrumb(category: String, message: String, data: Map<String, Any?>) {
        runCatching {
            val breadcrumb = JSONObject()
                .put("timestamp", SentryEnvelope.isoTimestamp(clock()))
                .put("category", SentryScrubber.text(category, 80))
                .put("message", SentryScrubber.text(message, 200))
                .put("level", "info")
            val safeData = sanitizeContext(data)
            if (safeData.length() > 0) breadcrumb.put("data", safeData)
            synchronized(breadcrumbs) {
                while (breadcrumbs.size >= MAX_BREADCRUMBS) breadcrumbs.removeFirst()
                breadcrumbs.addLast(breadcrumb)
            }
        }.onFailure { GlomoPayLogger.error("Unable to record SDK breadcrumb", it) }
    }

    /**
     * Snapshots what can change after this call (time, flow type, breadcrumbs, the caller's context
     * map) and hands the client a builder. The event JSON is built only if it survives the drop
     * checks, and on the delivery thread: callers include WebView and bridge callbacks on the main
     * thread, and a rate-limited burst must not cost a full event build per discarded event.
     */
    override fun capture(operation: String, error: Throwable, context: Map<String, Any?>) {
        runCatching {
            val snapshot = CaptureSnapshot(
                eventId = UUID.randomUUID().toString().replace("-", ""),
                timestampMillis = clock(),
                flowType = flowType,
                breadcrumbs = synchronized(breadcrumbs) { breadcrumbs.toList() },
                context = context.toMap(),
            )
            client.send(snapshot.eventId) { buildEvent(snapshot, operation, error) }
        }.onFailure { GlomoPayLogger.error("Unable to report SDK failure to Sentry", it) }
    }

    private class CaptureSnapshot(
        val eventId: String,
        val timestampMillis: Long,
        val flowType: String,
        val breadcrumbs: List<JSONObject>,
        val context: Map<String, Any?>,
    )

    private fun buildEvent(snapshot: CaptureSnapshot, operation: String, error: Throwable): JSONObject {
        val tags = JSONObject()
            .put("sdk_source", SDK_NAME)
            .put("operation", SentryScrubber.text(operation, 80))
            .put("flow_type", snapshot.flowType)
            .put("sdk_session_id", sessionId)
            .put("dev_mode", devMode.toString())
            .apply { orderIdTag?.let { put("order_id", it) } }
        val extra = sanitizeContext(snapshot.context).put("session_id", sessionId)
        val crumbs = JSONArray(snapshot.breadcrumbs)
        val (exception, framesDropped) = exception(operation, error)
        if (framesDropped > 0) extra.put("frames_truncated", framesDropped)

        return JSONObject()
            .put("event_id", snapshot.eventId)
            .put("timestamp", SentryEnvelope.isoTimestamp(snapshot.timestampMillis))
            .put("platform", "java")
            .put("level", "error")
            .put("logger", LOGGER)
            .put("release", "$SDK_NAME@$sdkVersion")
            .put("environment", SDK_NAME)
            .put("sdk", sdk())
            .put("tags", tags)
            .put("extra", extra)
            .put("contexts", contexts.toJson())
            .put("exception", JSONObject().put("values", JSONArray().put(exception)))
            .apply { if (crumbs.length() > 0) put("breadcrumbs", JSONObject().put("values", crumbs)) }
    }

    /**
     * `infer_ip: never`, explicitly: Sentry still derives an approximate location (`user.geo`) at
     * ingest but stores no `user.ip_address`. Left unset, the outcome depends on Relay's per-platform
     * default (for `cocoa` that stores the IP), so it is pinned here to match the iOS SDK.
     */
    private fun sdk(): JSONObject = JSONObject()
        .put("name", SDK_NAME)
        .put("version", sdkVersion)
        .put("settings", JSONObject().put("infer_ip", "never"))

    /**
     * The exception is replaced by a synthetic one named after the operation, keeping only the
     * original stack trace. The original message and causes may carry customer data.
     *
     * A trace longer than [MAX_FRAMES] keeps both ends: the innermost frames locate the throw, and
     * the outermost ones locate the SDK entry point, which for a merchant callback sits at the
     * outer end. Returns the exception and the number of frames dropped from the middle.
     */
    private fun exception(operation: String, error: Throwable): Pair<JSONObject, Int> {
        val trace = error.stackTrace
        val kept = if (trace.size <= MAX_FRAMES) {
            trace.asList()
        } else {
            trace.take(INNERMOST_FRAMES) + trace.takeLast(OUTERMOST_FRAMES)
        }
        val frames = JSONArray()
        // Sentry orders frames oldest first; Java orders them innermost first.
        kept.asReversed().forEach { element ->
            frames.put(
                JSONObject()
                    .put("module", element.className)
                    .put("function", element.methodName)
                    .apply {
                        element.fileName?.let { put("filename", it) }
                        if (element.lineNumber >= 0) put("lineno", element.lineNumber)
                        if (element.isNativeMethod) put("native", true)
                    }
                    .put("in_app", element.className.startsWith(IN_APP_PACKAGE)),
            )
        }
        val exception = JSONObject()
            .put("type", "RuntimeException")
            .put("module", "java.lang")
            .put("value", "$operation failed (${error.javaClass.simpleName})")
            .put("mechanism", JSONObject().put("type", "generic").put("handled", true))
            .apply { if (frames.length() > 0) put("stacktrace", JSONObject().put("frames", frames)) }
        return exception to (trace.size - kept.size)
    }

    private fun sanitizeContext(context: Map<String, Any?>): JSONObject = JSONObject().apply {
        SentryScrubber.properties(context).forEach { (key, value) ->
            if (key in ALLOWED_CONTEXT_KEYS && value != null) put(key, jsonValue(value))
        }
    }

    /** org.json rejects non-finite numbers; send those as text rather than lose the event. */
    private fun jsonValue(value: Any): Any = when (value) {
        is Double -> if (value.isFinite()) value else value.toString()
        is Float -> if (value.isFinite()) value else value.toString()
        is Boolean, is Number, is String -> value
        else -> value.toString()
    }

    private companion object {
        const val SDK_NAME = "glomo-android-sdk"
        const val LOGGER = "com.glomopay.sdk.android"
        const val IN_APP_PACKAGE = "com.glomopay.sdk.android"
        const val MAX_BREADCRUMBS = 30
        const val MAX_FRAMES = 100
        const val INNERMOST_FRAMES = 80
        const val OUTERMOST_FRAMES = MAX_FRAMES - INNERMOST_FRAMES
        val ALLOWED_CONTEXT_KEYS = setOf(
            "event_name",
            "error_type",
            "status_code",
            "webview_type",
            "source",
            "fallback_type",
        )
    }
}

internal object SdkErrorReporterFactory {
    @VisibleForTesting
    internal var reporterFactory: ((sessionId: String, flowType: String) -> SdkErrorReporter)? = null

    fun create(
        context: Context,
        config: GlomoPayConfig,
        sessionId: String,
        flowType: String,
    ): SdkErrorReporter = reporterFactory?.invoke(sessionId, flowType)
        ?: create(AndroidReporterEnvironment(context), config, sessionId, flowType)

    /**
     * A missing or malformed DSN, or a missing SDK version resource, yields the no-op reporter. A
     * failed host-app version lookup only omits `contexts.app`. Never throws.
     */
    fun create(
        environment: ReporterEnvironment,
        config: GlomoPayConfig,
        sessionId: String,
        flowType: String,
        devMode: Boolean = com.glomopay.sdk.android.BuildConfig.GLOMO_INTERNAL_BUILD,
    ): SdkErrorReporter = runCatching {
        val dsn = SentryDsn.parse(environment.sentryDsn()) ?: return NoOpSdkErrorReporter
        val sdkVersion = environment.sdkVersion()
        val bundle = SentryClientHolder.get(dsn, sdkVersion, environment)
        IsolatedSentryErrorReporter(
            client = bundle.client,
            sdkVersion = sdkVersion,
            sessionId = sessionId,
            initialFlowType = flowType,
            devMode = devMode,
            orderId = config.orderId,
            contexts = bundle.contexts,
        )
    }.getOrElse {
        GlomoPayLogger.error("Unable to initialize SDK error reporting", it)
        NoOpSdkErrorReporter
    }
}

private class SentryReporterBundle(val client: SentryEnvelopeClient, @Volatile var contexts: SentryContexts)

/**
 * One client per DSN for the process, so its delivery thread, queue bound, rate-limit state and
 * drop count are shared by every checkout rather than reset per session; Sentry counts limits per
 * project.
 *
 * Contexts are cached alongside it. The Build fields never change, but the host-app version lookup
 * can fail transiently (PackageManager unavailable early in startup, a RemoteException), so while
 * either app field is still missing it is retried on each later factory call and filled in once it
 * succeeds.
 */
private object SentryClientHolder {
    private val bundles = ConcurrentHashMap<String, SentryReporterBundle>()

    fun get(dsn: SentryDsn, sdkVersion: String, environment: ReporterEnvironment): SentryReporterBundle {
        var created = false
        val bundle = bundles.computeIfAbsent(dsn.value) {
            created = true
            SentryReporterBundle(
                client = SentryEnvelopeClient(dsn = dsn, clientName = "glomo-android-sdk/$sdkVersion"),
                contexts = runCatching { SentryContexts.collect(environment) }.getOrDefault(SentryContexts()),
            )
        }
        val current = bundle.contexts
        if (!created && (current.appVersion == null || current.appBuild == null)) {
            runCatching { environment.hostAppVersion() }.getOrNull()?.let { app ->
                bundle.contexts = current.copy(
                    appVersion = app.versionName ?: current.appVersion,
                    appBuild = app.versionCode ?: current.appBuild,
                )
            }
        }
        return bundle
    }
}
