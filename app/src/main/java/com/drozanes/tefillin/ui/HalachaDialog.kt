package com.drozanes.tefillin.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.Window
import com.drozanes.tefillin.databinding.DialogHalachaBinding

/**
 * Modal dialog presenting the Halachic rules for Shel Rosh.
 */
class HalachaDialog(context: Context) : Dialog(context) {

    private val binding = DialogHalachaBinding.inflate(LayoutInflater.from(context))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        binding.tvHalachaVersion.text = "${context.getString(com.drozanes.tefillin.R.string.app_title)} • v${com.drozanes.tefillin.BuildConfig.VERSION_NAME}"
        binding.btnShowDisclaimer.setOnClickListener {
            DisclaimerDialog(context, isFirstLaunch = false).show()
        }
        binding.btnPrivacyPolicy.setOnClickListener {
            PrivacyPolicyDialog(context).show()
        }
        binding.btnClose.setOnClickListener { dismiss() }
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
}
