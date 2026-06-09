package com.gavthan.escpos.print

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/** A printer choice the user can select, regardless of transport type. */
sealed class PrinterChoice {
    abstract val label: String
    /** Stable id for persisting per-printer settings (paper size). */
    abstract val deviceKey: String
    /** Raw device name for paper-size guessing. */
    abstract val deviceName: String?

    data class Bluetooth(
        val device: BluetoothDevice,
        override val label: String,
        override val deviceKey: String,
        override val deviceName: String?
    ) : PrinterChoice()

    data class Usb(
        val device: UsbDevice,
        override val label: String,
        override val deviceKey: String,
        override val deviceName: String?
    ) : PrinterChoice()
}

object DeviceDiscovery {

    /** Paired Bluetooth devices (printers must be paired in system settings first). */
    @SuppressLint("MissingPermission") // caller checks BLUETOOTH_CONNECT
    fun pairedBluetooth(): List<PrinterChoice.Bluetooth> {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        return adapter.bondedDevices.map {
            val name = it.name ?: "Unknown"
            PrinterChoice.Bluetooth(
                device = it,
                label = "$name — ${it.address}",
                deviceKey = "bt:${it.address}",   // MAC is stable per printer
                deviceName = name
            )
        }
    }

    /** Connected USB devices that look like they could be printers. */
    fun usbDevices(context: Context): List<PrinterChoice.Usb> {
        val mgr = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return mgr.deviceList.values.map {
            val name = it.productName ?: "USB Device"
            PrinterChoice.Usb(
                device = it,
                label = "$name — VID:${it.vendorId} PID:${it.productId}",
                deviceKey = "usb:${it.vendorId}:${it.productId}",
                deviceName = name
            )
        }
    }

    fun usbManager(context: Context): UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager
}
