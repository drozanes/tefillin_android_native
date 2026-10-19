package com.drozanes.tefillin.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.Window
import com.drozanes.tefillin.databinding.DialogHalachaBinding

/**
 * Modal dialog presenting the Halachic rules for Shel Rosh.
 */
class HalachaDialog(context: Context) : Dialog(context) {

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val binding = DialogHalachaBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)

        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        binding.btnClose.setOnClickListener { dismiss() }
    }
}
