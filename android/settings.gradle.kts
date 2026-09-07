pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DialectInterpreter"
include(":app")
include(":asset_pack_asr")
include(":asset_pack_tts")
include(":asset_pack_mt")
