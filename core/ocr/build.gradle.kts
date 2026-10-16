// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// :core:ocr — PaddleOCR + `.mokuro` + box-merge + overlay math (GPL-3.0-or-later).
// Engine-agnostic OCR IR + pipeline (contract §2.2/§3.3); the Paddle `.so` is a separate,
// checksummed artifact fetched by `fetchPaddleNativeLibs` (contract §1.7), not built here.
// Hard rule: this module must never depend on `:core:dictionary` (contract §1.1).
// KUN <--
import java.net.URI
import java.security.MessageDigest

plugins {
    id("mihon.library")
    kotlin("plugin.serialization")
    alias(libs.plugins.metro)
}

android {
    namespace = "mihon.core.ocr"

    defaultConfig {
        ndk {
            // ONE ABI set for all native artifacts (contract §1.5); the fetched `.so`s land in
            // `src/main/jniLibs/<abi>/` (contract §1.7).
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }
}

dependencies {
    // KUN -->
    implementation(projects.core.archive)
    implementation(projects.core.common)
    implementation(projects.core.metro)

    implementation(libs.metro.runtime)

    implementation(libs.image.decoder)

    implementation(platform(kotlinx.coroutines.bom))
    implementation(kotlinx.coroutines.core)
    implementation(kotlinx.serialization.json)

    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)
    // KUN <--
}

// KUN -->
// ---------------------------------------------------------------------------------------------
// Paddle OCR native artifact fetch skeleton (contract §1.7).
//
// The real `libpaddle_ocr.so` is a *separate artifact task*: it is our own JNI over Paddle-Lite
// (Apache-2.0) published to `komi-kun/komi-kun-ocr-models`, together with `libomp.so` /
// `libc++_shared.so` and a per-file SHA-256 `manifest.json`. This task only *consumes* that
// manifest — it never builds Paddle-Lite. It is intentionally NOT wired into `preBuild`/`assemble`
// so offline builds (and the W3 gates) stay green until the artifact actually exists; run it
// explicitly with `./gradlew :core:ocr:fetchPaddleNativeLibs`.
//
// The manifest lives at `core/ocr/paddle-manifest.json`; each entry has `abi`, `name`, `sha256`,
// `size` and `spdx`. Files land in `src/main/jniLibs/<abi>/<name>` (contract §1.7 option A).
// ---------------------------------------------------------------------------------------------
val paddleManifestFile = layout.projectDirectory.file("paddle-manifest.json")
val paddleJniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")

tasks.register("fetchPaddleNativeLibs") {
    group = "ocr"
    description = "Fetch + checksum-verify the Paddle OCR native libs into src/main/jniLibs/<abi>/."
    // Captured as plain Files so the task stays configuration-cache friendly (no `project`/`layout`
    // reference at execution time).
    val manifest = paddleManifestFile.asFile
    val jniLibs = paddleJniLibsDir.asFile
    inputs.file(manifest).optional()
    outputs.upToDateWhen { false }
    doLast {
        // Local helper (no script-object capture) so the task serializes under the configuration cache.
        fun sha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        if (!manifest.isFile) {
            logger.lifecycle("[ocr] no paddle-manifest.json — skipping (artifact not published yet)")
            return@doLast
        }

        @Suppress("UNCHECKED_CAST")
        val root = groovy.json.JsonSlurper().parse(manifest) as Map<String, Any?>
        val baseUrl = (root["baseUrl"] as? String)?.trimEnd('/')
            ?: error("[ocr] paddle-manifest.json is missing `baseUrl`")
        val files = (root["files"] as? List<*>)
            .orEmpty()
            .filterIsInstance<Map<*, *>>()
        if (files.isEmpty()) {
            logger.lifecycle("[ocr] paddle-manifest.json lists no files — skipping")
            return@doLast
        }

        files.forEach { entry ->
            val abi = entry["abi"] as? String ?: error("[ocr] manifest entry without `abi`")
            val name = entry["name"] as? String ?: error("[ocr] manifest entry without `name`")
            val expectedSha = (entry["sha256"] as? String)?.lowercase().orEmpty()
            val target = jniLibs.resolve("$abi/$name")
            if (expectedSha.isEmpty()) {
                logger.warn("[ocr] $abi/$name has no checksum in the manifest — skipping (artifact not published yet)")
                return@forEach
            }
            if (target.isFile && sha256Hex(target) == expectedSha) {
                logger.lifecycle("[ocr] $abi/$name already present + verified")
                return@forEach
            }
            val url = "$baseUrl/$abi/$name"
            logger.lifecycle("[ocr] downloading $url")
            target.parentFile.mkdirs()
            URI(url).toURL().openStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            check(sha256Hex(target) == expectedSha) {
                "[ocr] SHA-256 mismatch for $abi/$name — refusing to keep a bad artifact"
            }
        }
    }
}

// KUN <--
