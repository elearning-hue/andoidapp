# ESC/POS Printer (Android)

A native Android app for printing **images, PDFs, and text receipts** to an
**80mm (576-dot) thermal printer** over **Bluetooth (SPP) or USB (host/OTG)**.

Written in Kotlin, plain Android Views (no Compose), no third-party printing
libraries — the ESC/POS encoding is all in-project so you can read and tweak it.

---

## What it does

- Lists **paired Bluetooth** printers and **connected USB** printers.
- Pick an **image** (PNG/JPG) or **PDF** and print it — rendered to 1-bit raster
  via the `GS v 0` bit-image command (the widely-supported Epson-compatible mode).
- Type **text** and print a receipt (with bold/size/centering helpers available).
- Auto partial-cut after each job (configurable).

## Project layout

```
app/src/main/java/com/gavthan/escpos/
├─ print/
│  ├─ EscPos.kt            ESC/POS command bytes + bitmap→raster encoder
│  ├─ PrinterTransport.kt  transport interface + PrinterConfig (dot width etc.)
│  ├─ BluetoothTransport.kt  Bluetooth Classic SPP connection
│  ├─ UsbTransport.kt      USB host bulk-OUT connection
│  ├─ DeviceDiscovery.kt   list paired BT + connected USB devices
│  └─ PrintJob.kt          builds full byte streams for image/text jobs
├─ util/
│  └─ DocumentRenderer.kt  image decode + PDF→bitmap via PdfRenderer
└─ ui/
   └─ MainActivity.kt      permissions, device pick, file pick, print dispatch
```

## Build & run

You need **Android Studio** (Hedgehog or newer) or a local Android SDK.

### Android Studio (easiest)
1. **File → Open** and select the `escpos/` folder.
2. Let Gradle sync (it downloads Gradle 8.7 + the Android Gradle Plugin 8.5.2).
3. Plug in an Android phone with USB debugging on, or use an emulator (note:
   emulators can't reach real BT/USB printers — use a real device for printing).
4. Press **Run**.

### Command line
```bash
cd escpos
./gradlew assembleDebug        # macOS/Linux
gradlew.bat assembleDebug      # Windows
```
The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.
Install it: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

> If CLI build complains about the SDK, copy `local.properties.example` to
> `local.properties` and set `sdk.dir` to your Android SDK path.

## Using it

### Bluetooth
1. **Pair the printer first** in Android Settings → Bluetooth. (This app uses
   already-paired devices; it doesn't pair for you.)
2. Open the app → **Refresh devices** → grant the Bluetooth permission prompt.
3. Tap your printer in the list, pick a file or type text, **Print**.

### USB (OTG)
1. Connect the printer to the phone with a USB-OTG adapter.
2. **Refresh devices** → tap the USB entry → **Print**.
3. Android shows a USB-permission dialog the first time — allow it.

## Configuration

Everything tunable is in `PrinterConfig` (see `PrinterTransport.kt`) and is set
in `MainActivity` as `PrinterConfig(dotsWidth = 576)`:

| field          | meaning                                            | default |
|----------------|----------------------------------------------------|---------|
| `dotsWidth`    | printer width in dots (576 = 80mm, 384 = 58mm)     | 576     |
| `threshold`    | 0–255 luminance cutoff for B/W (lower = darker)    | 127     |
| `feedBeforeCut`| blank lines fed before cutting                     | 3       |
| `autoCut`      | send partial-cut command after the job             | true    |
| `chunkSize`    | bytes per write (smaller = safer for cheap printers)| 2048   |
| `chunkDelayMs` | pause between chunks (lets the buffer drain)       | 20      |

## Per-printer tuning profiles

The app auto-applies a tuned print profile based on the printer name + chosen
paper width (`PrinterConfig.forPrinter()`), so the two printers in use get the
right treatment automatically:

- **Xprinter XP-F600 (80mm / 576 dots):** honors `GS v 0` raster + standard
  density well, big buffer → large chunks, no bitmap dilation needed.
- **Cysno HOP-H58 (58mm / 384 dots):** budget controller that often ignores
  `ESC 7`/`DC2 #` and has a small buffer → small 512-byte chunks, longer
  inter-chunk delay, and **bitmap-side dilation enabled** (the reliable darkener
  for this class). This is why a 58mm print used to come out faint.

Unrecognized printers fall back to a safe generic profile. To tweak either
profile, edit `PrinterConfig.forPrinter()` in `PrinterTransport.kt`.

## Troubleshooting (ESC/POS reality check)

ESC/POS is a loose family of dialects, not one strict standard. If output looks
wrong, try these in order:

- **Prints nothing / disconnects mid-image:** lower `chunkSize` to 512 and raise
  `chunkDelayMs` to 40–60. Many budget BT printers have tiny buffers.
- **Image is shifted / garbled:** confirm `dotsWidth` matches the paper. 80mm is
  almost always 576; some 80mm heads are 512 or 640. Try 512 if 576 is off.
- **58mm printer crops the image / cuts off the right side:** the app remembers
  paper width **per printer**. The first time you print to a printer it asks
  58mm vs 80mm (or guesses from the device name and confirms) and remembers the
  choice. To change it later, **long-press the printer** in the device list. A
  wrong width is the #1 cause of cropping (sending 576-dot data to a 384-dot head).
- **Prints come out FADED / lighter than a vendor app or website:** this is a
  print-density (heat) issue — the vendor driver sets the printer's heating energy
  and raw raster doesn't. The app now sends `ESC 7` (heating) + `DC2 #` (density)
  before every job. Tune in `PrinterConfig` (MainActivity):
    - `heatingTime` (3..255) — the **main darkness knob**. Raise toward 220–255 for
      darker; lower if output smudges or the printer slows/overheats.
    - `density` (0..15) — darkness on printers that support `DC2 #`.
    - `threshold` (default 160) — higher = more pixels print as black dots.
    - `doubleStrikeText` — prints text lines twice for darker receipts.
    - `dilateImages` — thickens black strokes in raster output so faded thin
      lines/text read as solid. This works **even if the printer ignores all
      density commands**, because it's done in our bitmap, not on the printer.
      Set `false` if images come out too heavy/blobby.
  The app sends three density commands (`ESC 7`, `DC2 #`, `GS ( K`) to cover the
  main firmware families. If your printer ignores all of them (rare but real),
  `dilateImages` + `doubleStrikeText` + a higher `threshold` are your levers, plus
  the printer's own density menu/self-test.
- **Image too light / too dark (after density is set):** adjust `threshold` (try
  180 for darker, 130 for lighter).
- **Printer ignores `GS v 0`:** a few old models only support the legacy `ESC *`
  bit-image mode. That's a different encoder; ping me and I'll add an `ESC *`
  fallback path.
- **USB "no bulk OUT endpoint":** the printer may expose a vendor-specific class.
  Add its VID to `res/xml/usb_device_filter.xml`. The transport already falls
  back to the first bulk-OUT interface it finds.
- **Cut doesn't work:** set `autoCut = false` — many printers without a cutter
  will just feed instead, which is harmless, but some error on the command.

## Honest limitations

- Bluetooth uses Classic SPP (the receipt-printer norm). It does **not** support
  BLE-only printers; those need a different (GATT) transport.
- PDF rendering uses Android's built-in `PdfRenderer` (API 21+), which handles
  most PDFs but not encrypted ones.
```
