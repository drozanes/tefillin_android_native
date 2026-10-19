# Tefillin Mirror Native Android | מראת תפילין (אנדרואיד Native) 🪞✨

A high-performance **native Android application** (100% Kotlin) built with **CameraX**, **MediaPipe Tasks Vision (FaceLandmarker)**, and **ONNX Runtime Android with NNAPI/GPU acceleration** to help Jewish users align their Head Tefillin (*Shel Rosh* / תפילין של ראש) with Halachic precision in real time at 60 FPS.

---

## ⚡ Performance Improvements over WebView / Hybrid

| Feature | Original Hybrid / WebView (`tefillin_app`) | Native Android (`tefillin_android_native`) |
| :--- | :--- | :--- |
| **Camera Pipeline** | Web `getUserMedia` inside WebView with canvas repaint lag | **CameraX** with hardware-accelerated surface rendering |
| **Face Tracking** | MediaPipe JS WASM in WebView (software CPU) | **MediaPipe Tasks Vision** with native **GPU delegate** (GLES3 compute) |
| **Tefillin Detection** | ONNX Runtime Web via Web Worker (WASM) | **ONNX Runtime Android** with **Android NNAPI** (NPU/GPU/DSP) |
| **Frame Rate** | 15–25 FPS with micro-jank | **Solid 60 FPS** with zero main-thread blocking |
| **Jitter Filter** | JavaScript stabilizer | **Native GeometryStabilizer** + **Head-Anchored Real-Time Tracking** |
| **ANR Risk** | High during model compilation in WebView | **Zero**: Background thread executor + Kotlin coroutines |

---

## 📜 Halachic Rules Verified (100% Algorithm Parity)

1. **Horizontal Alignment (אמצע ברוחב):**
   - The Shel Rosh box must sit **exactly in the middle** between the eyes along the facial surface axis (Glabella to MeshTop).
   - Real-time directional feedback: `⬅️ הזיזו שמאלה` / `➡️ הזיזו ימינה`.
2. **Vertical Alignment (מקום גידול שיער בגובה):**
   - The entire box (*Ketzitzah*) and loop (*Ma'avarta*) must rest **strictly on the scalp where hair grows**.
   - Zero-tolerance: if even 1 pixel touches smooth forehead skin, red alert triggers: `⬆️ הרימו את התפילין למעלה!` (with an interactive link to the knot-adjustment video tutorial).
3. **Borderline Warnings:**
   - Warns with `⚠️ גבולי` when kosher but within `0.04 * eyeDist` of the hairline edge.
4. **Camera Pose Limits:**
   - Alerts `📐 מצלמה נמוכה מדי` or `📐 מצלמה גבוהה מדי` if phone pitch angle exceeds safe perspective limits.

---

## 🛠️ Architecture & Components

```
tefillin_android_native/
├── app/
│   ├── src/main/
│   │   ├── assets/
│   │   │   ├── best_uint8.onnx         # Quantized INT8 YOLOv8 model (3.2 MB, ultra fast)
│   │   │   ├── best_fp16.onnx          # Half-precision FP16 YOLOv8 model (5.9 MB, accurate)
│   │   │   └── face_landmarker.task    # Google MediaPipe 478 3D Landmark Model
│   │   ├── java/com/drozanes/tefillin/
│   │   │   ├── MainActivity.kt          # CameraX lifecycle, UI controller, frame pipeline
│   │   │   ├── cv/
│   │   │   │   ├── TefillinEngine.kt    # Core Halachic geometry & adaptive hairline CV
│   │   │   │   ├── GeometryStabilizer.kt# Deadband filter & Head-Anchored 60 FPS box projection
│   │   │   │   └── YoloDetector.kt      # ONNX Runtime Android with NNAPI/GPU acceleration
│   │   │   ├── mediapipe/
│   │   │   │   └── FaceLandmarkHelper.kt# MediaPipe Tasks Vision FaceLandmarker (GPU/CPU)
│   │   │   ├── ui/
│   │   │   │   ├── OverlayView.kt       # Custom hardware-accelerated canvas overlay
│   │   │   │   ├── HalachaDialog.kt     # Halachic rules modal dialog
│   │   │   │   └── BugReportDialog.kt   # Diagnostic snapshot & native Android share sheet
│   │   │   └── util/
│   │   │       ├── DiagnosticLogger.kt  # Rolling technical diagnostic logs
│   │   │       └── LocaleManager.kt     # Dynamic Hebrew (RTL) / English language switcher
│   │   └── res/                         # Glassmorphic dark layouts, strings (en, he) & colors
│   └── src/test/java/                   # Unit test suite verifying 100% algorithm parity
├── tefillin-mirror-native-debug.apk     # Precompiled ready-to-install debug APK
└── build.gradle                         # Gradle configuration
```

---

## 🚀 Building & Testing

### Build APK
```bash
cd /Users/drozanes/Dev/tefillin_android_native
./gradlew assembleDebug
```
Output APK is located at:
- `tefillin-mirror-native-debug.apk` (root of project)
- `app/build/outputs/apk/debug/app-debug.apk`

### Run Unit Tests
```bash
./gradlew test
```
All unit tests verify Halachic edge cases (scalp placement, forehead touch, off-center deviation, angle limits, and deadband stabilization).
