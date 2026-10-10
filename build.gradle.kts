buildscript {
    dependencies {
        classpath(libs.android.shortcut.gradle)
        classpath(sylibs.gradleversionsx)
    }
}

plugins {
    alias(kotlinx.plugins.serialization) apply false
    alias(libs.plugins.aboutLibraries) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.moko) apply false
    alias(libs.plugins.sqldelight) apply false
    // KMK -->
    alias(libs.plugins.metro) apply false
    // KMK <--
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
