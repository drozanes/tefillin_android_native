package com.drozanes.tefillin.ui

import android.app.Dialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup

object DialogUtils {

    /**
     * Configures the dialog window to be responsive across all screen dimensions and densities:
     * - Constrains width to 92% of screen width (capped at [maxWidthDp], e.g. 520dp on tablets).
     * - Constrains total dialog height to at most [maxHeightRatio] of screen height (default 85%).
     * - Automatically constrains [scrollBody] height so pinned headers and footers remain visible
     *   while internal content scrolls smoothly with visible scrollbars.
     */
    fun configureResponsiveDialog(
        dialog: Dialog,
        rootView: View,
        scrollBody: MaxHeightScrollView? = null,
        maxWidthDp: Int = 520,
        maxHeightRatio: Float = 0.85f,
        estimatedChromeDp: Int = 145
    ) {
        val window = dialog.window ?: return
        val dm = dialog.context.resources.displayMetrics
        val maxAllowedWidth = (maxWidthDp * dm.density).toInt()
        val targetWidth = minOf((dm.widthPixels * 0.92).toInt(), maxAllowedWidth)
        val maxAllowedDialogHeight = (dm.heightPixels * maxHeightRatio).toInt()

        if (scrollBody != null) {
            val chromeReserve = (estimatedChromeDp * dm.density).toInt()
            val maxScrollHeight = maxOf((120 * dm.density).toInt(), maxAllowedDialogHeight - chromeReserve)
            scrollBody.maxHeightPx = maxScrollHeight
        }

        rootView.measure(
            View.MeasureSpec.makeMeasureSpec(targetWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val measuredHeight = rootView.measuredHeight

        val finalHeight = if (measuredHeight > maxAllowedDialogHeight) {
            maxAllowedDialogHeight
        } else {
            ViewGroup.LayoutParams.WRAP_CONTENT
        }

        window.setLayout(targetWidth, finalHeight)
        window.setGravity(Gravity.CENTER)
    }
}
