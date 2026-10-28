package com.glomopay.sdk.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** Native equivalent of the Flutter API client used for order detection. */
public class GlomoPayApiClient public constructor(
    private val publicKey: String,
) {
    public suspend fun fetchOrder(orderId: String): Map<String, Any?> = fetchOrderWithStatus(orderId).second

    internal suspend fun fetchOrderWithStatus(orderId: String): Pair<Int, Map<String, Any?>> = withContext(Dispatchers.IO) {
        val url = URL("https://api.glomopay.com/api/public/v1/order/$orderId")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $publicKey")
            setRequestProperty("Content-Type", "application/json")
        }

        try {
            val status = connection.responseCode
            val body = if (status in SUCCESS_STATUS) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                ""
            }
            status to mapOrderFetchResponse(status, body)
        } catch (error: GlomoPayOrderFetchException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw GlomoPayRequestTimeout(error)
        } catch (error: IOException) {
            throw GlomoPayTransportError(error)
        } finally {
            connection.disconnect()
        }
    }

    internal fun mapOrderFetchResponse(status: Int, body: String): Map<String, Any?> {
        // Any 2xx is the backend answering; only a non-2xx is a status fault.
        // A 2xx with a body this client cannot parse is a malformed response, not a status error.
        if (status !in SUCCESS_STATUS) {
            throw GlomoPayHttpStatusError(status)
        }
        return try {
            JSONObject(body).toMap()
        } catch (_: JSONException) {
            // JSON parser messages can contain the response body; never retain them.
            throw GlomoPayMalformedResponse()
        }
    }

    private fun JSONObject.toMap(): Map<String, Any?> = keys().asSequence().associateWith { key ->
        when (val value = get(key)) {
            JSONObject.NULL -> null
            is JSONObject -> value.toMap()
            is JSONArray -> value.toList()
            else -> value
        }
    }

    private fun JSONArray.toList(): List<Any?> = (0 until length()).map { index ->
        when (val value = get(index)) {
            JSONObject.NULL -> null
            is JSONObject -> value.toMap()
            is JSONArray -> value.toList()
            else -> value
        }
    }

    internal companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 15_000
        internal val totalRequestTimeoutMs: Long
            get() = CONNECT_TIMEOUT_MS.toLong() + READ_TIMEOUT_MS
        private val SUCCESS_STATUS = 200..299
    }
}

internal sealed class GlomoPayOrderFetchException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal class GlomoPayRequestTimeout(
    cause: Throwable,
) : GlomoPayOrderFetchException("Order request timed out.", cause)

internal class GlomoPayTransportError(
    cause: Throwable,
) : GlomoPayOrderFetchException("Unable to fetch order.", cause)

internal class GlomoPayHttpStatusError(
    val statusCode: Int,
) : GlomoPayOrderFetchException("Failed to load order. Status: $statusCode")

internal fun GlomoPayHttpStatusError.toSdkError(): SdkError = SdkError(
    type = SdkErrorType.UNKNOWN,
    message = "Failed to load order. Status: $statusCode",
)

internal class GlomoPayMalformedResponse : GlomoPayOrderFetchException("Malformed order response.")
