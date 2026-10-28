# Android Integration Guide

## Add the SDK

After the `2.0.0` artifact is published, add Maven Central and the SDK dependency to the host app:

```kotlin
repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation("com.glomopay:glomo-android-sdk:2.0.0")
}
```

For local testing, use the in-repository [sample app](../sample-app/README.md).
It links the SDK with `implementation(project(":glomo-android-sdk"))`.

## Configure checkout

The host Activity should implement `GlomoPayListener`:

```kotlin
class CheckoutActivity : Activity(), GlomoPayListener {
    fun start(orderId: String) {
        val config = GlomoPayConfig(
            publicKey = "live_public_key",
            orderId = orderId,
        )

        GlomoPaySdk.startCheckout(this, config, this)
    }

    override fun onPaymentSuccess(payload: GlomoPayPayload) {
        // Fulfil only after server-side payment verification.
    }

    override fun onPaymentFailure(payload: GlomoPayPayload) { /* Show a failure state. */ }
    override fun onSdkError(errors: List<SdkError>) { /* Handle validation/device errors. */ }
    override fun onConnectionError(error: ConnectionError) { /* Handle network/WebView errors. */ }
}
```

## Order type detection

The default launcher mode is `auto`:

```kotlin
GlomoPaySdk.startCheckout(this, config, this, orderType = "auto")
```

For an order ID, the SDK applies this rule:

1. A non-empty `orderType` response field wins.
2. Otherwise, a non-null `lrs` response field selects LRS.
3. Otherwise, Standard is selected.
4. If order detection fails, checkout is not opened: the failure is delivered through `onConnectionError` (timeout, no connectivity) or `onSdkError` (HTTP status, malformed response) and the session ends. No order type is guessed, because guessing "standard" would route LRS traffic to the wrong checkout host.

Pass `orderType = "standard"` or `orderType = "lrs"` only when the host intentionally wants to override automatic detection. The sample app uses `auto` so the API remains the source of truth. Subscription IDs currently open the Standard checkout flow.

## Configuration rules

- Provide exactly one of `orderId` or `subscriptionId`.
- Use `test_` or `mock_` public keys for local testing. There is no merchant-settable `devMode`; SDK owners build a separate `-internal` artifact with `-PGLOMO_INTERNAL_BUILD=true`.
- Use a `live_` public key only for production on a compliant device.
- Do not log keys, identifiers, payment payloads, or signatures in production.
- Verify payment results on the merchant backend before fulfilling an order.

## Events and errors

`onEvent` provides diagnostic WebView and checkout lifecycle events. `onSdkError` is for SDK validation/device errors, while `onConnectionError` is for network and WebView failures. `onPaymentTerminate` is called when the user or SDK closes checkout.

`onPaymentFailure` is delivered on the checkout's failure event itself. Do not expect a
`signature` on it: that field exists so a host can verify a *success*, and a failure payload
has never carried one. The page's response travels verbatim in `rawResponse`.

`onUserJourneyCompleted` is required and reports a non-payment journey - today, submitted
bank-transfer details. It is not a payment result: there is no `paymentId` and no `signature`,
and no money has moved. Reconcile it server-side against the order, and never fulfil an order
from it. Hosts that previously received this through `onPaymentSuccess` were being told a
payment had completed when it had not.

## Local sample app

From the repository root:

```bash
./gradlew :sample-app:installDebug
```

The sample app accepts a public key and order/subscription ID, enables developer mode for testing, and records callback events on screen.
## Analytics build configuration

The Android AAR sends the documented checkout events directly to Mixpanel's `/track?ip=1` API. The
project token is compiled into the AAR per build type and is never accepted through the
merchant-facing SDK API. Release builds read only `MIXPANEL_TOKEN_PROD`; debug builds, including the
sample app, read only `MIXPANEL_TOKEN_STAGING`; each is a Gradle property or environment variable.
The legacy `MIXPANEL_TOKEN` is not read.

```bash
MIXPANEL_TOKEN_PROD="<prod-project-token>" ./gradlew :glomo-android-sdk:assembleRelease
MIXPANEL_TOKEN_STAGING="<staging-project-token>" ./gradlew :sample-app:installDebug
```

Release packaging (`assembleRelease`, `bundleReleaseAar`) and any publish task fail unless
`MIXPANEL_TOKEN_PROD` is set, differs from `MIXPANEL_TOKEN_STAGING`, and matches the prod token's
SHA-256 fingerprint committed in `glomo-android-sdk/build.gradle.kts`. Only the fingerprint is
committed, never the token. If the prod token is rotated, update `mixpanelProdTokenSha256` there.

CI packages the release AAR with `-PMIXPANEL_TOKEN_PROD=ci-placeholder`. That literal value skips the
fingerprint check and compiles an empty token, so the AAR has analytics off. It is for CI only; never
use it for a release.

If `MIXPANEL_TOKEN_STAGING` is absent, debug builds use a no-op tracker and checkout behavior is
unchanged. Do not commit either token to `gradle.properties`; provide it through the release CI
environment instead.

The AAR declares `ACCESS_NETWORK_STATE`, a normal Android permission that does not show a runtime
permission dialog. It is used only to populate `$wifi_enabled` and `$cellular_enabled`. Mixpanel's
`ip=1` ingestion option derives coarse `$city`, `$region`, and `mp_country_code` properties from the
request IP; the SDK does not request device location permission or read GPS coordinates.

Events follow Glomo's shared SDK catalogue: `sdk_loaded`, `sdk_page_viewed`, `sdk_api_backend`,
`sdk_action_submitted`, `sdk_action_failed`, `sdk_cta_clicked`, `sdk_control_changed`,
`sdk_checkout_failed` and `sdk_checkout_closed`. Every event carries `integration` (`android_sdk`),
`platform` (`android`), `sdk_version`, `public_key`, `dev_mode`, `key_mode` (`test` or `live`),
`resource_id` (the order or subscription id), `journey` (`standard`, `lrs` or `subscription`, once
known) and the approved device, screen, merchant-app and locale properties. `journey_id` and
`$device_id` carry the id generated for each `startCheckout` call, which checkout also receives as
`journeyId`; `distinct_id` is `$device:<journey_id>` and `$insert_id` is unique per event. Property
values are flat strings, numbers or booleans from fixed lists; no free-text error message is sent.

For bank/3DS redirects, only the `https://hostname` origin is transmitted, as `url_origin`.
Credentials, port, path, query, and fragment are discarded first. WebView context values are limited
to `main` and `flow`. Device compliance results are not sent to analytics; a blocked device is
reported as `sdk_checkout_failed` with `error_code` `compliance_blocked`.

## Sentry build configuration

The release build can report explicitly captured SDK and analytics-delivery failures to Glomo's
Sentry project through a small, dependency-free client for Sentry's HTTP envelope endpoint. Supply
its DSN using the `SENTRY_DSN` Gradle property or environment
variable:

```bash
SENTRY_DSN="<android-sdk-dsn>" ./gradlew :glomo-android-sdk:assembleRelease
```

If the DSN is absent or malformed, error reporting uses a no-op implementation and checkout
behavior is unchanged. The ingestion endpoint is derived from the DSN at runtime, so no Sentry host
or region is built into the SDK. Never commit the DSN to `gradle.properties`; inject it through release CI.

The client installs no uncaught-exception handler, shutdown hook, ANR, NDK, session, tracing,
profiling, or Session Replay collection. Only failures explicitly captured within the Glomo SDK
boundary are sent, gzip-compressed, on a background thread behind a small bounded queue; events are
dropped, never queued, when Sentry signals a rate limit, the queue is full or delivery fails. The
number of events dropped since the last successful send is reported on the next event that gets
through (`extra.dropped_since_last_send`). Stack traces longer than 100 frames keep the innermost
80 and outermost 20 frames, and `extra.frames_truncated` records how many were dropped. Events carry no request, server name, module list, or
thread dump, and the original exception message is replaced by the name of the failed operation.
The SDK sends no IP address and no user id, email, username or name. Sentry derives approximate
location (country, region, city) at ingest and the SDK does not store the device IP;
`infer_ip: "never"` makes this explicit.
Each event is tagged with the checkout's `order_id` from `GlomoPayConfig`, when one is set, so an
SDK error can be joined to backend logs for the same order.
For triage they carry the OS version and API level, the device manufacturer, brand and model, and
the host app's version name and code, a subset of what the Mixpanel events already carry. They
never carry ANDROID_ID, an advertising id, the user-set device name, locale, timezone, battery,
memory or screen details.

### Sentry dependency compatibility

The SDK does not depend on any Sentry artifact. A merchant application can use its own
`io.sentry:sentry-android` dependency and Sentry Gradle plugin at any version; the SDK neither
reads nor alters that configuration.

## ProGuard/R8 and Sentry mappings

No additional keep rules are required for normal SDK integration. The AAR packages consumer rules
that preserve `@JavascriptInterface` callbacks, runtime annotations, source file names, and line
numbers. Merchant release builds can keep shrinking, optimization, and obfuscation enabled.

The complete R8 mapping is generated only when the merchant application creates its final APK or
App Bundle. It is normally available at:

```text
app/build/outputs/mapping/<variant>/mapping.txt
```

SDK error events carry the stack trace as it exists at runtime. In a merchant release build where
R8 obfuscates SDK classes, frames arrive with obfuscated class and method names; source file names
and line numbers are preserved by the consumer rules. The SDK does not read the merchant
application's `sentry-debug-meta.properties` or attach a ProGuard UUID to its events, so merchants
do not need to upload their mapping to Glomo or change their own Sentry setup.
