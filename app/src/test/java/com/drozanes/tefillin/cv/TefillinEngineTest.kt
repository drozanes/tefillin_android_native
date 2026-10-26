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
            hairlinePt = hairline,
            eyeLeftPt = Point2D(midX - eyeDist / 2f, glabellaY + 10f),
            eyeRightPt = Point2D(midX + eyeDist / 2f, glabellaY + 10f),
            eyeDist = eyeDist,
            uEye = uEye,
            uUp = uUp,
            pitchAngle = pitchAngle,
            pitchCompression = 1.0f,
            eyebrowLevelY = glabellaY
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

        // Tefillin shifted slightly to the left (X = 330, offset = +10 > 8, triggers stiff center rule)
        val slightLeftWinner = TefillinBox(
            x = 330f,
            y = 150f,
            boxWidth = 40f,
            boxHeight = 40f,
            bottomY = 170f
        )
        val slightLeftResult = TefillinEngine.evaluateAlignment(slightLeftWinner, geometry)
        assertEquals(AlignmentStatus.OFF_CENTER, slightLeftResult.status)

        // Tefillin shifted far to the left (X = 350, offset = +30 > 8)
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

    @Test
    fun testGlassesOnEyebrowsOrFaceRejected() {
        val geometry = createMockGeometry(hairlineY = 200f, glabellaY = 280f, eyeDist = 100f)
        // Glasses frame sitting at eyebrow/eyes level (bottomY = 285px)
        val glassesOnFace = TefillinBox(
            x = 320f,
            y = 265f,
            boxWidth = 40f,
            boxHeight = 40f,
            bottomY = 285f
        )
        assertFalse(
            "Glasses near or below eyebrow level must be rejected",
            TefillinEngine.isValidTefillinBox(glassesOnFace, geometry)
        )
    }

    @Test
    fun testGlassesSpanningAcrossHeadRejected() {
        val geometry = createMockGeometry(hairlineY = 200f, glabellaY = 280f, eyeDist = 100f)
        // Glasses pushed up on top of the head: wide frame spanning ear to ear (width = 90px > 0.68 * 100px)
        val glassesOnHead = TefillinBox(
            x = 320f,
            y = 150f,
            boxWidth = 90f,
            boxHeight = 35f,
            bottomY = 167f
        )
        assertFalse(
            "Wide glasses frames on head must be rejected",
            TefillinEngine.isValidTefillinBox(glassesOnHead, geometry)
        )
    }

    @Test
    fun testGlassesElongatedAspectRatioRejected() {
        val geometry = createMockGeometry(hairlineY = 200f, glabellaY = 280f, eyeDist = 100f)
        // Thin elongated frame (width 60px, height 18px, aspect ratio = 3.33)
        val elongatedFrame = TefillinBox(
            x = 320f,
            y = 150f,
            boxWidth = 60f,
            boxHeight = 18f,
            bottomY = 159f
        )
        assertFalse(
            "Elongated frames with non-square aspect ratio must be rejected",
            TefillinEngine.isValidTefillinBox(elongatedFrame, geometry)
        )
    }

    @Test
    fun testGenuineTefillinAccepted() {
        val geometry = createMockGeometry(hairlineY = 200f, glabellaY = 280f, eyeDist = 100f)
        // Typical adult Tefillin Shel Rosh (38px x 38px, centered, resting above hairline)
        val kosherTefillin = TefillinBox(
            x = 320f,
            y = 150f,
            boxWidth = 38f,
            boxHeight = 38f,
            bottomY = 169f
        )
        assertTrue(
            "Genuine Tefillin box must be accepted",
            TefillinEngine.isValidTefillinBox(kosherTefillin, geometry)
        )
    }
}
