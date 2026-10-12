# Performance evidence

The local verification device is a disposable Android 9 arm64 emulator at 1920x1080, with two CPU cores, 1536 MB RAM, a 128 MB app heap and software graphics. GitHub tests use Android 11 TV with the same core/RAM/heap limits. Neither device models a Sony GPU, hardware decoder or network.

## Local optimized release smoke test

| Measurement | Observed value | Meaning |
| --- | --- | --- |
| APK size | 5,044,056 bytes | R8 and resource shrinking enabled; no native ABI libraries |
| Cold Activity launch (`am start -W`) | 662 ms | Activity launch only; excludes catalogue/image completion |
| Guest home process PSS | 95.6 MiB | One settled home snapshot, not a long-session peak |
| Software-rendered startup frames | 316/329 janky frames; median 32 ms | This emulator did not sustain 60 fps during startup; these timings cannot certify physical TV smoothness |

The guest catalogue and artwork loaded in the optimized APK, and no fatal exception or ANR was found in this smoke-test log. The real Settings update action reached GitHub. The automated startup fixture also verifies that blocked menu/account extras do not hold catalogue rendering; its subsecond timing is a fixture result, not real network speed.

## What the checks establish

27 JVM tests pass, full release lint passes, and native regressions cover paging/cancellation, startup, original motion-logo cleanup, player controls/seeks, text columns, captions, title bounds, skip progress, My Lists, search, genres and updater validation/status. Local native playback decoded and advanced the public Animals HLS trailer.

The release uses sized image decoding and bounded caches, cancels replaced work, hides idle controls and stops unused video/animation work. Update networking and APK verification run off the UI thread; Android Download Manager owns the background download.

## Public release and installation verification

The [v1.0.3 tag run](https://github.com/soeleia/30namaTV/actions/runs/38218000079) passed build, full release lint, 27 JVM tests and all 12 Android 11 TV regression suites, collecting 25 PNG screenshots. The live Animals HLS trailer decoded and advanced. The tag's TV job passed on its second attempt; the first failed the intro pause-freeze sample, while the identical main run passed. The regression now waits for playback-thread pause completion before taking its baseline, retaining the same 600 ms freeze assertion.

On the disposable Android 9 emulator, a temporary version 0.9.0/code 1 baseline used the production v1.0.3 updater and the public release signing key. Opening home automatically downloaded the actual stable GitHub release without a manual update check. Its size was 5,044,478 bytes and its SHA-256 was `cd5b554fcc590cfecc5425fd8551a9e9e8fa3addd4b53c9e206cb83607c75e65`, matching the signed CI artifact and GitHub asset digest.

The app accepted the downloaded APK and opened Android's unknown-source permission screen. After enabling permission and selecting Install again, the system installer offered an update preserving existing data. D-pad confirmation installed version 1.0.3/code 5; the installed `base.apk` matched the public release hash exactly. Reduce Motion, explicitly enabled before installation, remained enabled afterward. The updater reported Current and removed the downloaded APK on the next launch. No ADB install, uninstall or preference reset was used during this update cycle. This guest test establishes settings preservation, not retention of a signed-in account or physical Sony behavior.

## Hardware verification still needed

Run the [Sony TV checklist](testing.md), including a 20-minute playback/browse session and memory/frame captures. Do not infer a frame-rate guarantee or real-world startup benchmark from fixture success. Verify the exact photographed Friends subtitle cue and account retention during a future public update on the TV.
