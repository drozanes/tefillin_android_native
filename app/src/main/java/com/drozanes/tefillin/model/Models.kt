package com.drozanes.tefillin.model

data class Point2D(
    val x: Float,
    val y: Float
)

data class FaceGeometry(
    val glabellaPt: Point2D,
    val midEyesPt: Point2D,
    val nosePt: Point2D,
    val meshTopPt: Point2D,
    val hairlinePt: Point2D,
    val eyeLeftPt: Point2D,
    val eyeRightPt: Point2D,
    val eyeDist: Float,
    val uEye: Point2D,
    val uUp: Point2D,
    val pitchAngle: Float,
    val pitchCompression: Float,
    val eyebrowLevelY: Float
)

data class TefillinBox(
    val x: Float,
    val y: Float,
    val boxWidth: Float,
    val boxHeight: Float,
    val leftX: Float = x - boxWidth / 2f,
    val rightX: Float = x + boxWidth / 2f,
    val topY: Float = y - boxHeight / 2f,
    val bottomY: Float = y + boxHeight / 2f,
    val confidence: Float = 0.8f
)

enum class AlignmentStatus {
    NO_TEFILLIN,
    CAMERA_TOO_LOW,
    CAMERA_TOO_HIGH,
    FOREHEAD_ERROR,
    OFF_CENTER,
    ALIGNED
}

data class AlignmentResult(
    val status: AlignmentStatus,
    val warning: Boolean,
    val direction: String,
    val isKosher: Boolean,
    val isCentered: Boolean,
    val distAboveHairline: Float,
    val horizOffset: Float,
    val winner: TefillinBox?
)
