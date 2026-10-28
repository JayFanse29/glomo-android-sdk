package com.glomopay.sdk.android.analytics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.util.Locale

internal object AndroidAnalyticsProperties {
    fun collect(context: Context): Map<String, Any?> {
        val packageManager = context.packageManager
        val packageInfo = runCatching { packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val appName = runCatching {
            context.applicationInfo.loadLabel(packageManager).toString().takeIf(String::isNotBlank)
        }.getOrNull()
        val metrics = context.resources.displayMetrics
        val connectivity = networkState(context)

        return mapOf(
            "\$model" to Build.MODEL.takeIf(String::isNotBlank),
            "\$device" to Build.DEVICE.takeIf(String::isNotBlank),
            "\$os" to "Android",
            "\$os_version" to osVersion(),
            "\$manufacturer" to Build.MANUFACTURER.takeIf(String::isNotBlank),
            "\$brand" to Build.BRAND.takeIf(String::isNotBlank),
            "\$device_type" to "android",
            "\$screen_width" to metrics.widthPixels,
            "\$screen_height" to metrics.heightPixels,
            "\$screen_density" to metrics.density,
            "\$app_version_string" to packageInfo?.versionName,
            "\$app_namespace" to context.packageName,
            "\$app_build_number" to packageInfo?.let { PackageInfoCompat.getLongVersionCode(it).toString() },
            "\$app_name" to appName,
            "merchant_target_sdk_version" to context.applicationInfo.targetSdkVersion,
            "\$locale" to Locale.getDefault().toLanguageTag(),
            "\$lib_version" to MIXPANEL_REST_API_VERSION,
            "mp_lib" to "glomo-android-sdk",
            "\$wifi_enabled" to connectivity.wifi,
            "\$cellular_enabled" to connectivity.cellular,
        )
    }

    private fun networkState(context: Context): NetworkState {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkState(null, null)
        return runCatching {
            val capabilities = manager.activeNetwork?.let(manager::getNetworkCapabilities)
            NetworkState(
                wifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                cellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            )
        }.getOrDefault(NetworkState(null, null))
    }

    private fun osVersion(): String = Build.VERSION.RELEASE.takeIf(String::isNotBlank)
        ?: Build.VERSION.SDK_INT.toString()

    private data class NetworkState(val wifi: Boolean?, val cellular: Boolean?)

    private const val MIXPANEL_REST_API_VERSION = "1.0.0"
}
