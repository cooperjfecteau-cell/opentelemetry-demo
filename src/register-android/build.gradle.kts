// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
/**
 * The Dynatrace Gradle plugin must be applied in the *root* build file, not in the app module, and
 * it is the only way to configure the Android agent: there is no dynatrace.config.json for native
 * Android (bluebox-demo#40).
 */
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("com.dynatrace.tools.android:gradle-plugin:8.+")
    }
}

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}

apply(plugin = "com.dynatrace.instrumentation")

// Tenant identity never lands in git. The environment wins, a gradle property is the local
// fallback, and the last resort is an obvious placeholder so a clone still builds.
val dtApplicationId: String =
    System.getenv("DT_REGISTER_APP_ID")
        ?: providers.gradleProperty("dt.register.appId").orNull
        ?: "DT_REGISTER_APP_ID_PLACEHOLDER"

val dtBeaconUrl: String =
    System.getenv("DT_REGISTER_BEACON_URL")
        ?: providers.gradleProperty("dt.register.beaconUrl").orNull
        ?: "https://REPLACE-ME.live.dynatrace.com/mbeacon"

configure<com.dynatrace.tools.android.dsl.DynatraceExtension> {
    configurations {
        create("registerConfig") {
            autoStart {
                applicationId(dtApplicationId)
                beaconUrl(dtBeaconUrl)
            }

            // Not a consent story here: applyUserPrivacyOptions() is the only documented way to
            // force a session boundary on Android, and it throws unless user opt-in is on. It is
            // also what carries withScreenRecordOptedIn, so Session Replay depends on it too.
            userOptIn(true)

            // Defaults to false, which sends every app start to RUM Classic instead of Grail. For
            // short synthetic shifts that would lose the first event of each emulator.
            agentBehavior.startupWithGrailEnabled(true)
            agentBehavior.startupLoadBalancing(true)

            // Layer one of three for Session Replay. The other two are the tenant setting
            // (builtin:rum.mobile.enablement -> sessionReplay.fullSessionReplayOnGrail, plus
            // costAndTrafficControl) and the runtime withScreenRecordOptedIn(true) in
            // RegisterApplication.
            sessionReplay.enabled(true)

            // TEMPORARY (bluebox-demo#43 diagnosis): the agent's own enrichment pipeline logs to
            // logcat under dtxEnrichment why it drops custom properties. Off for the fleet.
            debug.agentLogging(true)
        }
    }
}
