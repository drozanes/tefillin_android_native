package com.drozanes.tefillin

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.drozanes.tefillin.cv.AiManager
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
import com.drozanes.tefillin.util.CrashHandler
import com.drozanes.tefillin.util.DiagnosticLogger
import com.drozanes.tefillin.util.LocaleManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "tefillin_app_prefs"
        private const val KEY_SELECTED_MODEL = "selected_model"
        private const val KNOT_GUIDE_URL = "https://www.youtube.com/watch?v=4BEhw4g_OJI"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var prefs: SharedPreferences

    // CameraX Lifecycle References
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var isCameraStarting = false

    private val videoStabilizer = GeometryStabilizer(
        deadbandPx = 2.0f,
        angleDeadbandDeg = 0.6f,
        minAlpha = 0.18f,
        maxAlpha = 0.85f
    )

    // State Tracking
    private var lastYoloTime = 0L
    private var lastYoloBox: TefillinBox? = null
    private var lastCameraResolution = "480x640"
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
        CrashHandler.install(this)
        super.onCreate(savedInstanceState)
        DiagnosticLogger.info("Application boot: Native Android Tefillin App")

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Ensure default app locale is synchronized to user choice (default Hebrew)
        val savedLang = LocaleManager.getLanguage(this)
        val currentAppLocales = AppCompatDelegate.getApplicationLocales()
        if (currentAppLocales.isEmpty) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(savedLang))
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Prompt user if a previous session crashed so they can easily copy/share the log!
        CrashHandler.checkAndPromptCrashReport(this)

        // Apply Window Insets for modern Android edge-to-edge screens.
        // Guarantees all buttons and titles sit comfortably below the physical status bar and camera punch-hole!
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainRoot) { _, insets ->
            val statusBarInsets = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val navBarInsets = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars()
            )

            val density = resources.displayMetrics.density
            val safeTopPadding = max(statusBarInsets.top + (8 * density).toInt(), (14 * density).toInt())
            binding.headerLayout.updatePadding(
                top = safeTopPadding,
                bottom = (10 * density).toInt()
            )

            val safeBottomPadding = max(navBarInsets.bottom + (8 * density).toInt(), (10 * density).toInt())
            binding.footerLayout.updatePadding(
                bottom = safeBottomPadding,
                top = (8 * density).toInt()
            )

            insets
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        initAiEngines()
        setupUI()
        checkCameraPermission()
    }

    private fun initAiEngines() {
        val savedModel = prefs.getString(KEY_SELECTED_MODEL, YoloDetector.MODEL_FP16) ?: YoloDetector.MODEL_FP16
        AiManager.initialize(this) { isGpu, model ->
            if (model != savedModel) {
                AiManager.yoloDetector?.loadModel(savedModel)
            }
            runOnUiThread {
                binding.tvGpuBadge.text = getString(if (isGpu) R.string.gpu_active_badge else R.string.cpu_active_badge)
                binding.tvGpuBadge.setTextColor(
                    ContextCompat.getColor(this@MainActivity, if (isGpu) R.color.accent_green else R.color.accent_gold)
                )
            }
        }
    }

    private fun setupUI() {
        // 1. Model Selector Toggle Pill Button (FP16 by default)
        val initialModel = prefs.getString(KEY_SELECTED_MODEL, YoloDetector.MODEL_FP16) ?: YoloDetector.MODEL_FP16
        updateModelButtonUi(initialModel)

        binding.btnModelSelect.setOnClickListener {
            val currentModel = prefs.getString(KEY_SELECTED_MODEL, YoloDetector.MODEL_FP16) ?: YoloDetector.MODEL_FP16
            val nextModel = if (currentModel == YoloDetector.MODEL_FP16) YoloDetector.MODEL_UINT8 else YoloDetector.MODEL_FP16
            prefs.edit().putString(KEY_SELECTED_MODEL, nextModel).apply()
            updateModelButtonUi(nextModel)

            AiManager.executor.execute {
                AiManager.yoloDetector?.loadModel(nextModel)
                DiagnosticLogger.info("Model switched to: $nextModel")
            }

            val toastMsg = if (nextModel == YoloDetector.MODEL_UINT8) {
                getString(R.string.model_switched_int8)
            } else {
                getString(R.string.model_switched_fp16)
            }
            Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
        }

        // 2. Language Switcher Button (Shows next target language)
        val nextLang = LocaleManager.getNextLanguage(this)
        binding.btnLangToggle.text = if (nextLang == LocaleManager.LANG_ENGLISH) "English" else "עברית"
        binding.btnLangToggle.setOnClickListener {
            val target = LocaleManager.getNextLanguage(this)
            DiagnosticLogger.info("Language switching to: $target")
            stopCameraSafely()
            LocaleManager.setLanguage(this, target)
        }

        // 3. Guidelines Toggle Pill Button
        updateGuidesButtonUi(binding.overlayView.showGuides)
        binding.btnToggleGuides.setOnClickListener {
            val nextState = !binding.overlayView.showGuides
            binding.overlayView.showGuides = nextState
            binding.overlayView.invalidate()
            updateGuidesButtonUi(nextState)
        }

        // 4. Halacha Guide Info
        binding.btnInfo.setOnClickListener {
            HalachaDialog(this).show()
        }

        // 5. Bug Report Dialog
        binding.btnBugReport.setOnClickListener {
            val snapshot = binding.previewView.bitmap ?: latestBitmapSnapshot
            val activeModel = AiManager.yoloDetector?.getActiveModel() ?: "unknown"
            val isGpu = AiManager.faceLandmarker?.isGpuActive == true
            BugReportDialog(this, snapshot, activeModel, isGpu, lastCameraResolution).show()
        }

        // 6. Knot Guide Video Link
        binding.tvKnotGuideLink.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(KNOT_GUIDE_URL))
                startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot launch browser for YouTube link", e)
            }
        }

        // 7. Permission Button
        binding.btnGrantPermission.setOnClickListener {
            checkCameraPermission()
        }
    }

    private fun updateModelButtonUi(model: String) {
        if (model == YoloDetector.MODEL_UINT8) {
            binding.btnModelSelect.text = getString(R.string.model_int8)
            binding.btnModelSelect.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
        } else {
            binding.btnModelSelect.text = getString(R.string.model_fp16)
            binding.btnModelSelect.setTextColor(ContextCompat.getColor(this, R.color.accent_gold))
        }
    }

    private fun updateGuidesButtonUi(showGuides: Boolean) {
        if (showGuides) {
            binding.btnToggleGuides.text = getString(R.string.guides_on)
            binding.btnToggleGuides.alpha = 1.0f
            binding.btnToggleGuides.setTextColor(ContextCompat.getColor(this, R.color.accent_gold))
        } else {
            binding.btnToggleGuides.text = getString(R.string.guides_off)
            binding.btnToggleGuides.alpha = 0.55f
            binding.btnToggleGuides.setTextColor(ContextCompat.getColor(this, R.color.text_subtle))
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
        if (isCameraStarting || cameraProvider != null || isFinishing || isDestroyed) return
        isCameraStarting = true

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            isCameraStarting = false
            if (isFinishing || isDestroyed) return@addListener

            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                // Selfie front camera (mirrored by default)
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.surfaceProvider = binding.previewView.surfaceProvider
                    }

                val analysis = ImageAnalysis.Builder()
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    try {
                        if (!isFinishing && !isDestroyed && !cameraExecutor.isShutdown) {
                            processCameraFrame(imageProxy)
                        } else {
                            imageProxy.close()
                        }
                    } catch (e: Exception) {
                        try { imageProxy.close() } catch (_: Exception) {}
                    }
                }
                imageAnalysis = analysis

                provider.unbindAll()
                provider.bindToLifecycle(this, cameraSelector, preview, analysis)
                DiagnosticLogger.info("CameraX bound to lifecycle with front camera")
            } catch (e: Exception) {
                DiagnosticLogger.error("Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCameraSafely() {
        isCameraStarting = false
        try {
            imageAnalysis?.clearAnalyzer()
            imageAnalysis = null
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing analyzer", e)
        }
        try {
            cameraProvider?.unbindAll()
            cameraProvider = null
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding camera", e)
        }
    }

    override fun onPause() {
        super.onPause()
        stopCameraSafely()
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (cameraProvider == null) {
                startCamera()
            }
        }
    }

    private fun processCameraFrame(imageProxy: ImageProxy) {
        try {
            if (isFinishing || isDestroyed) {
                imageProxy.close()
                return
            }

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

            val landmarker = AiManager.faceLandmarker
            val yolo = AiManager.yoloDetector

            if (landmarker == null || yolo == null) return

            // 1. Detect 478 3D Face Landmarks via MediaPipe
            val landmarks = landmarker.detect(bitmap)
            if (landmarks == null || landmarks.isEmpty()) {
                videoStabilizer.reset()
                lastYoloBox = null
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        binding.overlayView.clear()
                        updateUiForNoFace()
                    }
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
                lastYoloBox = null
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        binding.overlayView.clear()
                    }
                }
                return
            }

            // 3. AI Tefillin Detection (Throttled to ~250ms, runs asynchronously without lagging preview)
            val now = SystemClock.uptimeMillis()
            if (!yolo.isRunning() && (now - lastYoloTime >= 250L)) {
                lastYoloTime = now
                val detected = yolo.detect(bitmap)
                // Validate candidate detection against face geometry to eliminate glasses and non-Tefillin objects
                val validated = if (detected != null && TefillinEngine.isValidTefillinBox(detected, geometry)) {
                    detected
                } else {
                    null
                }
                // CRITICAL: Always update lastYoloBox (even when null) so tracking drops cleanly!
                lastYoloBox = validated
            }

            // 4. Head-Anchored Real-Time Box Tracking at 60 FPS
            // When lastYoloBox is null, tracking will cleanly expire after 6-8 frames!
            val winner = videoStabilizer.stabilizeBox(lastYoloBox, geometry)

            // 5. Evaluate Halachic Alignment Status
            val alignment = TefillinEngine.evaluateAlignment(winner, geometry)

            // 6. Push to UI
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    binding.overlayView.setResults(geometry, alignment, bitmap.width, bitmap.height)
                    updateUiWithAlignment(alignment)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in processCameraFrame", e)
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
        stopCameraSafely()
        try {
            cameraExecutor.shutdownNow()
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
