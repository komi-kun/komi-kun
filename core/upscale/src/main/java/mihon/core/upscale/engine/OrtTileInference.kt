// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The ONNX Runtime tile inference — the only place this module touches the native engine.
//
// One session per (model file, tensor shape), owned by `UpscaleEngine`: creating a session costs
// seconds and hundreds of MB of native weights, so it is built once, reused for every tile of every
// page that shares the shape, and rebuilt only when the shape (a tile-size change, a memory-pressure
// fallback) or the model file changes.
//
// **Execution providers.** WebGPU (when asked for) → XNNPACK → CPU, each attempt with its own
// `SessionOptions`, so a provider that is missing from this build, refuses the graph or dies at
// creation falls through to the next one instead of failing the page. The provider that actually
// opened the session is kept in [provider], and the last failure in [lastError].
//
// **Contract.** The graph must expose an input named `input` and an output named `output`, a float32
// or float16 `NCHW` tensor in and an `NCHW` tensor out of `scale` times the input. A symbolic (`-1`)
// input dimension is accepted — the engine feeds a concrete `[1, 3, H, W]` — and the output's real
// shape is checked against the tile after every run. Anything else is refused while the session is
// being set up — naming the shapes/types the model really has — rather than discovered as garbage
// pixels later.
// KUN <--
package mihon.core.upscale.engine

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.min

/** Which execution provider the session should try first; the rest of the chain is the fallback. */
enum class ExecutionProvider {
    /** The device GPU. */
    WEBGPU,

    /** CPU kernels with the XNNPACK backend (the CPU default when available). */
    XNNPACK,

    /** Plain ONNX Runtime CPU kernels. */
    CPU,
}

/**
 * One [TileInference] backed by an ONNX Runtime session.
 *
 * Not thread-safe — and not meant to be: `UpscaleEngine` creates, runs and closes it on its single
 * `upscale-inference` thread.
 */
class OrtTileInference private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val modelName: String,
    private val scale: Int,
    override val isFloat16: Boolean,
    /** The provider that actually opened the session. */
    val provider: ExecutionProvider,
    /** The last provider that failed to open before this one, if any (for logging). */
    val lastError: Throwable?,
) : TileInference {

    private val requestedOutputs: Set<String> = setOf(OUTPUT_NAME)

    override fun infer(input: TileInput): FloatArray {
        val width = input.width
        val height = input.height
        val plane = width * height
        val shape = longArrayOf(1L, 3L, height.toLong(), width.toLong())

        val tensor = when (input) {
            is TileInput.Float32 -> OnnxTensor.createTensor(env, planar(input.rgb, plane), shape)
            is TileInput.Float16 -> OnnxTensor.createTensor(
                env,
                planar(input.rgb, plane),
                shape,
                OnnxJavaType.FLOAT16,
            )
        }

        return tensor.use {
            session.run(mapOf(INPUT_NAME to tensor), requestedOutputs).use { result ->
                val value = result.get(OUTPUT_NAME).orElse(null)
                    ?: throw UpscaleModelContractException(
                        "$modelName exposed no `$OUTPUT_NAME` output (got ${result.size()} value(s))",
                    )
                val output = value as? OnnxTensor
                    ?: throw UpscaleModelContractException(
                        "$modelName's `$OUTPUT_NAME` is a ${value.javaClass.simpleName}, not a tensor",
                    )
                output.use { readOutput(it, width, height) }
            }
        }
    }

    /** Validates the output shape against the tile and the model's scale, then reads it. */
    private fun readOutput(output: OnnxTensor, width: Int, height: Int): FloatArray {
        val shape = output.info.shape
        val expectedWidth = width * scale
        val expectedHeight = height * scale
        if (shape.size != 4 || shape[0] != 1L || shape[1] != 3L ||
            shape[2].toInt() != expectedHeight || shape[3].toInt() != expectedWidth
        ) {
            throw UpscaleModelContractException(
                "$modelName returned ${shape.toList()} for a ${width}x$height tile; " +
                    "expected [1, 3, $expectedHeight, $expectedWidth]",
            )
        }
        // The model's output is NCHW (one plane per channel); `TileInference` promises interleaved
        // RGB, so the planes are woven here — once, for the tile's own pixels only.
        val plane = expectedHeight * expectedWidth
        val planar = FloatArray(3 * plane)
        when (output.info.type) {
            OnnxJavaType.FLOAT -> output.floatBuffer.get(planar)
            // A float16 graph hands back raw 16-bit halves, not a float buffer.
            OnnxJavaType.FLOAT16 -> {
                val halves = output.shortBuffer
                for (index in planar.indices) planar[index] = halfBitsToFloat(halves.get(index))
            }
            else -> throw UpscaleModelContractException(
                "$modelName returned an ${output.info.type} tensor; FLOAT and FLOAT16 only",
            )
        }
        val values = FloatArray(3 * plane)
        for (pixel in 0 until plane) {
            val left = planar[pixel]
            val middle = planar[plane + pixel]
            val right = planar[2 * plane + pixel]
            if (!left.isFinite() || !middle.isFinite() || !right.isFinite()) {
                throw UpscaleNonFiniteException(
                    "$modelName produced a non-finite value at pixel $pixel of $plane " +
                        "($left, $middle, $right)",
                )
            }
            values[pixel * 3] = left
            values[pixel * 3 + 1] = middle
            values[pixel * 3 + 2] = right
        }
        return values
    }

    override fun close() {
        runCatching { session.close() }
    }

    companion object {
        private const val INPUT_NAME = "input"
        private const val OUTPUT_NAME = "output"

        /** Cap on ONNX Runtime's intra-op threads: beyond this a page is slower, not faster. */
        private const val MAX_INTRA_OP_THREADS = 4

        /** The provider chain a [preferred] provider starts. */
        private fun candidates(preferred: ExecutionProvider): List<ExecutionProvider> = when (preferred) {
            ExecutionProvider.WEBGPU -> listOf(
                ExecutionProvider.WEBGPU,
                ExecutionProvider.XNNPACK,
                ExecutionProvider.CPU,
            )
            ExecutionProvider.XNNPACK -> listOf(ExecutionProvider.XNNPACK, ExecutionProvider.CPU)
            ExecutionProvider.CPU -> listOf(ExecutionProvider.CPU)
        }

        /** `min(4, cores)`: the intra-op thread count every session of this module uses. */
        fun defaultThreads(): Int =
            min(MAX_INTRA_OP_THREADS, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))

        /**
         * Opens [modelFile] with the first execution provider of [preferred]'s chain that works.
         *
         * Throws [UpscaleModelContractException] when the graph does not match what the pipeline
         * needs, and `IllegalStateException` (carrying the last provider error) when no provider could
         * open it at all.
         */
        fun open(
            modelFile: File,
            scale: Int,
            preferred: ExecutionProvider = ExecutionProvider.CPU,
            threads: Int = defaultThreads(),
            env: OrtEnvironment = OrtEnvironment.getEnvironment(),
        ): OrtTileInference {
            require(scale > 0) { "scale must be positive: $scale" }
            require(modelFile.isFile) { "model file does not exist: ${modelFile.absolutePath}" }

            var lastError: Throwable? = null
            var lastProvider = preferred
            for (provider in candidates(preferred)) {
                lastProvider = provider
                val session = try {
                    OrtSession.SessionOptions().use { options ->
                        configure(options, provider, threads)
                        env.createSession(modelFile.absolutePath, options)
                    }
                } catch (error: Throwable) {
                    // A missing provider, an unsupported op or an allocation failure at creation:
                    // fall through to the next provider rather than failing the page.
                    lastError = error
                    continue
                }
                return try {
                    validateContract(env, session, modelFile.name, scale, provider, lastError)
                } catch (error: UpscaleModelContractException) {
                    runCatching { session.close() }
                    throw error
                }
            }
            throw IllegalStateException(
                "could not open ${modelFile.name} with any execution provider (last: $lastProvider)",
                lastError,
            )
        }

        private fun configure(
            options: OrtSession.SessionOptions,
            provider: ExecutionProvider,
            threads: Int,
        ) {
            // ORT_ENABLE_ALL / ORT_SEQUENTIAL, as this ORT's Java enum spells them.
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            // Every tile has the same shape but a different buffer, and one page is thousands of
            // tiles: memory-pattern pre-allocation only costs memory here.
            options.setMemoryPatternOptimization(false)
            options.setIntraOpNumThreads(threads)
            when (provider) {
                ExecutionProvider.WEBGPU -> options.addWebGPU(emptyMap())
                // `intra_op.allow_spinning=0` stops the XNNPACK pool busy-waiting between tiles and
                // burning the reader's battery while the UI thread draws.
                ExecutionProvider.XNNPACK ->
                    options.addXnnpack(mapOf("intra_op.allow_spinning" to "0"))
                ExecutionProvider.CPU -> options.addCPU(true)
            }
        }

        /** Checks the graph against the contract the pipeline relies on. */
        private fun validateContract(
            env: OrtEnvironment,
            session: OrtSession,
            modelName: String,
            scale: Int,
            provider: ExecutionProvider,
            lastError: Throwable?,
        ): OrtTileInference {
            val inputs = session.inputInfo
            val inputInfo = inputs[INPUT_NAME]?.info as? TensorInfo
                ?: throw UpscaleModelContractException(
                    "$modelName exposes inputs ${inputs.describe()}; " +
                        "it must expose exactly one float tensor named `$INPUT_NAME`",
                )
            val outputs = session.outputInfo
            val outputInfo = outputs[OUTPUT_NAME]?.info as? TensorInfo
                ?: throw UpscaleModelContractException(
                    "$modelName exposes outputs ${outputs.describe()}; " +
                        "it must expose exactly one tensor named `$OUTPUT_NAME`",
                )
            val type = inputInfo.type
            if (type != OnnxJavaType.FLOAT && type != OnnxJavaType.FLOAT16) {
                throw UpscaleModelContractException(
                    "$modelName's `$INPUT_NAME` is $type; only FLOAT and FLOAT16 are supported",
                )
            }
            if (outputInfo.type != OnnxJavaType.FLOAT && outputInfo.type != OnnxJavaType.FLOAT16) {
                throw UpscaleModelContractException(
                    "$modelName's `$OUTPUT_NAME` is ${outputInfo.type}; " +
                        "only FLOAT and FLOAT16 are supported",
                )
            }
            val inputShape = inputInfo.shape
            // A `-1` dimension is symbolic: ONNX Runtime resolves it from the tensor the engine
            // feeds, which is always `[1, 3, shape.height, shape.width]` (see `infer`). The published
            // MangaJaNai / IllustrationJaNai exports all declare `[-1, 3, -1, -1]`, so rejecting
            // those would refuse every model the catalog ships.
            if (inputShape.size != 4 || !inputShape[0].accepts(1) || !inputShape[1].accepts(3)) {
                throw UpscaleModelContractException(
                    "$modelName's `$INPUT_NAME` is ${inputShape.toList()}; " +
                        "expected [1, 3, H, W] (`-1` is a dynamic dimension)",
                )
            }
            return OrtTileInference(
                env = env,
                session = session,
                modelName = modelName,
                scale = scale,
                isFloat16 = type == OnnxJavaType.FLOAT16,
                provider = provider,
                lastError = lastError,
            )
        }

        /** Interleaved RGB floats → one direct NCHW buffer, channel plane by channel plane. */
        private fun planar(rgb: FloatArray, plane: Int): FloatBuffer {
            val floats = ByteBuffer.allocateDirect(3 * plane * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            for (channel in 0 until 3) {
                val base = channel * plane
                var source = channel
                for (pixel in 0 until plane) {
                    floats.put(base + pixel, rgb[source])
                    source += 3
                }
            }
            return floats
        }

        /** Interleaved binary16 bit patterns → one direct NCHW buffer. */
        private fun planar(rgb: ShortArray, plane: Int): ShortBuffer {
            val halves = ByteBuffer.allocateDirect(3 * plane * Short.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asShortBuffer()
            for (channel in 0 until 3) {
                val base = channel * plane
                var source = channel
                for (pixel in 0 until plane) {
                    halves.put(base + pixel, rgb[source])
                    source += 3
                }
            }
            return halves
        }
    }
}

private fun Map<String, NodeInfo>.describe(): String =
    entries.joinToString { (name, node) -> "$name=${(node.info as? TensorInfo)?.type}" }

/** True when a declared dimension accepts [value]: an exact match, or a symbolic (`-1`) dimension. */
private fun Long.accepts(value: Int): Boolean = this == value.toLong() || this < 0L
