package com.glomopay.sdk.android.monitoring

internal object SentryScrubber {
    private val email = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
    private val bareNumeric = Regex("(?<![A-Za-z0-9_])(?:\\d[\\s-]*){6,}(?![A-Za-z0-9_])")
    private val pan = Regex("(?<![A-Za-z0-9_])[A-Z]{5}[0-9]{4}[A-Z](?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)
    private val passport = Regex("(?<![A-Za-z0-9_])[A-Z][0-9]{7}(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)
    private val voterId = Regex("(?<![A-Za-z0-9_])[A-Z]{3}[0-9]{7}(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)
    private val blockedKeys = Regex("(email|phone|mobile|customer_name|card|pan|account|aadhaar|passport|voter|kyc)", RegexOption.IGNORE_CASE)

    fun properties(input: Map<String, Any?>): Map<String, Any?> = buildMap {
        input.forEach { (key, value) ->
            if (!blockedKeys.containsMatchIn(key)) {
                if (value == null) put(key, null) else sanitizeValue(value)?.let { put(key, it) }
            }
        }
    }

    fun text(value: String, limit: Int): String = redact(value).take(limit)

    private fun sanitizeValue(value: Any?): Any? = when (value) {
        null, is Boolean, is Number -> value
        is String -> redact(value).take(MAX_STRING_LENGTH)
        else -> redact(value.toString()).take(MAX_STRING_LENGTH)
    }

    private fun redact(value: String): String = listOf(email, bareNumeric, pan, passport, voterId)
        .fold(value) { result, pattern -> result.replace(pattern, "[REDACTED]") }

    private const val MAX_STRING_LENGTH = 1_000
}
