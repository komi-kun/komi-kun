plugins {
    id("mihon.library")
    kotlin("android")

    // KMK -->
    alias(libs.plugins.metro)
    // KMK <--
}

android {
    namespace = "mihon.core.metro"
}

dependencies {
    implementation(libs.metro.runtime)
}
