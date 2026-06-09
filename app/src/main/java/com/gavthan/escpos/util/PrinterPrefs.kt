package com.gavthan.escpos.util

import android.content.Context

/**
 * Remembers the chosen paper width (58mm = 384 dots, 80mm = 576 dots) per
 * printer. ESC/POS printers can't reliably report paper width, so the user
 * picks once and we store it keyed by a stable device id (BT MAC / USB VID:PID).
 */
object PrinterPrefs {
    private const val FILE = "printer_prefs"
    private const val KEY_WIDTH_PREFIX = "width_"   // + deviceKey -> Int dots

    const val DOTS_58 = 384
    const val DOTS_80 = 576

    fun getDots(context: Context, deviceKey: String): Int? {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val v = sp.getInt(KEY_WIDTH_PREFIX + deviceKey, -1)
        return if (v > 0) v else null
    }

    fun setDots(context: Context, deviceKey: String, dots: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putInt(KEY_WIDTH_PREFIX + deviceKey, dots).apply()
    }

    /** Best-effort guess from a device name containing "58" or "80". */
    fun guessFromName(name: String?): Int? {
        val n = name?.lowercase() ?: return null
        return when {
            n.contains("58") -> DOTS_58
            n.contains("80") -> DOTS_80
            else -> null
        }
    }
}
