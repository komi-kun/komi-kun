plugins {
    id("mihon.library")
    id("mihon.library.compose")
    kotlin("plugin.serialization")
}

android {
    namespace = "mihon.core.navigation"
}

dependencies {
    implementation(projects.presentationCore)
    implementation(androidx.bundles.navigation3)

    api(libs.compose.materialmotion)
    api(compose.ui)
}
