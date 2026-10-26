package com.drozanes.tefillin.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Window
import com.drozanes.tefillin.databinding.DialogDisclaimerBinding

class DisclaimerDialog(
    context: Context,
    private val onDismissed: () -> Unit
) : Dialog(context) {

    private val binding: DialogDisclaimerBinding = DialogDisclaimerBinding.inflate(LayoutInflater.from(context))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setCancelable(false) // Must confirm understanding on first launch

        binding.btnConfirmDisclaimer.setOnClickListener {
            onDismissed()
            dismiss()
        }
    }
}
