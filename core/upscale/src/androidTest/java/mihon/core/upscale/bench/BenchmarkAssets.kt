// SPDX-License-Identifier: GPL-3.0-or-later
// The directory contract for the on-device upscaling benchmark (phase 3b writes the test itself).
//
// The benchmark needs two things on the device that are deliberately not in the repository: the
// published `.onnx` weights (CC BY-NC 4.0, never bundled in the APK) and a couple of real pages. Both
// are pushed by hand before the run and live outside the app's private storage, in the app's
// *external* files directory:
//
//   adb shell mkdir -p /sdcard/Android/data/<applicationId>/files/upscale-bench/{models,pages}
//   adb push <dist>/2x_MangaJaNai_1600p_V1_ESRGAN_90k.onnx \
//       /sdcard/Android/data/<applicationId>/files/upscale-bench/models/
//   adb push <page>.jpg /sdcard/Android/data/<applicationId>/files/upscale-bench/pages/
//
// NOTE: the destination is the *package's own* external files directory
// (`Context.getExternalFilesDir(null)`), so it is the package that is running that matters: the
// instrumented test package (`mihon.core.upscale.test`) when the benchmark runs as this module's
// androidTest, `app.komikun.dev` for a debug build, `app.komikun.benchmark` for the benchmark type.
//
// Locating it is `File(getExternalFilesDir(null), "upscale-bench")`, i.e. it resolves to
// `<externalFilesDir>/upscale-bench` — `getExternalFilesDir(null)` already ends in `/files`, so the
// directory name is a plain child and not a `../files/...` path. That keeps one literal path in the
// helper and in the `adb push` above. [missing] is the "skip the test, do not fail it" gate: a
// benchmark with no assets must report itself as skipped, never as a pass with no data and never as a
// failure that looks like a regression.
package mihon.core.upscale.bench

import android.content.Context
import mihon.core.upscale.model.UpscaleModel
import java.io.File

object BenchmarkAssets {

    /** Directory name under the app's external files directory. */
    private const val DIR = "upscale-bench"

    /** Subdirectory of the weights, one flat file per catalog `fileName`. */
    const val MODELS = "models"

    /** Subdirectory of the pages to upscale. */
    const val PAGES = "pages"

    /** File extensions [pages] accepts, lower-case. */
    private val PAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

    /** The benchmark's root directory; may not exist. */
    fun root(context: Context): File = File(externalFilesDir(context), DIR)

    /** Where the published `.onnx` weights are pushed; may not exist. */
    fun modelsDir(context: Context): File = File(root(context), MODELS)

    /** Where the pages to upscale are pushed; may not exist. */
    fun pagesDir(context: Context): File = File(root(context), PAGES)

    /** The pushed copy of [model]'s weights (the catalog's `fileName`, flat). */
    fun modelFile(context: Context, model: UpscaleModel): File =
        File(modelsDir(context), model.fileName)

    /** Every pushed page, by name, so a run is reproducible. */
    fun pages(context: Context): List<File> = pagesDir(context)
        .listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension.lowercase() in PAGE_EXTENSIONS }
        .sortedBy { it.name }

    /**
     * The assets [models] needs that are not on the device yet. Empty means the benchmark can run;
     * anything else is the reason a phase-3b test skips (`assumeTrue`) instead of measuring nothing.
     */
    fun missing(context: Context, models: Collection<UpscaleModel>): List<File> =
        models.map { modelFile(context, it) }.filterNot { it.isFile && it.length() > 0L } +
            if (pages(context).isEmpty()) listOf(pagesDir(context)) else emptyList()

    private fun externalFilesDir(context: Context): File = checkNotNull(
        context.getExternalFilesDir(null),
    ) { "external files directory is unavailable (is the device's storage mounted?)" }
}
