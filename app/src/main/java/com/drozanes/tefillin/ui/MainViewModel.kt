package com.drozanes.tefillin.ui

import androidx.lifecycle.ViewModel
import com.drozanes.tefillin.model.AlignmentResult
import com.drozanes.tefillin.model.AlignmentStatus
import com.drozanes.tefillin.model.FaceGeometry
import com.drozanes.tefillin.model.TefillinBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * MainViewModel manages the reactive application state for alignment,
 * camera resolution, and hardware acceleration status across configuration changes.
 */
class MainViewModel : ViewModel() {

    private val _alignmentResult = MutableStateFlow<AlignmentResult?>(null)
    val alignmentResult: StateFlow<AlignmentResult?> = _alignmentResult.asStateFlow()

    private val _faceGeometry = MutableStateFlow<FaceGeometry?>(null)
    val faceGeometry: StateFlow<FaceGeometry?> = _faceGeometry.asStateFlow()

    private val _showGuides = MutableStateFlow(true)
    val showGuides: StateFlow<Boolean> = _showGuides.asStateFlow()

    private val _cameraResolution = MutableStateFlow("640x480")
    val cameraResolution: StateFlow<String> = _cameraResolution.asStateFlow()

    private val _isGpuActive = MutableStateFlow(false)
    val isGpuActive: StateFlow<Boolean> = _isGpuActive.asStateFlow()

    private val _activeModel = MutableStateFlow("best_fp16.onnx")
    val activeModel: StateFlow<String> = _activeModel.asStateFlow()

    fun updateResults(geometry: FaceGeometry?, alignment: AlignmentResult?) {
        _faceGeometry.value = geometry
        _alignmentResult.value = alignment
    }

    fun clearResults() {
        _faceGeometry.value = null
        _alignmentResult.value = null
    }

    fun toggleGuides(): Boolean {
        val next = !_showGuides.value
        _showGuides.value = next
        return next
    }

    fun setGuides(show: Boolean) {
        _showGuides.value = show
    }

    fun setCameraResolution(width: Int, height: Int) {
        _cameraResolution.value = "${width}x${height}"
    }

    fun setAiStatus(isGpu: Boolean, modelName: String) {
        _isGpuActive.value = isGpu
        _activeModel.value = modelName
    }
}
