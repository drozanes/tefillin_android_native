package com.drozanes.tefillin.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Toast
import androidx.core.content.FileProvider
import com.drozanes.tefillin.R
import com.drozanes.tefillin.databinding.DialogBugReportBinding
import com.drozanes.tefillin.util.BugReporter
import com.drozanes.tefillin.util.CrashHandler
import com.drozanes.tefillin.util.DiagnosticLogger
import java.io.File
import java.io.FileOutputStream

/**
 * Bug reporting dialog with snapshot capture and direct anonymous transmission to developer.
 * The user's report is submitted directly without exposing developer's personal email or identity.
 */
class BugReportDialog(
    context: Context,
    private val snapshotBitmap: Bitmap?,
    private val activeModel: String,
    private val isGpu: Boolean,
    private val cameraRes: String
) : Dialog(context) {

    private val binding: DialogBugReportBinding = DialogBugReportBinding.inflate(LayoutInflater.from(context))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        if (snapshotBitmap != null) {
            binding.ivPreview.visibility = View.VISIBLE
            binding.tvPreviewLabel.visibility = View.VISIBLE
            binding.cbAttachSnapshot.visibility = View.VISIBLE
            binding.ivPreview.setImageBitmap(snapshotBitmap)
            binding.cbAttachSnapshot.setOnCheckedChangeListener { _, isChecked ->
                binding.ivPreview.visibility = if (isChecked) View.VISIBLE else View.GONE
                binding.tvPreviewLabel.visibility = if (isChecked) View.VISIBLE else View.GONE
            }
        } else {
            binding.ivPreview.visibility = View.GONE
            binding.tvPreviewLabel.visibility = View.GONE
            binding.cbAttachSnapshot.visibility = View.GONE
        }

        binding.btnSendReport.setOnClickListener {
            val notes = binding.etNotes.text.toString()
            val effectiveSnapshot = if (binding.cbAttachSnapshot.isChecked) snapshotBitmap else null

            if (BugReporter.isConfigured()) {
                setSendingState(true)
                BugReporter.sendReport(
                    context = context,
                    notes = notes,
                    activeModel = activeModel,
                    isGpu = isGpu,
                    cameraRes = cameraRes,
                    snapshotBitmap = effectiveSnapshot,
                    onSuccess = {
                        setSendingState(false)
                        Toast.makeText(context, context.getString(R.string.report_sent_success), Toast.LENGTH_LONG).show()
                        dismiss()
                    },
                    onError = { errMsg, canFallback ->
                        setSendingState(false)
                        Toast.makeText(context, context.getString(R.string.report_send_error, errMsg), Toast.LENGTH_LONG).show()
                        if (canFallback) {
                            binding.btnShareFallback.visibility = View.VISIBLE
                        }
                    }
                )
            } else {
                // If webhook endpoint is not configured yet, notify and allow fallback sharing
                Toast.makeText(context, context.getString(R.string.report_offline_or_failed), Toast.LENGTH_SHORT).show()
                binding.btnShareFallback.visibility = View.VISIBLE
                shareReport(notes, effectiveSnapshot)
            }
        }

        binding.btnShareFallback.setOnClickListener {
            val notes = binding.etNotes.text.toString()
            val effectiveSnapshot = if (binding.cbAttachSnapshot.isChecked) snapshotBitmap else null
            shareReport(notes, effectiveSnapshot)
        }

        binding.btnCloseBug.setOnClickListener { dismiss() }
    }

    private fun setSendingState(isSending: Boolean) {
        binding.pbSending.visibility = if (isSending) View.VISIBLE else View.GONE
        binding.btnSendReport.isEnabled = !isSending
        binding.btnSendReport.text = if (isSending) context.getString(R.string.sending_report) else context.getString(R.string.send_report)
    }

    override fun onStart() {
        super.onStart()
        window?.let { w ->
            val dm = context.resources.displayMetrics
            val width = (dm.widthPixels * 0.92).toInt()
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.CENTER)
        }
    }

    private fun shareReport(notes: String, effectiveSnapshot: Bitmap?) {
        try {
            var report = DiagnosticLogger.buildDiagnosticReport(notes, activeModel, isGpu, cameraRes)
            val crashReport = CrashHandler.getPendingCrashReport(context)
            if (!crashReport.isNullOrBlank()) {
                report = "$crashReport\n\n$report"
            }
            val cacheDir = context.cacheDir

            // Save log file
            val logFile = File(cacheDir, "tefillin_mirror_logs.txt")
            logFile.writeText(report)
            val logUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", logFile)

            val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                val uris = ArrayList<android.net.Uri>()
                uris.add(logUri)

                // Save snapshot if present and permitted
                if (effectiveSnapshot != null) {
                    val imgFile = File(cacheDir, "tefillin_mirror_snapshot.jpg")
                    val fos = FileOutputStream(imgFile)
                    effectiveSnapshot.compress(Bitmap.CompressFormat.JPEG, 92, fos)
                    fos.flush()
                    fos.close()
                    val imgUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", imgFile)
                    uris.add(imgUri)
                }

                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.share_subject))
                putExtra(Intent.EXTRA_TEXT, "${context.getString(R.string.share_subject)}\n${context.getString(R.string.bug_notes_label)} $notes")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.share_chooser_title)))
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.share_error, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }
}
