# Agreed scope

- Android 12/API31 or newer; standalone app AND Android PrintService.
- Core first: PDF, images, text; preview/page selection; image adjustments; print settings; copies/collation; progress/cancel; saved Bluetooth printer.
- Hardware testing was initially deferred. The user subsequently reported successful pairing/connection, firmware v6.0.4, battery raw 100 (units unknown), the built-in test strip and Android system printing. Record this as initial user-reported physical success, not assistant-run instrumentation or general hardware certification; advanced ranges/copies, lifecycle/cancel and density/feed/DPI calibration remain pending.
- Publish original sources to dm1sh/hz-a4dy-android. Build debug APKs on branch pushes and signed release APKs / GitHub Releases on matching v<versionName> tag pushes.
- The user authorized a new permanent release key, encrypted GitHub Actions secrets and a private backup. Keep CI debug signing separate. Never commit private keys, passwords or personal access tokens; workflows use the short-lived GITHUB_TOKEN.
- Original Kotlin/Material3 implementation. No vendor code, native libraries, artwork, models, templates, accounts, telemetry, or cloud dependencies in the APK.
- Download supplied is HyPrint1.4.9+26, package com.hztz.Hprint. Native integration com.netum.scanprint.MainActivity.
- update.json explicitly places A4DY in printerType.dy; separate printer families have DIFFERENT protocols and must not be mixed in.
- DyPrinter uses Classic SPP, UUID00001101-0000-1000-8000-00805F9B34FB, not a recovered BLE/GATT protocol.
- Important: raw Smali reverses a misleading JADX condition! In M2/a.run, paperType==1 sends1B4A64 (continuous); all other values send1F122002 (label location). Use Smali as source of truth.
- Bluetooth per-page START10FFFE01, compressed raster1F00 (big-endian stride/height/length), zlib minus first2bytes INCLUDING Adler32 trailer, paper feed, END10FFFE45. Do not send USB10FFF102/GSv0 framing by default.
- Density10FF1000n; status10FF40; battery10FF50F1. Status lastbyte0ready/1busy/4paper/6cover/8lowbattery/16overheat. Print ACK AA orASCII OK; FD failure; FF+error. May be split across reads.
- Vendor sender uses1024byte chunks and1ms pacing. Implementation will serialize requests and enforce connection/write/read timeouts.
- No physical cancellation command established. Close socket on cancellation, warn that buffered paper may finish; never auto-retry a partial page.
