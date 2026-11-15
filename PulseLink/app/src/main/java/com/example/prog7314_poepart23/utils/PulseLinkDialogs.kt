package com.example.prog7314_poepart23.utils

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.example.prog7314_poepart23.R

object PulseLinkDialogs {
    // Retain the dialog's existing actions while giving long translated labels their own row.
    fun show(dialog: AlertDialog): AlertDialog {
        dialog.setOnShowListener {
            val context = dialog.context
            val density = context.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            val buttons = listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE)
                .mapNotNull { code -> dialog.getButton(code)?.takeIf { it.visibility == android.view.View.VISIBLE }?.let { code to it } }
            val host = buttons.firstOrNull()?.second?.parent as? ViewGroup
            if (host != null && buttons.all { it.second.parent === host }) {
                val footer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                }
                buttons.forEach { (code, button) ->
                    host.removeView(button)
                    val filled = code != AlertDialog.BUTTON_NEGATIVE
                    val fill = ContextCompat.getColor(context, if (code == AlertDialog.BUTTON_NEUTRAL) R.color.pulse_accent_teal else R.color.pulse_primary_fill)
                    val shape = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(if (filled) fill else ContextCompat.getColor(context, R.color.pulse_card))
                        if (!filled) setStroke(dp(1), ContextCompat.getColor(context, R.color.pulse_border_strong))
                    }
                    button.background = RippleDrawable(ColorStateList.valueOf(ContextCompat.getColor(context,
                        if (filled) R.color.pulse_ripple_on_filled else R.color.pulse_ripple_on_light)), shape, null)
                    button.setTextColor(ContextCompat.getColor(context, if (filled) R.color.white else R.color.pulse_text_primary))
                    button.isAllCaps = false
                    button.maxLines = Integer.MAX_VALUE
                    button.minHeight = dp(48)
                    button.setPadding(dp(16), dp(12), dp(16), dp(12))
                    footer.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
                }
                host.removeAllViews()
                host.addView(footer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        dialog.show()
        return dialog
    }
}
