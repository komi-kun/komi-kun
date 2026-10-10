package mihon.buildlogic.tasks

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider
import java.io.File

private val emptyResourcesElement = "<resources>\\s*</resources>|<resources\\s*/>".toRegex()

fun Project.getLocalesConfigTask(outputResourceDir: File): TaskProvider<Task> {
    return tasks.register("generateLocalesConfig") {
        val localeFiles = layout.projectDirectory.dir("src/commonMain/moko-resources")
            .asFileTree
            .matching { include("**/strings.xml", "**/plurals.xml") }

        inputs.files(localeFiles)
            .withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.dir(outputResourceDir)

        doLast {
            val locales = localeFiles
                .filterNot { it.readText().contains(emptyResourcesElement) }
                .map {
                    it.parentFile.name
                        .replace("base", "en")
                        .replace("-r", "-")
                        .replace("+", "-")
                }
                .distinct()
                .sorted()
                .joinToString("\n") { "|   <locale android:name=\"$it\"/>" }

            val content = """
            |<?xml version="1.0" encoding="utf-8"?>
            |<locale-config xmlns:android="http://schemas.android.com/apk/res/android">
            $locales
            |</locale-config>
            """.trimMargin()

            outputResourceDir.resolve("xml/locales_config.xml").apply {
                parentFile.mkdirs()
                writeText(content)
            }
        }
    }
}
