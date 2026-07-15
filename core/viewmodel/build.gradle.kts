plugins {
    id("mihon.library")
    kotlin("android")
}

android {
    namespace = "mihon.core.viewmodel"
}

dependencies {
    implementation(androidx.lifecycle.viewmodel)
}
