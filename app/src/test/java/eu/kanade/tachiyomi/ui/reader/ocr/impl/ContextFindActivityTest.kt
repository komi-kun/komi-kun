// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The rotation guard in `ReaderOcrHostImpl.Overlay` asks the host activity exactly one question,
// `isChangingConfigurations`, to tell "the reader is being re-created for a rotation" (the open
// lookup must survive) from "the reader is going away" (the lookup is dismissed). It used to get the
// activity with `LocalContext.current as? Activity`, so any *wrapped* context answered "no activity"
// and the guard fell into the destroy branch: a rotation dismissed the popup it exists to keep, with
// nothing in the logs or the UI to show it. `findActivity` unwraps to the Activity instead.
package eu.kanade.tachiyomi.ui.reader.ocr.impl

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class ContextFindActivityTest {

    @Test
    fun `an activity is found through any number of context wrappers`() {
        val activity = mockk<Activity> {
            every { isChangingConfigurations } returns true
        }

        // The plain case: `LocalContext.current` is the Activity itself.
        activity.findActivity() shouldBe activity
        // The wrapped cases: a theme/`MutableContextWrapper` layer the overlay must see through.
        wrapper(activity).findActivity() shouldBe activity
        wrapper(wrapper(wrapper(activity))).findActivity() shouldBe activity
        // The rotation answer stays reachable once found — that is the whole point of unwrapping.
        wrapper(activity).findActivity()?.isChangingConfigurations shouldBe true
    }

    /**
     * Not every context has an Activity behind it (an application context, a service): the walk must
     * terminate with `null` instead of looping or throwing. `null` keeps the caller's old behaviour
     * for that case (treat it as a real destroy), which is why it must be `null` and not a guess.
     */
    @Test
    fun `a context without an activity behind it reports null`() {
        mockk<Context>().findActivity() shouldBe null
        wrapper(mockk<Context>()).findActivity() shouldBe null
        wrapper(wrapper(mockk<Context>())).findActivity() shouldBe null
    }

    /**
     * The framework's `ContextWrapper` cannot be instantiated on the JVM unit-test classpath (the
     * android.jar stubs throw), so the wrapper is a mock that models the one thing the walk uses:
     * `baseContext`.
     */
    private fun wrapper(base: Context): ContextWrapper = mockk<ContextWrapper> {
        every { baseContext } returns base
    }
}
// KUN <--
