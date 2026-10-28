package com.drozanes.tefillin.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Window
import android.widget.Toast
import com.drozanes.tefillin.R
import com.drozanes.tefillin.databinding.DialogPrivacyPolicyBinding

class PrivacyPolicyDialog(context: Context) : Dialog(context) {

    companion object {
        private const val ONLINE_POLICY_URL = "https://github.com/drozanes/tefillin_android_native/blob/main/PRIVACY_POLICY.md"
    }

    private val binding: DialogPrivacyPolicyBinding = DialogPrivacyPolicyBinding.inflate(LayoutInflater.from(context))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setCancelable(true)

        binding.btnOpenOnlinePolicy.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ONLINE_POLICY_URL))
                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, ONLINE_POLICY_URL, Toast.LENGTH_LONG).show()
            }
        }

        binding.btnClosePrivacy.setOnClickListener { dismiss() }
    }
}
