package com.example.prog7314_poepart23.utils

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.prog7314_poepart23.R
import androidx.appcompat.app.AppCompatDialog
import com.google.android.material.button.MaterialButton

object PulseLinkModal {
    fun show(context: Context, title: String, content: View,
        actions: List<Pair<String, (AppCompatDialog) -> Unit>>): AppCompatDialog {
        val dialog = AppCompatDialog(context, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
        dialog.supportRequestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
            setBackgroundColor(ContextCompat.getColor(context, R.color.pulse_card))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.pulse_card))
                val radius = dp(16).toFloat()
                cornerRadius = radius
            }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        header.addView(android.widget.ImageView(context).apply {
            setImageResource(R.drawable.ic_pulselink_emblem)
            contentDescription = context.getString(R.string.app_name)
        }, LinearLayout.LayoutParams(dp(28), dp(28)))
        header.addView(TextView(context).apply {
            text = title; textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.pulse_text_primary))
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        root.addView(header)
        if (content is ScrollView) content.setPadding(0, 0, 0, 0)
        val body = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val cap = (resources.displayMetrics.heightPixels * .48).toInt()
                val available = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) cap
                    else minOf(cap, View.MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST))
            }
        }.apply { addView(content); isFillViewport = false }
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val footer = LinearLayout(context).apply {
            orientation = if (actions.size == 2) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = android.view.Gravity.END
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(footer)
        val ordered = if (actions.size == 2) actions.withIndex().reversed() else actions.withIndex().toList()
        ordered.forEach { (index, action) ->
            footer.addView(MaterialButton(context).apply {
                tag = "modal_action_${index}"
                text = action.first; isAllCaps = false; minHeight = dp(48)
                cornerRadius = dp(12)
                backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context,
                    if (index == actions.lastIndex && actions.size > 1) R.color.pulse_card else R.color.pulse_primary_fill))
                setTextColor(ContextCompat.getColor(context,
                    if (index == actions.lastIndex && actions.size > 1) R.color.pulse_text_primary else R.color.white))
                if (index == actions.lastIndex && actions.size > 1) {
                    strokeWidth = dp(1)
                    strokeColor = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.pulse_border_strong))
                }
                setOnClickListener { action.second(dialog) }
            }, LinearLayout.LayoutParams(if (actions.size == 2) 0 else ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, if (actions.size == 2) 1f else 0f).apply {
                    if (actions.size == 2 && index == 0) marginStart = dp(12)
                    if (actions.size != 2) topMargin = dp(8)
                })
        }
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(android.view.Gravity.CENTER)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.show()
        val width = minOf(context.resources.displayMetrics.widthPixels - dp(32), dp(520))
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        return dialog
    }
}
