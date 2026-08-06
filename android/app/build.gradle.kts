plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.dialect.interpreter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dialect.interpreter"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")  // NPU only on 64-bit ARM
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

// Copy the canonical dialect catalog from shared/ into a generated asset dir.
// shared/dialect-catalog/catalog.json is the single source of truth; the app
// loads it at runtime (see DialectCatalog), so Kotlin/Swift never hardcode dialects.
tasks.register<Copy>("syncSharedDialectCatalog") {
    from(rootProject.file("../shared/dialect-catalog/catalog.json"))
    into(layout.buildDirectory.dir("generated/sharedAssets/dialect"))
}
tasks.named("preBuild").configure { dependsOn("syncSharedDialectCatalog") }

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

    // ONNX Runtime - core engine for on-device inference.
    // v1.22+ ships KleidiAI-optimized MLAS INT4 kernels (28-51% uplift) on Arm.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")

    // Play Asset Delivery - for bundled model files
    implementation("com.google.android.play:asset-delivery-ktx:2.2.2")

    // DataStore for settings
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // JSON parsing
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Google Fonts
    implementation("androidx.compose.ui:ui-text-google-fonts")

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
