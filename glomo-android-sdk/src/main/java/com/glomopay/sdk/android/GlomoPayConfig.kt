package com.glomopay.sdk.android

/** Configuration required to start a GlomoPay checkout. */
public data class GlomoPayConfig @JvmOverloads public constructor(
    public val publicKey: String,
    public val orderId: String? = null,
    public val subscriptionId: String? = null,
    public val server: String? = null,
    /**
     * The colour scheme to request from the checkout page. Defaults to [GlomoColorScheme.AUTO].
     *
     * Changing it during a live checkout goes through `GlomoPayCheckoutHandle.setAppColorScheme`,
     * not through a new config - rebuilding the URL would reload the WebView and lose the payment.
     */
    public val appColorScheme: GlomoColorScheme = GlomoColorScheme.AUTO,
) {
    public val checkoutId: String?
        get() = orderId ?: subscriptionId

    public val isSubscription: Boolean
        get() = !subscriptionId.isNullOrEmpty()

    @JvmOverloads
    public fun copyWith(
        publicKey: String = this.publicKey,
        orderId: String? = this.orderId,
        subscriptionId: String? = this.subscriptionId,
        server: String? = this.server,
        appColorScheme: GlomoColorScheme = this.appColorScheme,
    ): GlomoPayConfig = GlomoPayConfig(publicKey, orderId, subscriptionId, server, appColorScheme)
}
