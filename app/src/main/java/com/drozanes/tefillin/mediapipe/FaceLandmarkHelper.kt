package com.drozanes.tefillin.mediapipe

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.drozanes.tefillin.cv.TefillinEngine
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

/**
 * Helper to manage MediaPipe 478 3D FaceLandmarker with GPU / CPU delegates.
 */
class FaceLandmarkHelper(private val context: Context) {

    companion object {
        private const val TAG = "FaceLandmarkHelper"
        private const val MODEL_PATH = "face_landmarker.task"
    }

    private var faceLandmarker: FaceLandmarker? = null
    var isGpuActive = false
        private set

    init {
        initLandmarker()
    }

    private fun initLandmarker() {
        // Try GPU delegate first
        try {
            val baseOptionsGpu = BaseOptions.builder()
                .setModelAssetPath(MODEL_PATH)
                .setDelegate(Delegate.GPU)
                .build()

            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptionsGpu)
                .setMinFaceDetectionConfidence(0.35f)
                .setMinTrackingConfidence(0.35f)
                .setNumFaces(1)
                .setOutputFaceBlendshapes(false)
                .setOutputFacialTransformationMatrixes(false)
                .setRunningMode(RunningMode.IMAGE)
                .build()

            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
            isGpuActive = true
            Log.i(TAG, "MediaPipe FaceLandmarker initialized with hardware GPU delegate.")
            return
        } catch (e: Exception) {
            Log.w(TAG, "GPU delegate not supported on this device/driver, falling back to CPU", e)
        }

        // Fallback to CPU delegate
        try {
            val baseOptionsCpu = BaseOptions.builder()
                .setModelAssetPath(MODEL_PATH)
                .setDelegate(Delegate.CPU)
                .build()

            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptionsCpu)
                .setMinFaceDetectionConfidence(0.35f)
                .setMinTrackingConfidence(0.35f)
                .setNumFaces(1)
                .setOutputFaceBlendshapes(false)
                .setOutputFacialTransformationMatrixes(false)
                .setRunningMode(RunningMode.IMAGE)
                .build()

            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
            isGpuActive = false
            Log.i(TAG, "MediaPipe FaceLandmarker initialized with CPU delegate.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaPipe FaceLandmarker", e)
        }
    }

    fun detect(bitmap: Bitmap): List<TefillinEngine.LandmarkPoint>? {
        if (bitmap.isRecycled) return null
        val landmarker = faceLandmarker ?: return null
        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result: FaceLandmarkerResult = landmarker.detect(mpImage)
            val faceLandmarksList = result.faceLandmarks()
            if (faceLandmarksList.isEmpty()) return null

            val rawLandmarks = faceLandmarksList[0]
            val points = ArrayList<TefillinEngine.LandmarkPoint>(rawLandmarks.size)
            for (lm in rawLandmarks) {
                points.add(TefillinEngine.LandmarkPoint(lm.x(), lm.y(), lm.z()))
            }
            return points
        } catch (e: Exception) {
            Log.e(TAG, "FaceLandmark detection error", e)
            return null
        }
    }

    fun close() {
        try {
            faceLandmarker?.close()
            faceLandmarker = null
        } catch (_: Exception) {}
    }
}
