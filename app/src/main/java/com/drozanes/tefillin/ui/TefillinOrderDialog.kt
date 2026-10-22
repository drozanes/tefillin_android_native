package com.drozanes.tefillin.ui

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.core.content.ContextCompat
import com.drozanes.tefillin.R
import com.drozanes.tefillin.databinding.DialogTefillinOrderBinding
import com.google.android.material.button.MaterialButton

/**
 * Modal dialog presenting the complete Halachic order and blessings of Tefillin (סדר הנחת תפילין)
 * Supports dynamic switching between Ashkenaz, Sefard, and Edot HaMizrach with persistence.
 */
class TefillinOrderDialog(context: Context) : Dialog(context) {

    companion object {
        private const val PREFS_NAME = "tefillin_app_prefs"
        private const val KEY_SELECTED_NUSACH = "selected_nusach"

        const val NUSACH_ASHKENAZ = "ashkenaz"
        const val NUSACH_SEFARD = "sefard"
        const val NUSACH_EDOT_MIZRACH = "edot_mizrach"
    }

    private val binding: DialogTefillinOrderBinding = DialogTefillinOrderBinding.inflate(LayoutInflater.from(context))
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val currentNusach = prefs.getString(KEY_SELECTED_NUSACH, NUSACH_ASHKENAZ) ?: NUSACH_ASHKENAZ
        updateNusachUi(currentNusach)

        binding.btnNusachAshkenaz.setOnClickListener {
            selectNusach(NUSACH_ASHKENAZ)
        }

        binding.btnNusachSefard.setOnClickListener {
            selectNusach(NUSACH_SEFARD)
        }

        binding.btnNusachEdotMizrach.setOnClickListener {
            selectNusach(NUSACH_EDOT_MIZRACH)
        }

        binding.btnCloseOrder.setOnClickListener {
            dismiss()
        }
    }

    private fun selectNusach(nusach: String) {
        prefs.edit().putString(KEY_SELECTED_NUSACH, nusach).apply()
        updateNusachUi(nusach)
    }

    private fun updateNusachUi(nusach: String) {
        val goldColor = ContextCompat.getColor(context, R.color.accent_gold)
        val darkBgColor = ContextCompat.getColor(context, R.color.bg_primary)
        val inactiveBgColor = Color.parseColor("#243048")
        val inactiveTextColor = ContextCompat.getColor(context, R.color.text_muted)

        fun styleButton(btn: MaterialButton, isSelected: Boolean) {
            if (isSelected) {
                btn.backgroundTintList = ColorStateList.valueOf(goldColor)
                btn.setTextColor(darkBgColor)
            } else {
                btn.backgroundTintList = ColorStateList.valueOf(inactiveBgColor)
                btn.setTextColor(inactiveTextColor)
            }
        }

        styleButton(binding.btnNusachAshkenaz, nusach == NUSACH_ASHKENAZ)
        styleButton(binding.btnNusachSefard, nusach == NUSACH_SEFARD)
        styleButton(binding.btnNusachEdotMizrach, nusach == NUSACH_EDOT_MIZRACH)

        when (nusach) {
            NUSACH_ASHKENAZ -> {
                binding.tvRoshBlessingLead.visibility = View.VISIBLE
                binding.tvRoshBlessingLead.text = context.getString(R.string.step2_rosh_ashkenaz_blessing_title)
                binding.tvBlessingShelRosh.visibility = View.VISIBLE
                binding.tvRoshAfterLead.visibility = View.VISIBLE
                binding.tvRoshAfterLead.text = context.getString(R.string.step2_rosh_after_tightening)
                binding.tvBlessingBaruchShem.visibility = View.VISIBLE
                binding.tvRoshInterruptedRule.visibility = View.GONE
            }
            NUSACH_SEFARD -> {
                binding.tvRoshBlessingLead.visibility = View.VISIBLE
                binding.tvRoshBlessingLead.text = context.getString(R.string.step2_rosh_sefard_rule)
                binding.tvBlessingShelRosh.visibility = View.GONE
                binding.tvRoshAfterLead.visibility = View.GONE
                binding.tvBlessingBaruchShem.visibility = View.VISIBLE
                binding.tvRoshInterruptedRule.visibility = View.VISIBLE
                binding.tvRoshInterruptedRule.text = context.getString(R.string.step2_rosh_sefard_interrupted)
            }
            NUSACH_EDOT_MIZRACH -> {
                binding.tvRoshBlessingLead.visibility = View.VISIBLE
                binding.tvRoshBlessingLead.text = context.getString(R.string.step2_rosh_edot_rule)
                binding.tvBlessingShelRosh.visibility = View.GONE
                binding.tvRoshAfterLead.visibility = View.GONE
                binding.tvBlessingBaruchShem.visibility = View.GONE
                binding.tvRoshInterruptedRule.visibility = View.VISIBLE
                binding.tvRoshInterruptedRule.text = context.getString(R.string.step2_rosh_edot_interrupted)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        window?.let { w ->
            val dm = context.resources.displayMetrics
            val width = (dm.widthPixels * 0.94).toInt()
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.CENTER)
        }
    }
}
