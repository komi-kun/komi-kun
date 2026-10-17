// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.engine

import io.kotest.matchers.shouldBe
import mihon.core.dictionary.model.DictionaryStyle
import mihon.core.dictionary.model.Frequency
import mihon.core.dictionary.model.FrequencyEntry
import mihon.core.dictionary.model.GlossaryEntry
import mihon.core.dictionary.model.ImportResult
import mihon.core.dictionary.model.KanjiEntry
import mihon.core.dictionary.model.KanjiResult
import mihon.core.dictionary.model.LookupResult
import mihon.core.dictionary.model.PitchEntry
import mihon.core.dictionary.model.TermResult
import mihon.core.dictionary.model.TransformGroup
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Contract §2.1/§3.5: `libhoshidicts_jni.so` resolves the model records by **exact** binary
 * name + constructor descriptor (`FindClass`/`GetMethodID`), so a field change that isn't
 * mirrored in the C++ must fail loudly here rather than crash at runtime.
 *
 * Each expected descriptor is a verbatim `GetMethodID` literal from
 * `core/dictionary/src/main/cpp/hoshidicts_jni.cpp`; change both together.
 */
@Execution(ExecutionMode.CONCURRENT)
class JniSignatureTest {

    @Test
    fun `model constructors match the C++ JNI descriptors`() {
        val actual = listOf(
            ImportResult::class.java,
            DictionaryStyle::class.java,
            Frequency::class.java,
            FrequencyEntry::class.java,
            GlossaryEntry::class.java,
            PitchEntry::class.java,
            TermResult::class.java,
            LookupResult::class.java,
            TransformGroup::class.java,
            KanjiEntry::class.java,
            KanjiResult::class.java,
        ).associate { it.name to it.jniConstructorDescriptor() }

        actual shouldBe expectedDescriptors
    }
}

private val expectedDescriptors = mapOf(
    "mihon.core.dictionary.model.ImportResult" to
        "(ZLjava/lang/String;JJJJJJLjava/util/List;)V",
    "mihon.core.dictionary.model.DictionaryStyle" to
        "(Ljava/lang/String;Ljava/lang/String;)V",
    "mihon.core.dictionary.model.Frequency" to
        "(ILjava/lang/String;)V",
    "mihon.core.dictionary.model.FrequencyEntry" to
        "(Ljava/lang/String;Ljava/util/List;)V",
    "mihon.core.dictionary.model.GlossaryEntry" to
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
    "mihon.core.dictionary.model.PitchEntry" to
        "(Ljava/lang/String;[ILjava/util/List;)V",
    "mihon.core.dictionary.model.TermResult" to
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/util/List;Ljava/util/List;Ljava/util/List;)V",
    "mihon.core.dictionary.model.LookupResult" to
        "(Ljava/lang/String;Ljava/lang/String;Ljava/util/List;Lmihon/core/dictionary/model/TermResult;I)V",
    "mihon.core.dictionary.model.TransformGroup" to
        "(Ljava/lang/String;Ljava/lang/String;)V",
    "mihon.core.dictionary.model.KanjiEntry" to
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/util/List;Ljava/util/Map;)V",
    "mihon.core.dictionary.model.KanjiResult" to
        "(Ljava/lang/String;Ljava/util/List;)V",
)

/** JNI descriptor of the record's primary constructor, e.g. `(ILjava/lang/String;)V`. */
private fun Class<*>.jniConstructorDescriptor(): String {
    val primary = declaredConstructors.single { !it.isSynthetic }
    val arguments = primary.parameterTypes.joinToString("") { it.jniDescriptor() }
    return "($arguments)V"
}

private fun Class<*>.jniDescriptor(): String = when {
    this == java.lang.Boolean.TYPE -> "Z"
    this == java.lang.Byte.TYPE -> "B"
    this == java.lang.Character.TYPE -> "C"
    this == java.lang.Short.TYPE -> "S"
    this == Integer.TYPE -> "I"
    this == java.lang.Long.TYPE -> "J"
    this == java.lang.Float.TYPE -> "F"
    this == java.lang.Double.TYPE -> "D"
    this == Void.TYPE -> "V"
    isArray -> "[" + requireNotNull(componentType).jniDescriptor()
    else -> "L" + name.replace('.', '/') + ";"
}
