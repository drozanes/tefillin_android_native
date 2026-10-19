package com.drozanes.tefillin.cv

import android.graphics.RectF
import com.drozanes.tefillin.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class TefillinEngineTest {

    private fun createMockGeometry(
        hairlineY: Float = 200f,
        glabellaY: Float = 280f,
        midX: Float = 320f,
        eyeDist: Float = 100f,
        pitchAngle: Float = 0f
    ): FaceGeometry {
        val glabella = Point2D(midX, glabellaY)
        val meshTop = Point2D(midX, 150f)
        val hairline = Point2D(midX, hairlineY)
        val uUp = Point2D(0f, -1f) // pointing straight up in screen coordinates (decreasing Y)
        val uEye = Point2D(1f, 0f) // pointing right

        return FaceGeometry(
            glabellaPt = glabella,
            midEyesPt = Point2D(midX, glabellaY + 10f),
            nosePt = Point2D(midX, glabellaY + 40f),
            meshTopPt = meshTop,
            subnasalePt = Point2D(midX, glabellaY + 60f),
            mentonPt = Point2D(midX, glabellaY + 120f),
            hairlinePt = hairline,
            eyeLeftPt = Point2D(midX - eyeDist / 2f, glabellaY + 10f),
            eyeRightPt = Point2D(midX + eyeDist / 2f, glabellaY + 10f),
            eyeDist = eyeDist,
            uEye = uEye,
            uUp = uUp,
            pitchAngle = pitchAngle,
            pitchCompression = 1.0f,
            eyebrowLevelY = glabellaY,
            searchArea = RectF(midX - 50f, 100f, midX + 50f, 250f)
        )
    }

    @Test
    fun testAlignedTefillinIsKosher() {
        val geometry = createMockGeometry(hairlineY = 200f)
        // Tefillin is centered at X=320, with bottom at Y=180 (which is 20px ABOVE hairline in scalp area)
        val winner = TefillinBox(
            x = 320f,
            y = 150f,
            boxWidth = 40f,
            boxHeight = 40f,
            bottomY = 170f
        )

        val result = TefillinEngine.evaluateAlignment(winner, geometry)
        assertEquals(AlignmentStatus.ALIGNED, result.status)
        assertTrue(result.isKosher)
        assertTrue(result.isCentered)
        assertFalse(result.warning)
    }

    @Test
    fun testForeheadErrorWhenTouchingBelowHairline() {
        val geometry = createMockGeometry(hairlineY = 200f)
        // Lowest edge slipped down to Y=210 (below hairline on forehead skin)
        val winner = TefillinBox(
            x = 320f,
            y = 180f,
            boxWidth = 40f,
            boxHeight = 40f,
            bottomY = 210f
        )

        val result = TefillinEngine.evaluateAlignment(winner, geometry)
        assertEquals(AlignmentStatus.FOREHEAD_ERROR, result.status)
        assertFalse(result.isKosher)
    }

    @Test
    fun testOffCenterLeftAndRight() {
        val geometry = createMockGeometry(hairlineY = 200f, eyeDist = 100f)

        // Tefillin shifted far to the left (X = 350, offset = +30 > 18)
        val leftWinner = TefillinBox(
            x = 350f,
            y = 150f,
            boxWidth = 40f,
            boxHeight = 40f,
            bottomY = 170f
        )
        val leftResult = TefillinEngine.evaluateAlignment(leftWinner, geometry)
        assertEquals(AlignmentStatus.OFF_CENTER, leftResult.status)

        // Tefillin shifted far to the right (X = 290, offset = -30 < -18)
        val rightWinner = TefillinBox(
            x = 290f,
            y = 150f,
            boxWidth = 40f,
            boxHeight = 40f,
            bottomY = 170f
        )
        val rightResult = TefillinEngine.evaluateAlignment(rightWinner, geometry)
        assertEquals(AlignmentStatus.OFF_CENTER, rightResult.status)
    }

    @Test
    fun testCameraAngleWarnings() {
        // Camera too low (> 35 deg)
        val lowGeom = createMockGeometry(pitchAngle = (40.0 * PI / 180.0).toFloat())
        val winner = TefillinBox(x = 320f, y = 150f, boxWidth = 40f, boxHeight = 40f, bottomY = 170f)
        val lowResult = TefillinEngine.evaluateAlignment(winner, lowGeom)
        assertEquals(AlignmentStatus.CAMERA_TOO_LOW, lowResult.status)
        assertFalse(lowResult.isKosher)

        // Camera too high (< -20 deg)
        val highGeom = createMockGeometry(pitchAngle = (-25.0 * PI / 180.0).toFloat())
        val highResult = TefillinEngine.evaluateAlignment(winner, highGeom)
        assertEquals(AlignmentStatus.CAMERA_TOO_HIGH, highResult.status)
        assertFalse(highResult.isKosher)
    }

    @Test
    fun testBorderlineWarning() {
        val geometry = createMockGeometry(hairlineY = 200f, eyeDist = 100f)
        // Lowest edge at Y = 198 (only 2px above hairline, less than 0.04 * eyeDist = 4px)
        val winner = TefillinBox(
            x = 320f,
            y = 178f,
            boxWidth = 40f,
            boxHeight = 40f,
            bottomY = 198f
        )

        val result = TefillinEngine.evaluateAlignment(winner, geometry)
        assertEquals(AlignmentStatus.ALIGNED, result.status)
        assertTrue(result.isKosher)
        assertTrue(result.warning)
    }
}
