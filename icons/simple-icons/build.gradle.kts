plugins {
    id("mihon.library")
    id("mihon.library.compose")
    kotlin("android")

    alias(libs.plugins.valkyrie)
}

android {
    namespace = "mihon.icons.simpleicons"
}

spotless {
    format("svg") {
        target("src/**/*.svg")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

valkyrie {
    packageName = "mihon.icons.simpleicons"
    generateAtSync = true

    imageVector {
        suppressUnusedReceiverWarning = true
    }

    iconPack {
        name = "SimpleIcons"
        targetSourceSet = "main"
    }
}

// KMK -->
// Valkyrie 0.5.2 wires its generated sources through AGP's built-in Kotlin source
// directories, which are not populated when the external Kotlin Android plugin is used
// (AGP 8.x). Register the generated directory with the Kotlin Android source set instead.
kotlin {
    sourceSets["main"].kotlin.srcDir(
        layout.buildDirectory.dir("generated/sources/valkyrie/main/kotlin"),
    )
}
// KMK <--

dependencies {
    api(compose.ui)
}
