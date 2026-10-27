# Glomo Android SDK

Native Kotlin SDK for integrating GlomoPay Standard and LRS hosted checkout
flows into Android applications.

## Current Status

Version `2.0.0` is prepared for release and available for local integration testing.
Maven Central publication is planned; until then, use the local module or the
published artifact when it becomes available.

The SDK hosts GlomoPay checkout in a native Android WebView and exposes payment
results and lifecycle events through a Kotlin callback contract. Card details,
3DS, bank authentication, and document upload screens remain inside the hosted
checkout and are not implemented natively by the SDK.

It deliberately does **not** capture card numbers, CVVs, or expiry dates;
tokenize payment instruments; implement 3DS or SCA logic natively; or store or
transmit KYC document contents. Card data is handled entirely by the hosted
checkout page, server-side. This boundary is what keeps the SDK and every app
embedding it out of PCI scope. It is a hard architectural constraint, not a
current implementation detail. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Requirements

| Requirement | Value |
|---|---|
| Minimum Android version | Android 7.0 / API 24 |
| Compile SDK | 35 |
| Tested merchant `targetSdk` range | 34–36 |
| Kotlin | 2.0.21 or compatible |
| Java/JVM target | 17 |
| Kotlin/Java package | `com.glomopay.sdk.android` |
| Planned Maven coordinates | `com.glomopay:glomo-android-sdk:2.0.0` |

**Why minSdk 24.** The deciding factor is TLS, not market share. Devices below
Android 7.1.1 carry a stale CA trust store and can fail handshakes against modern
certificates. For a checkout SDK, that means a payment can fail before reaching
GlomoPay in a device-specific failure mode. API 24 sits at that support boundary,
covers the overwhelming majority of active devices, keeps WebView and Kotlin/AGP
tooling comfortable, and matches the modern React Native ecosystem floor.

Merchants currently using `minSdk 21` must raise their application's minimum SDK
before adopting this library. Coordinate that change with
`developer@glomopay.com` before planning the integration.

The merchant application's `targetSdk` is separate from the library's `minSdk`.
The supported test range is 34–36: 34 is the pre edge-to-edge control, while 35
and 36 cover current platform behavior. The AAR declares `minCompileSdk 35`.
Predictive Back uses Android's runtime callback on API 33+ and the legacy callback
below it; the target range is therefore a release test policy rather than a code
branch. The sample app defaults to 36 and can be built for each supported target:

```bash
./gradlew :sample-app:assembleDebug -PMERCHANT_TARGET_SDK=34
./gradlew :sample-app:assembleDebug -PMERCHANT_TARGET_SDK=35
./gradlew :sample-app:assembleDebug -PMERCHANT_TARGET_SDK=36
```

## Installation

When the artifact is available from Maven Central:

```kotlin
repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation("com.glomopay:glomo-android-sdk:2.0.0")
}
```

For local SDK development, include the `glomo-android-sdk` module in the host
application and use:

```kotlin
implementation(project(":glomo-android-sdk"))
```

## Basic Integration

Implement `GlomoPayListener` and start checkout from an Activity or another
Android context:

```kotlin
class CheckoutActivity : Activity(), GlomoPayListener {
    fun startPayment(orderId: String) {
        val config = GlomoPayConfig(
            publicKey = "live_public_key",
            orderId = orderId,
        )

        GlomoPaySdk.startCheckout(this, config, this, orderType = "auto")
    }

    override fun onPaymentSuccess(payload: GlomoPayPayload) {
        // Verify the payment on the merchant server before fulfilment.
    }

    override fun onPaymentFailure(payload: GlomoPayPayload) {}
    override fun onSdkError(errors: List<SdkError>) {}
    override fun onConnectionError(error: ConnectionError) {}
    override fun onPaymentTerminate(source: TerminationSource) {}
    override fun onEvent(name: String, payload: Map<String, Any?>) {}
}
```

## Checkout Types

Supported values are `auto`, `standard`, and `lrs`.

- `auto`: detects the order type from the order API response.
- `standard`: explicitly opens Standard checkout.
- `lrs`: explicitly opens LRS checkout.

Use `auto` when the API should remain the source of truth. Provide exactly one
of `orderId` or `subscriptionId` in `GlomoPayConfig`.

## Configuration

```kotlin
val config = GlomoPayConfig(
    publicKey = "test_public_key",
    orderId = "order_example",
    server = null,
    appColorScheme = GlomoColorScheme.AUTO, // AUTO | LIGHT | DARK
)
```

Guidelines:

- Use `test_` or `mock_` keys for development and QA.
- Use live keys only in production and on compliant devices.
- Never log public keys, identifiers, payment signatures, or raw payment data
  in production.
- Verify successful payments server-side before delivering goods or services.

`startCheckout` returns a `GlomoPayCheckoutHandle`. Retain it to dismiss that
session with `handle.close()`; the listener receives `PROGRAMMATIC` once.
Calls after completion do nothing, and closing one handle does not close another session.

There is no merchant-settable `devMode`. SDK owners can build a distinct
`-internal` artifact with `-PGLOMO_INTERNAL_BUILD=true`; any other value defaults
to false. The value is baked into the AAR, and analytics still reports `dev_mode`
with either value. Internal artifacts must not be distributed to merchants.
Remote WebView debugging is controlled by the embedding application.

The checkout Activity declares `configChanges` for UI mode, locale, layout
direction, font scale, density, keyboard and size changes, so a dark-mode toggle,
locale change or multi-window resize does not restart a live payment. The SDK
owns that Activity, so this needs nothing from the host - unlike the React Native
and Flutter SDKs, which run inside the merchant's own Activity.

### Dark mode

`appColorScheme` tells the Glomo checkout page which scheme to present. It
defaults to `GlomoColorScheme.AUTO`.

**It requests a scheme, it does not force one.** The page decides, against your
merchant dashboard settings. Setting `DARK` does not guarantee a dark checkout.

**What `AUTO` resolves to here.** Your app's own appearance, from the most
specific source that can answer:

1. `AppCompatDelegate.getDefaultNightMode()`, if you have set one.
2. Otherwise your theme's `isLightTheme`, read from the context you launch the
   checkout with.
3. Otherwise the device configuration.

Step 2 is what makes a light-only app on a dark device get a light checkout. It
is needed because `MODE_NIGHT_FOLLOW_SYSTEM` is the *default* return of step 1,
so an app that has never called `setDefaultNightMode` is indistinguishable there
from one that asked to follow the system.

Step 2 is read **once, when you launch the checkout**, against the context you
launch it with. A `DayNight` theme therefore gets the right answer at launch, but
a device light/dark toggle *while the checkout is open* is not followed: step 2
has already answered, so the new device configuration never reaches step 3. If
you need an open checkout to move, drive it yourself with
`setAppColorScheme(LIGHT)` or `setAppColorScheme(DARK)` from your own
configuration callback. Hosts that set a night mode through
`AppCompatDelegate` are unaffected, because step 1 is re-read on every change.

If your theme defines no `isLightTheme` - unusual, but possible with a custom
theme that does not inherit from a platform or Material parent - the SDK falls
through to the device. What `AUTO` derives from is a per-platform decision and
differs across the Glomo SDKs; only `LIGHT` and `DARK` mean the same thing
everywhere.

**Changing it mid-checkout is supported**, through the handle:

```kotlin
val handle = GlomoPaySdk.startCheckout(context, config, listener)
// later, without closing or restarting the checkout
handle.setAppColorScheme(GlomoColorScheme.DARK)
```

The page is told through the bridge rather than by reloading it, so a payment in
flight survives the change. Do not close and restart the checkout to change the
scheme. A host night-mode change reaches the open checkout on its own while the
scheme is `AUTO`; nothing is needed from the host for that.

**Three names, one concept.** The property is `appColorScheme`, the URL parameter
the page reads is `colorScheme`, and the page hook the SDK calls is
`window.glomoSetColorScheme`. The wire names were not renamed along with the
property.

Android's algorithmic darkening is switched off on every WebView the SDK renders,
including bank and 3DS pages - the platform must not invert a page that handles
its own colours, and a bank's page is not ours to recolour either. Both the modern
`setAlgorithmicDarkeningAllowed` and the legacy `setForceDark` are set, since the
first is ignored by hosts targeting SDK 32 and below.

After Activity recreation, checkout ends instead of replaying an interrupted
payment page. If the process is still alive, the listener receives an SDK error;
after process death, the lost listener cannot be recovered and no payment page
is opened. Start a new checkout from the merchant app after checking order status.
Listeners are retained for the active session and released on completion or launch failure.

`onUserJourneyCompleted` is required and has no default implementation, so every
integration must add it. It reports a non-payment journey - today, submitted
bank-transfer details - carrying `GlomoPayUserJourneyPayload`, which has no
`paymentId` and no `signature` because no money has moved. Reconcile it
server-side against the order; never fulfil an order from it. This used to arrive
through `onPaymentSuccess`, which told hosts a payment had completed when it had not.

`onPaymentFailure` is delivered on the checkout's failure event itself and does not
require a `signature`, which a failure payload has never carried.

`onEvent` is a deprecated diagnostic channel. SDK events use the
`glomo_android_sdk.` prefix; page events retain their original names. Use the
typed payment and error callbacks for integration logic. Unused `CheckoutStatus`
was removed, and `GlomoPayResult` is now internal.

## WebView and File Upload Behavior

The SDK provides:

- Native main checkout WebView and a separate secure bank/3DS flow overlay.
- JavaScript bridge events for payment, redirect, navigation, and errors.
- Android native file chooser support for hosted bank upload fields, including
  PDF/document uploads, with a Camera / Gallery / Files choice. Camera captures are
  capped at 2048px and JPEG quality 85 to stay under the bank's upload limit; do not
  raise those caps without re-confirming with the bank. If the user refuses the camera
  permission, the upload is cancelled, `onUserRefusedDevicePermissions` is delivered,
  and checkout stays open. `accept` only decides which picker opens - it never restricts
  what the user may choose, because the bank re-validates every upload.
- Loading, connection-error, retry, and back-navigation handling.
- Root, debugger, and developer-mode compliance checks for live sessions.

The hosted checkout remains responsible for payment UI, bank authentication,
3DS, and validation of uploaded documents.

Each session creates fresh WebViews. Startup does not erase shared cookies or
origin storage, which may also belong to merchant WebViews or another checkout.
This is a deliberate divergence from the Flutter SDK, which clears WebView state
at init as well as at teardown: on Android, `CookieManager` and `WebStorage` are
process-wide with no per-WebView scope, so clearing at checkout start would erase
the embedding app's own WebView sessions mid-use. A fresh `WebView` already starts
with empty navigation and form state. At teardown, the SDK clears WebView-local
history, form data and SSL preferences. It deliberately does not clear the
process-wide resource cache. Revisit only with a scoped-storage API or an explicit
product decision that the SDK may clear app-wide state.
File URL access and mixed content are disabled explicitly. Bank pages use the
same forced viewport behavior as the Flutter Android flow; bank/device validation
is required before release.

A 15-second render timeout is advisory and offers Retry/Cancel. A later bridge
handshake dismisses that surface; Retry starts a new timeout budget. Connection
errors marked `shouldAutoClose` close checkout with `CONNECTION_ERROR`. Structured
page dependency failures remain diagnostic events and do not draw native error UI.

## Testing

Run the SDK and sample-app tests from this repository:

```bash
./gradlew :glomo-android-sdk:testDebugUnitTest :sample-app:testDebugUnitTest
```

The in-repository [`sample-app`](sample-app/README.md) consumes the local SDK
module and is used to test Standard, LRS, subscription, validation,
developer-mode, bank redirect, and file-upload flows. The sample APK is a QA
artifact and is not the SDK dependency.

## Analytics and privacy

The SDK emits operational checkout lifecycle events to GlomoPay's Mixpanel
project. It does not send customer contact details, card data, bank account
numbers, or KYC document contents. External bank URLs are stripped of userinfo,
port, path, query parameters, and fragments so only the HTTPS hostname leaves the
device. Analytics delivery failures never interrupt checkout. Device/app context,
Wi-Fi/cellular transport state, and IP-derived coarse location follow the approved
mobile analytics v1.1 contract. See the [integration guide](docs/integration.md)
for release-time token and privacy configuration.

Analytics and internal SDK failures can be reported to Glomo's Sentry
project through a small, dependency-free client for Sentry's HTTP envelope
endpoint. The SDK does not depend on any Sentry artifact, so it cannot conflict
with or alter the merchant app's own Sentry setup. It installs no crash, ANR,
NDK, session or tracing hooks; only explicitly captured SDK errors are
reported. Events carry no user identifiers and no IP address; Sentry derives
an approximate location (country, region, city) at ingest but does not store
the device IP.

## ProGuard and R8

The published AAR includes consumer rules that preserve the WebView JavaScript
bridge and stack-trace source positions. R8 shrinking, optimization, and
obfuscation can remain enabled in the merchant app. The final mapping file is
generated by the merchant application build, not by the SDK AAR build; see the
[integration guide](docs/integration.md#proguardr8-and-sentry-mappings) for how
this affects SDK error reports.

## Documentation

- [Integration guide](docs/integration.md)
- [API reference](docs/api-reference.md)
- [Maven Central publishing guide](docs/maven-central-publishing.md)
- [Release process](docs/release-process.md)

## Support and Security

For integration questions, contact `developer@glomopay.com`. For security
reports, follow [SECURITY.md](SECURITY.md) and do not open a public issue.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
