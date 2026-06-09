package com.gavthan.escpos.print

import android.graphics.Bitmap

/**
 * Builds complete ESC/POS byte streams for different job types and sends them
 * over a transport. Keep all the "what to send" logic here so the UI just picks
 * content + transport.
 *
 * @param dotsWidth the SELECTED printer's width (384 for 58mm, 576 for 80mm).
 *        Passed per-job because it's remembered per printer, not global.
 */
class PrintJob(private val config: PrinterConfig, private val dotsWidth: Int) {

    /** Build the byte stream for a list of bitmaps (images or PDF pages). */
    fun buildImageJob(bitmaps: List<Bitmap>): ByteArray {
        val parts = ArrayList<ByteArray>()
        parts.add(EscPos.INIT)
        parts.addAll(densityPreamble())
        parts.add(EscPos.align(1)) // center images
        for ((idx, bmp) in bitmaps.withIndex()) {
            // Use the SELECTED printer's dot width so 58mm doesn't get cropped.
            parts.add(EscPos.rasterImage(bmp, dotsWidth, config.threshold, config.dilateImages))
            if (idx < bitmaps.size - 1) parts.add(EscPos.feed(2))
        }
        parts.add(EscPos.feed(config.feedBeforeCut))
        if (config.autoCut) parts.add(EscPos.CUT_PARTIAL)
        return EscPos.concat(*parts.toTypedArray())
    }

    /** Build the byte stream for a plain-text receipt. */
    fun buildTextJob(text: String, centerTitle: String? = null): ByteArray {
        val parts = ArrayList<ByteArray>()
        parts.add(EscPos.INIT)
        parts.addAll(densityPreamble())
        if (config.doubleStrikeText) parts.add(EscPos.doubleStrike(true))
        if (!centerTitle.isNullOrBlank()) {
            parts.add(EscPos.align(1))
            parts.add(EscPos.bold(true))
            parts.add(EscPos.textSize(2, 2))
            parts.add(EscPos.text(centerTitle + "\n"))
            parts.add(EscPos.textSize(1, 1))
            parts.add(EscPos.bold(false))
        }
        parts.add(EscPos.align(0))
        parts.add(EscPos.text(normalizeNewlines(text)))
        if (config.doubleStrikeText) parts.add(EscPos.doubleStrike(false))
        parts.add(EscPos.feed(config.feedBeforeCut))
        if (config.autoCut) parts.add(EscPos.CUT_PARTIAL)
        return EscPos.concat(*parts.toTypedArray())
    }

    /** Heat + density commands sent right after INIT to darken output. */
    private fun densityPreamble(): List<ByteArray> {
        if (!config.applyDensity) return emptyList()
        return listOf(
            EscPos.setHeat(config.heatingDots, config.heatingTime, config.heatingInterval),
            EscPos.setDensity(config.density)
        )
    }

    /** Send pre-built bytes over a transport (open/close handled by caller). */
    fun send(transport: PrinterTransport, bytes: ByteArray) {
        transport.write(bytes)
    }

    private fun normalizeNewlines(s: String): String {
        val sb = StringBuilder(s.replace("\r\n", "\n").replace("\r", "\n"))
        if (sb.isEmpty() || sb.last() != '\n') sb.append('\n')
        return sb.toString()
    }
}
