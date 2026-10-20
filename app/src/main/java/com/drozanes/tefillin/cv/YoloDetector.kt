package com.drozanes.tefillin.cv

import ai.onnxruntime.*
import android.content.Context
import android.graphics.*
import android.util.Log
import com.drozanes.tefillin.model.TefillinBox
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * High-Performance YOLOv8 Tefillin Box Neural Network Detector
 * Supports GPU / NNAPI hardware acceleration and multi-threading on Android devices.
 * Uses exact ONNX models from tefillin_app (best_uint8.onnx and best_fp16.onnx).
 */
class YoloDetector(private val context: Context) {

    companion object {
        private const val TAG = "YoloDetector"
        const val MODEL_WIDTH = 640
        const val MODEL_HEIGHT = 640
        const val CONF_THRESHOLD = 0.45f
        const val MODEL_FP16 = "best_fp16.onnx"
        const val MODEL_UINT8 = "best_uint8.onnx"
    }

    private var ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var activeModelName: String = MODEL_FP16
    private var isBusy = false

    // Reusable buffers to eliminate garbage collection latency
    private val letterboxedBitmap = Bitmap.createBitmap(MODEL_WIDTH, MODEL_HEIGHT, Bitmap.Config.ARGB_8888)
    private val letterboxCanvas = Canvas(letterboxedBitmap)
    private val letterboxPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixelBuffer = IntArray(MODEL_WIDTH * MODEL_HEIGHT)
    private val floatBuffer: FloatBuffer = FloatBuffer.allocate(3 * MODEL_WIDTH * MODEL_HEIGHT)

    init {
        loadModel(MODEL_FP16)
    }

    @Synchronized
    fun loadModel(modelFileName: String): Boolean {
        try {
            activeModelName = modelFileName
            session?.close()

            val sessionOptions = OrtSession.SessionOptions().apply {
                try {
                    // Enable NNAPI to utilize Android NPU / DSP / GPU
                    addNnapi()
                    Log.i(TAG, "Hardware acceleration enabled: Android NNAPI")
                } catch (e: Exception) {
                    Log.w(TAG, "NNAPI unavailable, using multi-threaded CPU SIMD", e)
                    setIntraOpNumThreads(2)
                    setInterOpNumThreads(2)
                }
            }

            val modelBytes = context.assets.open(modelFileName).readBytes()
            session = ortEnv.createSession(modelBytes, sessionOptions)
            Log.i(TAG, "Successfully loaded YOLO ONNX model: $modelFileName (${modelBytes.size / 1024} KB)")

            // Warmup inference to compile JIT shaders / kernels
            try {
                val dummyBuffer = FloatBuffer.allocate(3 * MODEL_WIDTH * MODEL_HEIGHT)
                val dummyTensor = OnnxTensor.createTensor(ortEnv, dummyBuffer, longArrayOf(1, 3, MODEL_HEIGHT.toLong(), MODEL_WIDTH.toLong()))
                val inputName = session!!.inputNames.iterator().next()
                session!!.run(mapOf(inputName to dummyTensor)).close()
                dummyTensor.close()
                Log.i(TAG, "Model warmup complete.")
            } catch (wErr: Exception) {
                Log.w(TAG, "Warmup warning:", wErr)
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: $modelFileName", e)
            return false
        }
    }

    fun getActiveModel(): String = activeModelName

    fun isRunning(): Boolean = isBusy

    @Synchronized
    fun detect(origBitmap: Bitmap): TefillinBox? {
        if (origBitmap.isRecycled) return null
        val currSession = session ?: return null
        if (isBusy) return null
        isBusy = true

        try {
            val origW = origBitmap.width
            val origH = origBitmap.height
            if (origW <= 0 || origH <= 0) return null

            // 1. Preprocess: Aspect-ratio preserving letterbox into 640x640 with pad color RGB(114, 114, 114)
            val scale = min(MODEL_WIDTH.toFloat() / origW, MODEL_HEIGHT.toFloat() / origH)
            val scaledW = (origW * scale).roundToInt()
            val scaledH = (origH * scale).roundToInt()
            val padX = (MODEL_WIDTH - scaledW) / 2f
            val padY = (MODEL_HEIGHT - scaledH) / 2f

            letterboxCanvas.drawColor(Color.rgb(114, 114, 114))
            val dstRect = Rect(padX.toInt(), padY.toInt(), (padX + scaledW).toInt(), (padY + scaledH).toInt())
            letterboxCanvas.drawBitmap(origBitmap, null, dstRect, letterboxPaint)

            letterboxedBitmap.getPixels(pixelBuffer, 0, MODEL_WIDTH, 0, 0, MODEL_WIDTH, MODEL_HEIGHT)

            // 2. Normalization & Planar (NCHW) Float Tensor Population
            val totalPixels = MODEL_WIDTH * MODEL_HEIGHT
            val gOffset = totalPixels
            val bOffset = totalPixels * 2
            val inv255 = 1.0f / 255.0f

            floatBuffer.clear()
            for (i in 0 until totalPixels) {
                val color = pixelBuffer[i]
                floatBuffer.put(i, ((color shr 16) and 0xFF) * inv255)
                floatBuffer.put(gOffset + i, ((color shr 8) and 0xFF) * inv255)
                floatBuffer.put(bOffset + i, (color and 0xFF) * inv255)
            }
            floatBuffer.rewind()

            // 3. ONNX Model Inference
            val inputTensor = OnnxTensor.createTensor(
                ortEnv,
                floatBuffer,
                longArrayOf(1, 3, MODEL_HEIGHT.toLong(), MODEL_WIDTH.toLong())
            )
            val inputName = currSession.inputNames.iterator().next()
            val results = currSession.run(mapOf(inputName to inputTensor))

            // 4. Postprocess: Output shape is [1, 5, 8400]
            val outputTensor = results.get(0) as OnnxTensor
            val outputFloats = outputTensor.floatBuffer

            var bestConf = 0f
            var bestIdx = -1
            val confOffset = 4 * 8400

            for (i in 0 until 8400) {
                val conf = outputFloats.get(confOffset + i)
                if (conf > bestConf && conf > CONF_THRESHOLD) {
                    bestConf = conf
                    bestIdx = i
                }
            }

            var detectedBox: TefillinBox? = null
            if (bestIdx >= 0) {
                val cx = outputFloats.get(bestIdx)
                val cy = outputFloats.get(8400 + bestIdx)
                val w = outputFloats.get(16800 + bestIdx)
                val h = outputFloats.get(25200 + bestIdx)

                // Map coordinates back to original image space
                val origCx = (cx - padX) / scale
                val origCy = (cy - padY) / scale
                val origW = w / scale
                val origH = h / scale

                detectedBox = TefillinBox(
                    x = origCx,
                    y = origCy,
                    boxWidth = origW,
                    boxHeight = origH,
                    leftX = origCx - origW / 2f,
                    rightX = origCx + origW / 2f,
                    topY = origCy - origH / 2f,
                    bottomY = origCy + origH / 2f,
                    confidence = bestConf
                )
            }

            inputTensor.close()
            results.close()
            return detectedBox
        } catch (e: Exception) {
            Log.e(TAG, "YOLO inference error", e)
            return null
        } finally {
            isBusy = false
        }
    }

    fun close() {
        try {
            session?.close()
            session = null
        } catch (_: Exception) {}
    }
}
