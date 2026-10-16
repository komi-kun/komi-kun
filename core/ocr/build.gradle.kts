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
// Paddle OCR native artifact fetch (contract §1.7).
//
// The real `libpaddle_ocr.so` is a *separate artifact*: it is our own JNI over Paddle-Lite
// (Apache-2.0) published to `komi-kun/komi-kun-ocr-models`, together with `libomp.so` and a
// per-file SHA-256 `manifest.json`. This task only *consumes* that manifest — it never builds
// Paddle-Lite.
//
// Wiring: `fetchPaddleNativeLibs` is a dependency of the per-variant JNI/native-lib merge tasks,
// so every `assemble*` packages the fetched `.so`s (see README.md). It is deliberately NOT a
// dependency of `preBuild`, so the inner loop (`compileDebugKotlin`, `testDebugUnitTest`) stays
// offline. The task declares its expected outputs (derived from the manifest) and is therefore
// UP-TO-DATE after the first successful fetch — repeat builds hit no network.
//
// The manifest lives at `core/ocr/paddle-manifest.json`; each native entry has `abi`, `name`,
// `path` (the flat release-asset name), `sha256`, `size` and `spdx`. Files land in
// `src/main/jniLibs/<abi>/<name>` (git-ignored). Entries without an `abi` are the runtime-
// downloaded models (see `paddle/ModelDownloader`), not native libs, and are skipped here.
// ---------------------------------------------------------------------------------------------
val paddleManifestFile = layout.projectDirectory.file("paddle-manifest.json")
val paddleJniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")

// Native-lib entries resolved at configuration time so the fetch task can declare them as outputs
// (making it UP-TO-DATE once present). Model entries (no `abi`) download at runtime, not here.
val paddleTargets: List<File> = run {
    val manifest = paddleManifestFile.asFile
    if (!manifest.isFile) {
        emptyList()
    } else {
        @Suppress("UNCHECKED_CAST")
        val root = groovy.json.JsonSlurper().parse(manifest) as Map<String, Any?>
        (root["files"] as? List<*>).orEmpty()
            .filterIsInstance<Map<*, *>>()
            .mapNotNull { entry ->
                val abi = (entry["abi"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = (entry["name"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                paddleJniLibsDir.asFile.resolve("$abi/$name")
            }
    }
}

val fetchPaddleNativeLibs = tasks.register("fetchPaddleNativeLibs") {
    group = "ocr"
    description = "Fetch + checksum-verify the Paddle OCR native libs into src/main/jniLibs/<abi>/."
    // Captured as plain Files so the task stays configuration-cache friendly (no `project`/`layout`
    // reference at execution time).
    val manifest = paddleManifestFile.asFile
    val jniLibs = paddleJniLibsDir.asFile
    inputs.file(manifest).optional()
    outputs.files(paddleTargets)
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
            // Entries without an `abi` are the runtime-downloaded models, not native libs.
            val abi = (entry["abi"] as? String)?.takeIf { it.isNotBlank() } ?: return@forEach
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
            // Release assets are flat (a `/` in an asset name is rewritten), so the URL is built from
            // the manifest's `path`, not from `$abi/$name`. Fall back to the nested layout if absent.
            val assetPath = (entry["path"] as? String)?.takeIf { it.isNotBlank() } ?: "$abi/$name"
            val url = "$baseUrl/$assetPath"
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

// Package the fetched `.so`s into every APK/AAR. Hooking the per-variant JNI / native-lib merge
// tasks (not `preBuild`) means only packaging pulls the artifact, so `compile*`/`test*` stay
// offline; combined with the declared outputs above, the fetch itself is UP-TO-DATE after the
// first run. See README.md §"PaddleOCR artifact (integration point)".
tasks.matching {
    it.name.startsWith("merge") &&
        (it.name.endsWith("JniLibFolders") || it.name.endsWith("NativeLibs"))
}.configureEach {
    dependsOn(fetchPaddleNativeLibs)
}

// KUN <--
