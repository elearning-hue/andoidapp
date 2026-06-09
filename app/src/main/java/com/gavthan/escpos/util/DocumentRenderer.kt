package com.gavthan.escpos.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

/**
 * Turns content Uris (images or PDFs) into a list of bitmaps sized for the
 * printer width, ready to be raster-encoded.
 */
object DocumentRenderer {

    /** Load and decode an image Uri into a single bitmap. */
    fun renderImage(context: Context, uri: Uri): List<Bitmap> {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException("Cannot open image: $uri")
        input.use {
            val bmp = android.graphics.BitmapFactory.decodeStream(it)
                ?: throw IllegalStateException("Could not decode image")
            return listOf(ensureOpaqueWhiteBackground(bmp))
        }
    }

    /**
     * Render each page of a PDF into a bitmap.
     *
     * Pages are rendered at a width that, when scaled to the printer dot width,
     * keeps text crisp. We render at [renderWidthPx] then let the encoder scale
     * down to the printer width.
     */
    fun renderPdf(context: Context, uri: Uri, renderWidthPx: Int = 1600): List<Bitmap> {
        val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw FileNotFoundException("Cannot open PDF: $uri")
        val pages = mutableListOf<Bitmap>()
        pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                for (i in 0 until renderer.pageCount) {
                    renderer.openPage(i).use { page ->
                        val scale = renderWidthPx.toFloat() / page.width.toFloat()
                        val w = renderWidthPx
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        // Fill white first — PDF pages are transparent where unpainted
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        pages.add(bmp)
                    }
                }
            }
        }
        if (pages.isEmpty()) throw IllegalStateException("PDF has no pages")
        return pages
    }

    /** Flatten any transparency onto white so the threshold step behaves. */
    private fun ensureOpaqueWhiteBackground(src: Bitmap): Bitmap {
        if (!src.hasAlpha()) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.WHITE)
        val canvas = android.graphics.Canvas(out)
        canvas.drawBitmap(src, 0f, 0f, null)
        return out
    }
}
