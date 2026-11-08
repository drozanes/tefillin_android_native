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
        pitchAngle: Float = 0f,
        yawAngle: Float = 0f,
        rollAngle: Float = 0f
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
            eyebrowLevelY = glabellaY,
            yawAngle = yawAngle,
            rollAngle = rollAngle
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
        // Lowest edge slipped down to Y=210 (below hairline on forehead skin, past -5px margin)
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
        assertFalse(result.warning)
    }

    @Test
    fun testBorderlineWarningWhenSlightlyBelowHairline() {
        val geometry = createMockGeometry(hairlineY = 200f, eyeDist = 100f)
        // Lowest edge at Y=203 (3px below hairline, within ±5px borderline margin):
        // Should respond with yellow warning rather than strict forehead error
        val winner = TefillinBox(
            x = 320f,
            y = 175f,
            boxWidth = 40f,
            boxHeight = 40f,
            bottomY = 203f
        )

        val result = TefillinEngine.evaluateAlignment(winner, geometry)
        assertEquals(AlignmentStatus.ALIGNED, result.status)
        assertTrue(result.warning)
        assertTrue(result.isKosher)
        assertEquals("⚠️ Borderline", result.direction)
    }

    @Test
    fun testBorderlineWarningWhenSlightlyAboveHairline() {
        val geometry = createMockGeometry(hairlineY = 200f, eyeDist = 100f)
        // Lowest edge at Y=198 (2px above hairline, within ±5px borderline margin):
        // Should respond with yellow warning rather than strict green perfect
        val winner = TefillinBox(
            x = 320f,
            y = 170f,
            boxWidth = 40f,
            boxHeight = 40f,
            bottomY = 198f
        )

        val result = TefillinEngine.evaluateAlignment(winner, geometry)
        assertEquals(AlignmentStatus.ALIGNED, result.status)
        assertTrue(result.warning)
        assertTrue(result.isKosher)
        assertEquals("⚠️ Borderline", result.direction)
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

        // Camera too high (< -18 deg)
        val highGeom = createMockGeometry(pitchAngle = (-25.0 * PI / 180.0).toFloat())
        val highResult = TefillinEngine.evaluateAlignment(winner, highGeom)
        assertEquals(AlignmentStatus.CAMERA_TOO_HIGH, highResult.status)
        assertFalse(highResult.isKosher)

        // Camera angled sideways (yaw > 15 deg)
        val sidewaysGeom = createMockGeometry(yawAngle = (20.0 * PI / 180.0).toFloat())
        val sidewaysResult = TefillinEngine.evaluateAlignment(winner, sidewaysGeom)
        assertEquals(AlignmentStatus.CAMERA_ANGLED_SIDEWAYS, sidewaysResult.status)
        assertFalse(sidewaysResult.isKosher)
        assertTrue(sidewaysResult.warning)

        // Head tilted sideways (roll > 15 deg)
        val tiltedGeom = createMockGeometry(rollAngle = (20.0 * PI / 180.0).toFloat())
        val tiltedResult = TefillinEngine.evaluateAlignment(winner, tiltedGeom)
        assertEquals(AlignmentStatus.HEAD_TILTED, tiltedResult.status)
        assertFalse(tiltedResult.isKosher)
        assertTrue(tiltedResult.warning)
    }

    @Test
    fun testAngleWarningsPrioritizedEvenWhenWinnerNull() {
        // When winner == null, angle violations must still be flagged
        val lowGeom = createMockGeometry(pitchAngle = (35.0 * PI / 180.0).toFloat())
        val lowResult = TefillinEngine.evaluateAlignment(null, lowGeom)
        assertEquals(AlignmentStatus.CAMERA_TOO_LOW, lowResult.status)
        assertTrue(lowResult.warning)

        val sidewaysGeom = createMockGeometry(yawAngle = (25.0 * PI / 180.0).toFloat())
        val sidewaysResult = TefillinEngine.evaluateAlignment(null, sidewaysGeom)
        assertEquals(AlignmentStatus.CAMERA_ANGLED_SIDEWAYS, sidewaysResult.status)
        assertTrue(sidewaysResult.warning)

        val tiltedGeom = createMockGeometry(rollAngle = (-22.0 * PI / 180.0).toFloat())
        val tiltedResult = TefillinEngine.evaluateAlignment(null, tiltedGeom)
        assertEquals(AlignmentStatus.HEAD_TILTED, tiltedResult.status)
        assertTrue(tiltedResult.warning)

        // Frontal pose without winner returns standard NO_TEFILLIN
        val normalGeom = createMockGeometry()
        val normalResult = TefillinEngine.evaluateAlignment(null, normalGeom)
        assertEquals(AlignmentStatus.NO_TEFILLIN, normalResult.status)
        assertFalse(normalResult.warning)
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
        // Glasses pushed up on top of the head: wide ear-to-ear frame (width = 150px > 1.35 * 100px, aspect ratio 3.75)
        val glassesOnHead = TefillinBox(
            x = 320f,
            y = 150f,
            boxWidth = 150f,
            boxHeight = 40f,
            bottomY = 170f
        )
        assertFalse(
            "Wide glasses frames on head must be rejected",
            TefillinEngine.isValidTefillinBox(glassesOnHead, geometry)
        )
    }

    @Test
    fun testGlassesElongatedAspectRatioRejected() {
        val geometry = createMockGeometry(hairlineY = 200f, glabellaY = 280f, eyeDist = 100f)
        // Thin elongated frame (width 60px, height 18px, aspect ratio = 3.33 > 2.20)
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

    @Test
    fun testSmallTefillin2cmAccepted() {
        val geometry = createMockGeometry(hairlineY = 200f, glabellaY = 280f, eyeDist = 100f)
        // Small ~2cm pocket / youth Tefillin (width 12px, height 12px, ratio = 0.12 >= 0.10)
        val smallTefillin = TefillinBox(
            x = 320f,
            y = 150f,
            boxWidth = 12f,
            boxHeight = 12f,
            bottomY = 156f
        )
        assertTrue(
            "Small ~2cm Tefillin must be accepted",
            TefillinEngine.isValidTefillinBox(smallTefillin, geometry)
        )
    }

    @Test
    fun testLargeTefillin4cmWithCoverOnSmallFaceAccepted() {
        val geometry = createMockGeometry(hairlineY = 200f, glabellaY = 280f, eyeDist = 100f)
        // Large ~4cm Gasot Tefillin with plastic cover on small face / close-up angle (width = 85px, height = 82px, ratio = 0.85 <= 1.35)
        val largeTefillinWithCover = TefillinBox(
            x = 320f,
            y = 150f,
            boxWidth = 85f,
            boxHeight = 82f,
            bottomY = 191f
        )
        assertTrue(
            "Large ~4cm Tefillin with protective cover on small face must be accepted",
            TefillinEngine.isValidTefillinBox(largeTefillinWithCover, geometry)
        )
    }
}
