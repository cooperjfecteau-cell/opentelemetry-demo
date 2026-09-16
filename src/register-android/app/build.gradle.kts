// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Build-time defaults for a register that launches with no intent extras. Real identities come
// from extras at launch (see RegisterConfig), so one APK serves all twelve registers.
fun provisioning(name: String, fallback: String): String =
    System.getenv(name) ?: providers.gradleProperty(name.lowercase().replace('_', '.')).orNull ?: fallback

val astroshopBaseUrl: String =
    System.getenv("ASTROSHOP_BASE_URL")
        ?: providers.gradleProperty("astroshop.baseUrl").orNull
        ?: "http://10.0.2.2:8080"

android {
    namespace = "com.astroshop.register"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.astroshop.register"
        // 23 is the Dynatrace agent's floor; 24 keeps the AndroidX and Compose baselines simple.
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "ASTROSHOP_BASE_URL", "\"$astroshopBaseUrl\"")
        buildConfigField("String", "DEFAULT_STORE_ID", "\"${provisioning("STORE_ID", "0142")}\"")
        buildConfigField("String", "DEFAULT_STORE_NAME", "\"${provisioning("STORE_NAME", "Denver Tech Center")}\"")
        buildConfigField("String", "DEFAULT_STORE_REGION", "\"${provisioning("STORE_REGION", "mountain")}\"")
        buildConfigField("String", "DEFAULT_REGISTER_ID", "\"${provisioning("REGISTER_ID", "0142-03")}\"")
        buildConfigField("String", "DEFAULT_CASHIER_NUMBER", "\"${provisioning("CASHIER_NUMBER", "0317")}\"")

        // The checkout identity. It reaches Astro Shop's checkout and nothing else: it is never put
        // on a log, a span, or a Dynatrace event (bluebox-demo#18, #33).
        buildConfigField("String", "STORE_EMAIL", "\"${provisioning("STORE_EMAIL", "store-0142@astroshop.example")}\"")
        buildConfigField("String", "STORE_STREET_ADDRESS", "\"${provisioning("STORE_STREET_ADDRESS", "4200 Orbit Way")}\"")
        buildConfigField("String", "STORE_CITY", "\"${provisioning("STORE_CITY", "Denver")}\"")
        buildConfigField("String", "STORE_STATE", "\"${provisioning("STORE_STATE", "CO")}\"")
        buildConfigField("String", "STORE_COUNTRY", "\"${provisioning("STORE_COUNTRY", "United States")}\"")
        buildConfigField("String", "STORE_ZIP_CODE", "\"${provisioning("STORE_ZIP_CODE", "80237")}\"")
        // The same test card the shopper checkout form defaults to, so payment's validation passes
        // and every sale produces a full backend trace, whatever tender the cashier chose.
        buildConfigField("String", "HOUSE_CARD_NUMBER", "\"${provisioning("HOUSE_CARD_NUMBER", "4432-8015-6152-0454")}\"")
        buildConfigField("int", "HOUSE_CARD_CVV", provisioning("HOUSE_CARD_CVV", "672"))
        buildConfigField("int", "HOUSE_CARD_EXPIRATION_MONTH", provisioning("HOUSE_CARD_EXPIRATION_MONTH", "1"))
        buildConfigField("int", "HOUSE_CARD_EXPIRATION_YEAR", provisioning("HOUSE_CARD_EXPIRATION_YEAR", "2030"))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        compose = true
        buildConfig = true
    }

    lint {
        // The build is the only verification available: no tenant, no emulator.
        abortOnError = true
        warningsAsErrors = false
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Retrofit over OkHttp: the Dynatrace agent auto-instruments OkHttp 3/4/5, which is how the
    // register's calls get web-request events and W3C trace headers without any code here.
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // No Dynatrace dependency is declared here on purpose. The `com.dynatrace.instrumentation`
    // plugin in the root build file injects agent-android, and - because sessionReplay is enabled -
    // android-replay-agent, which is what puts DynatraceSessionReplay, MaskingConfiguration and the
    // Compose `dtMask` modifier on this module's classpath. Declaring them by hand would only
    // invite a version that disagrees with the injected agent.
}
