# Changelog

All notable changes to the Glomo Android SDK are documented here.
This project follows [Semantic Versioning](https://semver.org/).

Starting with 1.0.0, the public API follows Semantic Versioning. Breaking
public API changes require a new major release.

## Unreleased

### Changed

- Each `startCheckout` call now passes a `journeyId` to checkout, and the SDK's analytics events carry it, so one checkout can be followed end to end.

## [2.0.0] - 2026-10-01

### Breaking

- `GlomoPayListener.onUserJourneyCompleted(GlomoPayUserJourneyPayload)` is a new **required**
  callback with no default implementation, so every integration must add it. It is required on
  purpose: the SDK cannot tell which merchants have bank transfers enabled - the order decides
  that, server side - so a default body would let a host upgrade, keep compiling, and silently
  stop hearing about a journey it used to be told about.
- `payment.bank_transfer_submitted` no longer reaches `onPaymentSuccess`. Hosts that fulfil
  orders from `onPaymentSuccess` were being told a payment had completed when no money had moved.
- `GlomoPayConfig.devMode` and `GlomoPayCheckoutActivity.EXTRA_DEV_MODE` are removed, and
  `startCheckout` now returns a `GlomoPayCheckoutHandle`.

### Merchant readiness

- Track monotonic checkout-open checkpoints and timeouts; add a recoverable 15-second render warning and honor connection-error auto-close.
- Validate popup URLs, set explicit WebView security settings, and apply the Flutter Android bank viewport behavior.
- End interrupted sessions on recreation instead of silently replaying checkout, and release session listeners at completion.
- Return a session-specific checkout handle with programmatic close.
- Replace the public devMode configuration with an SDK build-time GLOMO_INTERNAL_BUILD constant; internal builds have an `-internal` artifact version suffix.
- Remove unused CheckoutStatus and CheckoutUrlBuilder; make GlomoPayResult internal. Deprecate diagnostic onEvent and namespace SDK-originated events.
- Add localized error messages and Retry/Cancel controls to bank-flow errors.

### Changed

- Removed the `io.sentry:sentry` dependency. SDK error reports are now sent by a small internal
  client for Sentry's HTTP envelope endpoint, so the SDK no longer adds a Sentry artifact to the
  merchant's dependency graph or conflicts with the merchant's own Sentry version. No public API
  change. Reported fields, tags, context allowlist and breadcrumbs are unchanged.
- SDK error events now carry a minimal Sentry `contexts` block for triage: OS name, version and
  API level; device manufacturer, brand and model; host app version name and code. These are
  already sent to Mixpanel; no new data category. No device identifiers, locale, timezone,
  battery, memory or screen data.
- SDK error events send no IP address and no user object (no id, email, username or name).
  Sentry derives approximate location (country, region, city) at ingest and the SDK does not store
  the device IP; `infer_ip: "never"` makes this explicit.
- SDK error events are tagged with the checkout's `order_id` (from `GlomoPayConfig.orderId`, when
  set) as a join key to backend logs.
- SDK error envelopes are sent gzip-compressed. Events dropped by a rate limit, a full queue or a
  failed delivery are counted and reported on the next event that gets through as
  `extra.dropped_since_last_send`.
- Stack traces longer than 100 frames keep the innermost 80 and outermost 20 frames, so the SDK
  entry point survives on deep merchant-callback traces; `extra.frames_truncated` records the
  number dropped.
- SDK error events no longer carry the merchant application's ProGuard UUID from
  `sentry-debug-meta.properties`; merchant mapping uploads are not used by Glomo's Sentry project.
- Sentry issue grouping for SDK errors may change once, because the reported client name, SDK
  name and payload shape differ from the previous Sentry Java client.

### Fixed

- Preserve merchant cookies, the process-wide WebView resource cache, and the
  application's WebView debugging preference.
- Install the main checkout bridge at document start where Android WebView supports
  it, with page-start/page-finish fallback for older implementations.
- Report order HTTP status failures as SDK errors rather than connectivity errors.
- Close bank overlays on Back; handle predictive Back and allow dismissal during pending payments.
- Install the bank opener bridge before page scripts where supported.
- Contain a failing native bridge. If the WebView's native peer is gone, the injected `bridge()` call threw: into the injection script itself, aborting the bridge setup, and into the page's error listener, which called `bridge()` again. Only the bridge's own `postMessage` is now guarded, and it cannot re-enter itself. A failure is recorded once per page: a window marker always, and a console warning in internal builds only. Errors the page raises are still reported one-for-one, exactly as before.
- Show the LRS education carousel on the page's actual signal, `{type: 'lrs.has_education_steps', value: true}`, and only on that. 2.0.0 had been reading `{event, hasContent}`, which the page never sends, so the carousel and `Education Steps Shown` could only fire through the DOM fallback. As in the RN SDK, no signal now means no carousel: `value: false` and other shapes are ignored, and the 3-second DOM-polling fallback is removed. A signal sent before the bridge is ready is still delivered.
- Block external schemes in bank flows and isolate merchant callback exceptions. Merchant exceptions are now reported by type and stack trace, never by message.
- Fail order detection explicitly instead of guessing the checkout host; exclude API bodies from errors.
- Accept any 2xx order response as the backend answering; a 2xx body the client cannot parse is reported as a malformed response rather than a status error.
- Report order faults that the backend answered through `onSdkError` only. They no longer also deliver `onPaymentTerminate(CONNECTION_ERROR)`, which told hosts the network had failed.
- Declare `configChanges` on the checkout Activity, so a dark-mode toggle, locale change, font-size change or multi-window resize no longer ends a live payment as an interrupted session. Only real process death reaches that path.
- Deliver `onPaymentFailure` on the checkout's failure event instead of requiring a signature that a failure payload has never carried. The callback previously could not fire for a backend-confirmed decline in any release build. A payload with no `orderId` is still delivered and captured as `thin_payment_failure_payload`.
- Route `payment.bank_transfer_submitted` to the new `onUserJourneyCompleted` with a `GlomoPayUserJourneyPayload`, instead of reporting it as a payment success with no `paymentId` and no `signature` for the host to verify. Journey fields are read coercively in both camelCase and snake_case, and a payload with no `orderId` is rejected but captured as `thin_bank_transfer_payload`.

### Uploads

- Add camera, gallery and file entry points, camera permission refusal handling, and JPEG camera output limited to 2048 pixels per side at quality 85. Picked documents are not read or size-limited.
- Deliver the new `GlomoPayListener.onUserRefusedDevicePermissions` callback when the camera permission is refused, instead of reporting the user's choice as an SDK error. It has a default implementation, so existing integrations keep compiling.
- Sweep stale camera captures at checkout start. Captures (KYC document photos) now live in a per-checkout directory under `cacheDir/glomopay-capture`, and opening a checkout deletes every capture not owned by a checkout still live in the process. Photos left by a crash, a process death or a kill mid-upload no longer stay on disk. A sweep failure never interrupts checkout.
- Open the photo picker on API 33+ for the Gallery entry point and fall back to the documents picker where it is unavailable, replacing a wildcard `ACTION_PICK` that several OEM galleries do not handle.

### Documented decisions

- WebView state is cleared at teardown only, not at checkout start: on Android `CookieManager`
  and `WebStorage` are process-wide, so clearing at start would erase the embedding app's own
  WebView sessions. This is a deliberate divergence from the Flutter SDK.
- The merchant listener is held strongly for the lifetime of one checkout and released on every
  terminal path. A weak reference was rejected because hosts commonly pass an inline listener
  that nothing else retains, which would silently drop the payment result.
- Bank-page viewport forcing and predictive-back gestures need real bank/device evidence before
  signoff; see `docs/bank-flow-device-validation.md`.
- Support and test merchant applications targeting SDK 34–36. The sample host can
  build each target from a Gradle property, and the AAR declares `minCompileSdk 35`.

### Build and verification

- Run the JavaScript bridge contract from Gradle `check` and Android CI.
- Remove the unused public `Validator.isValidBankTransferPayload` and
  `CheckoutUiState.Error` APIs, then regenerate the public API dump.

### Added

- Added automatic LRS education carousel support above the secure bank flow,
  with a responsive 15/85 split, hidden fallback, and per-checkout state reset.

## [1.0.0] - 2026-08-17

### Added

- Added dependency-free Mixpanel REST analytics for SDK, compliance, checkout,
  WebView, redirect, payment, error, and file-upload lifecycle events.
- Added build-time `MIXPANEL_TOKEN` injection, PII filtering, external URL
  sanitization, and fire-and-forget delivery that cannot block checkout.
- Added an isolated SDK-owned Sentry client for explicitly captured SDK and
  analytics delivery failures, with build-time `SENTRY_DSN` injection.
- Kept global Sentry initialization, app-wide crash/ANR capture, NDK, Session
  Replay, default PII, and performance tracing disabled.
- Added consumer ProGuard/R8 rules that preserve JavaScript bridge callbacks and
  source line metadata while allowing the remaining SDK code to be optimized.
- Aligned Mixpanel telemetry with the mobile analytics v1.1 contract: hostname-only
  bank URLs, special device/app properties, network transport signals, IP-derived
  geolocation, explicit null values, session insert IDs, and `main`/`flow` WebView types.
- Added the normal `ACCESS_NETWORK_STATE` permission for Wi-Fi/cellular telemetry;
  it does not require a runtime permission prompt.

### Fixed

- Fixed a WebView callback race that could leave checkout stuck behind the
  `Loading checkout... 100%` overlay.
- Fixed valid ISO-8601 Mixpanel timestamps being incorrectly redacted by the
  generic numeric PII sanitizer.

### Changed

- Declared the first stable Android SDK release and applied Semantic Versioning
  guarantees to the public API.

## [0.0.2]

### Changed

- All 4 points bugs/changes of android-sdk is completed with version 0.0.2
- Restored WebView loading percentages for the checkout and secure bank-flow
  overlays without restoring the removed top progress bar.

## [0.0.1] - Initial native Kotlin SDK

### Added

- Native Kotlin SDK module with public GlomoPay configuration and listener APIs.
- Standard, LRS, subscription, and automatic order-type checkout handling.
- Hosted checkout WebView with JavaScript bridge event routing.
- Separate secure flow WebView overlay for bank and 3DS redirects.
- Payment success, failure, pending, cancellation, SDK error, and connection
  error callbacks.
- Root, debugger, developer-mode, and device compliance checks.
- Loading, retry, error, back-navigation, and checkout termination handling.
- Android native file chooser support for hosted bank document uploads.
- Local test-wrapper integration and SDK unit-test coverage.

### Fixed

- Android hosted bank upload controls now open the native file picker and pass
  selected `content://` document URIs back to the WebView.
- Bank/3DS pages are rendered above the main checkout instead of replacing the
  main WebView layer.

### Changed

- Renamed the Android artifact to `glomo-android-sdk`.
- Moved Kotlin and Java APIs to the `com.glomopay.sdk.android` package.

### Distribution

- Maven coordinates are planned as `com.glomopay:glomo-android-sdk:0.0.1`.
- Maven Central publication and signed release metadata are still pending.
