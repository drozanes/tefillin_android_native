package com.drozanes.tefillin.util

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.drozanes.tefillin.BuildConfig
import com.drozanes.tefillin.R
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global Uncaught Exception Handler that intercepts any app crash,
 * formats a full technical diagnostic report (device, Android version, stack trace, recent logs),
 * and persists it to internal storage so the user can easily copy and share it with developers.
 */
object CrashHandler {
    private const val TAG = "CrashHandler"
    private const val PREF_NAME = "tefillin_crash_prefs"
    private const val KEY_LAST_CRASH = "last_crash_report"
    private const val CRASH_FILE_NAME = "tefillin_crash_report.txt"

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var isInstalled = false

    fun install(context: Context) {
        if (isInstalled) return
        isInstalled = true

        val appContext = context.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                handleCrash(appContext, thread, throwable)
            } catch (e: Exception) {
                Log.e(TAG, "Error saving crash report", e)
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun handleCrash(context: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        val stackTrace = sw.toString()

        val timeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
        val timestamp = timeFormat.format(Date())

        val runtime = Runtime.getRuntime()
        val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMem = runtime.maxMemory() / (1024 * 1024)

        val report = buildString {
            appendLine("================================================================")
            appendLine("             TEFILLIN MIRROR CRASH REPORT")
            appendLine("================================================================")
            appendLine("Timestamp: $timestamp")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("Android OS: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Crashing Thread: ${thread.name} (ID: ${thread.id})")
            appendLine("Memory: Used ${usedMem}MB / Max ${maxMem}MB")
            appendLine("Active Language: ${LocaleManager.getLanguage(context)}")
            appendLine()
            appendLine("----------------------- EXCEPTION DETAILS -----------------------")
            appendLine("Exception: ${throwable.javaClass.name}")
            appendLine("Message: ${throwable.message}")
            appendLine("Stack Trace:")
            appendLine(stackTrace)
            appendLine()
            appendLine("------------------- RECENT DIAGNOSTIC LOGS ---------------------")
            appendLine(DiagnosticLogger.getRecentLogs())
            appendLine("================================================================")
        }

        Log.e(TAG, "FATAL CRASH CAPTURED:\n$report")

        // 1. Save to SharedPreferences
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_CRASH, report).apply()

        // 2. Save to internal filesDir
        try {
            val file = File(context.filesDir, CRASH_FILE_NAME)
            file.writeText(report)
        } catch (_: Exception) {}

        // 3. Save to cacheDir
        try {
            val cacheFile = File(context.cacheDir, CRASH_FILE_NAME)
            cacheFile.writeText(report)
        } catch (_: Exception) {}
    }

    fun getPendingCrashReport(context: Context): String? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_CRASH, null)
    }

    fun clearPendingCrashReport(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_LAST_CRASH).apply()
    }

    /**
     * Checks if a previous crash occurred and presents a user-friendly dialog
     * allowing the user to copy or share the crash log with one tap.
     */
    fun checkAndPromptCrashReport(context: Context) {
        val report = getPendingCrashReport(context) ?: return

        val title = context.getString(R.string.crash_dialog_title)
        val message = context.getString(R.string.crash_dialog_message)
        val btnCopy = context.getString(R.string.crash_dialog_btn_copy)
        val btnShare = context.getString(R.string.crash_dialog_btn_share)
        val btnDismiss = context.getString(R.string.crash_dialog_btn_dismiss)
        val copiedMsg = context.getString(R.string.crash_dialog_copied_toast)

        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(btnCopy) { _, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Tefillin Mirror Crash Report", report))
                Toast.makeText(context, copiedMsg, Toast.LENGTH_LONG).show()
                clearPendingCrashReport(context)
            }
            .setNeutralButton(btnShare) { _, _ ->
                shareCrashReport(context, report)
                clearPendingCrashReport(context)
            }
            .setNegativeButton(btnDismiss) { _, _ ->
                clearPendingCrashReport(context)
            }
            .setCancelable(false)
            .show()
    }

    private fun shareCrashReport(context: Context, report: String) {
        try {
            val cacheFile = File(context.cacheDir, CRASH_FILE_NAME)
            cacheFile.writeText(report)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cacheFile)

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Tefillin Mirror Crash Report")
                putExtra(Intent.EXTRA_TEXT, report)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.crash_dialog_chooser_title)))
        } catch (e: Exception) {
            Log.e(TAG, "Error sharing crash report", e)
        }
    }
}
