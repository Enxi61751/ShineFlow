package com.android.calendar.settings

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.android.calendar.Utils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ws.xsoh.etar.R

class PeriodColorPreference(context: Context, attrs: AttributeSet) :
    Preference(context, attrs) {

    init {
        layoutResource = R.layout.period_color_preference
    }

    private val colors = arrayOf(
        // Pink shades (for period)
        0xFF4F9A.toInt(), 0xFF69B4.toInt(), 0xFF1493.toInt(), 0xFFC71585.toInt(),
        // Red shades
        0xFFFF4444.toInt(), 0xFFE91E63.toInt(), 0xFFF44336.toInt(), 0xFFD32F2F.toInt(),
        // Purple shades (for ovulation)
        0xFF8E5BF0.toInt(), 0xFF9C27B0.toInt(), 0xFF7C4DFF.toInt(), 0xFF673AB7.toInt(),
        // Teal/blue shades (for fertile)
        0xFF0FBFA5.toInt(), 0xFF009688.toInt(), 0xFF00BCD4.toInt(), 0xFF03A9F4.toInt(),
        // Light pink (for predicted)
        0xFFFFB0CE.toInt(), 0xFFFFABAB.toInt(), 0xFFFF80AB.toInt(), 0xFFF48FB1.toInt(),
        // Warm tones
        0xFFFF9800.toInt(), 0xFFFF5722.toInt(), 0xFFFFC107.toInt(), 0xFF795548.toInt(),
        // Cool tones
        0xFF2196F3.toInt(), 0xFF3F51B5.toInt(), 0xFF4CAF50.toInt(), 0xFF607D8B.toInt(),
    )

    private var colorSwatch: ImageView? = null

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        colorSwatch = holder.findViewById(R.id.color_swatch) as? ImageView
        updateSwatch()
    }

    private fun updateSwatch() {
        val color = getColor()
        colorSwatch?.setBackgroundColor(color)
    }

    fun getColor(): Int {
        val hex = Utils.getSharedPreference(context, key, "#FF4F9A")
        return try {
            Color.parseColor(hex)
        } catch (e: Exception) {
            Color.parseColor("#FF4F9A")
        }
    }

    override fun onClick() {
        showColorPicker()
    }

    private fun showColorPicker() {
        val currentColor = getColor()
        val names = arrayOfNulls<String>(colors.size)
        val colorViews = colors.map { c ->
            val v = android.widget.ImageView(context)
            v.setBackgroundColor(c)
            v.layoutParams = android.view.ViewGroup.LayoutParams(80, 80)
            v.setPadding(4, 4, 4, 4)
            v
        }

        val grid = android.widget.GridLayout(context).apply {
            columnCount = 7
            rowCount = 4
            setPadding(16, 16, 16, 16)
        }
        colorViews.forEach { grid.addView(it) }

        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(grid)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { dialog ->
                colorViews.forEachIndexed { idx, v ->
                    v.setOnClickListener {
                        val hex = String.format("#%06X", 0xFFFFFF and colors[idx])
                        Utils.setSharedPreference(context, key, hex)
                        updateSwatch()
                        dialog.dismiss()
                        callChangeListener(hex)
                    }
                }
            }
    }

    companion object {
        fun getPeriodColor(context: Context, prefKey: String, defaultValue: String): Int {
            val hex = Utils.getSharedPreference(context, prefKey, defaultValue)
            return try { Color.parseColor(hex) } catch (e: Exception) {
                Color.parseColor(defaultValue)
            }
        }

        fun getStatusColor(context: Context, status: Int): Int {
            return when (status) {
                1 -> getPeriodColor(context, "preferences_period_color_period", "#FF4F9A")
                2 -> getPeriodColor(context, "preferences_period_color_predicted", "#FFB0CE")
                3 -> getPeriodColor(context, "preferences_period_color_fertile", "#8A2BE2")
                4 -> getPeriodColor(context, "preferences_period_color_ovulation", "#87CEEB")
                else -> 0
            }
        }

        fun getStatusColorLight(context: Context, status: Int): Int {
            val base = getStatusColor(context, status)
            val r = (base shr 16) and 0xFF
            val g = (base shr 8) and 0xFF
            val b = base and 0xFF
            return Color.argb(64, r, g, b)
        }
    }
}
