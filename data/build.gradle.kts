plugins {
    id("mihon.library")
    kotlin("android")
    kotlin("plugin.serialization")
    alias(libs.plugins.sqldelight)
    // KMK -->
    alias(libs.plugins.metro)
    // KMK <--
}

android {
    namespace = "tachiyomi.data"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    sqldelight {
        databases {
            create("Database") {
                packageName.set("tachiyomi.data")
                dialect(libs.sqldelight.dialects.sql)
                schemaOutputDirectory.set(project.file("./src/main/sqldelight"))
            }
        }
    }
}

kotlin {
    compilerOptions {
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }
}

dependencies {
    implementation(projects.sourceApi)
    implementation(projects.domain)
    implementation(projects.core.common)

    // KMK -->
    implementation(libs.metro.runtime)
    // KMK <--

    implementation(kotlinx.serialization.json)
    implementation(kotlinx.serialization.json.okio)
    implementation(kotlinx.serialization.protobuf)

    api(libs.bundles.sqldelight)
}
