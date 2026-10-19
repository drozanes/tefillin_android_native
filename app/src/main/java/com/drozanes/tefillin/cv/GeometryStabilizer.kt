package com.drozanes.tefillin.cv

import com.drozanes.tefillin.model.FaceGeometry
import com.drozanes.tefillin.model.Point2D
import com.drozanes.tefillin.model.TefillinBox
import kotlin.math.*

/**
 * Temporal Geometry Stabilizer for live video streams (matching createGeometryStabilizer from tefillin_engine.js).
 * Eliminates 1-2px micro-jitter via deadband thresholding and smooths deliberate
 * head movement using an adaptive low-pass filter (Exponential Moving Average).
 * 
 * Also implements Head-Anchored Real-Time Tracking (zero lag on head movement).
 */
class GeometryStabilizer(
    private val deadbandPx: Float = 2.0f,
    private val angleDeadbandDeg: Float = 0.6f,
    private val minAlpha: Float = 0.18f,
    private val maxAlpha: Float = 0.85f
) {
    private var prevGeometry: FaceGeometry? = null
    private var headRelBox: HeadRelBox? = null
    private var consecutiveMissCount = 0

    var lastVisualRatio: Float? = null
    var missingVisualCount: Int = 0

    private data class HeadRelBox(
        var relX: Float,
        var relY: Float,
        var relW: Float,
        var relH: Float,
        var conf: Float
    )

    fun reset() {
        prevGeometry = null
        headRelBox = null
        consecutiveMissCount = 0
        lastVisualRatio = null
        missingVisualCount = 0
    }

    private fun stabilizePoint(prevPt: Point2D?, rawPt: Point2D, deadband: Float, scale: Float = 1.0f): Point2D {
        if (prevPt == null) return rawPt
        val dx = rawPt.x - prevPt.x
        val dy = rawPt.y - prevPt.y
        val dist = hypot(dx, dy)
        val effectiveDeadband = deadband * scale

        // Deadband: within noise tolerance, keep strictly unchanged
        if (dist <= effectiveDeadband) {
            return prevPt
        }

        // Large jump (face teleported or camera repositioned): snap immediately
        if (dist > 90f * scale) {
            return rawPt
        }

        // Adaptive alpha based on displacement beyond deadband
        val t = min(1.0f, (dist - effectiveDeadband) / (25f * scale))
        val alpha = minAlpha + t * (maxAlpha - minAlpha)

        return Point2D(
            prevPt.x + alpha * dx,
            prevPt.y + alpha * dy
        )
    }

    private fun stabilizeAngle(prevAngle: Float?, rawAngle: Float, deadbandRad: Float): Float {
        if (prevAngle == null) return rawAngle
        var diff = rawAngle - prevAngle
        while (diff < -Math.PI.toFloat()) diff += (Math.PI * 2).toFloat()
        while (diff > Math.PI.toFloat()) diff -= (Math.PI * 2).toFloat()

        if (abs(diff) <= deadbandRad) {
            return prevAngle
        }

        val t = min(1.0f, (abs(diff) - deadbandRad) / 0.18f)
        val alpha = minAlpha + t * (maxAlpha - minAlpha)
        return prevAngle + alpha * diff
    }

    fun update(rawGeometry: FaceGeometry?): FaceGeometry? {
        if (rawGeometry == null) {
            reset()
            return null
        }

        val prev = prevGeometry
        if (prev == null) {
            prevGeometry = rawGeometry
            return rawGeometry
        }

        val scale = max(0.6f, rawGeometry.eyeDist / 140.0f)
        val angleDeadbandRad = (angleDeadbandDeg * Math.PI / 180.0).toFloat()

        // 1. Stabilize Eye Angle and Vector
        val rawEyeAngle = atan2(rawGeometry.uEye.y, rawGeometry.uEye.x)
        val prevEyeAngle = atan2(prev.uEye.y, prev.uEye.x)
        val stabEyeAngle = stabilizeAngle(prevEyeAngle, rawEyeAngle, angleDeadbandRad)
        val stabUEye = Point2D(cos(stabEyeAngle), sin(stabEyeAngle))

        // 2. Stabilize Up Vector
        val rawUpAngle = atan2(rawGeometry.uUp.y, rawGeometry.uUp.x)
        val prevUpAngle = atan2(prev.uUp.y, prev.uUp.x)
        val stabUpAngle = stabilizeAngle(prevUpAngle, rawUpAngle, angleDeadbandRad)
        val stabUUp = Point2D(cos(stabUpAngle), sin(stabUpAngle))

        // 3. Stabilize Anchor Points with Deadband
        val stabGlabella = stabilizePoint(prev.glabellaPt, rawGeometry.glabellaPt, deadbandPx * 0.8f, scale)
        val stabMeshTop = stabilizePoint(prev.meshTopPt, rawGeometry.meshTopPt, deadbandPx * 1.0f, scale)
        val stabMidEyes = stabilizePoint(prev.midEyesPt, rawGeometry.midEyesPt, deadbandPx * 0.8f, scale)

        // 4. Stabilize Hairline Point
        val stabHairline = stabilizePoint(prev.hairlinePt, rawGeometry.hairlinePt, deadbandPx, scale)

        // 5. Stabilize Eye Distance smoothly
        val eyeDistDiff = abs(rawGeometry.eyeDist - prev.eyeDist)
        var stabEyeDist = prev.eyeDist
        if (eyeDistDiff > deadbandPx * scale) {
            stabEyeDist = prev.eyeDist + 0.25f * (rawGeometry.eyeDist - prev.eyeDist)
        }

        val stabilized = rawGeometry.copy(
            glabellaPt = stabGlabella,
            meshTopPt = stabMeshTop,
            midEyesPt = stabMidEyes,
            hairlinePt = stabHairline,
            uEye = stabUEye,
            uUp = stabUUp,
            eyeDist = stabEyeDist
        )

        prevGeometry = stabilized
        return stabilized
    }

    /**
     * Head-Anchored Real-Time Tracking: Projects box into head space coordinates.
     * Prevents YOLO frame-interval lag and eliminates jitter while keeping zero latency
     * on head movement.
     */
    fun stabilizeBox(winner: TefillinBox?, geometry: FaceGeometry?): TefillinBox? {
        if (geometry == null) return winner

        val anchor = geometry.glabellaPt
        val eyeDist = max(10f, geometry.eyeDist)
        val uEye = geometry.uEye
        val uUp = geometry.uUp

        if (winner != null) {
            consecutiveMissCount = 0
            val dx = winner.x - anchor.x
            val dy = winner.y - anchor.y

            // Project winner into Head Space coordinates:
            val rawRelX = (dx * uEye.x + dy * uEye.y) / eyeDist
            val rawRelY = (dx * uUp.x + dy * uUp.y) / eyeDist
            val rawRelW = winner.boxWidth / eyeDist
            val rawRelH = winner.boxHeight / eyeDist

            val curRel = headRelBox
            if (curRel == null) {
                headRelBox = HeadRelBox(rawRelX, rawRelY, rawRelW, rawRelH, winner.confidence)
            } else {
                val dRelX = rawRelX - curRel.relX
                val dRelY = rawRelY - curRel.relY
                val dDist = hypot(dRelX, dRelY)

                val relDeadband = 0.018f
                if (dDist > relDeadband) {
                    val alpha = if (dDist > 0.12f) 0.85f else 0.45f
                    curRel.relX += alpha * dRelX
                    curRel.relY += alpha * dRelY
                    curRel.relW += alpha * (rawRelW - curRel.relW)
                    curRel.relH += alpha * (rawRelH - curRel.relH)
                    curRel.conf = winner.confidence
                }
            }
        } else {
            consecutiveMissCount++
            if (consecutiveMissCount > 8) {
                headRelBox = null
                return null
            }
        }

        val box = headRelBox ?: return null

        // Project Head-Relative box onto the CURRENT 60 FPS head position
        val currX = anchor.x + (box.relX * uEye.x + box.relY * uUp.x) * eyeDist
        val currY = anchor.y + (box.relX * uEye.y + box.relY * uUp.y) * eyeDist
        val currW = box.relW * eyeDist
        val currH = box.relH * eyeDist
        val bottomY = currY - uUp.y * (currH / 2f)

        return TefillinBox(
            x = currX,
            y = currY,
            boxWidth = currW,
            boxHeight = currH,
            leftX = currX - currW / 2f,
            rightX = currX + currW / 2f,
            topY = currY - currH / 2f,
            bottomY = bottomY,
            confidence = box.conf
        )
    }
}
