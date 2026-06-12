plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.android.libraries.mapsplatform.secrets-gradle-plugin")
}

android {
    namespace = "com.rayneo.visionclaw"
    compileSdk = 35

    defaultConfig {
        // Public release identity (0.2b): distinct applicationId so the
        // public build installs alongside a private build on the glasses.
        applicationId = "uk.tapinsight.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.2b"

        // Operator relay base (Cloudflare-tunnelled image_relay.py), e.g.
        // "https://relay.example.com". NOT committed: set
        // TAPINSIGHT_RELAY_BASE in ~/.gradle/gradle.properties. Blank means
        // "derive the relay from the configured agent endpoint at runtime"
        // (RelayUrlHelper) — the app works without it.
        buildConfigField(
            "String",
            "DEFAULT_RELAY_BASE",
            "\"${(project.findProperty("TAPINSIGHT_RELAY_BASE") as? String).orEmpty()}\""
        )

        // GEMINI_API_KEY generated HERE (not by the secrets plugin — its
        // empty-value handling emits invalid Java; see ignoreList below).
        // Optional ~/.gradle/gradle.properties: GEMINI_API_KEY=AIza...
        // Blank is fine: every consumer null-checks and falls back to the
        // key configured in the companion app at runtime.
        buildConfigField(
            "String",
            "GEMINI_API_KEY",
            "\"${(project.findProperty("GEMINI_API_KEY") as? String).orEmpty()}\""
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
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
        buildConfig = true
    }
}

secrets {
    propertiesFileName = "local.properties"
    defaultPropertiesFileName = "local.defaults.properties"
    ignoreList.add("sdk.dir")
    // Generated explicitly in defaultConfig instead — the plugin emits
    // `= ;` (invalid Java) for blank values.
    ignoreList.add("GEMINI_API_KEY")
}

dependencies {
    implementation(project(":tapbrowser"))

    // TapBrowser runtime SDKs (compileOnly in :tapbrowser, provided here for runtime)
    implementation(files("../tapbrowser/libs/MercuryAndroidSDK-v0.2.2-20250717110238_48b655b3.aar"))
    implementation(files("../tapbrowser/libs/RayNeoIPCSDK-For-Android-V0.1.0-20231128201840_9b41f025.aar"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    // Phase 4d: LifecycleService for GeminiSessionForegroundService —
    // gives the Service a LifecycleOwner so CameraX can bind to it.
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("com.google.guava:guava:33.4.0-android")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    implementation("org.bouncycastle:bcprov-jdk18on:1.80")

    // Lightweight embedded HTTP server for companion config page
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}

configurations.all {
    resolutionStrategy {
        force("androidx.core:core-ktx:1.15.0")
        force("androidx.core:core:1.15.0")
    }
    if (name.contains("ClasspathCopy")) {
        isCanBeConsumed = false
    }
}
configurations.all {
    if (name.contains("debugRuntimeClasspathCopy")) {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
}

configurations.all {
    if (name.contains("ClasspathCopy")) {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
}
