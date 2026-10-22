package com.glomopay.sdk.android.analytics

import java.net.URI

internal object AnalyticsSanitizer {
    private val email = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
    private val bareNumeric = Regex("(?<![A-Za-z0-9_])(?:\\d[\\s-]*){6,}(?![A-Za-z0-9_])")
    private val pan = Regex("(?<![A-Za-z0-9_])[A-Z]{5}[0-9]{4}[A-Z](?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)
    private val passport = Regex("(?<![A-Za-z0-9_])[A-Z][0-9]{7}(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)
    private val voterId = Regex("(?<![A-Za-z0-9_])[A-Z]{3}[0-9]{7}(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)
    private val blockedKeys = Regex("(email|phone|mobile|customer_name|card|pan|account|aadhaar|passport|voter|kyc)", RegexOption.IGNORE_CASE)
    private val isoTimestamp = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}(?:Z|[+-]\\d{2}:\\d{2})")
    private val identifierKeys = setOf(
        "distinct_id",
        "\$device_id",
        "journey_id",
        "session_id",
        "\$insert_id",
        "order_id",
        "subscription_id",
        "public_key",
        "sdk_version",
    )

    fun properties(input: Map<String, Any?>): Map<String, Any?> = buildMap {
        input.forEach { (key, value) ->
            if (!blockedKeys.containsMatchIn(key)) {
                when {
                    value == null -> put(key, null)
                    key in identifierKeys && value is String -> put(key, value)
                    key == "timestamp" && value is String && isoTimestamp.matches(value) -> put(key, value)
                    else -> sanitizeValue(value)?.let { put(key, it) }
                }
            }
        }
    }

    fun text(value: String, limit: Int): String = redact(value).take(limit)

    /** Main checkout URLs retain a redacted path but never credentials, query, or fragment data. */
    fun navigationUrl(value: String): String? = runCatching {
        val uri = URI(value)
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
        URI(uri.scheme, null, uri.host, uri.port, sanitizePath(uri.path), null, null).toString()
    }.getOrNull()

    /** Bank URLs may contain PII anywhere after the host, so only the HTTPS origin is retained. */
    fun bankRedirectUrl(value: String): String? = runCatching {
        val uri = URI(value)
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null
        URI("https", null, uri.host.lowercase(), -1, null, null, null).toString()
    }.getOrNull()

    private fun sanitizeValue(value: Any?): Any? = when (value) {
        null, is Boolean, is Number -> value
        is String -> redact(value).take(MAX_STRING_LENGTH)
        else -> redact(value.toString()).take(MAX_STRING_LENGTH)
    }

    private fun redact(value: String): String = listOf(email, bareNumeric, pan, passport, voterId)
        .fold(value) { result, pattern -> result.replace(pattern, "[REDACTED]") }

    private fun sanitizePath(path: String?): String {
        if (path.isNullOrBlank()) return "/"
        return path.split('/').joinToString("/") { segment ->
            if (segment.isBlank()) segment else redact(segment)
        }
    }

    private const val MAX_STRING_LENGTH = 1_000
}
