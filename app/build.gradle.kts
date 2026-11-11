plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ghaly.findme"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ghaly.findme"
        minSdk = 26
        targetSdk = 34
        // Bump BOTH of these with every change that ships: CI publishes them
        // in version.json alongside the APK, and that's how a phone already
        // running the app recognizes a newer build exists (see
        // com.ghaly.findme.update.UpdateChecker) and offers to install it
        // in-app, instead of someone having to go back to GitHub and
        // download the APK by hand every time.
        versionCode = 20
        versionName = "0.20.0"
    }

    // Without this, every CI build gets signed with a throwaway key that
    // Android's build tools auto-generate on whatever fresh runner happens to
    // build it — a different one each time. Installing a newer APK over an
    // older one only counts as an "update" (keeping all of a user's local
    // data — saved places, chat history, notes, profile) when both are
    // signed with the SAME key; otherwise Android refuses the install until
    // the old app is uninstalled first, wiping everything. Pointing debug
    // builds at one committed keystore (keystore/debug.keystore — a debug-
    // only key, safe to commit, not used to publish anywhere) makes every
    // build from here on share one signature, so updates actually update.
    signingConfigs {
        getByName("debug") {
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.fragment:fragment-ktx:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.json:json:20240303")

    // Sunset/sunrise calculation (NOAA solar position algorithm), used for the
    // "daylight remaining" indicator. Pure Java, no network, no dependencies —
    // matters here since a wrong hand-rolled formula would be actively
    // dangerous in a desert-safety context.
    implementation("org.shredzone.commons:commons-suncalc:3.11")
}
