package com.gavthan.escpos.ui

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import com.gavthan.escpos.R
import com.gavthan.escpos.databinding.ActivityMainBinding
import com.gavthan.escpos.print.*
import com.gavthan.escpos.util.DocumentRenderer
import com.gavthan.escpos.util.PrinterPrefs
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val io = Executors.newSingleThreadExecutor()
    // The print profile (darkness, chunking, dilation) is chosen per printer from
    // its name + paper width — see PrinterConfig.forPrinter(). Tuned for the
    // Xprinter XP-F600 (80mm) and Cysno HOP-H58 (58mm).

    private var choices: List<PrinterChoice> = emptyList()
    // Config for the in-flight job, built per printer (set in onPrintFile/onPrintText).
    private var activeConfig: PrinterConfig = PrinterConfig()
    private var selected: PrinterChoice? = null
    private var pickedUri: Uri? = null
    private var pickedIsPdf = false

    companion object {
        private const val ACTION_USB_PERMISSION = "com.gavthan.escpos.USB_PERMISSION"
    }

    // ---- File picker ----
    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            pickedUri = uri
            val type = contentResolver.getType(uri) ?: ""
            pickedIsPdf = type.contains("pdf", ignoreCase = true) ||
                    uri.toString().endsWith(".pdf", ignoreCase = true)
            b.txtPicked.text = "Picked: " + (uri.lastPathSegment ?: uri.toString()) +
                    if (pickedIsPdf) "  [PDF]" else "  [image]"
        }
    }

    // ---- Runtime permissions (Bluetooth on Android 12+) ----
    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshDevices() }

    // ---- USB permission receiver ----
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted) {
                    val dev: UsbDevice? = if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (dev != null) doPrintUsb(dev)
                } else {
                    toast("USB permission denied")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        // Toolbar is a visual header; its app:title shows "Gavthan ESC/POS".
        // (Not set as support action bar, so the title isn't overridden by the label.)

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }

        b.btnRefresh.setOnClickListener { ensurePermsThenRefresh() }
        b.btnPickFile.setOnClickListener { pickFile.launch("*/*") }
        b.btnPrintFile.setOnClickListener { onPrintFile() }
        b.btnPrintText.setOnClickListener { onPrintText() }
        b.btnPaperSize.setOnClickListener {
            val sel = selected
            if (sel == null) toast("Select a printer first")
            else askPaperSize(sel) { updateSelectedLabel() }
        }

        b.listDevices.setOnItemClickListener { _, _, pos, _ ->
            selected = choices.getOrNull(pos)
            updateSelectedLabel()
        }

        // Long-press a printer to change its remembered paper size.
        b.listDevices.setOnItemLongClickListener { _, _, pos, _ ->
            val sel = choices.getOrNull(pos)
            if (sel != null) {
                selected = sel
                askPaperSize(sel) { updateSelectedLabel() }
            }
            true
        }

        ensurePermsThenRefresh()
    }

    private fun updateSelectedLabel() {
        val sel = selected
        if (sel == null) { b.txtSelected.text = "Selected: none"; return }
        val dots = PrinterPrefs.getDots(this, sel.deviceKey)
        val paper = when (dots) {
            PrinterPrefs.DOTS_58 -> "58mm"
            PrinterPrefs.DOTS_80 -> "80mm"
            else -> "paper: ask on print (long-press to set)"
        }
        b.txtSelected.text = "Selected: ${sel.label}  [$paper]"
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        io.shutdownNow()
    }

    private fun ensurePermsThenRefresh() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (needed.isNotEmpty()) requestPerms.launch(needed.toTypedArray())
        else refreshDevices()
    }

    private fun refreshDevices() {
        val bt = try { DeviceDiscovery.pairedBluetooth() } catch (e: SecurityException) { emptyList() }
        val usb = DeviceDiscovery.usbDevices(this)
        choices = bt + usb
        val labels = choices.map {
            when (it) {
                is PrinterChoice.Bluetooth -> "BT  " + it.label
                is PrinterChoice.Usb -> "USB " + it.label
            }
        }
        b.listDevices.adapter = ArrayAdapter(this, R.layout.item_printer, labels)
        if (choices.isEmpty())
            toast("No printers found. Pair a BT printer in Settings, or connect USB via OTG.")
    }

    // ---- Print actions ----

    private fun onPrintFile() {
        val sel = selected ?: return toast("Select a printer first")
        val uri = pickedUri ?: return toast("Pick a file first")
        resolvePaperThen(sel) { dots ->
            val cfg = PrinterConfig.forPrinter(sel.deviceName, dots)
            activeConfig = cfg
            val job = PrintJob(cfg, dots)
            runOnIo {
                try {
                    val bitmaps = if (pickedIsPdf)
                        DocumentRenderer.renderPdf(this, uri)
                    else
                        DocumentRenderer.renderImage(this, uri)
                    val bytes = job.buildImageJob(bitmaps)
                    dispatch(sel, bytes)
                } catch (e: Exception) {
                    runOnUiThread { toast("Render failed: ${e.message}") }
                }
            }
        }
    }

    private fun onPrintText() {
        val sel = selected ?: return toast("Select a printer first")
        val text = b.editText.text?.toString().orEmpty()
        if (text.isBlank()) return toast("Type some text first")
        resolvePaperThen(sel) { dots ->
            val cfg = PrinterConfig.forPrinter(sel.deviceName, dots)
            activeConfig = cfg
            val job = PrintJob(cfg, dots)
            runOnIo {
                val bytes = job.buildTextJob(text)
                dispatch(sel, bytes)
            }
        }
    }

    /**
     * Resolve the printer's paper width in dots, then run [onResolved].
     * Order: remembered setting → guess from device name → ask the user (and
     * remember the answer). ESC/POS printers can't reliably report paper width,
     * so this is the practical equivalent of auto-detect.
     */
    private fun resolvePaperThen(sel: PrinterChoice, onResolved: (Int) -> Unit) {
        PrinterPrefs.getDots(this, sel.deviceKey)?.let { return onResolved(it) }

        val guess = PrinterPrefs.guessFromName(sel.deviceName)
        if (guess != null) {
            // Confirm the guess once, then remember.
            MaterialAlertDialogBuilder(this)
                .setTitle("Paper size")
                .setMessage("Detected ${if (guess == PrinterPrefs.DOTS_80) "80mm" else "58mm"} from the printer name. Use it?")
                .setPositiveButton("Yes") { _, _ ->
                    PrinterPrefs.setDots(this, sel.deviceKey, guess); onResolved(guess)
                }
                .setNegativeButton("Choose…") { _, _ -> askPaperSize(sel, onResolved) }
                .show()
            return
        }
        askPaperSize(sel, onResolved)
    }

    private fun askPaperSize(sel: PrinterChoice, onResolved: (Int) -> Unit) {
        val options = arrayOf("58mm (384 dots)", "80mm (576 dots)")
        MaterialAlertDialogBuilder(this)
            .setTitle("Select paper width for\n${sel.label}")
            .setItems(options) { _, which ->
                val dots = if (which == 0) PrinterPrefs.DOTS_58 else PrinterPrefs.DOTS_80
                PrinterPrefs.setDots(this, sel.deviceKey, dots)
                onResolved(dots)
            }
            .show()
    }

    /** Route to the right transport. USB may need a permission round-trip. */
    private fun dispatch(sel: PrinterChoice, bytes: ByteArray) {
        when (sel) {
            is PrinterChoice.Bluetooth -> printBluetooth(sel, bytes)
            is PrinterChoice.Usb -> {
                val mgr = DeviceDiscovery.usbManager(this)
                if (mgr.hasPermission(sel.device)) {
                    pendingUsbBytes = bytes
                    doPrintUsb(sel.device)
                } else {
                    pendingUsbBytes = bytes
                    requestUsbPermission(mgr, sel.device)
                }
            }
        }
    }

    private var pendingUsbBytes: ByteArray? = null

    private fun requestUsbPermission(mgr: UsbManager, device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
        mgr.requestPermission(device, pi)
    }

    private fun doPrintUsb(device: UsbDevice) {
        val bytes = pendingUsbBytes ?: return
        pendingUsbBytes = null
        runOnIo {
            val mgr = DeviceDiscovery.usbManager(this)
            val transport = UsbTransport(mgr, device, activeConfig)
            sendWith(transport, bytes)
        }
    }

    private fun printBluetooth(sel: PrinterChoice.Bluetooth, bytes: ByteArray) {
        runOnIo {
            val transport = BluetoothTransport(sel.device, activeConfig)
            sendWith(transport, bytes)
        }
    }

    private fun sendWith(transport: PrinterTransport, bytes: ByteArray) {
        try {
            setStatus("Connecting to ${transport.name}…", busy = true)
            transport.open()
            setStatus("Transmitting ${bytes.size} bytes…", busy = true)
            transport.write(bytes)
            runOnUiThread {
                toast("Sent ${bytes.size} bytes to ${transport.name}")
            }
            setStatus("Done — sent ${bytes.size} bytes", busy = false)
        } catch (e: Exception) {
            runOnUiThread { toast("Print failed: ${e.message}") }
            setStatus("Failed: ${e.message}", busy = false, error = true)
        } finally {
            transport.close()
        }
    }

    /** Updates the status line under the printer list. UI only — no print logic. */
    private fun setStatus(msg: String, busy: Boolean, error: Boolean = false) {
        runOnUiThread {
            b.txtStatus.text = (if (busy) "● " else if (error) "✕ " else "✓ ") + msg
            val attr = when {
                error -> com.google.android.material.R.attr.colorError
                busy -> com.google.android.material.R.attr.colorPrimary
                else -> com.google.android.material.R.attr.colorPrimary
            }
            val tv = android.util.TypedValue()
            theme.resolveAttribute(attr, tv, true)
            b.txtStatus.setTextColor(tv.data)
        }
    }

    // ---- helpers ----
    private fun runOnIo(block: () -> Unit) = io.execute(block)
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
