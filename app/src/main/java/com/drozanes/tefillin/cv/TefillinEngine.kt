package com.drozanes.tefillin.cv

import android.graphics.Bitmap
import android.graphics.Color
import com.drozanes.tefillin.model.*
import kotlin.math.*

/**
 * TefillinEngine - Computer Vision & Halachic Alignment Engine
 * Direct, faithful Kotlin port of tefillin_engine.js from tefillin_app with
 * named constants, optimized memory safety, and halachic boundary verification.
 */
object TefillinEngine {

    // --- Named Constants for Visual Hairline Detection ---
    const val HAIRLINE_SCAN_START_RATIO = 0.60f
    const val HAIRLINE_SCAN_END_RATIO = 0.95f
    const val HAIRLINE_BASELINE_START_RATIO = 0.35f
    const val HAIRLINE_BASELINE_STEP_RATIO = 0.02f
    const val HAIRLINE_PROBE_LATERAL_OFFSET_RATIO = 0.15f
    const val HAIRLINE_CENTER_DIFF_THRESHOLD = 24
    const val HAIRLINE_CENTER_RATIO_THRESHOLD = 0.76f
    const val HAIRLINE_SIDE_DIFF_THRESHOLD = 20
    const val HAIRLINE_SIDE_RATIO_THRESHOLD = 0.80f
    const val HAIRLINE_TEXTURE_STD_DEV_MIN = 18f
    const val HAIRLINE_TEXTURE_SKIN_DIFF = 15

    // --- Anthropometric & Perspective Foreshortening Priors ---
    const val ANATOMICAL_HAIRLINE_RATIO = 0.82f
    const val MIN_PITCH_FACTOR = 0.60f
    const val PITCH_REF_ANGLE_DEG = 25f
    const val TRICHION_MIN_FACTOR = 0.90f
    const val TRICHION_MAX_FACTOR = 1.15f
    const val MIN_MIDFACE_RATIO = 0.65f
    const val MIN_HAIRLINE_RATIO = 0.66f

    // --- Halachic Alignment & Candidate Box Verification ---
    const val HALACHIC_CENTER_TOLERANCE_RATIO = 0.18f
    const val HALACHIC_BORDERLINE_MARGIN_RATIO = 0.04f
    const val CAMERA_PITCH_TOO_LOW_DEG = 35f
    const val CAMERA_PITCH_TOO_HIGH_DEG = -20f
    const val BOX_EYEBROW_EXCLUSION_MARGIN_RATIO = 0.05f
    const val BOX_CEILING_MAX_DISTANCE_RATIO = 3.0f
    const val BOX_HORIZONTAL_MAX_DEVIATION_RATIO = 0.60f
    const val BOX_MIN_DIMENSION_RATIO = 0.15f
    const val BOX_MAX_DIMENSION_RATIO = 0.68f
    const val BOX_MIN_ASPECT_RATIO = 0.45f
    const val BOX_MAX_ASPECT_RATIO = 2.0f

    data class LandmarkPoint(
        val x: Float, // Normalized 0..1
        val y: Float, // Normalized 0..1
        val z: Float  // Normalized 0..1
    )

    /**
     * 1. Helper: Adaptive Visual Hairline Detection
     * Samples forehead skin tone above Glabella and scans along the central facial symmetry axis (uUp)
     * to find the true physiological boundary where forehead skin ends and hair roots begin.
     */
    fun detectVisualHairline(
        bitmap: Bitmap?,
        width: Int,
        height: Int,
        glabellaPt: Point2D,
        uUp: Point2D,
        eyeDist: Float,
        pitchAngle: Float
    ): Float? {
        if (bitmap == null || width < 10 || height < 10) return null
        try {
            val refRad = PITCH_REF_ANGLE_DEG * (Math.PI / 180f).toFloat()
            val pitchFactor = max(MIN_PITCH_FACTOR, cos(refRad + pitchAngle) / cos(refRad))
            val uCross = Point2D(-uUp.y, uUp.x)

            val scanStart = HAIRLINE_SCAN_START_RATIO * eyeDist * pitchFactor
            val scanEnd = HAIRLINE_SCAN_END_RATIO * eyeDist * pitchFactor

            fun getGray(px: Float, py: Float): Int {
                val rx = px.roundToInt()
                val ry = py.roundToInt()
                if (rx < 0 || rx >= bitmap.width || ry < 0 || ry >= bitmap.height) return -1
                val pixel = bitmap.getPixel(rx, ry)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                return (r * 77 + g * 150 + b * 29) shr 8
            }

            // Sample baseline forehead skin tone (between 0.35 and 0.50 * eyeDist)
            var skinSum = 0
            var skinCount = 0
            for (s in 0 until 8) {
                val d = (HAIRLINE_BASELINE_START_RATIO + s * HAIRLINE_BASELINE_STEP_RATIO) * eyeDist
                val g = getGray(glabellaPt.x + uUp.x * d, glabellaPt.y + uUp.y * d)
                if (g >= 0) {
                    skinSum += g
                    skinCount++
                }
            }
            if (skinCount == 0) return null
            val skinMed = skinSum.toFloat() / skinCount

            val stepPx = max(2, (eyeDist * 0.012f).roundToInt())
            val numSteps = floor((scanEnd - scanStart) / stepPx).toInt()

            for (i in 0 until numSteps) {
                val d = scanStart + i * stepPx
                val cx = glabellaPt.x + uUp.x * d
                val cy = glabellaPt.y + uUp.y * d

                val cVal = getGray(cx, cy)
                if (cVal < 0) continue

                val lateralOffset = eyeDist * HAIRLINE_PROBE_LATERAL_OFFSET_RATIO
                val lx = cx - uCross.x * lateralOffset
                val ly = cy - uCross.y * lateralOffset
                val rx = cx + uCross.x * lateralOffset
                val ry = cy + uCross.y * lateralOffset

                val lVal = getGray(lx, ly)
                val rVal = getGray(rx, ry)

                val isDarkCenter = (cVal < skinMed - HAIRLINE_CENTER_DIFF_THRESHOLD) || (cVal < skinMed * HAIRLINE_CENTER_RATIO_THRESHOLD)
                val isDarkLeft = lVal >= 0 && ((lVal < skinMed - HAIRLINE_SIDE_DIFF_THRESHOLD) || (lVal < skinMed * HAIRLINE_SIDE_RATIO_THRESHOLD))
                val isDarkRight = rVal >= 0 && ((rVal < skinMed - HAIRLINE_SIDE_DIFF_THRESHOLD) || (rVal < skinMed * HAIRLINE_SIDE_RATIO_THRESHOLD))

                // 5-point local texture std dev
                var sum = 0
                var sumSq = 0
                var count = 0
                for (dy in -2..2 step 2) {
                    for (dx in -2..2 step 2) {
                        val v = getGray(cx + dx, cy + dy)
                        if (v >= 0) {
                            sum += v
                            sumSq += v * v
                            count++
                        }
                    }
                }
                val mean = if (count > 0) sum.toFloat() / count else cVal.toFloat()
                val variance = if (count > 0) (sumSq.toFloat() / count) - (mean * mean) else 0f
                val stdDev = sqrt(max(0f, variance))

                if ((isDarkCenter && (isDarkLeft || isDarkRight)) ||
                    (stdDev > HAIRLINE_TEXTURE_STD_DEV_MIN && cVal < skinMed - HAIRLINE_TEXTURE_SKIN_DIFF)) {
                    return d
                }
            }
            return null
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * 2. Extract 3D Facial Geometry & Halachic Reference Points from MediaPipe Landmarks
     */
    fun analyzeFaceGeometry(
        landmarks: List<LandmarkPoint>,
        width: Int,
        height: Int,
        hairlineUserAdj: Float = 0f,
        bitmap: Bitmap? = null,
        stabilizer: GeometryStabilizer? = null
    ): FaceGeometry? {
        if (landmarks.size < 468) return null

        // Key Anthropometric Landmarks
        val glabella = landmarks.getOrNull(8) ?: landmarks.getOrNull(9) ?: landmarks[168]
        val midEyes = landmarks[168]
        val noseBridge = landmarks[6]
        val meshTop = landmarks[10]
        val eyeLeft = landmarks[33]
        val eyeRight = landmarks[263]

        val eyeLeftPt = Point2D(eyeLeft.x * width, eyeLeft.y * height)
        val eyeRightPt = Point2D(eyeRight.x * width, eyeRight.y * height)
        val glabellaPt = Point2D(glabella.x * width, glabella.y * height)
        val midEyesPt = Point2D(midEyes.x * width, midEyes.y * height)
        val nosePt = Point2D(noseBridge.x * width, noseBridge.y * height)
        val meshTopPt = Point2D(meshTop.x * width, meshTop.y * height)

        // Eye distance scale (invariant to camera distance)
        val eyeVector = Point2D(eyeRightPt.x - eyeLeftPt.x, eyeRightPt.y - eyeLeftPt.y)
        val eyeDist = hypot(eyeVector.x, eyeVector.y)
        val uEye = Point2D(eyeVector.x / eyeDist, eyeVector.y / eyeDist)

        // UP vector along central facial symmetry axis
        val faceUpVector = Point2D(meshTopPt.x - nosePt.x, meshTopPt.y - nosePt.y)
        val faceLen = hypot(faceUpVector.x, faceUpVector.y)
        val uUp = Point2D(faceUpVector.x / faceLen, faceUpVector.y / faceLen)

        // 3D Head Pitch Angle (perspective foreshortening compensation)
        val dz = (meshTop.z - noseBridge.z) * width
        val dy = (meshTopPt.y - nosePt.y)
        val pitchAngle = atan2(dz, abs(dy))
        val pitchCompression = max(0.7f, cos(pitchAngle))
        val refRad = PITCH_REF_ANGLE_DEG * (Math.PI / 180f).toFloat()
        val pitchFactor = max(MIN_PITCH_FACTOR, cos(refRad + pitchAngle) / cos(refRad))

        // Adaptive visual hairline detection
        var visualHairDist = detectVisualHairline(bitmap, width, height, glabellaPt, uUp, eyeDist, pitchAngle)

        // Temporal visual consistency if stabilizer is provided
        if (stabilizer != null) {
            if (visualHairDist != null) {
                val rawRatio = visualHairDist / eyeDist
                val lastRatio = stabilizer.lastVisualRatio
                stabilizer.lastVisualRatio = if (lastRatio != null) {
                    0.80f * lastRatio + 0.20f * rawRatio
                } else {
                    rawRatio
                }
                stabilizer.missingVisualCount = 0
                visualHairDist = stabilizer.lastVisualRatio!! * eyeDist
            } else if (stabilizer.lastVisualRatio != null && stabilizer.missingVisualCount < 18) {
                stabilizer.missingVisualCount++
                visualHairDist = stabilizer.lastVisualRatio!! * eyeDist
            } else {
                stabilizer.lastVisualRatio = null
            }
        }

        // Skeletal Facial Third (Subnasale -> Glabella)
        val subnasale = landmarks.getOrNull(2)
        val rawMidfaceDist = if (subnasale != null) {
            val subnasalePt = Point2D(subnasale.x * width, subnasale.y * height)
            hypot(glabellaPt.x - subnasalePt.x, glabellaPt.y - subnasalePt.y)
        } else {
            eyeDist * 0.68f
        }
        // Protect against 2D perspective foreshortening under upward camera pitch
        val midfaceDist = max(rawMidfaceDist, eyeDist * MIN_MIDFACE_RATIO)
        val anatomicalTrichionDist = midfaceDist * 1.08f

        // Dynamic Clamping Bounds:
        // Anti-Poni / Anti-Cap Visor: Hair roots cannot physically grow below 90% of anatomical midface
        val minTrichionDist = max(eyeDist * MIN_HAIRLINE_RATIO * pitchFactor, anatomicalTrichionDist * TRICHION_MIN_FACTOR)
        // Anti-Balding: Caps hairline so receding hair doesn't force tefillin past the crown
        val maxTrichionDist = min(eyeDist * 0.90f, anatomicalTrichionDist * TRICHION_MAX_FACTOR)

        val boundedVisualDist = if (visualHairDist != null) {
            visualHairDist.coerceIn(minTrichionDist, maxTrichionDist)
        } else {
            null
        }

        val distFromGlabella = if (boundedVisualDist != null) {
            boundedVisualDist + (hairlineUserAdj * 0.15f * eyeDist)
        } else {
            anatomicalTrichionDist + (hairlineUserAdj * 0.20f * eyeDist)
        }

        val hairlinePt = Point2D(
            glabellaPt.x + uUp.x * distFromGlabella,
            glabellaPt.y + uUp.y * distFromGlabella
        )

        // Strict Eyebrow & Eyes Exclusion
        val leftBrowTopY = landmarks.getOrNull(107)?.let { it.y * height } ?: (glabellaPt.y - eyeDist * 0.10f)
        val rightBrowTopY = landmarks.getOrNull(336)?.let { it.y * height } ?: (glabellaPt.y - eyeDist * 0.10f)
        val highestBrowY = min(leftBrowTopY, rightBrowTopY)
        val eyebrowLevelY = highestBrowY

        val rawGeometry = FaceGeometry(
            glabellaPt = glabellaPt,
            midEyesPt = midEyesPt,
            nosePt = nosePt,
            meshTopPt = meshTopPt,
            hairlinePt = hairlinePt,
            eyeLeftPt = eyeLeftPt,
            eyeRightPt = eyeRightPt,
            eyeDist = eyeDist,
            uEye = uEye,
            uUp = uUp,
            pitchAngle = pitchAngle,
            pitchCompression = pitchCompression,
            eyebrowLevelY = eyebrowLevelY
        )

        return stabilizer?.update(rawGeometry) ?: rawGeometry
    }

    /**
     * Validates that a detected candidate box matches the physical and geometric
     * criteria of a genuine Head Tefillin (Shel Rosh), rejecting glasses, frames, and background noise.
     */
    fun isValidTefillinBox(box: TefillinBox, geometry: FaceGeometry): Boolean {
        val eyeDist = max(10f, geometry.eyeDist)

        // 1. Eyebrow / Glasses Exclusion:
        // Tefillin Shel Rosh sits on the scalp/hairline, never touching or below the eyebrows/eyes
        val eyebrowThreshold = geometry.eyebrowLevelY - eyeDist * BOX_EYEBROW_EXCLUSION_MARGIN_RATIO
        if (box.bottomY > eyebrowThreshold) {
            return false
        }

        // 2. Ceiling / Background Exclusion:
        // Cannot be floating way up in the ceiling
        val ceilingThreshold = geometry.glabellaPt.y - eyeDist * BOX_CEILING_MAX_DISTANCE_RATIO
        if (box.y < ceilingThreshold) {
            return false
        }

        // 3. Horizontal Midline Centering:
        // Perpendicular distance from box center to Glabella-MeshTop symmetry axis
        val a = geometry.glabellaPt
        val b = geometry.meshTopPt
        val lineLen = hypot(b.x - a.x, b.y - a.y)
        val horizOffset = if (lineLen > 0.1f) {
            abs(((b.x - a.x) * (a.y - box.y) - (b.y - a.y) * (a.x - box.x)) / lineLen)
        } else {
            abs(box.x - a.x)
        }
        if (horizOffset > eyeDist * BOX_HORIZONTAL_MAX_DEVIATION_RATIO) {
            return false
        }

        // 4. Physical Dimensions relative to Eye Distance:
        // Real Tefillin box is ~32-40mm wide. Inter-pupillary distance is ~63mm (~0.35-0.55 * eyeDist).
        // Glasses pushed up on head span across the entire skull (width >= 0.80 * eyeDist).
        val minDim = eyeDist * BOX_MIN_DIMENSION_RATIO
        val maxDim = eyeDist * BOX_MAX_DIMENSION_RATIO
        if (box.boxWidth < minDim || box.boxWidth > maxDim) {
            return false
        }
        if (box.boxHeight < minDim || box.boxHeight > maxDim) {
            return false
        }

        // 5. Aspect Ratio (Squareness):
        // A Tefillin box is roughly square (0.45 <= W/H <= 2.0). Glasses rims/frames are elongated.
        val aspectRatio = box.boxWidth / box.boxHeight
        if (aspectRatio < BOX_MIN_ASPECT_RATIO || aspectRatio > BOX_MAX_ASPECT_RATIO) {
            return false
        }

        return true
    }

    /**
     * 3. Evaluate Halachic Alignment (Distance from Hairline & Horizontal Symmetry)
     */
    fun evaluateAlignment(winner: TefillinBox?, geometry: FaceGeometry?): AlignmentResult {
        if (winner == null || geometry == null) {
            return AlignmentResult(
                status = AlignmentStatus.NO_TEFILLIN,
                warning = false,
                direction = "",
                isKosher = false,
                isCentered = false,
                distAboveHairline = 0f,
                horizOffset = 0f,
                winner = null
            )
        }

        val hairlinePt = geometry.hairlinePt
        val uUp = geometry.uUp
        val eyeDist = geometry.eyeDist

        // Lowest edge vector from hairline point
        val lowestEdgeX = winner.x
        val lowestEdgeY = winner.bottomY
        val lowestEdgeVector = Point2D(lowestEdgeX - hairlinePt.x, lowestEdgeY - hairlinePt.y)

        // Positive = Lowest edge is ABOVE hairline on scalp (Kosher).
        // Negative = Lowest edge is BELOW hairline on forehead skin (Passul).
        val distAboveHairline = lowestEdgeVector.x * uUp.x + lowestEdgeVector.y * uUp.y
        val isEntirelyOnScalp = distAboveHairline >= 0f

        // Horizontal offset from middle of eyes using surface axis (Glabella -> MeshTop)
        val a = geometry.glabellaPt
        val b = geometry.meshTopPt
        val p = Point2D(winner.x, winner.y)
        val lineLen = hypot(b.x - a.x, b.y - a.y)
        var horizOffsetLocal = 0f
        if (lineLen > 0.1f) {
            horizOffsetLocal = ((b.x - a.x) * (a.y - p.y) - (b.y - a.y) * (a.x - p.x)) / lineLen
        }
        val isCentered = abs(horizOffsetLocal) < (eyeDist * HALACHIC_CENTER_TOLERANCE_RATIO)

        // Camera Pose / Angle Limits
        val pitchDeg = (geometry.pitchAngle * 180f) / Math.PI.toFloat()
        val isCameraTooLow = pitchDeg > CAMERA_PITCH_TOO_LOW_DEG
        val isCameraTooHigh = pitchDeg < CAMERA_PITCH_TOO_HIGH_DEG

        var status = AlignmentStatus.ALIGNED
        var warning = false
        var direction = ""

        if (isCameraTooLow) {
            status = AlignmentStatus.CAMERA_TOO_LOW
            warning = true
            direction = "📐 Camera too low"
        } else if (isCameraTooHigh) {
            status = AlignmentStatus.CAMERA_TOO_HIGH
            warning = true
            direction = "📐 Camera too high"
        } else if (!isEntirelyOnScalp) {
            status = AlignmentStatus.FOREHEAD_ERROR
            direction = "⬆️ Move Up"
        } else if (!isCentered) {
            status = AlignmentStatus.OFF_CENTER
            direction = if (horizOffsetLocal > 0) "⬅️ Move Left" else "➡️ Move Right"
        } else {
            status = AlignmentStatus.ALIGNED
            if (distAboveHairline < (eyeDist * HALACHIC_BORDERLINE_MARGIN_RATIO)) {
                warning = true
                direction = "⚠️ Borderline"
            } else {
                direction = "✅ Aligned"
            }
        }

        return AlignmentResult(
            status = status,
            warning = warning,
            direction = direction,
            isKosher = isEntirelyOnScalp && !isCameraTooLow && !isCameraTooHigh,
            isCentered = isCentered,
            distAboveHairline = distAboveHairline.roundToInt().toFloat(),
            horizOffset = horizOffsetLocal.roundToInt().toFloat(),
            winner = winner
        )
    }
}
