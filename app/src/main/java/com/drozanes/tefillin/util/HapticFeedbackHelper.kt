package com.drozanes.tefillin.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.drozanes.tefillin.model.AlignmentStatus

/**
 * HapticFeedbackHelper provides tactile feedback to the user on halachic alignment state changes.
 * Especially helpful because the user is looking directly at themselves in the mirror/front camera
 * and adjusting tefillin on their head with their hands.
 */
class HapticFeedbackHelper(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private var lastStatus: AlignmentStatus? = null

    fun onAlignmentChanged(newStatus: AlignmentStatus) {
        if (newStatus == lastStatus) return
        val previous = lastStatus
        lastStatus = newStatus

        // Do not vibrate on initial app start or missing vibrator hardware
        if (previous == null || vibrator == null || !vibrator.hasVibrator()) return

        when (newStatus) {
            AlignmentStatus.ALIGNED -> {
                // Short, crisp positive confirmation pulse (Kosher placement achieved)
                vibrate(35, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            AlignmentStatus.FOREHEAD_ERROR -> {
                // Double alert pulse: invalid forehead placement (Passul alert)
                vibratePattern(longArrayOf(0, 45, 60, 45))
            }
            AlignmentStatus.OFF_CENTER -> {
                // Subtle corrective tick
                vibrate(20, 120)
            }
            AlignmentStatus.CAMERA_TOO_LOW, AlignmentStatus.CAMERA_TOO_HIGH -> {
                vibrate(25, 100)
            }
            AlignmentStatus.NO_TEFILLIN -> {
                // No vibration on detection drop
            }
        }
    }

    private fun vibrate(durationMs: Long, amplitude: Int = VibrationEffect.DEFAULT_AMPLITUDE) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val safeAmplitude = if (amplitude == VibrationEffect.DEFAULT_AMPLITUDE) {
                    VibrationEffect.DEFAULT_AMPLITUDE
                } else {
                    amplitude.coerceIn(1, 255)
                }
                vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, safeAmplitude))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(durationMs)
            }
        } catch (_: Exception) {}
    }

    private fun vibratePattern(timings: LongArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(timings, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(timings, -1)
            }
        } catch (_: Exception) {}
    }
}
