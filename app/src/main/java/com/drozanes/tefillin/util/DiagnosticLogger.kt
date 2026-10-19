package com.drozanes.tefillin.util

import android.os.Build
import java.text.SimpleDateFormat
import java.util.*

/**
 * Diagnostic logger matching the diagnostic logging system in tefillin_app.
 */
object DiagnosticLogger {
    private const val MAX_STARTUP_LOGS = 50
    private const val MAX_RECENT_LOGS = 150

    private val startupLogs = Collections.synchronizedList(mutableListOf<String>())
    private val recentLogs = Collections.synchronizedList(mutableListOf<String>())
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(level: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val formatted = "[$timestamp] [$level] $message"

        synchronized(startupLogs) {
            if (startupLogs.size < MAX_STARTUP_LOGS) {
                startupLogs.add(formatted)
            }
        }
        synchronized(recentLogs) {
            recentLogs.add(formatted)
            if (recentLogs.size > MAX_RECENT_LOGS) {
                recentLogs.removeAt(0)
            }
        }
    }

    fun info(msg: String) = log("INFO", msg)
    fun warn(msg: String) = log("WARN", msg)
    fun error(msg: String, tr: Throwable? = null) {
        val detail = if (tr != null) "$msg\n${tr.stackTraceToString()}" else msg
        log("ERROR", detail)
    }

    fun buildDiagnosticReport(userNotes: String, activeModel: String, isGpu: Boolean, cameraRes: String): String {
        val sb = StringBuilder()
        sb.appendLine("================================================================")
        sb.appendLine("            TEFILLIN MIRROR NATIVE ANDROID BUG REPORT")
        sb.appendLine("================================================================")
        sb.appendLine("Timestamp: ${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())}")
        sb.appendLine("User Notes: ${userNotes.ifBlank { "None provided" }}")
        sb.appendLine("Active Model: $activeModel")
        sb.appendLine("GPU Acceleration: ${if (isGpu) "Active" else "CPU Fallback"}")
        sb.appendLine("Camera Resolution: $cameraRes")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("Android OS: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine()
        sb.appendLine("----------------- STARTUP LOGS (FROM APP BOOT) -----------------")
        synchronized(startupLogs) {
            startupLogs.forEach { sb.appendLine(it) }
        }
        sb.appendLine()
        sb.appendLine("----------------- RECENT LOGS (LAST 150 EVENTS) -----------------")
        synchronized(recentLogs) {
            recentLogs.forEach { sb.appendLine(it) }
        }
        sb.appendLine("================================================================")
        return sb.toString()
    }
}
