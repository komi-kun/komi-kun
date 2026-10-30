// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The JNI contract of `nativeProcess`: `ocr-models-v5`'s shim gained a 7th argument, the detector
// long side (`maxSideLen`). The Kotlin side must declare it — otherwise the resolved 960/1280/1600
// has nowhere to go and every mode runs at the shim's 960 — and it must be the 7th slot, because
// the shim reads the arguments positionally. This is a declaration check, not an execution: it
// needs no native code.
//
// The *name* needs no assertion here: `recognizeLocked` passes the limit as the named argument
// `maxSideLen = detectorLimitSide`, so a renamed parameter fails to compile.
// KUN <--
package mihon.core.ocr.paddle

import android.graphics.Bitmap
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class PaddleOcrEngineJniTest {

    @Test
    fun `nativeProcess carries the v5 signature with the detector limit as its seventh argument`() {
        val method = PaddleOcrEngine::class.java.declaredMethods.single { it.name == "nativeProcess" }

        method.parameterTypes.toList() shouldBe listOf(
            Bitmap::class.java,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        // Still the JNI entry point, not an ordinary Kotlin function the shim could not bind.
        Modifier.isNative(method.modifiers) shouldBe true
    }
}
