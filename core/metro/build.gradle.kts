plugins {
    id("mihon.library")
    kotlin("android")

    alias(libs.plugins.metro)
}

android {
    namespace = "mihon.core.metro"
}

dependencies {
    implementation(libs.metro.runtime)
}
