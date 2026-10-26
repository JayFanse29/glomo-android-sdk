plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

kotlin {
    explicitApi()
}

group = "com.glomopay"
version = "2.0.0"

val mixpanelStagingToken = providers.gradleProperty("MIXPANEL_TOKEN_STAGING")
    .orElse(providers.environmentVariable("MIXPANEL_TOKEN_STAGING"))
    .orElse("")
val mixpanelProdToken = providers.gradleProperty("MIXPANEL_TOKEN_PROD")
    .orElse(providers.environmentVariable("MIXPANEL_TOKEN_PROD"))
    .orElse("")
val sentryDsn = providers.gradleProperty("SENTRY_DSN")
    .orElse(providers.environmentVariable("SENTRY_DSN"))
    .orElse("")
val nodeBinary = providers.gradleProperty("NODE_BINARY")
    .orElse(providers.environmentVariable("NODE_BINARY"))
    .orElse("node")
val nodeVersion = providers.exec {
    commandLine(nodeBinary.get(), "--version")
}.standardOutput.asText

android {
    namespace = "com.glomopay.sdk.android"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        aarMetadata {
            minCompileSdk = 35
        }
        // Baked into the AAR by the SDK owner. Only exact "true" enables it.
        buildConfigField("boolean", "GLOMO_INTERNAL_BUILD", providers.gradleProperty("GLOMO_INTERNAL_BUILD")
            .orElse(providers.environmentVariable("GLOMO_INTERNAL_BUILD"))
            .map { (it == "true").toString() }.orElse("false").get())
        consumerProguardFiles("consumer-rules.pro")
        resValue("string", "glomopay_sdk_version", project.version.toString())
        resValue("string", "glomopay_sentry_dsn", sentryDsn.get())
    }

    buildTypes {
        debug {
            resValue("string", "glomopay_mixpanel_token", mixpanelStagingToken.get())
        }
        release {
            resValue("string", "glomopay_mixpanel_token", mixpanelProdToken.get())
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

}

val bridgeContractTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the JavaScript bridge contract against the scripts embedded in Kotlin."
    workingDir(rootProject.projectDir)
    commandLine(nodeBinary.get(), file("src/test/js/bridge-contract.cjs").absolutePath)
    inputs.files(
        file("src/test/js/bridge-contract.cjs"),
        file("src/main/java/com/glomopay/sdk/android/bridge/GlomoPayInjectionScripts.kt"),
    )
    doFirst {
        try {
            logger.lifecycle(nodeVersion.get().trim())
        } catch (error: Exception) {
            throw GradleException(
                "bridgeContractTest requires Node.js. Install node on PATH or set NODE_BINARY " +
                    "or -PNODE_BINARY to the node executable.",
                error,
            )
        }
    }
}

tasks.named("check") {
    dependsOn(bridgeContractTest)
}

val verifyReleaseMixpanelToken by tasks.registering {
    group = "verification"
    description = "Fails release packaging and publishing when MIXPANEL_TOKEN_PROD is not set."
    val token = mixpanelProdToken
    doLast {
        if (token.get().isBlank()) {
            throw GradleException(
                "Release builds require MIXPANEL_TOKEN_PROD. Set it as a Gradle property " +
                    "(-PMIXPANEL_TOKEN_PROD=...) or an environment variable.",
            )
        }
    }
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleReleaseAar" || it.name.startsWith("publish") }
    .configureEach { dependsOn(verifyReleaseMixpanelToken) }

// Internal builds have distinct coordinates and cannot replace the merchant artifact.
if (providers.gradleProperty("GLOMO_INTERNAL_BUILD")
        .orElse(providers.environmentVariable("GLOMO_INTERNAL_BUILD")).orNull == "true") {
    version = "$version-internal"
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.scottyab:rootbeer-lib:0.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.0.21")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.0.21")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
