package com.drozanes.tefillin

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.drozanes.tefillin.cv.GeometryStabilizer
import com.drozanes.tefillin.cv.TefillinEngine
import com.drozanes.tefillin.cv.YoloDetector
import com.drozanes.tefillin.databinding.ActivityMainBinding
import com.drozanes.tefillin.mediapipe.FaceLandmarkHelper
import com.drozanes.tefillin.model.AlignmentResult
import com.drozanes.tefillin.model.AlignmentStatus
import com.drozanes.tefillin.model.FaceGeometry
import com.drozanes.tefillin.model.TefillinBox
import com.drozanes.tefillin.ui.BugReportDialog
import com.drozanes.tefillin.ui.HalachaDialog
import com.drozanes.tefillin.util.DiagnosticLogger
import com.drozanes.tefillin.util.LocaleManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val KNOT_GUIDE_URL = "https://www.youtube.com/watch?v=4BEhw4g_OJI"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    // AI & CV Components
    private var faceLandmarker: FaceLandmarkHelper? = null
    private var yoloDetector: YoloDetector? = null
    private val videoStabilizer = GeometryStabilizer(
        deadbandPx = 2.0f,
        angleDeadbandDeg = 0.6f,
        minAlpha = 0.18f,
        maxAlpha = 0.85f
    )

    // State Tracking
    private var lastYoloTime = 0L
    private var lastYoloBox: TefillinBox? = null
    private var lastCameraResolution = "640x480"
    private var latestBitmapSnapshot: Bitmap? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            binding.permissionOverlay.visibility = View.GONE
            startCamera()
        } else {
            binding.permissionOverlay.visibility = View.VISIBLE
            binding.tvStatus.text = getString(R.string.permission_required)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticLogger.info("Application boot: Native Android Tefillin App")

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        initAiEngines()
        setupUI()
        checkCameraPermission()
    }

    private fun initAiEngines() {
        cameraExecutor.execute {
            try {
                faceLandmarker = FaceLandmarkHelper(this@MainActivity)
                yoloDetector = YoloDetector(this@MainActivity)

                val isGpu = faceLandmarker?.isGpuActive == true
                runOnUiThread {
                    binding.tvGpuBadge.text = if (isGpu) " • ⚡ GPU Active" else " • 💻 CPU Active"
                    binding.tvGpuBadge.setTextColor(
                        ContextCompat.getColor(this@MainActivity, if (isGpu) R.color.accent_green else R.color.accent_gold)
                    )
                }
                DiagnosticLogger.info("AI Engines initialized (GPU: $isGpu)")
            } catch (e: Exception) {
                DiagnosticLogger.error("Error initializing AI engines", e)
            }
        }
    }

    private fun setupUI() {
        // Model Selector
        val modelOptions = listOf(
            getString(R.string.model_int8),
            getString(R.string.model_fp16)
        )
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modelOptions)
        binding.spinnerModel.adapter = spinnerAdapter
        binding.spinnerModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedModel = if (position == 0) YoloDetector.MODEL_UINT8 else YoloDetector.MODEL_FP16
                cameraExecutor.execute {
                    yoloDetector?.loadModel(selectedModel)
                    DiagnosticLogger.info("Model switched to: $selectedModel")
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Language Toggle
        binding.btnLangToggle.setOnClickListener {
            val curr = LocaleManager.getLanguage(this)
            val newLang = if (curr == "he") "en" else "he"
            LocaleManager.setLanguage(this, newLang)
            DiagnosticLogger.info("Language changed to: $newLang")
            recreate()
        }

        // Guidelines Toggle
        binding.btnToggleGuides.setOnClickListener {
            binding.overlayView.showGuides = !binding.overlayView.showGuides
            binding.overlayView.invalidate()
            val alpha = if (binding.overlayView.showGuides) 1.0f else 0.4f
            binding.btnToggleGuides.alpha = alpha
        }

        // Halacha Guide Info
        binding.btnInfo.setOnClickListener {
            HalachaDialog(this).show()
        }

        // Bug Report Dialog
        binding.btnBugReport.setOnClickListener {
            val snapshot = binding.previewView.bitmap ?: latestBitmapSnapshot
            val activeModel = yoloDetector?.getActiveModel() ?: "unknown"
            val isGpu = faceLandmarker?.isGpuActive == true
            BugReportDialog(this, snapshot, activeModel, isGpu, lastCameraResolution).show()
        }

        // Knot Guide Video Link
        binding.tvKnotGuideLink.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(KNOT_GUIDE_URL))
                startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot launch browser for YouTube link", e)
            }
        }

        // Permission Button
        binding.btnGrantPermission.setOnClickListener {
            checkCameraPermission()
        }
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            binding.permissionOverlay.visibility = View.GONE
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Selfie front camera (mirrored by default)
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            val preview = Preview.Builder()
                .build()
                .also {
                    it.surfaceProvider = binding.previewView.surfaceProvider
                }

            val imageAnalysis = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processCameraFrame(imageProxy)
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
                DiagnosticLogger.info("CameraX bound to lifecycle with front camera")
            } catch (e: Exception) {
                DiagnosticLogger.error("Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processCameraFrame(imageProxy: ImageProxy) {
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val rawBitmap = imageProxy.toBitmap()
        imageProxy.close()

        val bitmap = if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
        } else {
            rawBitmap
        }

        latestBitmapSnapshot = bitmap
        lastCameraResolution = "${bitmap.width}x${bitmap.height}"

        val landmarker = faceLandmarker
        val yolo = yoloDetector

        if (landmarker == null || yolo == null) return

        // 1. Detect 478 3D Face Landmarks via MediaPipe
        val landmarks = landmarker.detect(bitmap)
        if (landmarks == null || landmarks.isEmpty()) {
            videoStabilizer.reset()
            runOnUiThread {
                binding.overlayView.clear()
                updateUiForNoFace()
            }
            return
        }

        // 2. Extract Geometry with Hairline Detection and Temporal Stabilization
        val geometry = TefillinEngine.analyzeFaceGeometry(
            landmarks = landmarks,
            width = bitmap.width,
            height = bitmap.height,
            hairlineUserAdj = 0f,
            bitmap = bitmap,
            stabilizer = videoStabilizer
        )

        if (geometry == null) {
            runOnUiThread { binding.overlayView.clear() }
            return
        }

        // 3. AI Tefillin Detection (Throttled to ~300ms, runs asynchronously without lagging preview)
        val now = SystemClock.uptimeMillis()
        if (!yolo.isRunning() && (now - lastYoloTime >= 300L)) {
            lastYoloTime = now
            val detected = yolo.detect(bitmap)
            if (detected != null) {
                lastYoloBox = detected
            }
        }

        // 4. Head-Anchored Real-Time Box Tracking at 60 FPS
        val winner = videoStabilizer.stabilizeBox(lastYoloBox, geometry)

        // 5. Evaluate Halachic Alignment Status
        val alignment = TefillinEngine.evaluateAlignment(winner, geometry)

        // 6. Push to UI
        runOnUiThread {
            binding.overlayView.setResults(geometry, alignment, bitmap.width, bitmap.height)
            updateUiWithAlignment(alignment)
        }
    }

    private fun updateUiForNoFace() {
        binding.statusDot.setBackgroundResource(R.drawable.dot_searching)
        binding.tvStatus.text = getString(R.string.no_face)
        binding.tvDirectionArrow.text = "🔍"
        binding.tvGuidanceTitle.text = getString(R.string.no_face)
        binding.tvGuidanceSub.text = getString(R.string.no_tefillin_sub)
        binding.tvKnotGuideLink.visibility = View.GONE
    }

    private fun updateUiWithAlignment(alignment: AlignmentResult) {
        val winner = alignment.winner
        if (winner == null) {
            binding.statusDot.setBackgroundResource(R.drawable.dot_searching)
            binding.tvStatus.text = getString(R.string.no_tefillin)
            binding.tvDirectionArrow.text = "🔳"
            binding.tvGuidanceTitle.text = getString(R.string.no_tefillin)
            binding.tvGuidanceSub.text = getString(R.string.no_tefillin_sub)
            binding.tvKnotGuideLink.visibility = View.GONE
            return
        }

        when (alignment.status) {
            AlignmentStatus.CAMERA_TOO_LOW -> {
                binding.statusDot.setBackgroundResource(R.drawable.dot_caution)
                binding.tvStatus.text = getString(R.string.camera_too_low)
                binding.tvDirectionArrow.text = "📐"
                binding.tvGuidanceTitle.text = getString(R.string.camera_angle_title)
                binding.tvGuidanceSub.text = getString(R.string.camera_too_low_sub)
                binding.tvKnotGuideLink.visibility = View.GONE
            }
            AlignmentStatus.CAMERA_TOO_HIGH -> {
                binding.statusDot.setBackgroundResource(R.drawable.dot_caution)
                binding.tvStatus.text = getString(R.string.camera_too_high)
                binding.tvDirectionArrow.text = "📐"
                binding.tvGuidanceTitle.text = getString(R.string.camera_angle_title)
                binding.tvGuidanceSub.text = getString(R.string.camera_too_high_sub)
                binding.tvKnotGuideLink.visibility = View.GONE
            }
            AlignmentStatus.FOREHEAD_ERROR -> {
                binding.statusDot.setBackgroundResource(R.drawable.dot_misaligned)
                binding.tvStatus.text = getString(R.string.touches_forehead_alert)
                binding.tvDirectionArrow.text = "⬆️"
                binding.tvGuidanceTitle.text = getString(R.string.move_up_title)
                binding.tvGuidanceSub.text = getString(R.string.move_up_sub)
                binding.tvKnotGuideLink.visibility = View.VISIBLE
            }
            AlignmentStatus.OFF_CENTER -> {
                binding.statusDot.setBackgroundResource(R.drawable.dot_caution)
                binding.tvStatus.text = getString(R.string.off_center_alert)
                val isMoveLeft = alignment.horizOffset > 0
                binding.tvDirectionArrow.text = if (isMoveLeft) "⬅️" else "➡️"
                binding.tvGuidanceTitle.text = getString(if (isMoveLeft) R.string.move_left_title else R.string.move_right_title)
                binding.tvGuidanceSub.text = getString(if (isMoveLeft) R.string.move_left_sub else R.string.move_right_sub)
                binding.tvKnotGuideLink.visibility = View.GONE
            }
            AlignmentStatus.ALIGNED -> {
                if (alignment.warning) {
                    binding.statusDot.setBackgroundResource(R.drawable.dot_caution)
                    binding.tvStatus.text = getString(R.string.borderline_alert)
                    binding.tvDirectionArrow.text = "⚠️"
                    binding.tvGuidanceTitle.text = getString(R.string.borderline_title)
                    binding.tvGuidanceSub.text = getString(R.string.borderline_sub)
                } else {
                    binding.statusDot.setBackgroundResource(R.drawable.dot_aligned)
                    binding.tvStatus.text = getString(R.string.perfect_alert)
                    binding.tvDirectionArrow.text = "✨"
                    binding.tvGuidanceTitle.text = getString(R.string.perfect_title)
                    binding.tvGuidanceSub.text = getString(R.string.perfect_sub)
                }
                binding.tvKnotGuideLink.visibility = View.GONE
            }
            AlignmentStatus.NO_TEFILLIN -> {
                updateUiForNoFace()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        faceLandmarker?.close()
        yoloDetector?.close()
    }
}
