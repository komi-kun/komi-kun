pluginManagement {
    resolutionStrategy {
        eachPlugin {
            val regex = "com.android.(library|application)".toRegex()
            if (regex matches requested.id.id) {
                useModule("com.android.tools.build:gradle:${requested.version}")
            }
        }
    }
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven(url = "https://www.jitpack.io")
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    versionCatalogs {
        create("kotlinx") {
            from(files("gradle/kotlinx.versions.toml"))
        }
        create("androidx") {
            from(files("gradle/androidx.versions.toml"))
        }
        create("compose") {
            from(files("gradle/compose.versions.toml"))
        }
        create("sylibs") {
            from(files("gradle/sy.versions.toml"))
        }
    }

    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    @Suppress("UnstableApiUsage")
    repositories {
        // KUN -->
        // In-repo Maven repository for the komi-kun/webgpuviewer fork `:library` artifact
        // (`ocr-accessors-1`, §1.6/§2.6). JitPack cannot build the fork's AGP 9.4.1 project,
        // so the release AAR published by the fork is vendored here. Remove this line and
        // `maven/` once the accessors land upstream and the JitPack coordinate works again.
        maven { url = uri("$rootDir/maven") }
        // KUN <--
        mavenCentral()
        google()
        maven(url = "https://www.jitpack.io")
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// KUN -->
rootProject.name = "Komikun"
// KUN <--
include(":app")
include(":baseline-profile")
include(":core-metadata")
include(":core:archive")
include(":core:common")
// KUN -->
include(":core:dictionary")
include(":core:metro")
include(":core:ocr")
// KUN <--
include(":data")
include(":domain")
include(":i18n")
// KMK -->
include(":i18n-kmk")
include(":flagkit")
// KMK <--
// SY -->
include(":i18n-sy")
// SY <--
include(":icons:custom-material-symbols")
include(":icons:material-symbols")
include(":icons:simple-icons")
include(":presentation-core")
include(":presentation-widget")
include(":source-api")
include(":source-local")
include(":telemetry")
