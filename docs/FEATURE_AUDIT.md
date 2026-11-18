# Vendor feature audit and first-release scope

Reference: HyPrint 1.4.9+26 (`com.hztz.Hprint`) from the user-supplied APK set. This is a **static audit**, not a claim that every vendor screen or feature was exercised. Android manifest/resources, Flutter assets/AOT strings, Java integration classes, native exports and decompiled/Smali control flow were inspected. Flutter AOT code prevents a complete source-level reconstruction of its Dart UI.

The agreed first release is **core printing first, Android 12+, standalone app AND system PrintService**. Hardware testing was initially deferred; the user later reported successful pairing, connection, the test strip and Android system printing on firmware v6.0.4 (see [TESTING.md](TESTING.md)). Broader hardware/calibration testing remains pending. The new Material 3 design and implementation are original; vendor code, artwork, templates and native libraries are not copied into the replacement.

| Vendor surface found | Evidence / confidence | New first release |
|---|---|---|
| DY Bluetooth printer integration | Method bridge, sender, receiver and device-family asset; direct protocol evidence | Classic SPP, saved printer, discovery/pairing workflow and optional insecure-SPP compatibility fallback |
| Documents / PDF | Flutter/assets/integration strings; exact complete vendor import behavior not reconstructed | Content-URI PDF import, Android PDF renderer, multi-document page list |
| Photos / image printing | Bitmap/native print integration and processing options | Android-supported images, EXIF-aware bounded software decode |
| Text / notes | Text-related surface and native `text` processing mode; these are distinct concepts | Typed/shared plain-text note → local PDF; no claim of vendor text-mode equivalence |
| Preview / page settings | Print arguments and native `PrintOptions` fields | Monochrome raster preview, ordered page ranges, A4/A5/Letter, fit/fill, margins |
| Copies | Print workflow evidence; complete Dart-side sequencing not recovered | Original explicit software expansion; standalone collated or uncollated; system jobs collated |
| Crop / rotate / mirror | Native `PrintOptions` fields, processing interfaces | Symmetric crop percentages, 90° steps and mirror. Not a free-form vendor crop editor clone |
| Density, status, battery and firmware | Direct DY command/reply-handler evidence | Density 0–3 initial profile; status query; firmware; raw battery code, **not** an assumed percentage |
| Photo / gray / line / text / mix / matte / tattoo processing | Native `PrintOptions` mode names and `ScanPrintNative` interfaces | Original threshold/Floyd–Steinberg processing, brightness/contrast/negative. No proprietary algorithms or matching preset claims |
| Document scanning / camera workflow | Bundled scan integration, manifest/libraries and strings | **Deferred**; use a scanner app to create a PDF, then Share/Open it |
| OCR | Huawei ML/OCR components and strings; exact UI workflow not exercised | **Deferred**; no OCR SDK or model bundled |
| Web printing / capture | AOT/integration strings; exact browser/session behavior not reconstructed | **Deferred**; print from another app through the new system PrintService, or save as PDF |
| Templates, tattoo/stencil assets | Flutter asset inventory and strings | **Deferred**; no proprietary templates/artwork redistributed |
| Several non-DY printer families | Device-family mapping plus unrelated embedded SDKs | **Not supported**. Do not mix their command sets with A4DY |
| Linux / USB printing | A separate previously delivered working driver | Remains separate; app has no USB transport and does not alter the driver |
| Android system PrintService | Requested scope for this project; not asserted to be vendor feature parity | Original service with monochrome/simplex capabilities, spooler page/copy handling, progress and cancellation |

## Privacy and intentional differences

- No vendor account, cloud storage, updates, telemetry, advertising, OCR downloads or network permission.
- No direct broad filesystem/photos-library access; Android content permissions are used.
- The manufacturer’s native libraries and Flutter runtime are not dependencies.
- Original green/neutral Material 3 layout, original vectors, system fonts, light/dark mode and optional dynamic colour.
- Query results are not turned into unsupported claims: battery units remain unknown; “sent” is not an eject sensor; status polling is not a guarantee that paper physically finished.
- If a name belongs to a Pro/300 dpi or other model, do not use the 203 dpi A4DY profile merely because the app permits manual address selection.

## Evidence index

- `research/vendor-input-hashes.json` and `vendor-download-info.json`: provenance.
- `research/base-apk-entries.json`: APK/asset inventory; filenames are evidence, not redistributed asset contents.
- `research/dex-bluetooth-string-index.txt`: relevant DEX strings.
- `research/static-analysis-index.json`: class/method anchors and reliable Smali conclusions.
- `research/vendor-packet-probe.json`: isolated vendor-bytecode comparison, **27 exact packet matches**.
- [PROTOCOL.md](PROTOCOL.md): recovered wire format and unresolved firmware details.

Broader parity can be added later as original implementations, but scanning, OCR, web capture and templates are not represented as completed in this release.
