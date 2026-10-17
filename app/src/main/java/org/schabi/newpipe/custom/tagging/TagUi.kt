/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.tagging

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.schabi.newpipe.R
import org.schabi.newpipe.util.ThemeHelper

/** Shared native appearance for the real page and reusable tag editor, based on NewPipe tokens. */
internal class TagUi(val context: Context) {
    val light = ThemeHelper.isLightThemeSelected(context)
    private val black = ThemeHelper.getThemeForService(context, -1) == R.style.BlackTheme
    val background = context.getColor(
        if (light) {
            R.color.light_background_color
        } else if (black) {
            R.color.black_background_color
        } else {
            R.color.dark_background_color
        }
    )
    val surface = context.getColor(
        if (light) {
            R.color.light_card_item_background_color
        } else if (black) {
            R.color.black_card_item_background_color
        } else {
            R.color.dark_card_item_background_color
        }
    )
    val dialogBackground = context.getColor(if (light) R.color.light_dialog_background_color else R.color.dark_dialog_background_color)
    val border = context.getColor(if (light) R.color.light_border_color else R.color.dark_border_color)
    val foreground = if (light) 0xFF212121.toInt() else Color.WHITE
    val secondary = if (light) 0xFF616161.toInt() else 0xFFCCCCCC.toInt()
    val accent = context.getColor(if (light) R.color.light_settings_accent_color else R.color.dark_settings_accent_color)
    val primary = context.getColor(R.color.ic_launcher_background)
    val exclude = if (light) 0xFF9A4500.toInt() else 0xFFFFC078.toInt()

    fun dp(value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()
    fun params(width: Int = -1, height: Int = -2) = LinearLayout.LayoutParams(if (width < 0) width else dp(width), if (height < 0) height else dp(height))
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    fun space(parent: LinearLayout, height: Int = 8) {
        parent.addView(View(context), params(1, height))
    }
    fun shape(color: Int, rounded: Int = 10, stroke: Int = border) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(rounded).toFloat()
        setStroke(dp(1), stroke)
    }
    fun text(value: String, size: Float = 14f, bold: Boolean = false, color: Int = foreground) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    fun button(value: String, filled: Boolean = false, click: () -> Unit) = text(value, 14f, true, if (filled) Color.WHITE else foreground).apply {
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = shape(if (filled) primary else surface)
        isFocusable = true
        isClickable = true
        setOnClickListener { click() }
    }
    fun input(hint: String) = EditText(context).apply {
        this.hint = hint
        textSize = 15f
        setSingleLine(true)
        setTextColor(this@TagUi.foreground)
        setHintTextColor(secondary)
    }
    fun dialog() = AlertDialog.Builder(context, ThemeHelper.getDialogTheme(context))
    fun spinnerAdapter(values: List<String>) = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, values) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = (super.getView(position, convertView, parent) as TextView).apply {
            setTextColor(this@TagUi.foreground)
            setBackgroundColor(surface)
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = (super.getDropDownView(position, convertView, parent) as TextView).apply {
            setTextColor(this@TagUi.foreground)
            setBackgroundColor(dialogBackground)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
    }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

    fun prepareSheet(dialog: BottomSheetDialog, content: LinearLayout) {
        content.setPadding(dp(18), dp(16), dp(18), dp(24))
        content.setBackgroundColor(dialogBackground)
        val scroll = ScrollView(context).apply {
            setBackgroundColor(dialogBackground)
            addView(content)
        }
        dialog.setContentView(scroll)
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { container ->
                container.background = shape(dialogBackground, 16)
                container.layoutParams.height = (context.resources.displayMetrics.heightPixels * 0.85).toInt()
                container.requestLayout()
            }
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    fun errorMessage(error: Throwable): String = when (error) {
        is SQLiteConstraintException -> "That name already exists, or the selected category was removed. Check the name and category."
        else -> error.message ?: "The change could not be saved. Please retry."
    }
}
