package com.gavthan.escpos.print

/** Printer print-quality configuration. Paper width (58/80mm) is chosen
 *  per-printer at print time and is NOT part of this config. */
data class PrinterConfig(
    val threshold: Int = 160,        // image B/W cutoff — HIGHER = more pixels print = darker
    val feedBeforeCut: Int = 3,      // blank lines fed before cutting
    val autoCut: Boolean = true,
    val chunkSize: Int = 2048,       // bytes per write (some printers choke on large writes)
    val chunkDelayMs: Long = 20,     // pause between chunks to let the buffer drain

    // --- Darkness controls (fixes faded prints) ---
    val applyDensity: Boolean = true,   // send heat/density commands before each job
    val heatingDots: Int = 11,          // ESC 7 n1 — max heating dots (unit 8). ~11 ≈ 96 dots
    val heatingTime: Int = 200,         // ESC 7 n2 — HEATING TIME, the main darkness knob (3..255)
    val heatingInterval: Int = 3,       // ESC 7 n3 — heating interval
    val density: Int = 15,              // DC2 # — print density 0..15 (higher = darker)
    val doubleStrikeText: Boolean = true, // print text lines twice for darker receipts
    val dilateImages: Boolean = true    // thicken black pixels — darkens images even if heat is ignored
) {
    companion object {
        /**
         * Pick a tuned profile from the printer name + chosen paper width.
         *
         * SAFETY NOTE: density/heat commands (ESC 7, DC2 #) are non-standard and a
         * printer that mis-parses them will read the following raster as commands
         * and print garbage. So they default OFF here, and darkness is handled
         * entirely in the BITMAP (gamma + threshold + dilation) — that can never
         * corrupt the command stream. Flip applyDensity=true per profile only if
         * you've confirmed that printer honors the commands.
         *
         *   - Xprinter XP-F600 (80mm/576): renders raster cleanly; big buffer.
         *   - Cysno HOP-H58 (58mm/384): budget controller, small buffer, prints
         *     light → small chunks, longer delay, dilation ON.
         */
        fun forPrinter(name: String?, dots: Int): PrinterConfig {
            val n = (name ?: "").lowercase()
            return when {
                // Cysno HOP-H58 (or any 58mm budget printer)
                n.contains("hop") || n.contains("h58") || n.contains("cysno") || dots <= 384 ->
                    PrinterConfig(
                        threshold = 175,
                        feedBeforeCut = 3,
                        autoCut = true,
                        chunkSize = 512,        // small buffer → small writes
                        chunkDelayMs = 40,      // give it time to drain
                        applyDensity = false,   // budget controllers choke on these → garbage
                        doubleStrikeText = true,
                        dilateImages = true     // darkness done safely in the bitmap
                    )

                // Xprinter XP-F600 (or any 80mm)
                n.contains("xp-f600") || n.contains("xprinter") || n.contains("f600") || dots >= 576 ->
                    PrinterConfig(
                        threshold = 160,
                        feedBeforeCut = 3,
                        autoCut = true,
                        chunkSize = 4096,       // bigger buffer, faster
                        chunkDelayMs = 10,
                        applyDensity = false,   // off by default; safe. Bitmap handles darkness.
                        doubleStrikeText = true,
                        dilateImages = false    // sharp enough without it
                    )

                else -> PrinterConfig(applyDensity = false) // safe generic default
            }
        }
    }
}

/**
 * A connection to a printer that can accept raw ESC/POS bytes.
 * Implemented by BluetoothTransport and UsbTransport.
 */
interface PrinterTransport {
    /** Human-readable identifier (device name / address). */
    val name: String

    /** Open the connection. Throws on failure. */
    @Throws(Exception::class)
    fun open()

    /** Write raw bytes to the printer. */
    @Throws(Exception::class)
    fun write(bytes: ByteArray)

    /** Close and release resources. */
    fun close()

    val isOpen: Boolean
}
