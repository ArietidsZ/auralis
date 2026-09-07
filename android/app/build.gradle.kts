plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.dialect.interpreter"
    compileSdk = 35
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "com.dialect.interpreter"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-preview.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")  // Native libraries are validated only for arm64.
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    }

    lint {
        disable += setOf(
            "ChromeOsAbiSupport",
            "GradleDependency",
            "ObsoleteSdkInt",
            "ObsoleteLintCustomCheck"
        )
    }

    // No compression for ONNX model files in assets
    androidResources {
        noCompress += listOf("onnx", "json", "model")
    }

    // Use asset packs for large model files via Play Asset Delivery
    assetPacks += listOf(":asset_pack_asr", ":asset_pack_tts", ":asset_pack_mt")

    // Bundle the canonical dialect catalog (from shared/) as a generated app asset
    // so the runtime consumes a single source of truth instead of a hardcoded list.
    sourceSets.getByName("main") {
        assets.srcDir(layout.buildDirectory.dir("generated/sharedAssets"))
    }
}

// Copy canonical shared contracts from ../shared/ into generated app assets so the
// runtime consumes a single source of truth; Kotlin/Swift/Python never hardcode them.
//   assets/dialect/catalog.json      <- shared/dialect-catalog/catalog.json
//   assets/models/{asr,mt,tts}.json  <- shared/model-manifests/*.json (v2)
// Contract map: docs/specs/2026-09-05-auralis/reports/interface-C.md
tasks.register<Copy>("syncSharedDialectCatalog") {
    from(rootProject.file("../shared/dialect-catalog/catalog.json"))
    into(layout.buildDirectory.dir("generated/sharedAssets/dialect"))
}

tasks.register<Copy>("syncSharedModelManifests") {
    from(rootProject.file("../shared/model-manifests")) {
        include("*.json")
    }
    into(layout.buildDirectory.dir("generated/sharedAssets/models"))
}
tasks.register<Sync>("syncThirdPartyNotices") {
    from(rootProject.file("../licenses"))
    from(rootProject.file("../LICENSE"))
    from(rootProject.file("../THIRD_PARTY_NOTICES.md"))
    into(layout.buildDirectory.dir("generated/sharedAssets/legal"))
}
tasks.named("preBuild").configure {
    dependsOn("syncSharedDialectCatalog", "syncSharedModelManifests", "syncThirdPartyNotices")
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")

    // Core Android
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // ONNX Runtime - TTS / speaker-encoder Java API.
    // ASR JNI and Java TTS share this exact Runtime; see jniLibs/SHERPA_ONNX.md.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.2")

    // Play Asset Delivery - for bundled model files
    implementation("com.google.android.play:asset-delivery-ktx:2.2.2")

    // DataStore for settings
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // JSON parsing
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Test
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
