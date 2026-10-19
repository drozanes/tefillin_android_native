package com.drozanes.tefillin.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.drozanes.tefillin.model.AlignmentResult
import com.drozanes.tefillin.model.AlignmentStatus
import com.drozanes.tefillin.model.FaceGeometry
import com.drozanes.tefillin.model.Point2D
import com.drozanes.tefillin.model.TefillinBox
import kotlin.math.atan2
import kotlin.math.max

/**
 * Hardware-accelerated custom view that renders Halachic guidelines,
 * detected Tefillin box, and lowest-edge alignment indicator.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var faceGeometry: FaceGeometry? = null
    private var alignmentResult: AlignmentResult? = null
    private var imageWidth: Int = 640
    private var imageHeight: Int = 480
    var isMirrored: Boolean = true
    var showGuides: Boolean = true

    // Paints
    private val midlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FBBF24")
        strokeWidth = 5f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(15f, 12f), 0f)
    }

    private val hairlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22C55E")
        strokeWidth = 7f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val boxPaintAligned = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22C55E")
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }

    private val boxPaintError = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EF4444")
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }

    private val bottomEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 10f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun setResults(
        geometry: FaceGeometry?,
        alignment: AlignmentResult?,
        frameWidth: Int,
        frameHeight: Int
    ) {
        faceGeometry = geometry
        alignmentResult = alignment
        imageWidth = frameWidth
        imageHeight = frameHeight
        postInvalidate()
    }

    fun clear() {
        faceGeometry = null
        alignmentResult = null
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val geometry = faceGeometry ?: return
        if (width <= 0 || height <= 0 || imageWidth <= 0 || imageHeight <= 0) return

        // Compute aspect-fill scaling to match CameraX PreviewView
        val scale = max(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val scaledW = imageWidth * scale
        val scaledH = imageHeight * scale
        val offsetX = (width - scaledW) / 2f
        val offsetY = (height - scaledH) / 2f

        fun transform(pt: Point2D): PointF {
            val px = if (isMirrored) (imageWidth - pt.x) else pt.x
            return PointF(px * scale + offsetX, pt.y * scale + offsetY)
        }

        // 1. Draw Symmetry Centerline & Hairline Boundary
        if (showGuides) {
            val a = geometry.glabellaPt
            val b = geometry.meshTopPt
            val midlineVecX = b.x - a.x
            val midlineVecY = b.y - a.y

            val pStart = transform(Point2D(a.x - midlineVecX * 2.0f, a.y - midlineVecY * 2.0f))
            val pEnd = transform(Point2D(b.x + midlineVecX * 0.6f, b.y + midlineVecY * 0.6f))
            canvas.drawLine(pStart.x, pStart.y, pEnd.x, pEnd.y, midlinePaint)

            val hPt = geometry.hairlinePt
            val uEye = geometry.uEye
            val span = geometry.eyeDist * 1.35f

            val hStart = transform(Point2D(hPt.x - uEye.x * span, hPt.y - uEye.y * span))
            val hEnd = transform(Point2D(hPt.x + uEye.x * span, hPt.y + uEye.y * span))
            canvas.drawLine(hStart.x, hStart.y, hEnd.x, hEnd.y, hairlinePaint)
        }

        // 2. Draw Detected Tefillin Box & Lowest Edge Indicator
        val winner = alignmentResult?.winner
        if (winner != null && showGuides) {
            val center = transform(Point2D(winner.x, winner.y))
            val w = winner.boxWidth * scale
            val h = winner.boxHeight * scale

            val isError = alignmentResult?.status == AlignmentStatus.FOREHEAD_ERROR
            val activeBoxPaint = if (isError) boxPaintError else boxPaintAligned
            val activeColor = if (isError) Color.parseColor("#EF4444") else Color.parseColor("#22C55E")

            bottomEdgePaint.color = activeColor
            centerDotPaint.color = activeColor

            // Roll angle for rotation: flip sign if mirrored
            val rawRoll = Math.toDegrees(atan2(geometry.uEye.y.toDouble(), geometry.uEye.x.toDouble())).toFloat()
            val rollAngle = if (isMirrored) -rawRoll else rawRoll

            canvas.save()
            canvas.translate(center.x, center.y)
            canvas.rotate(rollAngle)

            // Tefillin Box Outline
            canvas.drawRect(-w / 2f, -h / 2f, w / 2f, h / 2f, activeBoxPaint)

            // Prominent Lowest Edge Line
            canvas.drawLine(-w / 2f - 6f, h / 2f, w / 2f + 6f, h / 2f, bottomEdgePaint)

            // Center Dot
            canvas.drawCircle(0f, 0f, 10f, centerDotPaint)

            canvas.restore()
        }
    }
}
