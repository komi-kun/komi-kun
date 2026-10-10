import mihon.buildlogic.tasks.GenerateVectorDrawablesTask

plugins {
    id("mihon.library")
    id("mihon.library.compose")
    kotlin("android")

    alias(libs.plugins.valkyrie)
}

android {
    namespace = "mihon.icons.materialsymbols"
}

spotless {
    format("svg") {
        target("src/**/*.svg")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

valkyrie {
    packageName = "mihon.icons.materialsymbols"
    generateAtSync = true

    imageVector {
        suppressUnusedReceiverWarning = true
    }

    iconPack {
        name = "MaterialSymbols"
        targetSourceSet = "main"

        nested {
            name = "Rounded"
            sourceFolder = "rounded"
            autoMirror = false
        }

        nested {
            name = "RoundedFilled"
            sourceFolder = "roundedFilled"
            autoMirror = false
        }

        // https://github.com/ComposeGears/Valkyrie/pull/1019
        nested {
            name = "AutoMirroredRounded"
            sourceFolder = "autoMirroredRounded"
            autoMirror = true
        }

        nested {
            name = "AutoMirroredRoundedFilled"
            sourceFolder = "autoMirroredRoundedFilled"
            autoMirror = true
        }
    }
}

androidComponents {
    onVariants { variant ->
        val resSource = variant.sources.res ?: return@onVariants

        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val task = tasks.register<GenerateVectorDrawablesTask>("generate${variantName}VectorDrawables") {
            svgFiles.from(
                listOf(
                    "rounded/book",
                    "rounded/check",
                    "rounded/close",
                    "rounded/drag_handle",
                    "rounded/eyeglasses_2",
                    "rounded/info",
                    "rounded/more_vert",
                    "rounded/refresh",
                    "rounded/share",
                    "rounded/system_update_alt",
                    "roundedFilled/extension",
                    "roundedFilled/folder",
                    "roundedFilled/pause",
                    "roundedFilled/photo",
                    "roundedFilled/play_arrow",
                    "roundedFilled/warning",
                ).map { layout.projectDirectory.file("src/main/valkyrieResources/$it.svg") },
            )
        }
        resSource.addGeneratedSourceDirectory(task) { it.outputDir }
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
