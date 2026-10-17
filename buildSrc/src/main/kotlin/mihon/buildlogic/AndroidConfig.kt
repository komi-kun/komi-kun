package mihon.buildlogic

import org.gradle.api.JavaVersion as GradleJavaVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget as KotlinJvmTarget

object AndroidConfig {
    // Minor API level form, mirrors mihon/main's `android-sdk-compile = "37.2"`.
    const val COMPILE_SDK = "37.2"
    const val TARGET_SDK = 36
    const val MIN_SDK = 26

    // https://youtrack.jetbrains.com/issue/KT-66995/JvmTarget-and-JavaVersion-compatibility-for-easier-JVM-version-setup
    val JavaVersion = GradleJavaVersion.VERSION_17
    val JvmTarget = KotlinJvmTarget.JVM_17

    // KUN -->
    // Native toolchain for the vendored dictionary engine (:core:dictionary). Pinned so the
    // module and CI build against the same NDK/CMake (contract §1.4).
    const val NDK_VERSION = "29.0.14206865"
    const val CMAKE_VERSION = "3.22.1"
    // KUN <--
}
