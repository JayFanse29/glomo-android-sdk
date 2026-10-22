package com.glomopay.sdk.android.analytics

import android.content.Context
import com.glomopay.sdk.android.ConfigManager
import com.glomopay.sdk.android.GlomoPayConfig
import com.glomopay.sdk.android.R
import com.glomopay.sdk.android.monitoring.NoOpSdkErrorReporter
import com.glomopay.sdk.android.monitoring.SdkErrorReporter
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors

internal const val MIXPANEL_TRACK_ENDPOINT = "https://api.mixpanel.com/track?ip=1"

internal fun interface AnalyticsTransport {
    fun send(event: AnalyticsEvent)
}

internal class MixpanelAnalyticsTracker(
    private val config: GlomoPayConfig,
    private val sessionId: String,
    private val sdkVersion: String,
    initialFlowType: String,
    private val transport: AnalyticsTransport,
    private val executor: Executor = DEFAULT_EXECUTOR,
    private val clock: () -> Long = System::currentTimeMillis,
    private val deviceProperties: () -> Map<String, Any?>,
    private val errorReporter: SdkErrorReporter = NoOpSdkErrorReporter,
) : AnalyticsTracker {
    @Volatile private var flowType: String = initialFlowType
    @Volatile private var checkoutUrl: String? = null
    private val timestampFormatter = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        Locale.US,
    ).apply { timeZone = INDIA_TIME_ZONE }

    override fun updateFlowType(flowType: String) {
        this.flowType = flowType
    }

    override fun updateCheckoutUrl(url: String) {
        checkoutUrl = url
    }

    override fun track(event: String, properties: Map<String, Any?>) {
        errorReporter.addBreadcrumb("analytics", event, mapOf("event_name" to event))
        val now = clock()
        val eventProperties = commonProperties(now) + properties
        val analyticsEvent = AnalyticsEvent(event, AnalyticsSanitizer.properties(eventProperties))
        executor.execute {
            runCatching { transport.send(analyticsEvent) }
                .onFailure {
                    GlomoPayLogger.error("Analytics event delivery failed: $event", it)
                    errorReporter.capture(
                        operation = "mixpanel_delivery",
                        error = it,
                        context = mapOf("event_name" to event),
                    )
                }
        }
    }

    private fun commonProperties(now: Long): Map<String, Any?> = buildMap {
        put("sdk_version", sdkVersion)
        put("sdk_source", "glomo-android-sdk")
        put("platform", "android")
        put("surface", "android-sdk")
        putAll(deviceProperties())
        put("flow_type", flowType)
        put("order_id", config.orderId)
        put("subscription_id", config.subscriptionId)
        put("public_key", config.publicKey)
        put("checkout_url", checkoutUrl)
        put("dev_mode", com.glomopay.sdk.android.BuildConfig.GLOMO_INTERNAL_BUILD)
        put("mock_mode", ConfigManager.isTestOrMock(config.publicKey))
        put("time", now)
        put("timestamp", isoTimestamp(now))
        put("session_id", sessionId)
        put("journey_id", sessionId)
        put("\$device_id", sessionId)
        put("\$insert_id", UUID.randomUUID().toString())
        put("distinct_id", "\$device:$sessionId")
    }

    private fun isoTimestamp(timeMillis: Long): String = synchronized(timestampFormatter) {
        timestampFormatter.format(Date(timeMillis))
    }

    private companion object {
        val INDIA_TIME_ZONE: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")
        val DEFAULT_EXECUTOR: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "GlomoPay-Mixpanel").apply { isDaemon = true }
        }
    }
}

internal class MixpanelHttpTransport(
    private val token: String,
    private val endpoint: String = MIXPANEL_TRACK_ENDPOINT,
) : AnalyticsTransport {
    override fun send(event: AnalyticsEvent) {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/plain")
        }

        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use {
                it.write(MixpanelEventEncoder.encode(event, token))
            }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            validateMixpanelResponse(status, response)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000
    }
}

internal fun validateMixpanelResponse(status: Int, response: String) {
    if (status !in 200..299) throw IllegalStateException("Mixpanel returned HTTP $status")
    if (response.trim() != "1") throw IllegalStateException("Mixpanel rejected the analytics event")
}

internal object MixpanelEventEncoder {
    fun encode(event: AnalyticsEvent, token: String): String {
        val properties = JSONObject().apply {
            event.properties.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
            put("token", token)
        }
        return JSONArray().put(JSONObject().put("event", event.name).put("properties", properties)).toString()
    }
}

internal object AnalyticsFactory {
    fun create(
        context: Context,
        config: GlomoPayConfig,
        sessionId: String,
        flowType: String,
        errorReporter: SdkErrorReporter,
    ): AnalyticsTracker {
        val token = context.getString(R.string.glomopay_mixpanel_token)
        if (token.isBlank()) return NoOpAnalyticsTracker
        return MixpanelAnalyticsTracker(
            config = config,
            sessionId = sessionId,
            sdkVersion = context.getString(R.string.glomopay_sdk_version),
            initialFlowType = flowType,
            transport = MixpanelHttpTransport(token),
            deviceProperties = { AndroidAnalyticsProperties.collect(context.applicationContext) },
            errorReporter = errorReporter,
        )
    }
}
