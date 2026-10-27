package com.glomopay.sdk.android.sampleApp

import com.glomopay.sdk.android.GlomoColorScheme
import com.glomopay.sdk.android.GlomoPayConfig

/** Form model kept separate so the sample application's request mapping is testable. */
data class CheckoutFormState(
    val publicKey: String,
    val identifier: String,
    val appColorScheme: GlomoColorScheme = GlomoColorScheme.AUTO,
) {
    fun toConfig(): GlomoPayConfig {
        require(publicKey.isNotBlank()) { "Public key is required" }
        require(identifier.isNotBlank()) { "Order ID or Subscription ID is required" }
        val value = identifier.trim()
        val isSubscription = value.startsWith("sub_")
        return GlomoPayConfig(
            publicKey = publicKey.trim(),
            orderId = value.takeUnless { isSubscription },
            subscriptionId = value.takeIf { isSubscription },
            appColorScheme = appColorScheme,
        )
    }
}
