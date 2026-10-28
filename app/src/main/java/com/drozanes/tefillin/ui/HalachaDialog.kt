package com.drozanes.tefillin.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Window
import com.drozanes.tefillin.BuildConfig
import com.drozanes.tefillin.databinding.DialogHalachaBinding

class HalachaDialog(context: Context) : Dialog(context) {

    private val binding: DialogHalachaBinding = DialogHalachaBinding.inflate(LayoutInflater.from(context))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setCancelable(true)

        binding.tvHalachaVersion.text = "v${BuildConfig.VERSION_NAME}"

        binding.btnShowDisclaimer.setOnClickListener {
            dismiss()
            DisclaimerDialog(context, isFirstLaunch = false) {
                HalachaDialog(context).show()
            }.show()
        }
        binding.btnPrivacyPolicy.setOnClickListener {
            dismiss()
            PrivacyPolicyDialog(context) {
                HalachaDialog(context).show()
            }.show()
        }
        binding.btnClose.setOnClickListener { dismiss() }
    }

    override fun onStart() {
        super.onStart()
        DialogUtils.configureResponsiveDialog(
            dialog = this,
            rootView = binding.root,
            scrollBody = binding.scrollBody,
            estimatedChromeDp = 140
        )
    }
}
