package com.gavthan.escpos.print

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/**
 * USB Host transport. Finds a bulk-OUT endpoint on the printer and writes
 * raw ESC/POS bytes via bulkTransfer.
 *
 * Works with printers that present a USB printer class interface (class 7) or
 * a vendor-specific interface exposing a bulk OUT endpoint. The user must grant
 * USB permission (handled in the UI), and the device should be connected via
 * an OTG cable.
 */
class UsbTransport(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val config: PrinterConfig
) : PrinterTransport {

    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var endpointOut: UsbEndpoint? = null

    override val name: String
        get() = (device.productName ?: "USB Printer") +
                " (VID:${device.vendorId} PID:${device.productId})"

    override val isOpen: Boolean
        get() = connection != null && endpointOut != null

    override fun open() {
        // Find an interface with a bulk OUT endpoint. Prefer the printer class (7).
        var chosenIface: UsbInterface? = null
        var chosenOut: UsbEndpoint? = null

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    ep.direction == UsbConstants.USB_DIR_OUT
                ) {
                    // Prefer printer class; otherwise take the first bulk OUT we see
                    if (iface.interfaceClass == UsbConstants.USB_CLASS_PRINTER) {
                        chosenIface = iface; chosenOut = ep
                    } else if (chosenIface == null) {
                        chosenIface = iface; chosenOut = ep
                    }
                }
            }
        }

        if (chosenIface == null || chosenOut == null) {
            throw IllegalStateException("No bulk OUT endpoint found on USB device")
        }

        val conn = usbManager.openDevice(device)
            ?: throw IllegalStateException("Could not open USB device (permission denied?)")

        if (!conn.claimInterface(chosenIface, true)) {
            conn.close()
            throw IllegalStateException("Could not claim USB interface")
        }

        connection = conn
        usbInterface = chosenIface
        endpointOut = chosenOut
    }

    override fun write(bytes: ByteArray) {
        val conn = connection ?: throw IllegalStateException("USB not open")
        val ep = endpointOut ?: throw IllegalStateException("USB endpoint missing")

        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + config.chunkSize, bytes.size)
            val chunk = bytes.copyOfRange(offset, end)
            val sent = conn.bulkTransfer(ep, chunk, chunk.size, 5000)
            if (sent < 0) throw IllegalStateException("USB bulkTransfer failed at offset $offset")
            offset += sent
            if (config.chunkDelayMs > 0 && offset < bytes.size) {
                try { Thread.sleep(config.chunkDelayMs) } catch (_: InterruptedException) {}
            }
        }
    }

    override fun close() {
        try { usbInterface?.let { connection?.releaseInterface(it) } } catch (_: Exception) {}
        try { connection?.close() } catch (_: Exception) {}
        connection = null
        usbInterface = null
        endpointOut = null
    }
}
