package com.drozanes.tefillin.util

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.drozanes.tefillin.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/**
 * Handles anonymous, direct bug reporting from the app to the developer.
 * Keeps developer identity (personal email, phone, name) completely private from the user.
 *
 * Supported delivery endpoints:
 * 1. Discord Webhook (instant notification + photo in your private channel)
 * 2. Telegram Bot (instant notification + photo to your Telegram chat)
 * 3. Generic HTTP POST (Web3Forms, Formspree, Cloudflare Worker, Google Apps Script)
 */
object BugReporter {

    private const val TAG = "BugReporter"
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Webhook endpoint for direct anonymous bug reports.
     * Configured via local.properties (tefillin.bug_report_webhook_url) or environment variable.
     * If left blank, the app gracefully offers the native Android share sheet as a fallback.
     */
    @Volatile
    var WEBHOOK_URL: String = BuildConfig.BUG_REPORT_WEBHOOK_URL

    fun isConfigured(): Boolean = WEBHOOK_URL.isNotBlank() && WEBHOOK_URL.startsWith("http")

    fun sendReport(
        context: Context,
        notes: String,
        activeModel: String,
        isGpu: Boolean,
        cameraRes: String,
        snapshotBitmap: Bitmap?,
        onSuccess: () -> Unit,
        onError: (String, Boolean) -> Unit // (errorMessage, canFallbackToShare)
    ) {
        if (!isConfigured()) {
            onError("Webhook URL not configured in BugReporter.kt", true)
            return
        }

        executor.execute {
            try {
                // 1. Gather diagnostics
                var fullReport = DiagnosticLogger.buildDiagnosticReport(notes, activeModel, isGpu, cameraRes)
                val crashReport = CrashHandler.getPendingCrashReport(context)
                if (!crashReport.isNullOrBlank()) {
                    fullReport = "=== PENDING CRASH REPORT ===\n$crashReport\n\n$fullReport"
                }

                // 2. Compress snapshot bitmap if available
                var imageBytes: ByteArray? = null
                if (snapshotBitmap != null && !snapshotBitmap.isRecycled) {
                    try {
                        val baos = ByteArrayOutputStream()
                        snapshotBitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                        imageBytes = baos.toByteArray()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to compress snapshot", e)
                    }
                }

                val targetUrl = WEBHOOK_URL.trim()

                val success = when {
                    targetUrl.contains("discord.com/api/webhooks") -> {
                        sendToDiscord(targetUrl, notes, fullReport, imageBytes)
                    }
                    targetUrl.contains("api.telegram.org") -> {
                        sendToTelegram(targetUrl, notes, fullReport, imageBytes)
                    }
                    else -> {
                        sendGenericPost(targetUrl, notes, fullReport, imageBytes)
                    }
                }

                if (success) {
                    mainHandler.post { onSuccess() }
                } else {
                    mainHandler.post {
                        onError("Server rejected report submission", true)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending bug report", e)
                mainHandler.post {
                    onError(e.message ?: "Network error", true)
                }
            }
        }
    }

    private fun sendToDiscord(webhookUrl: String, notes: String, reportText: String, imageBytes: ByteArray?): Boolean {
        val boundary = "==TefillinBoundary" + UUID.randomUUID().toString()
        val url = URL(webhookUrl)
        val conn = (url.openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            doInput = true
            useCaches = false
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("User-Agent", "TefillinMirrorAndroid/1.0")
        }

        val deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})"
        val userNote = if (notes.isBlank()) "No comment provided" else notes.take(300)

        // Discord embed description limit is 4096 chars; take the end of logs which has the latest info
        val safeReport = if (reportText.length > 3500) {
            "...\n" + reportText.takeLast(3500)
        } else {
            reportText
        }

        val hasImage = imageBytes != null && imageBytes.isNotEmpty()
        val imageEmbedPart = if (hasImage) """, "image": { "url": "attachment://snapshot.jpg" }""" else ""

        // Construct Discord JSON payload
        val jsonPayload = """
            {
                "content": "🐞 **New Tefillin Mirror Bug Report**\n**Device:** `$deviceInfo`\n**User Notes:** $userNote",
                "embeds": [
                    {
                        "title": "📋 Diagnostic Logs & State",
                        "description": "```yaml\n${escapeJson(safeReport)}\n```",
                        "color": 16753920
                        $imageEmbedPart
                    }
                ]
            }
        """.trimIndent()

        val dos = DataOutputStream(conn.outputStream)

        // Part 1: payload_json
        dos.writeBytes("--$boundary\r\n")
        dos.writeBytes("Content-Disposition: form-data; name=\"payload_json\"\r\n")
        dos.writeBytes("Content-Type: application/json; charset=UTF-8\r\n\r\n")
        dos.write(jsonPayload.toByteArray(Charsets.UTF_8))
        dos.writeBytes("\r\n")

        // Part 2: file attachment (if snapshot present)
        if (imageBytes != null && imageBytes.isNotEmpty()) {
            dos.writeBytes("--$boundary\r\n")
            dos.writeBytes("Content-Disposition: form-data; name=\"files[0]\"; filename=\"snapshot.jpg\"\r\n")
            dos.writeBytes("Content-Type: image/jpeg\r\n\r\n")
            dos.write(imageBytes)
            dos.writeBytes("\r\n")
        }

        dos.writeBytes("--$boundary--\r\n")
        dos.flush()
        dos.close()

        val responseCode = conn.responseCode
        if (responseCode !in 200..299) {
            val errText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            Log.e(TAG, "Discord webhook failed: $responseCode - $errText")
        } else {
            Log.i(TAG, "Discord webhook response: $responseCode (SUCCESS)")
        }
        return responseCode in 200..299
    }

    private fun sendToTelegram(apiUrl: String, notes: String, reportText: String, imageBytes: ByteArray?): Boolean {
        val boundary = "==TelegramBoundary" + UUID.randomUUID().toString()
        val url = URL(apiUrl)
        val conn = (url.openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            doInput = true
            useCaches = false
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        val caption = "🐞 Tefillin Mirror Report\nDevice: ${Build.MANUFACTURER} ${Build.MODEL}\nNotes: $notes\n\n${reportText.take(900)}"

        val dos = DataOutputStream(conn.outputStream)

        // Caption part
        dos.writeBytes("--$boundary\r\n")
        dos.writeBytes("Content-Disposition: form-data; name=\"caption\"\r\n\r\n")
        dos.write(caption.toByteArray(Charsets.UTF_8))
        dos.writeBytes("\r\n")

        // Photo part
        if (imageBytes != null && imageBytes.isNotEmpty()) {
            dos.writeBytes("--$boundary\r\n")
            dos.writeBytes("Content-Disposition: form-data; name=\"photo\"; filename=\"snapshot.jpg\"\r\n")
            dos.writeBytes("Content-Type: image/jpeg\r\n\r\n")
            dos.write(imageBytes)
            dos.writeBytes("\r\n")
        }

        dos.writeBytes("--$boundary--\r\n")
        dos.flush()
        dos.close()

        val responseCode = conn.responseCode
        Log.i(TAG, "Telegram response: $responseCode")
        return responseCode in 200..299
    }

    private fun sendGenericPost(targetUrl: String, notes: String, reportText: String, imageBytes: ByteArray?): Boolean {
        val boundary = "==FormBoundary" + UUID.randomUUID().toString()
        val url = URL(targetUrl)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            doInput = true
            useCaches = false
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("User-Agent", "TefillinMirrorAndroid/1.0")
        }

        val dos = DataOutputStream(conn.outputStream)

        // Notes
        writeFormField(dos, boundary, "notes", notes)
        writeFormField(dos, boundary, "device", "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
        writeFormField(dos, boundary, "logs", reportText)

        // Image
        if (imageBytes != null && imageBytes.isNotEmpty()) {
            dos.writeBytes("--$boundary\r\n")
            dos.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"snapshot.jpg\"\r\n")
            dos.writeBytes("Content-Type: image/jpeg\r\n\r\n")
            dos.write(imageBytes)
            dos.writeBytes("\r\n")
        }

        dos.writeBytes("--$boundary--\r\n")
        dos.flush()
        dos.close()

        val code = conn.responseCode
        Log.i(TAG, "Generic POST response: $code")
        return code in 200..299
    }

    private fun writeFormField(dos: DataOutputStream, boundary: String, name: String, value: String) {
        dos.writeBytes("--$boundary\r\n")
        dos.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        dos.write(value.toByteArray(Charsets.UTF_8))
        dos.writeBytes("\r\n")
    }

    private fun escapeJson(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\u000C", "\\f")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
