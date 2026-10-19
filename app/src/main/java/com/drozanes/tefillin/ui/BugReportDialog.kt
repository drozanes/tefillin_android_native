package com.drozanes.tefillin.ui

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.Toast
import androidx.core.content.FileProvider
import com.drozanes.tefillin.databinding.DialogBugReportBinding
import com.drozanes.tefillin.util.DiagnosticLogger
import java.io.File
import java.io.FileOutputStream

/**
 * Bug reporting dialog with snapshot capture and native Android share sheet.
 */
class BugReportDialog(
    context: Context,
    private val snapshotBitmap: Bitmap?,
    private val activeModel: String,
    private val isGpu: Boolean,
    private val cameraRes: String
) : Dialog(context) {

    private val binding: DialogBugReportBinding = DialogBugReportBinding.inflate(LayoutInflater.from(context))

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        if (snapshotBitmap != null) {
            binding.ivPreview.visibility = View.VISIBLE
            binding.ivPreview.setImageBitmap(snapshotBitmap)
        } else {
            binding.ivPreview.visibility = View.GONE
        }

        binding.btnShare.setOnClickListener {
            val notes = binding.etNotes.text.toString()
            shareReport(notes)
            dismiss()
        }

        binding.btnCopyLogs.setOnClickListener {
            val notes = binding.etNotes.text.toString()
            val report = DiagnosticLogger.buildDiagnosticReport(notes, activeModel, isGpu, cameraRes)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Tefillin Mirror Logs", report))
            Toast.makeText(context, "Logs copied to clipboard!", Toast.LENGTH_SHORT).show()
        }

        binding.btnCloseBug.setOnClickListener { dismiss() }
    }

    private fun shareReport(notes: String) {
        try {
            val report = DiagnosticLogger.buildDiagnosticReport(notes, activeModel, isGpu, cameraRes)
            val cacheDir = context.cacheDir

            // Save log file
            val logFile = File(cacheDir, "tefillin_mirror_logs.txt")
            logFile.writeText(report)
            val logUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", logFile)

            val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                val uris = ArrayList<android.net.Uri>()
                uris.add(logUri)

                // Save snapshot if present
                if (snapshotBitmap != null) {
                    val imgFile = File(cacheDir, "tefillin_mirror_snapshot.jpg")
                    val fos = FileOutputStream(imgFile)
                    snapshotBitmap.compress(Bitmap.CompressFormat.JPEG, 92, fos)
                    fos.flush()
                    fos.close()
                    val imgUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", imgFile)
                    uris.add(imgUri)
                }

                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                putExtra(Intent.EXTRA_SUBJECT, "Tefillin Mirror Bug Report")
                putExtra(Intent.EXTRA_TEXT, "Tefillin Mirror Diagnostic Logs and Snapshot.\nNotes: $notes")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(Intent.createChooser(shareIntent, "Share Bug Report via..."))
        } catch (e: Exception) {
            Toast.makeText(context, "Share error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
