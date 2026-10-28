package com.glomopay.sdk.android

import com.glomopay.sdk.android.analytics.AnalyticsSanitizer
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnalyticsSanitizerTest {
    @Test
    fun shared_fixture_cases_are_masked_as_specified() {
        val fixture = JSONObject(
            requireNotNull(javaClass.classLoader?.getResource("analytics-sanitizer-fixture.json")).readText(),
        )
        val cases = fixture.getJSONArray("cases")

        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val key = case.getString("key")
            val sanitized = AnalyticsSanitizer.properties(mapOf(key to fixtureValue(case.get("value"))))

            if (case.optBoolean("absent")) {
                assertFalse(sanitized.containsKey(key), case.getString("name"))
            } else {
                assertTrue(sanitized.containsKey(key), case.getString("name"))
                assertEquals(fixtureValue(case.get("expected")), sanitized[key], case.getString("name"))
            }
        }
    }

    @Test
    fun bank_redirect_url_keeps_only_https_scheme_and_lowercase_hostname() {
        val sanitized = AnalyticsSanitizer.bankRedirectUrl(
            "https://user:pass@3DS.IN.Secure.Bank.COM:8443/verify/ABCDE1234F?phone=9876543210#otp",
        )

        assertEquals("https://3ds.in.secure.bank.com", sanitized)
    }

    @Test
    fun bank_redirect_url_rejects_non_https_or_malformed_urls() {
        assertEquals(null, AnalyticsSanitizer.bankRedirectUrl("http://bank.example/otp"))
        assertEquals(null, AnalyticsSanitizer.bankRedirectUrl("not-a-url"))
    }

    @Test
    fun random_uuid_identifiers_pass_through_unchanged() {
        repeat(1_000) {
            val id = UUID.randomUUID().toString()
            val input = mapOf(
                "journey_id" to id,
                "\$device_id" to id,
                "distinct_id" to "\$device:$id",
                "resource_id" to "order_$id",
                "\$insert_id" to UUID.randomUUID().toString(),
            )

            assertEquals(input, AnalyticsSanitizer.properties(input))
        }
    }

    @Test
    fun identifier_values_are_capped_at_the_max_string_length() {
        val sanitized = AnalyticsSanitizer.properties(mapOf("resource_id" to "o".repeat(2_000)))

        assertEquals("o".repeat(AnalyticsSanitizer.MAX_STRING_LENGTH), sanitized["resource_id"])
    }

    @Test
    fun device_and_app_metadata_keep_their_raw_values() {
        val metadata = mapOf(
            "\$app_build_number" to "2026102801",
            "\$app_version_string" to "2.4.0+2026102801",
            "\$app_name" to "Wallet (1234567)",
            "\$model" to "SM-G991B",
            "\$os_version" to "14",
            "\$manufacturer" to "samsung",
            "\$lib_version" to "2",
            "mp_lib" to "glomo-android-sdk",
        )

        assertEquals(metadata, AnalyticsSanitizer.properties(metadata))
    }

    @Test
    fun metadata_values_are_capped_at_the_max_string_length() {
        val sanitized = AnalyticsSanitizer.properties(mapOf("\$app_name" to "a".repeat(2_000)))

        assertEquals("a".repeat(AnalyticsSanitizer.MAX_STRING_LENGTH), sanitized["\$app_name"])
    }

    @Test
    fun identifier_allowlist_does_not_exempt_other_keys() {
        val sanitized = AnalyticsSanitizer.properties(mapOf(
            "journey_id" to "123456789012",
            "error_detail" to "123456789012",
        ))

        assertEquals("123456789012", sanitized["journey_id"])
        assertEquals("••••9012", sanitized["error_detail"])
    }

    private fun fixtureValue(value: Any?): Any? = when (value) {
        JSONObject.NULL -> null
        is JSONArray -> (0 until value.length()).map { value.get(it) }
        is JSONObject -> value.keys().asSequence().associateWith { value.get(it) }
        is Int -> value.toLong()
        else -> value
    }
}
