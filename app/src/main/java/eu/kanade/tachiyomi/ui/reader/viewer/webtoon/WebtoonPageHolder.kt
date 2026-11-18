package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updateMargins
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.ocr.OcrDisplayTransform
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageErrorView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.reader.viewer.processStripImage
import eu.kanade.tachiyomi.util.system.dpToPx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okio.Buffer
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat

/**
 * Holder of the webtoon reader for a single page of a chapter.
 *
 * @param frame the root view for this holder.
 * @param viewer the webtoon viewer.
 * @constructor creates a new webtoon holder.
 */
class WebtoonPageHolder(
    private val frame: ReaderPageImageView,
    viewer: WebtoonViewer,
    // KMK -->
    @ColorInt private val seedColor: Int? = null,
    // KMK <--
) : WebtoonBaseHolder(frame, viewer) {

    /**
     * Loading progress bar to indicate the current progress.
     */
    private val progressIndicator = createProgressIndicator()

    /**
     * Progress bar container. Needed to keep a minimum height size of the holder, otherwise the
     * adapter would create more views to fill the screen, which is not wanted.
     */
    private lateinit var progressContainer: ViewGroup

    /**
     * Error layout to show when the image fails to load.
     */
    private var errorLayout: ReaderPageErrorView? = null

    /**
     * Getter to retrieve the height of the recycler view.
     */
    private val parentHeight
        get() = viewer.recycler.height

    /**
     * Page of a chapter.
     */
    private var page: ReaderPage? = null

    private val scope = MainScope()

    /**
     * Job for loading the page.
     */
    private var loadJob: Job? = null

    // KUN -->
    /**
     * How the displayed sheet differs from the raw page image (webtoon "split and merge" only), for
     * the OCR overlay mapping. Crop borders are applied at decode time and are a documented v1
     * limitation (the trim rect is not exposed here).
     */
    @Volatile
    private var ocrTransform: OcrDisplayTransform = OcrDisplayTransform.None

    fun ocrPage(): ReaderPage? = page

    fun ocrFrame(): ReaderPageImageView = frame

    fun ocrDisplayTransform(): OcrDisplayTransform = ocrTransform
    // KUN <--

    init {
        refreshLayoutParams()

        frame.onImageLoaded = { onImageDecoded() }
        frame.onImageLoadError = { error -> setError(error) }
        frame.onScaleChanged = { viewer.activity.hideMenu() }
    }

    /**
     * Binds the given [page] with this view holder, subscribing to its state.
     */
    fun bind(page: ReaderPage) {
        this.page = page
        frame.pageColorFilter = viewer.pageColorFilter
        loadJob?.cancel()
        loadJob = scope.launch { loadPageAndProcessStatus() }
        refreshLayoutParams()
    }

    private fun refreshLayoutParams() {
        frame.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            if (!viewer.isContinuous) {
                bottomMargin = 15.dpToPx
            }

            marginEnd = viewer.recycler.sidePaddingPixels
            marginStart = viewer.recycler.sidePaddingPixels
        }
    }

    /**
     * Called when the view is recycled and added to the view pool.
     */
    override fun recycle() {
        loadJob?.cancel()
        loadJob = null

        removeErrorLayout()
        frame.recycle()
        progressIndicator.setProgress(0)
        progressContainer.isVisible = true
    }

    /**
     * Loads the page and processes changes to the page's status.
     *
     * Returns immediately if there is no page or the page has no PageLoader.
     * Otherwise, this function does not return. It will continue to process status changes until
     * the Job is cancelled.
     */
    private suspend fun loadPageAndProcessStatus() {
        val page = page ?: return
        val loader = page.chapter.pageLoader ?: return
        supervisorScope {
            launch(Dispatchers.IO) {
                loader.loadPage(page)
            }
            page.statusFlow.collectLatest { state ->
                when (state) {
                    Page.State.Queue -> setQueued()
                    Page.State.LoadPage -> setLoading()
                    Page.State.DownloadImage -> {
                        setDownloading()
                        page.progressFlow.collectLatest { value ->
                            progressIndicator.setProgress(value)
                        }
                    }
                    Page.State.Ready -> setImage()
                    is Page.State.Error -> setError(state.error)
                }
            }
        }
    }

    /**
     * Called when the page is queued.
     */
    private fun setQueued() {
        progressContainer.isVisible = true
        progressIndicator.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is loading.
     */
    private fun setLoading() {
        progressContainer.isVisible = true
        progressIndicator.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is downloading
     */
    private fun setDownloading() {
        progressContainer.isVisible = true
        progressIndicator.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is ready.
     */
    private suspend fun setImage() {
        // KUN -->
        ocrTransform = OcrDisplayTransform.None
        // KUN <--
        progressIndicator.setProgress(0)

        val streamFn = page?.stream ?: return

        try {
            val buffer = withContext(Dispatchers.IO) { streamFn().use { Buffer().readFrom(it) } }
            // Splitting and merging decode the image, which is CPU work
            val (source, isAnimated) = withContext(Dispatchers.Default) {
                // KUN --> the side a split put on top, for the OCR overlay's mapping
                val source = viewer.simpleLongStrip.page.processStripImage(buffer) { side ->
                    ocrTransform = OcrDisplayTransform.SplitAndMerge(upperIsRight = side == ImageUtil.Side.RIGHT)
                }
                // KUN <--
                val isAnimated = ImageUtil.isAnimatedAndSupported(source)
                Pair(source, isAnimated)
            }
            withContext(Dispatchers.Main) {
                frame.setImage(
                    source,
                    isAnimated,
                    ReaderPageImageView.Config(
                        zoomDuration = viewer.general.doubleTapAnimDuration,
                        minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_FIT_WIDTH,
                        // KUN -->
                        // SY: each long strip mode crops on its own preference (R50).
                        cropBorders = (viewer.longStrip.cropBorders && viewer.isContinuous) ||
                            (viewer.longStrip.continuousCropBorders && !viewer.isContinuous),
                        // KUN <--
                    ),
                )
                removeErrorLayout()
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            withContext(Dispatchers.Main) {
                setError(e)
            }
        }
    }

    /**
     * Called when the page has an error.
     */
    private fun setError(error: Throwable?) {
        progressContainer.isVisible = false
        initErrorLayout(error)
    }

    /**
     * Called when the image is decoded and going to be displayed.
     */
    private fun onImageDecoded() {
        progressContainer.isVisible = false
        removeErrorLayout()
    }

    /**
     * Creates a new progress bar.
     */
    private fun createProgressIndicator(): ReaderProgressIndicator {
        progressContainer = FrameLayout(context)
        frame.addView(progressContainer, MATCH_PARENT, parentHeight)

        val progress = ReaderProgressIndicator(
            context,
            // KMK -->
            seedColor = seedColor,
            // KMK <--
        ).apply {
            updateLayoutParams<FrameLayout.LayoutParams> {
                updateMargins(top = parentHeight / 4)
            }
        }
        progressContainer.addView(progress)
        return progress
    }

    /**
     * Shows the error of the page.
     */
    private fun initErrorLayout(error: Throwable?) {
        val page = page ?: return
        val errorLayout = errorLayout ?: ReaderPageErrorView(
            context,
            // KMK -->
            seedColor = seedColor,
            // KMK <--
        ).also {
            frame.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, (parentHeight * 0.8).toInt()))
            errorLayout = it
        }
        errorLayout.bind(page = page, error = error, sourceId = viewer.activity.viewModel.manga?.source)
    }

    /**
     * Removes the decode error layout from the holder, if found.
     */
    private fun removeErrorLayout() {
        errorLayout?.let {
            frame.removeView(it)
            errorLayout = null
        }
    }
}
