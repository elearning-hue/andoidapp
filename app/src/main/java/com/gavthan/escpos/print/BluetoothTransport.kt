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
    }

    private var socket: BluetoothSocket? = null
    private var out: OutputStream? = null

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
        // if you blast a full image at once.
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + config.chunkSize, bytes.size)
            stream.write(bytes, offset, end - offset)
            stream.flush()
            offset = end
            if (config.chunkDelayMs > 0 && offset < bytes.size) {
                try { Thread.sleep(config.chunkDelayMs) } catch (_: InterruptedException) {}
            }
        }
    }

    override fun close() {
        try { out?.flush() } catch (_: Exception) {}
        try { out?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        out = null
        socket = null
    }
}
