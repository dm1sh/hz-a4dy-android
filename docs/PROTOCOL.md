# HZ-A4DY Bluetooth protocol — recovered facts and implementation choices

## Provenance and method

Reference supplied by the user: https://pixeldrain.com/u/hPCqnVqn — `HyPrint_1.4.9_apkcube.apks`, vendor package `com.hztz.Hprint`, app 1.4.9+26. The base and ARM64 split were downloaded separately and independently hashed:

| Input | Bytes | SHA-256 |
|---|---:|---|
| `base.apk` | 29,117,829 | `d3ed4870762173fed497b7e6ada2a4f66a95bd9f1e1849420369dbb17c41b45c` |
| `split_config.arm64_v8a.apk` | 27,931,819 | `aa0bfb48f125e341fb54c4959d0ae15731f56079a6893b883c4290a86a24b4dd` |

The archive-level hash in the download API is recorded separately; it was not independently recomputed from a full archive download. `research/` preserves provenance and the APK entry inventory. Vendor binaries, native libraries and decompiled implementations are not part of this distribution or APK.

Tools: JADX 1.5.3, apktool 2.7, baksmali 2.5.2 and enjarify 1.0.3. Several critical JADX methods were omitted or structurally wrong. Conclusions below were checked against Smali, not simply copied from plausible-looking decompiled Java. Flutter’s Dart UI/business logic is AOT-compiled in `libapp.so`; JADX does not recover it as ordinary Java.

### Selecting the correct SDK

The bundled `assets/flutter_assets/update.json` explicitly places `A4DY` in `printerType.dy`. It lists a **different** `dy300DPI` family, including `GD-88H` and `A4[A-Z]{2}-Pro`. The APK contains several unrelated printer SDKs; commands from Q2/Y2/W2 and other printer families must not be mixed into DY.

Relevant chain: Flutter method bridge `J.d` / `M2.b` → `o2.w` / `o2.j` → `A0.e`, with replies dispatched by `A0.c`. `M2/a.run`, synthetic selector 1, implements the recovered page sender. Exact evidence anchors and Smali hashes are in `research/static-analysis-index.json`.

## Transport

**Bluetooth Classic RFCOMM/SPP**, UUID:

```
00001101-0000-1000-8000-00805F9B34FB
```

The vendor first uses secure RFCOMM, with a reflected channel-1 socket fallback. This app uses the documented secure API; an explicitly enabled **insecure SPP** fallback is an original compatibility choice, **not** a reproduction of that reflection workaround. No BLE/GATT transport was recovered for this profile.

The vendor writer sends chunks of at most **1024 bytes**, sleeping **1 ms before each write**. The app follows this pacing. One mutex owns the link across app and system jobs, printing, queries and settings; only one request/reply transaction can be outstanding.

## Per-page wire sequence

All numbers below are hexadecimal unless indicated otherwise.

```
10 FF FE 01                              start
1F 00 ss ss hh hh nn nn nn nn [payload]   raster
1B 4A 64                                 continuous paper feed
  OR 1F 12 20 02                         label/page-location mode
10 FF FE 45                              end
```

Raster header:

| Offset | Length | Meaning |
|---:|---:|---|
| 0 | 2 | `1F 00` |
| 2 | 2 | bytes per raster row, **big-endian** |
| 4 | 2 | raster height in rows, **big-endian** |
| 6 | 4 | compressed payload length, **big-endian** |
| 10 | variable | default Java zlib output with **only its first two bytes removed** |

Pixels: row-major, `ceil(width / 8)` bytes per row, **MSB-left, 1 = black**, white unused bits on the right. No extra blank row is inserted by the encoder.

Compression is `DeflaterOutputStream` with default settings. **Retain the Adler-32 trailer.** This is not merely a raw-deflate stream with the trailer discarded. Packet length counts the zlib output minus its two-byte header, including that trailer.

### The paper-type branch matters

Smali shows `paperType` loaded directly into `v11`; equality with **1** selects `1B 4A 64`. Other values select `1F 12 20 02`. An earlier JADX rendering inverted the apparent condition. The app maps `CONTINUOUS` to the former and `LABEL` to the latter. The physical interpretation/feed distance still needs confirmation.

### Do not substitute the Linux USB frame

The working Linux driver uses the USB `10 FF F1 02 …` initialization and raw `GS v 0` raster language. That is **not** this vendor Bluetooth path. Reusing that framing here would discard direct APK evidence.

## Queries and settings

| Operation | Command |
|---|---|
| Status | `10 FF 40` |
| Battery | `10 FF 50 F1` |
| Firmware | `10 FF 20 F1` |
| Serial | `10 FF 20 F2` |
| Name | `10 FF 30 11` |
| Bluetooth MAC | `10 FF 30 12` |
| Query density | `10 FF 11` |
| Set density | `10 FF 10 00 n` |

The app uses status, battery, firmware and density setting; it does not need to query or export serial/MAC identifiers. Its density UI conservatively permits 0–3, initially 0. The usable range/heat effect is not hardware verified.

Vendor reply routing depends on the **stored current method**, not a recovered self-identifying opcode. Status is selected by the **last byte**, exact values, not a proven bit mask:

| Last byte, decimal | Meaning |
|---:|---|
| 0 | Ready |
| 1 | Busy/printing |
| 4 | No paper |
| 6 | Cover open |
| 8 | Low battery |
| 16 | Overheated |
| other / empty | Unknown |

Print/density acknowledgement: exact `AA` or ASCII `OK`. `FD` is failure; a reply beginning `FF` with another byte reports an error code. The original reports a signed second byte; the app displays its unsigned value for diagnosis. Unknown replies are not silently promoted to a physical-completion acknowledgement.

Battery is parsed by the vendor only for a two-byte reply and exposed as the second signed byte converted to text. **Units and scale are unknown.** The new UI reports its raw unsigned code, not a percentage. Firmware/serial text uses GB2312 in the handler.

## Receive framing: known limit

Smali of `A0/a` resolves the broken JADX receiver loop: it sleeps 100 ms, checks `InputStream.available()`, and performs one available-sized read; otherwise it repeats until about 2 seconds have elapsed. It then dispatches `Message.what = 16`. This does **not** reveal a firmware-defined delimiter or guarantee one read equals one message.

The app instead accumulates split reads using a **120 ms quiet gap**, a 1.5 second gather window after the first chunk, a 256-byte reply bound and a bounded incoming channel. These are **implementation heuristics**. Delayed/coalesced replies can still be ambiguous. Fake/chunked-reader tests are not proof of real firmware timing.

Other current limits: 12 seconds per connection attempt; 90-second write watchdog; readiness polling before printing and after each page; 30-minute job/wake-lock cap. Errors stop subsequent pages. No automatic retry after partial transmission. Cancellation closes the socket; no physical cancel opcode has been recovered.

## Dimensions and image processing

The implementation adopts the working USB profile’s **203 dpi** and maximum width **1728 dots**. A4 is **1678 × 2374 dots**; Letter width is 1726. This is a reasoned initial profile, **not a recovered exact Flutter A4DY sizing decision or Bluetooth hardware measurement**.

The vendor’s simple raster packing averages RGB and compares with 190. The original app deliberately provides weighted-luma thresholding/diffusion, brightness, contrast, white alpha composition, mirroring and negative instead of reproducing proprietary native photo/tattoo processing. Differences in image appearance are expected.

Android images are decoded in software with EXIF handling, bounded to a 3072-pixel long edge and 6 megapixels; one page is rasterized at a time. The preview displays the same raster conversion, scaled to at most 840 pixels wide.

## Independent bytecode comparison

`research/vendor-packet-probe.json` records **27 exact matches**: nine bitmap sizes, including odd widths, 1728-dot width and a full A4 raster, each with `paperType` 0, 1 and 2. The vendor’s **actual translated `M2.a` bytecode** was executed against original, inert bitmap/bridge/writer stubs. Its four output packets were compared byte-for-byte with the new core for black/white fixtures, using the same JVM compressor. Only that selected vendor class was loaded. No Android app, Bluetooth stack or printer was involved.

The optional probe refuses unknown input/translated-class hashes. To reproduce with your own copy of the supplied base APK:

```sh
./gradlew :core:jar
# Install enjarify 1.0.3 and use a Python interpreter that can import it.
python3 tools/probe-vendor.py /path/to/base.apk \
  --python /usr/bin/python3 \
  --stdlib /path/to/kotlin-stdlib-2.2.21.jar
```

It translates locally, extracts only `M2/a.class` into a temporary classpath, compiles the original stubs, runs the comparison, writes a JSON report and removes the temporary classes. Proprietary class files are not redistributed.

**What this establishes:** packing/stride, frame fields, compressed bytes, both paper branches and start/end commands for these fixtures. **What it does not establish:** physical DPI/head alignment, Android PDF rendering, RFCOMM pairing, write/reply timing, battery units, density/heat safety, actual feed behavior or physical completion.

## Later physical user report (separate evidence)

After the original build-only validation, the user reported successful pairing/connection, firmware **v6.0.4**, battery raw **100**, a printed built-in test strip and successful Android system printing. The report and metadata-sanitized photo are recorded in `research/hardware-user-report.json` and `docs/hardware/first-test-strip.jpg`. This establishes initial reported interoperability on that setup, not assistant-run device instrumentation or universal timing/firmware compatibility. Battery units, measured DPI/head alignment, density range, feed/label calibration, advanced copies/ranges, lifecycle/cancel behavior and physical-completion detection remain unverified.
