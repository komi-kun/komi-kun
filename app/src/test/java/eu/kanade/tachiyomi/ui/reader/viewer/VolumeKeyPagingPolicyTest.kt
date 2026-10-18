package eu.kanade.tachiyomi.ui.reader.viewer

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies the rule that decides whether a volume-key press may be turned into page navigation.
 *
 * While the OCR dictionary overlay is enabled the answer is always "no": the overlay is where the
 * user looks words up and plays the dictionary's word audio, so the volume keys must reach the
 * system and change the media volume. With the overlay off the existing behaviour is untouched
 * (the "volume keys turn pages" preference and the menu gate still apply).
 */
class VolumeKeyPagingPolicyTest {

    @Test
    fun `pages when the preference is on, the menu is hidden and the overlay is off`() {
        assertTrue(
            VolumeKeyPagingPolicy.shouldConsumeForPaging(
                volumeKeysEnabled = true,
                menuVisible = false,
                ocrOverlayEnabled = false,
            ),
        )
    }

    @Test
    fun `never pages while the ocr overlay is enabled`() {
        assertFalse(
            VolumeKeyPagingPolicy.shouldConsumeForPaging(
                volumeKeysEnabled = true,
                menuVisible = false,
                ocrOverlayEnabled = true,
            ),
        )
    }

    @Test
    fun `overlay still overrides when the menu is visible`() {
        assertFalse(
            VolumeKeyPagingPolicy.shouldConsumeForPaging(
                volumeKeysEnabled = true,
                menuVisible = true,
                ocrOverlayEnabled = true,
            ),
        )
    }

    @Test
    fun `never pages when the preference is off`() {
        assertFalse(
            VolumeKeyPagingPolicy.shouldConsumeForPaging(
                volumeKeysEnabled = false,
                menuVisible = false,
                ocrOverlayEnabled = false,
            ),
        )
    }

    @Test
    fun `never pages while the reader menu is visible`() {
        assertFalse(
            VolumeKeyPagingPolicy.shouldConsumeForPaging(
                volumeKeysEnabled = true,
                menuVisible = true,
                ocrOverlayEnabled = false,
            ),
        )
    }
}
