# Release Process

This document is for SDK maintainers. The sample APK is a QA artifact; the publishable artifact is the `glomo-android-sdk` AAR.

## Version

Update the library version in:

```text
glomo-android-sdk/build.gradle.kts
```

Keep the same version in `CHANGELOG.md` and the README dependency example. Never reuse a version after publishing.

## Verification

From the repository root:

```bash
./gradlew clean test
MIXPANEL_TOKEN_PROD="<prod-project-token>" ./gradlew :glomo-android-sdk:assembleRelease

# Confirm consumer ProGuard/R8 rules are packaged in the AAR.
unzip -p glomo-android-sdk/build/outputs/aar/glomo-android-sdk-release.aar proguard.txt

# Run the in-repository sample app tests and build.
./gradlew :sample-app:testDebugUnitTest
./gradlew :sample-app:assembleDebug
```

`assembleRelease` fails unless `MIXPANEL_TOKEN_PROD` is set and matches the prod token's SHA-256 fingerprint in `glomo-android-sdk/build.gradle.kts`; a missing, staging or other token stops the build. Do not use the CI-only `ci-placeholder` value for a release: it builds an AAR with analytics off.

Manually test Standard, LRS, subscription, validation-error, connection-error, and developer-mode scenarios in the sample app.

## Publishing credentials

Maven Central publishing requires a verified Sonatype namespace, deployment credentials, and signed Maven metadata. Store credentials and signing material outside source control using Gradle properties or CI secrets. Never commit passwords, private keys, or keystores.

The intended coordinates are:

```text
groupId:    com.glomopay
artifactId: glomo-android-sdk
version:    2.0.0
```

## Publish

Follow the detailed [Maven Central publishing guide](maven-central-publishing.md) to register the namespace, complete DNS verification, configure signing, and create the publishing credentials. After the Maven publishing plugin and repository credentials are configured:

```bash
MIXPANEL_TOKEN_PROD="<prod-project-token>" ./gradlew :glomo-android-sdk:publish
```

Validate the deployment in the Sonatype Central Portal, then verify that a clean consumer project can resolve the dependency from Maven Central.

## Tag and document

Update `CHANGELOG.md`, review `README.md`, and create a `v`-prefixed Git tag for the library version:

```bash
git tag v2.0.0
git push origin v2.0.0
```

Use the standalone test app APK only for QA. Consumers should depend on the published AAR rather than an APK or unsigned local artifact.

## Checklist

- [ ] Version updated in `glomo-android-sdk/build.gradle.kts`.
- [ ] README dependency version updated.
- [ ] Changelog entry added.
- [ ] Unit and integration tests pass.
- [ ] Release AAR builds successfully with the real `MIXPANEL_TOKEN_PROD` (passes the fingerprint check).
- [ ] AAR contains the expected `proguard.txt` consumer rules.
- [ ] A minified wrapper release build completes and its checkout JavaScript bridge is smoke-tested.
- [ ] Sample app QA completed.
- [ ] Maven metadata and signatures validate.
- [ ] Deployment is visible in Central Portal/Maven Central.
- [ ] Git tag created and pushed.
