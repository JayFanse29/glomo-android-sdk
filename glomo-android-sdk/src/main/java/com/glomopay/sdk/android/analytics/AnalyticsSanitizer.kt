package com.glomopay.sdk.android.analytics

import java.net.URI

internal object AnalyticsSanitizer {
    private val email = Regex("([A-Z0-9._%+-])[A-Z0-9._%+-]*@([A-Z0-9.-]+\\.[A-Z]{2,})", RegexOption.IGNORE_CASE)
    private val cardCandidate = Regex("(?<![A-Za-z0-9_])\\d(?:[ -]?\\d){12,18}(?![A-Za-z0-9_])")
    private val taxPan = Regex("(?<![A-Za-z0-9_])[A-Z]{5}[0-9]{4}[A-Z](?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)
    private val phoneLike = Regex("(?<![A-Za-z0-9_])\\d(?:[ -]?\\d){9,11}(?![A-Za-z0-9_])")
    private val passport = Regex("(?<![A-Za-z0-9_])[A-Z][0-9]{7}(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)
    private val voterId = Regex("(?<![A-Za-z0-9_])[A-Z]{3}[0-9]{7}(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)
    private val maskedKeyTokens = setOf(
        "email", "phone", "mobile", "pan", "aadhaar", "name", "account", "card", "address", "dob", "token", "otp",
        "passport", "voter",
    )
    private val exemptKeys = setOf(
        "page_name", "cta_name", "step_name", "control_name", "otp_channel", "status", "failure_reason",
        "error_code", "trigger", "error_detail", "action_name", "webview_type", "permission", "request_source",
        "last_step", "page_event", "payment_event", "api_path",
        "integration", "platform", "sdk_version", "public_key", "dev_mode", "key_mode", "resource_id", "journey",
        "journey_id",
    )
    private val identifierKeys = setOf(
        "journey_id",
        "\$device_id",
        "\$insert_id",
        "distinct_id",
        "resource_id",
    )

    fun properties(input: Map<String, Any?>): Map<String, Any?> = buildMap {
        input.forEach { (key, value) ->
            when {
                value == null -> put(key, null)
                value is Boolean -> put(key, value)
                value !is String && value !is Number -> Unit
                key in identifierKeys -> put(key, value.toString().take(MAX_STRING_LENGTH))
                isMaskedKey(key) -> put(key, maskByKey(key, value.toString()))
                value is String -> put(key, maskValue(value).take(MAX_STRING_LENGTH))
                else -> put(key, value)
            }
        }
    }

    /** Bank URLs may contain PII anywhere after the host, so only the HTTPS origin is retained. */
    fun bankRedirectUrl(value: String): String? = runCatching {
        val uri = URI(value)
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null
        URI("https", null, uri.host.lowercase(), -1, null, null, null).toString()
    }.getOrNull()

    private fun isMaskedKey(key: String): Boolean {
        if (key.startsWith("$") || key in exemptKeys) return false
        return keyTokens(key).any { it in maskedKeyTokens }
    }

    private fun maskByKey(key: String, value: String): String =
        if ("account" in keyTokens(key)) "••••${value.takeLast(4)}" else "•••"

    private fun keyTokens(key: String): List<String> = key.lowercase().split('_')

    private fun maskValue(value: String): String {
        val withoutEmails = email.replace(value) { "${it.groupValues[1]}•••@${it.groupValues[2]}" }
        val withoutCards = cardCandidate.replace(withoutEmails) { match ->
            val digits = match.value.filter(Char::isDigit)
            if (isLuhnValid(digits)) "XXXX-XXXX-XXXX-${digits.takeLast(4)}" else match.value
        }
        val withoutTaxPans = taxPan.replace(withoutCards) { "XXXXXX${it.value.takeLast(4).uppercase()}" }
        val withoutPhones = phoneLike.replace(withoutTaxPans) { match ->
            "••••${match.value.filter(Char::isDigit).takeLast(4)}"
        }
        return listOf(passport, voterId).fold(withoutPhones) { result, pattern -> result.replace(pattern, "•••") }
    }

    private fun isLuhnValid(digits: String): Boolean {
        val sum = digits.reversed().mapIndexed { index, char ->
            val digit = char - '0'
            if (index % 2 == 1) (digit * 2).let { if (it > 9) it - 9 else it } else digit
        }.sum()
        return sum % 10 == 0
    }

    internal const val MAX_STRING_LENGTH = 1_000
}
