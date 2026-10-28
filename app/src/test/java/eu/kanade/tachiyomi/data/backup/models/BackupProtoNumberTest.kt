// KUN -->
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Modifier
import java.util.jar.JarFile

/**
 * Guards the fork-reserved protobuf block (`10000-10999`, see AGENTS.md "Backup protobuf field
 * numbers"). Upstream claims and shipped backups make these numbers permanent, so accidental
 * renumbering must fail the build rather than silently change the wire format.
 */
@OptIn(ExperimentalSerializationApi::class)
class BackupProtoNumberTest {

    @Test
    fun `komi-kun backup fields keep their reserved proto numbers`() {
        val numbers = protoNumbers(BackupManga.serializer().descriptor).toMap()

        assertEquals(10000, numbers["customInfoIncluded"])
        assertEquals(10001, numbers["mergedMangaReferencesIncluded"])
    }

    @Test
    fun `every backup field number is unique and inside the fork block or an upstream number`() {
        val descriptors = backupSerializers()

        assertTrue(
            descriptors.size >= 10,
            "expected every Backup* model in ${BackupManga::class.java.packageName}, found " +
                descriptors.map { it.serialName },
        )

        for (descriptor in descriptors) {
            val numbers = protoNumbers(descriptor)

            val reused = numbers.groupBy { it.second }.filterValues { it.size > 1 }
            assertEquals(
                emptyMap<Int, List<Pair<String, Int>>>(),
                reused,
                "${descriptor.serialName} reuses a proto number",
            )

            val outsideForkBlock = numbers.filter { (_, number) -> number >= 1000 && number !in FORK_BLOCK }
            assertEquals(
                emptyList<Pair<String, Int>>(),
                outsideForkBlock,
                "${descriptor.serialName} has a field number outside the fork block $FORK_BLOCK",
            )
        }
    }

    private fun protoNumbers(descriptor: SerialDescriptor): List<Pair<String, Int>> =
        (0 until descriptor.elementsCount)
            .mapNotNull { index ->
                val number = descriptor.getElementAnnotations(index)
                    .filterIsInstance<ProtoNumber>()
                    .singleOrNull()
                    ?.number
                number?.let { descriptor.getElementName(index) to it }
            }

    /**
     * Every `Backup*` model in this package, discovered from the compiled classes next to
     * [BackupManga] so a newly added model is covered without editing this test.
     */
    private fun backupSerializers(): List<SerialDescriptor> {
        val packagePath = BackupManga::class.java.packageName.replace('.', '/')
        val location = File(BackupManga::class.java.protectionDomain.codeSource.location.toURI())
        val classNames = compiledClassNames(location, packagePath)
            .filter { it.startsWith("Backup") && it.endsWith(".class") && '$' !in it }

        return classNames.sorted().mapNotNull { name ->
            val type = Class.forName(
                BackupManga::class.java.packageName + "." + name.removeSuffix(".class"),
                false,
                BackupManga::class.java.classLoader,
            )
            // The serialization plugin puts `serializer()` on the model's companion object, which is
            // what `BackupManga.serializer()` resolves to. A plain Kotlin class (file facade, value
            // holder) has no companion and is skipped.
            val companion = type.declaredFields
                .firstOrNull { it.name == "Companion" && Modifier.isStatic(it.modifiers) }
                ?.also { it.isAccessible = true }
                ?.get(null)
                ?: return@mapNotNull null
            val serializer = companion.javaClass.methods
                .firstOrNull { it.name == "serializer" && it.parameterCount == 0 }
                ?.invoke(companion)
                ?: return@mapNotNull null
            when (serializer) {
                is SerialDescriptor -> serializer
                is KSerializer<*> -> serializer.descriptor
                else -> null
            }
        }
    }

    /** Simple file names of the compiled classes of [packagePath], from a class directory or a jar. */
    private fun compiledClassNames(location: File, packagePath: String): List<String> {
        if (location.isDirectory) {
            val packageDir = File(location, packagePath)
            assertTrue(packageDir.isDirectory, "cannot locate the compiled backup models at $packageDir")
            return packageDir.listFiles().orEmpty().map { it.name }
        }
        return JarFile(location).use { jar ->
            jar.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("$packagePath/") && !it.removePrefix("$packagePath/").contains('/') }
                .map { it.removePrefix("$packagePath/") }
                .toList()
        }
    }

    private companion object {
        /** komi-kun's reserved block (AGENTS.md "Backup protobuf field numbers"). */
        val FORK_BLOCK = 10000..10999
    }
}
// KUN <--
