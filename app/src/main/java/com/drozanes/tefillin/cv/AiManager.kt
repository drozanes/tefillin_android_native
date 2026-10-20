package com.drozanes.tefillin.cv

import android.content.Context
import android.util.Log
import com.drozanes.tefillin.mediapipe.FaceLandmarkHelper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Application-scoped singleton managing AI inference engines (MediaPipe FaceLandmarker and YOLOv8).
 * Preserves model sessions across Activity recreation (e.g. language or theme switching)
 * to avoid expensive GPU re-initialization, memory churn, and native C++ lifecycle race conditions.
 */
object AiManager {
    private const val TAG = "AiManager"

    @Volatile
    private var isInitialized = false

    var faceLandmarker: FaceLandmarkHelper? = null
        private set

    var yoloDetector: YoloDetector? = null
        private set

    val executor: ExecutorService = Executors.newSingleThreadExecutor()

    fun initialize(context: Context, onReady: ((isGpu: Boolean, model: String) -> Unit)? = null) {
        val appContext = context.applicationContext
        executor.execute {
            try {
                if (faceLandmarker == null) {
                    faceLandmarker = FaceLandmarkHelper(appContext)
                }
                if (yoloDetector == null) {
                    yoloDetector = YoloDetector(appContext)
                }
                isInitialized = true
                val isGpu = faceLandmarker?.isGpuActive == true
                val model = yoloDetector?.getActiveModel() ?: YoloDetector.MODEL_FP16
                Log.i(TAG, "AiManager initialized successfully (GPU: $isGpu, Model: $model)")
                onReady?.invoke(isGpu, model)
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing AI models in AiManager", e)
            }
        }
    }
}
