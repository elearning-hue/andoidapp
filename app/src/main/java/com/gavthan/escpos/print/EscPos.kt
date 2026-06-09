package com.gavthan.escpos.print

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream

/**
 * ESC/POS command builder + raster image encoder.
 *
 * Targets the GS v 0 raster bit-image command, which is the most widely
 * supported image mode across 58mm/80mm thermal printers (Epson-compatible
 * dialect). For an 80mm printer the printable width is 576 dots (72 bytes).
 *
 * If your printer prints garbage for images, the usual culprits are:
 *   - wrong dot width (see PrinterConfig.dotsWidth)
 *   - printer wants the older ESC * mode instead of GS v 0 (rare now)
 *   - printer expects data in chunks with delays (handled by the transports)
 */
object EscPos {

    // --- Raw command bytes ---
    val INIT = byteArrayOf(0x1B, 0x40)                       // ESC @  (reset)
    val LF = byteArrayOf(0x0A)                               // line feed
    val CUT_FULL = byteArrayOf(0x1D, 0x56, 0x00)             // GS V 0 (full cut)
    val CUT_PARTIAL = byteArrayOf(0x1D, 0x56, 0x01)          // GS V 1 (partial cut)

    fun feed(lines: Int): ByteArray = byteArrayOf(0x1B, 0x64, lines.toByte()) // ESC d n

    // --- Print darkness / density ---
    // The single biggest reason app-side prints look faded vs. a vendor driver is
    // that the driver sets the printer's heating energy and we don't. These two
    // commands set it explicitly. Unsupported printers safely consume the params.

    /**
     * ESC 7 n1 n2 n3 — set heating parameters (the main darkness control on the
     * generic thermal controllers used in most 58/80mm printers).
     *   n1 = max heating dots (unit 8 dots)
     *   n2 = heating TIME (unit 10µs) — HIGHER = DARKER  (this is the key knob)
     *   n3 = heating interval (unit 10µs) — higher = crisper but slower
     */
    fun setHeat(maxDots: Int, heatTime: Int, heatInterval: Int): ByteArray =
        byteArrayOf(
            0x1B, 0x37,
            maxDots.coerceIn(0, 255).toByte(),
            heatTime.coerceIn(3, 255).toByte(),
            heatInterval.coerceIn(0, 255).toByte()
        )

    /**
     * DC2 # n — set print density (honored by many clones, ignored by the rest).
     *   high nibble = density (0..15, higher = darker)
     *   low  nibble = break time
     */
    fun setDensity(density: Int, breakTime: Int = 0): ByteArray =
        byteArrayOf(
            0x12, 0x23,
            (((density.coerceIn(0, 15)) shl 4) or (breakTime.coerceIn(0, 7))).toByte()
        )

    /** ESC G n — double-strike (prints each text line twice → darker text). */
    fun doubleStrike(on: Boolean): ByteArray = byteArrayOf(0x1B, 0x47, if (on) 1 else 0)

    // Text alignment: 0=left, 1=center, 2=right
    fun align(n: Int): ByteArray = byteArrayOf(0x1B, 0x61, n.toByte())        // ESC a n

    // Emphasis (bold) on/off
    fun bold(on: Boolean): ByteArray = byteArrayOf(0x1B, 0x45, if (on) 1 else 0)

    // Character size: width/height multipliers 1..8 -> n = (w-1)<<4 | (h-1)
    fun textSize(w: Int, h: Int): ByteArray {
        val wn = (w.coerceIn(1, 8) - 1)
        val hn = (h.coerceIn(1, 8) - 1)
        return byteArrayOf(0x1D, 0x21, ((wn shl 4) or hn).toByte())            // GS ! n
    }

    /** Encode text as bytes. Most printers use the ASCII/CP437 range cleanly. */
    fun text(s: String): ByteArray = s.toByteArray(Charsets.ISO_8859_1)

    /**
     * Convert a bitmap to a GS v 0 raster image payload.
     *
     * The bitmap is scaled to [targetWidthDots] (preserving aspect ratio),
     * converted to 1-bit via luminance threshold, and packed MSB-first into
     * rows of ceil(width/8) bytes.
     *
     * @param src source bitmap (any config)
     * @param targetWidthDots printer dot width (576 for 80mm, 384 for 58mm)
     * @param threshold 0..255 luminance cutoff; lower = darker output
     */
    fun rasterImage(src: Bitmap, targetWidthDots: Int, threshold: Int = 127, dilate: Boolean = false): ByteArray {
        // 1) Scale to target width, keeping aspect ratio. Width must be a multiple
        //    of 8 for clean byte packing; we floor to the nearest multiple of 8.
        val w = (targetWidthDots / 8) * 8
        val scale = w.toFloat() / src.width.toFloat()
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)

        val bytesPerRow = w / 8
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)

        // --- Luminance map (0=black..255=white). Transparent treated as white. ---
        val lum = IntArray(w * h)
        for (i in 0 until w * h) {
            val px = pixels[i]
            lum[i] = if (Color.alpha(px) < 128) 255 else
                (0.299 * Color.red(px) + 0.587 * Color.green(px) + 0.114 * Color.blue(px)).toInt()
        }

        // --- Darken pass (gamma < 1 pushes mid/light grays toward black) so thin
        //     and light-weight fonts survive instead of getting dropped. ---
        // gamma 0.7 ≈ noticeably darker; out = 255 * (in/255)^gamma
        val gamma = 0.7
        for (i in lum.indices) {
            val v = Math.pow(lum[i] / 255.0, gamma) * 255.0
            lum[i] = v.toInt().coerceIn(0, 255)
        }

        // --- Adaptive (local mean) threshold. Compares each pixel to the average
        //     of its neighbourhood minus a bias. This keeps light text that a
        //     single global threshold would erase, and avoids whole-image blobbing.
        //     Falls back to global `threshold` if the local window is uniform. ---
        val radius = 6                 // ~13px window — good for receipt text
        val bias = 12                  // pixel must be this much darker than local mean
        val black = BooleanArray(w * h)
        // Build an integral image for O(1) window sums.
        val integ = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0L
            for (x in 0 until w) {
                rowSum += lum[y * w + x]
                integ[(y + 1) * (w + 1) + (x + 1)] = integ[y * (w + 1) + (x + 1)] + rowSum
            }
        }
        fun windowMean(cx: Int, cy: Int): Int {
            val x0 = (cx - radius).coerceAtLeast(0)
            val y0 = (cy - radius).coerceAtLeast(0)
            val x1 = (cx + radius).coerceAtMost(w - 1)
            val y1 = (cy + radius).coerceAtMost(h - 1)
            val a = integ[y0 * (w + 1) + x0]
            val bb = integ[y0 * (w + 1) + (x1 + 1)]
            val c = integ[(y1 + 1) * (w + 1) + x0]
            val d = integ[(y1 + 1) * (w + 1) + (x1 + 1)]
            val area = (x1 - x0 + 1) * (y1 - y0 + 1)
            return ((d - bb - c + a) / area).toInt()
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val mean = windowMean(x, y)
                // Dark if clearly below the global threshold, OR below local mean-bias.
                black[i] = lum[i] < threshold || lum[i] < (mean - bias)
            }
        }

        // Optional dilation: turn on the right + down neighbour of every black dot.
        // This thickens thin strokes so faded output reads as solid black — a
        // reliable darkener that works even when the printer ignores heat commands.
        val grid = if (dilate) {
            val out = BooleanArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                if (black[y * w + x]) {
                    out[y * w + x] = true
                    if (x + 1 < w) out[y * w + x + 1] = true
                    if (y + 1 < h) out[(y + 1) * w + x] = true
                }
            }
            out
        } else black

        val out = ByteArrayOutputStream()
        // GS v 0 m xL xH yL yH [data]
        out.write(0x1D); out.write(0x76); out.write(0x30); out.write(0x00)
        out.write(bytesPerRow and 0xFF); out.write((bytesPerRow shr 8) and 0xFF)
        out.write(h and 0xFF); out.write((h shr 8) and 0xFF)

        for (y in 0 until h) {
            for (xByte in 0 until bytesPerRow) {
                var b = 0
                for (bit in 0 until 8) {
                    val x = xByte * 8 + bit
                    if (grid[y * w + x]) b = b or (0x80 shr bit)
                }
                out.write(b)
            }
        }

        if (scaled != src) scaled.recycle()
        return out.toByteArray()
    }

    /** Convenience: concatenate multiple byte arrays. */
    fun concat(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        val res = ByteArray(total)
        var pos = 0
        for (p in parts) { System.arraycopy(p, 0, res, pos, p.size); pos += p.size }
        return res
    }
}
