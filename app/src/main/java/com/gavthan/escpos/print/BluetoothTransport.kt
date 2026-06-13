package com.gavthan.escpos.print

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth Classic (SPP) transport. The vast majority of thermal receipt
 * printers expose the Serial Port Profile with the well-known SPP UUID.
 *
 * Requires (runtime) BLUETOOTH_CONNECT on Android 12+, and the device must
 * already be paired in system settings.
 */
@SuppressLint("MissingPermission") // permission is checked in the UI before use
class BluetoothTransport(
    private val device: BluetoothDevice,
    private val config: PrinterConfig
) : PrinterTransport {

    companion object {
        // Standard Serial Port Profile UUID
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        // --- Bluetooth-specific transport caps ---
        // Chunking size/delay in PrinterConfig is tuned for print *darkness* per
        // printer, but over SPP the real constraint is the link, not the printer:
        // RFCOMM has no app-level flow control, so a large burst with a short gap
        // overflows the printer's serial receive buffer and bytes get dropped —
        // every following byte is then misread as a command and the output turns
        // to garbage / misaligned control characters. So regardless of the chosen
        // profile we cap writes to something every budget BT controller can keep
        // up with. (USB uses bulkTransfer, which has real flow control, so it is
        // immune and keeps its own faster profile.)
        const val BT_MAX_CHUNK = 512
        const val BT_MIN_CHUNK_DELAY_MS = 40L

        // After the final byte is queued we must wait for the local RFCOMM buffer
        // to actually drain over the air before closing the socket. write() returns
        // once bytes are buffered, not once they're transmitted, and
        // BluetoothSocket's flush() is a no-op — so closing immediately discards
        // whatever is still in flight, truncating the tail (lost cut/feed, garbled
        // end). Drain time is estimated from the bytes sent at a conservative SPP
        // throughput, with a floor and a sane ceiling.
        private const val BT_DRAIN_BYTES_PER_MS = 16    // ~16 KB/s effective
        private const val BT_DRAIN_FLOOR_MS = 60L
        private const val BT_DRAIN_CEILING_MS = 5000L
    }

    private var socket: BluetoothSocket? = null
    private var out: OutputStream? = null

    // Bytes handed to the stream since open(), used to size the pre-close drain.
    private var bytesWritten: Long = 0

    override val name: String
        get() = (device.name ?: "Unknown") + " (" + device.address + ")"

    override val isOpen: Boolean
        get() = socket?.isConnected == true

    override fun open() {
        BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
        val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
        try {
            s.connect()
        } catch (e: Exception) {
            // Fallback: reflection-based socket (works around some OEM quirks)
            try {
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                val fallback = m.invoke(device, 1) as BluetoothSocket
                fallback.connect()
                socket = fallback
                out = fallback.outputStream
                return
            } catch (e2: Exception) {
                try { s.close() } catch (_: Exception) {}
                throw e
            }
        }
        socket = s
        out = s.outputStream
    }

    override fun write(bytes: ByteArray) {
        val stream = out ?: throw IllegalStateException("Bluetooth not open")
        // Chunk the writes — many BT printers have small buffers and drop data
        // if you blast a full image at once. Over SPP we cap the chunk size and
        // floor the delay regardless of the printer profile (see BT_MAX_CHUNK /
        // BT_MIN_CHUNK_DELAY_MS) so a fast profile can't overflow the link.
        val chunkSize = minOf(config.chunkSize, BT_MAX_CHUNK).coerceAtLeast(1)
        val chunkDelayMs = maxOf(config.chunkDelayMs, BT_MIN_CHUNK_DELAY_MS)
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + chunkSize, bytes.size)
            stream.write(bytes, offset, end - offset)
            stream.flush()
            bytesWritten += (end - offset)
            offset = end
            if (chunkDelayMs > 0 && offset < bytes.size) {
                try { Thread.sleep(chunkDelayMs) } catch (_: InterruptedException) {}
            }
        }
    }

    override fun close() {
        // Wait for the RFCOMM buffer to drain over the air before tearing the
        // socket down — closing too early truncates whatever is still in flight.
        if (bytesWritten > 0) {
            val drainMs = (bytesWritten / BT_DRAIN_BYTES_PER_MS)
                .coerceIn(BT_DRAIN_FLOOR_MS, BT_DRAIN_CEILING_MS)
            try { Thread.sleep(drainMs) } catch (_: InterruptedException) {}
        }
        try { out?.flush() } catch (_: Exception) {}
        try { out?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        out = null
        socket = null
        bytesWritten = 0
    }
}
