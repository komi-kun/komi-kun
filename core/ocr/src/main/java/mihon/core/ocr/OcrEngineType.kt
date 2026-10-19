// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.ocr

/**
 * OCR Engine model type.
 *
 * - [PP_OCRV6_SMALL]: On-device 2-stage DBNet + CTC ONNX Runtime model (~16 MB, ~50 ms).
 * - [PADDLEOCR_VL]: High-precision Vision-Language Model (~0.9B parameters) for complex layouts & stylised text.
 */
enum class OcrEngineType(val id: String) {
    PP_OCRV6_SMALL("pp_ocrv6_small"),
    PADDLEOCR_VL("paddleocr_vl"),
    ;

    companion object {
        fun fromId(id: String?): OcrEngineType =
            entries.firstOrNull { it.id == id } ?: PP_OCRV6_SMALL
    }
}
// KUN <--
