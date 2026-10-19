package com.drozanes.tefillin.cv

import android.graphics.RectF
import com.drozanes.tefillin.model.FaceGeometry
import com.drozanes.tefillin.model.Point2D
import com.drozanes.tefillin.model.TefillinBox
import org.junit.Assert.*
import org.junit.Test

class GeometryStabilizerTest {

    private fun createGeometry(hairlineX: Float, hairlineY: Float, eyeDist: Float = 100f): FaceGeometry {
        return FaceGeometry(
            glabellaPt = Point2D(320f, 280f),
            midEyesPt = Point2D(320f, 290f),
            nosePt = Point2D(320f, 320f),
            meshTopPt = Point2D(320f, 150f),
            subnasalePt = null,
            mentonPt = null,
            hairlinePt = Point2D(hairlineX, hairlineY),
            eyeLeftPt = Point2D(270f, 290f),
            eyeRightPt = Point2D(370f, 290f),
            eyeDist = eyeDist,
            uEye = Point2D(1f, 0f),
            uUp = Point2D(0f, -1f),
            pitchAngle = 0f,
            pitchCompression = 1f,
            eyebrowLevelY = 280f,
            searchArea = RectF(270f, 100f, 370f, 250f)
        )
    }

    @Test
    fun testDeadbandFiltersSmallMicroJitter() {
        val stabilizer = GeometryStabilizer(deadbandPx = 2.0f)
        val initial = createGeometry(320f, 200f)
        val stab1 = stabilizer.update(initial)
        assertNotNull(stab1)
        assertEquals(200f, stab1!!.hairlinePt.y, 0.001f)

        // Micro-jitter: 0.8px displacement (within 2.0px deadband)
        val jittered = createGeometry(320f, 200.8f)
        val stab2 = stabilizer.update(jittered)
        assertNotNull(stab2)
        // Position must remain EXACTLY at 200f
        assertEquals(200f, stab2!!.hairlinePt.y, 0.001f)
    }

    @Test
    fun testHeadAnchoredTrackingLocksBoxToHead() {
        val stabilizer = GeometryStabilizer()
        val geom1 = createGeometry(320f, 200f)

        val rawBox = TefillinBox(x = 320f, y = 150f, boxWidth = 40f, boxHeight = 40f)
        val locked1 = stabilizer.stabilizeBox(rawBox, geom1)
        assertNotNull(locked1)
        assertEquals(320f, locked1!!.x, 0.1f)

        // Frame 2: YOLO missed (null), but user moved their head to the right by 15px (glabella at 335f)
        val geom2 = geom1.copy(
            glabellaPt = Point2D(335f, 280f),
            meshTopPt = Point2D(335f, 150f)
        )
        val locked2 = stabilizer.stabilizeBox(null, geom2)
        assertNotNull("Box must track head even on missed YOLO frames", locked2)
        // Box must move WITH the head by 15px to X=335f!
        assertEquals(335f, locked2!!.x, 0.5f)
    }
}
