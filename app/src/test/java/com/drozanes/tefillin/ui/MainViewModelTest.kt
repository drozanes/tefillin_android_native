package com.drozanes.tefillin.ui

import com.drozanes.tefillin.model.AlignmentResult
import com.drozanes.tefillin.model.AlignmentStatus
import com.drozanes.tefillin.model.FaceGeometry
import com.drozanes.tefillin.model.Point2D
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MainViewModelTest {

    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        viewModel = MainViewModel()
    }

    @Test
    fun testInitialState() {
        assertNull("AlignmentResult should initially be null", viewModel.alignmentResult.value)
        assertNull("FaceGeometry should initially be null", viewModel.faceGeometry.value)
        assertTrue("ShowGuides should default to true", viewModel.showGuides.value)
        assertEquals("640x480", viewModel.cameraResolution.value)
        assertFalse("isGpuActive should initially be false", viewModel.isGpuActive.value)
        assertEquals("best_fp16.onnx", viewModel.activeModel.value)
    }

    @Test
    fun testUpdateResultsPublishesState() {
        val dummyGeom = FaceGeometry(
            glabellaPt = Point2D(320f, 200f),
            midEyesPt = Point2D(320f, 210f),
            nosePt = Point2D(320f, 240f),
            meshTopPt = Point2D(320f, 100f),
            hairlinePt = Point2D(320f, 150f),
            eyeLeftPt = Point2D(270f, 210f),
            eyeRightPt = Point2D(370f, 210f),
            eyeDist = 100f,
            uEye = Point2D(1f, 0f),
            uUp = Point2D(0f, -1f),
            pitchAngle = 0f,
            pitchCompression = 1f,
            eyebrowLevelY = 200f
        )
        val dummyAlignment = AlignmentResult(
            status = AlignmentStatus.ALIGNED,
            warning = false,
            direction = "✅ Aligned",
            isKosher = true,
            isCentered = true,
            distAboveHairline = 10f,
            horizOffset = 0f,
            winner = null
        )

        viewModel.updateResults(dummyGeom, dummyAlignment)

        assertEquals(dummyGeom, viewModel.faceGeometry.value)
        assertEquals(dummyAlignment, viewModel.alignmentResult.value)
    }

    @Test
    fun testClearResultsResetsToNull() {
        val dummyGeom = FaceGeometry(
            glabellaPt = Point2D(320f, 200f),
            midEyesPt = Point2D(320f, 210f),
            nosePt = Point2D(320f, 240f),
            meshTopPt = Point2D(320f, 100f),
            hairlinePt = Point2D(320f, 150f),
            eyeLeftPt = Point2D(270f, 210f),
            eyeRightPt = Point2D(370f, 210f),
            eyeDist = 100f,
            uEye = Point2D(1f, 0f),
            uUp = Point2D(0f, -1f),
            pitchAngle = 0f,
            pitchCompression = 1f,
            eyebrowLevelY = 200f
        )
        val dummyAlignment = AlignmentResult(
            status = AlignmentStatus.ALIGNED,
            warning = false,
            direction = "✅ Aligned",
            isKosher = true,
            isCentered = true,
            distAboveHairline = 10f,
            horizOffset = 0f,
            winner = null
        )

        viewModel.updateResults(dummyGeom, dummyAlignment)
        assertNotNull(viewModel.faceGeometry.value)
        assertNotNull(viewModel.alignmentResult.value)

        // Clear when face leaves screen
        viewModel.clearResults()

        assertNull("FaceGeometry must be null after clearResults", viewModel.faceGeometry.value)
        assertNull("AlignmentResult must be null after clearResults", viewModel.alignmentResult.value)
    }

    @Test
    fun testToggleGuides() {
        assertTrue(viewModel.showGuides.value)

        val newState = viewModel.toggleGuides()
        assertFalse("toggleGuides should return false after first toggle", newState)
        assertFalse("showGuides value should be false", viewModel.showGuides.value)

        val toggledBack = viewModel.toggleGuides()
        assertTrue("toggleGuides should return true after second toggle", toggledBack)
        assertTrue("showGuides value should be true", viewModel.showGuides.value)
    }

    @Test
    fun testSetCameraResolution() {
        viewModel.setCameraResolution(1280, 720)
        assertEquals("1280x720", viewModel.cameraResolution.value)
    }

    @Test
    fun testSetAiStatus() {
        viewModel.setAiStatus(isGpu = true, modelName = "best_uint8.onnx")
        assertTrue(viewModel.isGpuActive.value)
        assertEquals("best_uint8.onnx", viewModel.activeModel.value)
    }
}
