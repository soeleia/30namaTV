# Performance evidence

The local verification device is a disposable Android 9 arm64 emulator at 1920x1080, with two CPU cores, 1536 MB RAM, a 128 MB app heap and software graphics. GitHub tests use Android 11 TV with the same core/RAM/heap limits. Neither device models a Sony GPU, hardware decoder or network.

## Local optimized release smoke test

| Measurement | Observed value | Meaning |
| --- | --- | --- |
| APK size | 5,044,021 bytes | R8 and resource shrinking enabled; no native ABI libraries |
| Cold Activity launch (`am start -W`) | 662 ms | Activity launch only; excludes catalogue/image completion |
| Guest home process PSS | 95.6 MiB | One settled home snapshot, not a long-session peak |
| Software-rendered startup frames | 316/329 janky frames; median 32 ms | This emulator did not sustain 60 fps during startup; these timings cannot certify physical TV smoothness |

The guest catalogue and artwork loaded in the optimized APK, and no fatal exception or ANR was found in this smoke-test log. The real Settings update action reached GitHub. The automated startup fixture also verifies that blocked menu/account extras do not hold catalogue rendering; its subsecond timing is a fixture result, not real network speed.

## What the checks establish

27 JVM tests pass, full release lint passes, and native regressions cover paging/cancellation, startup, original motion-logo cleanup, player controls/seeks, text columns, captions, title bounds, skip progress, My Lists, search, genres and updater validation/status. Local native playback decoded and advanced the public Animals HLS trailer.

The release uses sized image decoding and bounded caches, cancels replaced work, hides idle controls and stops unused video/animation work. Update networking and APK verification run off the UI thread; Android Download Manager owns the background download.

## Hardware verification still needed

Run the [Sony TV checklist](testing.md), including a 20-minute playback/browse session and memory/frame captures. Do not infer a frame-rate guarantee or real-world startup benchmark from fixture success. A complete future-version download and installation must also be exercised with the same public signing key; current tests cover metadata/APK validation, UI states and installer safeguards.
